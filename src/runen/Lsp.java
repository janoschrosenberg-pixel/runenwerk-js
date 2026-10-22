package runen;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/**
 * Client fuer einen Language Server (typescript-language-server --stdio). Antworten und
 * Meldungen des Servers laufen ueber den Swing-Faden, damit der Editor nichts sperren muss.
 */
final class Lsp {
    interface Hoerer {
        void diagnosen(String uri, List<Object> diags);

        void meldung(String text, boolean fehler);

        void bereit();
    }

    static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    private Process proc;
    private OutputStream aus;
    private int naechste = 1;
    private final Map<Integer, Consumer<Object>> offen = new ConcurrentHashMap<>();
    private final Hoerer hoerer;
    volatile boolean bereit;
    volatile String status = "aus";

    Lsp(Hoerer h) {
        this.hoerer = h;
    }

    static String uri(Path p) {
        return p.toAbsolutePath().normalize().toUri().toString();
    }

    /** Befehl suchen: zuerst node_modules/.bin im Projekt, dann der aus editor.config */
    static List<String> befehl(Path wurzel, List<String> konfig) {
        List<String> b = new ArrayList<>(konfig);
        if (b.isEmpty()) return b;
        Path lokal = wurzel.resolve("node_modules").resolve(".bin").resolve(b.get(0) + (WINDOWS ? ".cmd" : ""));
        if (Files.isRegularFile(lokal)) b.set(0, lokal.toString());
        if (WINDOWS) {
            // .cmd-Huellen (npm) laufen nur ueber cmd.exe
            b.add(0, "/c");
            b.add(0, "cmd.exe");
        }
        return b;
    }

    /**
     * Aus dem Finder/Explorer gestartet fehlt oft der PATH der Shell (node, npm):
     * uebliche Orte anhaengen.
     */
    static void umgebung(ProcessBuilder pb) {
        if (WINDOWS) return;
        String home = System.getProperty("user.home");
        Map<String, String> env = pb.environment();
        StringBuilder p = new StringBuilder(env.getOrDefault("PATH", "/usr/bin:/bin"));
        List<String> dazu = new ArrayList<>(List.of("/opt/homebrew/bin", "/usr/local/bin", home + "/.volta/bin", home + "/.local/bin", home + "/.bun/bin"));
        Path nvm = Path.of(home, ".nvm", "versions", "node");
        try (var s = Files.list(nvm)) {
            s.sorted(java.util.Comparator.reverseOrder()).findFirst().ifPresent(v -> dazu.add(v.resolve("bin").toString()));
        } catch (IOException e) {
            // kein nvm
        }
        for (String d : dazu) if (!(":" + p + ":").contains(":" + d + ":")) p.append(':').append(d);
        env.put("PATH", p.toString());
    }

    void start(Path wurzel, List<String> konfig) {
        stop();
        List<String> b = befehl(wurzel, konfig);
        if (b.isEmpty()) return;
        try {
            ProcessBuilder pb = new ProcessBuilder(b).directory(wurzel.toFile());
            umgebung(pb);
            if (!WINDOWS && !b.get(0).contains("/")) {
                for (String d : pb.environment().get("PATH").split(":")) {
                    Path x = Path.of(d, b.get(0));
                    if (Files.isExecutable(x)) {
                        b.set(0, x.toString());
                        break;
                    }
                }
                pb.command(b);
            }
            proc = pb.start();
        } catch (IOException e) {
            status = "fehlt";
            hoerer.meldung("Language Server nicht gestartet (" + String.join(" ", konfig) + "): " + e.getMessage()
                    + "  ->  npm install -g typescript typescript-language-server", true);
            return;
        }
        status = "startet";
        aus = proc.getOutputStream();
        Process p = proc;
        Thread leser = new Thread(() -> lesen(p), "lsp-lesen");
        leser.setDaemon(true);
        leser.start();
        Thread fehler = new Thread(() -> {
            try (InputStream in = p.getErrorStream()) {
                in.transferTo(OutputStream.nullOutputStream());
            } catch (IOException e) {
                // egal
            }
        }, "lsp-stderr");
        fehler.setDaemon(true);
        fehler.start();

        String ru = uri(wurzel);
        Map<String, Object> caps = Json.obj(
                "textDocument", Json.obj(
                        "synchronization", Json.obj("didSave", true, "dynamicRegistration", false),
                        "completion", Json.obj("completionItem", Json.obj("snippetSupport", false, "documentationFormat", List.of("plaintext"))),
                        "hover", Json.obj("contentFormat", List.of("plaintext", "markdown")),
                        "definition", Json.obj("linkSupport", true),
                        "references", Json.obj(),
                        "formatting", Json.obj(),
                        "rename", Json.obj("prepareSupport", false),
                        "publishDiagnostics", Json.obj("relatedInformation", false)),
                "workspace", Json.obj("workspaceFolders", true, "configuration", true, "applyEdit", false),
                "window", Json.obj("workDoneProgress", false));
        Map<String, Object> init = Json.obj(
                "processId", ProcessHandle.current().pid(),
                "rootUri", ru,
                "rootPath", wurzel.toString(),
                "workspaceFolders", List.of(Json.obj("uri", ru, "name", wurzel.getFileName() == null ? "root" : wurzel.getFileName().toString())),
                "capabilities", caps,
                "initializationOptions", Json.obj("preferences", Json.obj("includeCompletionsForModuleExports", true,
                        "includeCompletionsWithInsertText", true)));
        senden(Json.obj("jsonrpc", "2.0", "id", naechste, "method", "initialize", "params", init), true);
        offen.put(naechste++, r -> {
            senden(Json.obj("jsonrpc", "2.0", "method", "initialized", "params", Json.obj()), true);
            bereit = true;
            status = "an";
            hoerer.bereit();
        });
    }

    void stop() {
        bereit = false;
        status = "aus";
        offen.clear();
        if (proc != null) {
            try {
                senden(Json.obj("jsonrpc", "2.0", "id", naechste++, "method", "shutdown"), true);
                senden(Json.obj("jsonrpc", "2.0", "method", "exit"), true);
            } catch (RuntimeException e) {
                // schon weg
            }
            proc.descendants().forEach(ProcessHandle::destroy);
            proc.destroy();
            proc = null;
        }
    }

    boolean an() {
        return proc != null && proc.isAlive() && bereit;
    }

    void anfrage(String methode, Object params, Consumer<Object> antwort) {
        if (!an()) return;
        int id = naechste++;
        offen.put(id, antwort);
        senden(Json.obj("jsonrpc", "2.0", "id", id, "method", methode, "params", params), false);
    }

    void meldung(String methode, Object params) {
        if (!an()) return;
        senden(Json.obj("jsonrpc", "2.0", "method", methode, "params", params), false);
    }

    private synchronized void senden(Object nachricht, boolean immer) {
        if (aus == null) return;
        try {
            byte[] b = Json.write(nachricht).getBytes(StandardCharsets.UTF_8);
            aus.write(("Content-Length: " + b.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            aus.write(b);
            aus.flush();
        } catch (IOException e) {
            if (!immer) status = "weg";
        }
    }

    private void lesen(Process p) {
        try (InputStream in = new BufferedInputStream(p.getInputStream())) {
            while (true) {
                int laenge = -1;
                ByteArrayOutputStream kopf = new ByteArrayOutputStream();
                while (true) {
                    int c = in.read();
                    if (c < 0) throw new IOException("Ende");
                    if (c == '\n') {
                        String z = kopf.toString(StandardCharsets.US_ASCII).trim();
                        kopf.reset();
                        if (z.isEmpty()) break;
                        if (z.toLowerCase().startsWith("content-length:")) laenge = Integer.parseInt(z.substring(15).trim());
                    } else {
                        kopf.write(c);
                    }
                }
                if (laenge < 0) continue;
                byte[] b = in.readNBytes(laenge);
                Object m = Json.parse(new String(b, StandardCharsets.UTF_8));
                SwingUtilities.invokeLater(() -> verarbeiten(m));
            }
        } catch (IOException | RuntimeException e) {
            if (proc == p) {
                SwingUtilities.invokeLater(() -> {
                    if (proc == p) {
                        bereit = false;
                        status = "weg";
                        hoerer.meldung("Language Server beendet", true);
                    }
                });
            }
        }
    }

    private void verarbeiten(Object m) {
        Object id = Json.get(m, "id");
        String methode = Json.str(Json.get(m, "method"));
        if (methode == null && id instanceof Number n) {
            Consumer<Object> c = offen.remove(n.intValue());
            if (c == null) return;
            if (Json.get(m, "error") != null) {
                String t = Json.str(Json.get(m, "error", "message"));
                if (t != null && !t.contains("No content")) hoerer.meldung("LSP: " + t, true);
                c.accept(null);
                return;
            }
            c.accept(Json.get(m, "result"));
            return;
        }
        if (methode == null) return;
        if (id != null) {
            // Anfragen des Servers an uns
            Object ergebnis = null;
            if (methode.equals("workspace/configuration")) {
                List<Object> l = new ArrayList<>();
                for (Object ignored : Json.list(Json.get(m, "params", "items"))) l.add(null);
                ergebnis = l;
            }
            senden(Json.obj("jsonrpc", "2.0", "id", id, "result", ergebnis), false);
            return;
        }
        switch (methode) {
            case "textDocument/publishDiagnostics" -> hoerer.diagnosen(Json.str(Json.get(m, "params", "uri")), Json.list(Json.get(m, "params", "diagnostics")));
            case "window/showMessage" -> {
                int art = Json.num(Json.get(m, "params", "type"), 3);
                hoerer.meldung(Json.str(Json.get(m, "params", "message")), art == 1);
            }
            default -> {
            }
        }
    }
}
