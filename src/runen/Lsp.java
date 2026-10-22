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
    private final List<String> fehlerZeilen = new ArrayList<>();
    volatile boolean bereit;
    volatile String status = "aus";

    /** fuer Meldungen: TS oder Java */
    final String name;
    /** was zu tun ist, wenn das Programm fehlt */
    String hinweis = "npm install -g typescript typescript-language-server";
    /** liefert nach dem Beenden weitere Gruende (z. B. aus dem Eclipse-Log), oder null */
    java.util.function.Supplier<List<String>> logGruende = () -> null;
    /** nach einem Abbruch mit diesem Exit-Code: true, wenn der Aufrufer selbst neu startet */
    java.util.function.IntPredicate neustart = c -> false;
    /** Antwort auf workspace/configuration und initializationOptions */
    private Map<String, Object> einstellungen = Map.of();
    private Object initOptionen;

    Lsp(String name, Hoerer h) {
        this.name = name;
        this.hoerer = h;
    }

    static String uri(Path p) {
        return p.toAbsolutePath().normalize().toUri().toString();
    }

    /**
     * Programm suchen: zuerst node_modules/.bin im Projekt, dann im PATH (unter Windows mit
     * .cmd/.exe/.bat). null, wenn es nicht da ist. npm-Huellen (.cmd) laufen ueber cmd.exe.
     */
    static List<String> befehl(Path wurzel, List<String> konfig, Map<String, String> env) {
        if (konfig.isEmpty()) return List.of();
        List<String> b = new ArrayList<>(konfig);
        String name = b.get(0);
        List<String> endungen = WINDOWS ? List.of(".cmd", ".exe", ".bat", "") : List.of("");
        Path gefunden = null;
        if (name.contains("/") || name.contains("\\")) {
            gefunden = Path.of(name);
        } else {
            List<Path> orte = new ArrayList<>();
            orte.add(wurzel.resolve("node_modules").resolve(".bin"));
            String pfad = env.getOrDefault("PATH", "");
            for (String d : pfad.split(java.io.File.pathSeparator)) if (!d.isBlank()) orte.add(Path.of(d.replace("\"", "")));
            suche:
            for (Path o : orte) {
                for (String e : endungen) {
                    Path x = o.resolve(name + e);
                    if (Files.isRegularFile(x) && (WINDOWS || Files.isExecutable(x))) {
                        gefunden = x;
                        break suche;
                    }
                }
            }
        }
        if (gefunden == null || !Files.isRegularFile(gefunden)) return null;
        b.set(0, gefunden.toString());
        String klein = gefunden.toString().toLowerCase();
        if (WINDOWS && (klein.endsWith(".cmd") || klein.endsWith(".bat"))) {
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
        String home = System.getProperty("user.home");
        Map<String, String> env = pb.environment();
        String trenner = java.io.File.pathSeparator;
        StringBuilder p = new StringBuilder(env.getOrDefault("PATH", WINDOWS ? "" : "/usr/bin:/bin"));
        List<String> dazu = new ArrayList<>();
        if (WINDOWS) {
            String appdata = System.getenv("APPDATA");
            if (appdata != null) dazu.add(appdata + "\\npm");
            String pf = System.getenv("ProgramFiles");
            if (pf != null) dazu.add(pf + "\\nodejs");
        } else {
            dazu.addAll(List.of("/opt/homebrew/bin", "/usr/local/bin", home + "/.volta/bin", home + "/.local/bin", home + "/.bun/bin"));
            Path nvm = Path.of(home, ".nvm", "versions", "node");
            try (var s = Files.list(nvm)) {
                s.sorted(java.util.Comparator.reverseOrder()).findFirst().ifPresent(v -> dazu.add(v.resolve("bin").toString()));
            } catch (IOException e) {
                // kein nvm
            }
        }
        for (String d : dazu) {
            boolean da = (trenner + p + trenner).toLowerCase().contains((trenner + d + trenner).toLowerCase());
            if (!da && Files.isDirectory(Path.of(d))) p.append(trenner).append(d);
        }
        env.put("PATH", p.toString());
    }

    void start(Path wurzel, List<String> konfig, Map<String, Object> einstellungen, Object initOptionen) {
        this.einstellungen = einstellungen == null ? Map.of() : einstellungen;
        this.initOptionen = initOptionen;
        start(wurzel, konfig);
    }

    void start(Path wurzel, List<String> konfig) {
        stop();
        if (konfig.isEmpty()) return;
        ProcessBuilder pb = new ProcessBuilder().directory(wurzel.toFile());
        umgebung(pb);
        List<String> b = befehl(wurzel, konfig, pb.environment());
        if (b == null) {
            status = "fehlt";
            String t = name + ": " + konfig.get(0) + " nicht gefunden  ->  " + hinweis;
            Main.log("Language Server: " + t + "; PATH=" + pb.environment().get("PATH"));
            hoerer.meldung(t, true);
            return;
        }
        Main.log(name + " Language Server: " + String.join(" ", b));
        fehlerZeilen.clear();
        try {
            proc = pb.command(b).start();
        } catch (IOException e) {
            status = "fehlt";
            hoerer.meldung(name + " Language Server nicht gestartet (" + String.join(" ", b) + "): " + e.getMessage()
                    + "  ->  " + hinweis, true);
            return;
        }
        status = "startet";
        aus = proc.getOutputStream();
        Process p = proc;
        Thread leser = new Thread(() -> lesen(p), "lsp-lesen");
        leser.setDaemon(true);
        leser.start();
        Thread fehler = new Thread(() -> {
            // die letzten Zeilen der Fehlerausgabe fuer die Meldung beim Beenden
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String z;
                while ((z = r.readLine()) != null) {
                    if (z.isBlank()) continue;
                    synchronized (fehlerZeilen) {
                        fehlerZeilen.add(z);
                        if (fehlerZeilen.size() > 30) fehlerZeilen.remove(0);
                    }
                }
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
                "initializationOptions", initOptionen != null ? initOptionen : Json.obj("preferences", Json.obj("includeCompletionsForModuleExports", true,
                        "includeCompletionsWithInsertText", true)));
        senden(Json.obj("jsonrpc", "2.0", "id", naechste, "method", "initialize", "params", init), true);
        offen.put(naechste++, r -> {
            senden(Json.obj("jsonrpc", "2.0", "method", "initialized", "params", Json.obj()), true);
            bereit = true;
            if (!"laedt".equals(status)) status = "an";
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
                int rc;
                try {
                    rc = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) ? p.exitValue() : -1;
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    rc = -1;
                }
                List<String> zeilen;
                synchronized (fehlerZeilen) {
                    zeilen = List.copyOf(fehlerZeilen);
                }
                int code = rc;
                SwingUtilities.invokeLater(() -> {
                    if (proc != p) return;
                    bereit = false;
                    status = "weg";
                    if (neustart.test(code)) {
                        Main.log(name + " Language Server beendet (" + code + "), wird neu gestartet");
                        return;
                    }
                    for (String z : zeilen) hoerer.meldung("  lsp: " + z, false);
                    List<String> log = logGruende.get();
                    if (log != null) for (String z : log) hoerer.meldung("  log: " + z, true);
                    // WARNING-Zeilen (z. B. incubator modules) sind nicht der Grund
                    String letzte = null;
                    for (String z : zeilen) if (!z.startsWith("WARNING")) letzte = z;
                    if (log != null && !log.isEmpty()) letzte = log.get(log.size() - 1);
                    String grund = letzte == null ? "" : ": " + letzte;
                    String hilfe = String.join(" ", zeilen).contains("TypeScript") ? "  ->  npm install -g typescript" : "";
                    String t = name + " Language Server beendet (Exit-Code " + code + ")" + grund + hilfe;
                    Main.log(t + (zeilen.isEmpty() ? "" : System.lineSeparator() + String.join(System.lineSeparator(), zeilen)));
                    hoerer.meldung(t, true);
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
                if (t != null && !t.contains("No content")) hoerer.meldung(name + ": " + t, true);
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
                for (Object item : Json.list(Json.get(m, "params", "items"))) {
                    String sektion = Json.str(Json.get(item, "section"));
                    l.add(sektion == null ? einstellungen : Json.get(einstellungen, sektion.split("\\.")));
                }
                ergebnis = l;
            }
            senden(Json.obj("jsonrpc", "2.0", "id", id, "result", ergebnis), false);
            return;
        }
        switch (methode) {
            case "textDocument/publishDiagnostics" -> hoerer.diagnosen(Json.str(Json.get(m, "params", "uri")), Json.list(Json.get(m, "params", "diagnostics")));
            case "language/status" -> {
                // jdtls: Starting, ProjectStatus, ServiceReady ...
                String art = Json.str(Json.get(m, "params", "type"));
                if ("ServiceReady".equals(art)) status = "an";
                else if ("Starting".equals(art)) status = "laedt";
                else if ("Error".equals(art)) hoerer.meldung(name + ": " + Json.str(Json.get(m, "params", "message")), true);
            }
            case "window/showMessage" -> {
                int art = Json.num(Json.get(m, "params", "type"), 3);
                hoerer.meldung(Json.str(Json.get(m, "params", "message")), art == 1);
            }
            default -> {
            }
        }
    }
}
