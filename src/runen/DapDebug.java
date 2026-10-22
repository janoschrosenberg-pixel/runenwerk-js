package runen;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * JS/TS-Debugger ueber das Debug Adapter Protocol mit js-debug (Node und Chrome, Source Maps).
 * js-debug laeuft als eigener Server (dapDebugServer.js); fuer das eigentliche Programm verlangt
 * er per startDebugging eine Kind-Sitzung auf demselben Port.
 */
final class DapDebug implements Debug.Backend {
    /** Variablen-Bereich (Local, Closure, Global) */
    record Bereich(Sitzung s, int ref) {}

    private final Debug.Ui ui;
    private final Process server;
    private final int port;
    private final List<Sitzung> sitzungen = new CopyOnWriteArrayList<>();
    private Sitzung halt;
    private int haltFaden;
    private volatile boolean aus;

    DapDebug(Debug.Ui ui, Process server, int port) {
        this.ui = ui;
        this.server = server;
        this.port = port;
    }

    @Override
    public String name() {
        return "JS/TS";
    }

    /** Hauptsitzung starten: art launch oder attach, konfig wie in launch.json */
    void starten(String art, Map<String, Object> konfig) {
        Thread t = new Thread(() -> {
            try {
                Sitzung s = new Sitzung(null);
                s.beginnen(art, konfig);
            } catch (IOException e) {
                ui.fehler("js-debug nicht erreichbar: " + e.getMessage());
                ende("keine Verbindung");
            }
        }, "dap-start");
        t.setDaemon(true);
        t.start();
    }

    private void ende(String grund) {
        if (aus) return;
        aus = true;
        for (Sitzung s : sitzungen) s.schliessen();
        server.descendants().forEach(ProcessHandle::destroy);
        server.destroy();
        ui.beendet(grund);
    }

    /** eine DAP-Verbindung zum js-debug-Server */
    final class Sitzung {
        private final Socket socket;
        private final OutputStream aus;
        private int seq = 1;
        private final Map<Integer, Consumer<Object>> offen = new ConcurrentHashMap<>();
        private final Sitzung eltern;
        private String art;
        private Map<String, Object> konfig;

        Sitzung(Sitzung eltern) throws IOException {
            this.eltern = eltern;
            IOException letzter = null;
            Socket so = null;
            for (int i = 0; i < 50 && so == null; i++) {
                try {
                    so = new Socket("127.0.0.1", port);
                } catch (IOException e) {
                    letzter = e;
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ie) {
                        throw new IOException("unterbrochen");
                    }
                }
            }
            if (so == null) throw letzter;
            socket = so;
            aus = socket.getOutputStream();
            sitzungen.add(this);
            Thread t = new Thread(this::lesen, "dap-lesen");
            t.setDaemon(true);
            t.start();
        }

        void beginnen(String art, Map<String, Object> konfig) {
            this.art = art;
            this.konfig = konfig;
            anfrage("initialize", Json.obj("clientID", "runenjs", "clientName", "RUNENWERK JS", "adapterID", String.valueOf(konfig.get("type")),
                    "linesStartAt1", true, "columnsStartAt1", true, "pathFormat", "path", "supportsVariableType", true,
                    "supportsStartDebuggingRequest", true, "supportsRunInTerminalRequest", false), r -> anfrage(art, konfig, a -> {
                        if (a == null && eltern == null) ende("Start fehlgeschlagen");
                    }));
        }

        synchronized void senden(Map<String, Object> m) {
            try {
                m.put("seq", seq++);
                byte[] b = Json.write(m).getBytes(StandardCharsets.UTF_8);
                aus.write(("Content-Length: " + b.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                aus.write(b);
                aus.flush();
            } catch (IOException e) {
                // Verbindung zu
            }
        }

        void anfrage(String befehl, Object args, Consumer<Object> antwort) {
            Map<String, Object> m = Json.obj("type", "request", "command", befehl, "arguments", args);
            synchronized (this) {
                offen.put(seq, antwort == null ? x -> {} : antwort);
                senden(m);
            }
        }

        private void lesen() {
            try (InputStream in = new BufferedInputStream(socket.getInputStream())) {
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
                    verarbeiten(Json.parse(new String(in.readNBytes(laenge), StandardCharsets.UTF_8)));
                }
            } catch (IOException | RuntimeException e) {
                sitzungen.remove(this);
                if (eltern == null) ende("Verbindung beendet");
            }
        }

        private void verarbeiten(Object m) {
            String typ = Json.str(Json.get(m, "type"));
            if ("response".equals(typ)) {
                Consumer<Object> c = offen.remove(Json.num(Json.get(m, "request_seq"), -1));
                if (!Boolean.TRUE.equals(Json.get(m, "success"))) {
                    String t = Json.str(Json.get(m, "message"));
                    Object fehlerText = Json.get(m, "body", "error", "format");
                    ui.fehler(Json.get(m, "command") + ": " + (fehlerText != null ? fehlerText : t));
                    if (c != null) c.accept(null);
                    return;
                }
                if (c != null) c.accept(Json.get(m, "body"));
                return;
            }
            if ("request".equals(typ)) {
                // Rueckfragen des Adapters
                String befehl = Json.str(Json.get(m, "command"));
                int nr = Json.num(Json.get(m, "seq"), 0);
                senden(Json.obj("type", "response", "request_seq", nr, "command", befehl, "success", true, "body", Json.obj()));
                if ("startDebugging".equals(befehl)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> k = (Map<String, Object>) Json.get(m, "arguments", "configuration");
                    String a = Json.str(Json.get(m, "arguments", "request"));
                    Thread t = new Thread(() -> {
                        try {
                            new Sitzung(this).beginnen(a == null ? "launch" : a, k);
                        } catch (IOException e) {
                            ui.fehler("Kind-Sitzung: " + e.getMessage());
                        }
                    }, "dap-kind");
                    t.setDaemon(true);
                    t.start();
                }
                return;
            }
            String ev = Json.str(Json.get(m, "event"));
            Object body = Json.get(m, "body");
            if (ev == null) return;
            switch (ev) {
                case "initialized" -> {
                    for (var e : ui.haltepunkte().entrySet()) setBreakpoints(e.getKey(), e.getValue());
                    anfrage("configurationDone", Json.obj(), null);
                }
                case "stopped" -> {
                    int faden = Json.num(Json.get(body, "threadId"), 0);
                    String grund = Json.str(Json.get(body, "reason"));
                    anfrage("stackTrace", Json.obj("threadId", faden, "levels", 100), r -> {
                        List<Debug.Frame> l = new ArrayList<>();
                        for (Object f : Json.list(Json.get(r, "stackFrames"))) {
                            String pfad = Json.str(Json.get(f, "source", "path"));
                            Path p = null;
                            if (pfad != null && !pfad.startsWith("<") && !pfad.contains("://")) {
                                try {
                                    p = Path.of(pfad);
                                } catch (RuntimeException e) {
                                    // kein Dateipfad
                                }
                            }
                            l.add(new Debug.Frame(String.valueOf(Json.get(f, "name")), p, Math.max(0, Json.num(Json.get(f, "line"), 1) - 1),
                                    Json.num(Json.get(f, "id"), 0)));
                        }
                        halt = this;
                        haltFaden = faden;
                        ui.angehalten(l, grund);
                    });
                }
                case "continued" -> ui.laeuft();
                case "output" -> {
                    String kat = Json.str(Json.get(body, "category"));
                    String text = Json.str(Json.get(body, "output"));
                    if (text != null && !"telemetry".equals(kat)) ui.ausgabe(text, "stderr".equals(kat));
                }
                case "terminated" -> {
                    if (eltern == null) ende("Programm beendet");
                }
                default -> {
                }
            }
        }

        void setBreakpoints(Path datei, List<Integer> zeilen) {
            List<Object> bp = new ArrayList<>();
            for (int z : zeilen) bp.add(Json.obj("line", z + 1));
            anfrage("setBreakpoints", Json.obj("source", Json.obj("path", datei.toString(), "name", datei.getFileName().toString()), "breakpoints", bp), null);
        }

        void schliessen() {
            try {
                socket.close();
            } catch (IOException e) {
                // egal
            }
        }
    }

    // ---------------------------------------------------------------- Backend
    @Override
    public void haltepunkte(Path datei, List<Integer> zeilen) {
        for (Sitzung s : sitzungen) s.setBreakpoints(datei, zeilen);
    }

    @Override
    public void weiter() {
        if (halt == null) return;
        halt.anfrage("continue", Json.obj("threadId", haltFaden), null);
        halt = null;
        ui.laeuft();
    }

    @Override
    public void schritt(int art) {
        if (halt == null) {
            ui.meldung("nicht angehalten");
            return;
        }
        halt.anfrage(art == 1 ? "stepIn" : art == 2 ? "stepOut" : "next", Json.obj("threadId", haltFaden), null);
        halt = null;
        ui.laeuft();
    }

    @Override
    public void stop() {
        if (!sitzungen.isEmpty()) sitzungen.get(0).anfrage("disconnect", Json.obj("terminateDebuggee", true), null);
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                // egal
            }
            ende("gestoppt");
        });
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void variablen(Debug.Frame f, Consumer<List<Debug.Var>> fertig) {
        Sitzung s = halt;
        if (s == null) {
            fertig.accept(List.of());
            return;
        }
        s.anfrage("scopes", Json.obj("frameId", f.ref()), r -> {
            List<Debug.Var> l = new ArrayList<>();
            for (Object b : Json.list(Json.get(r, "scopes"))) {
                int ref = Json.num(Json.get(b, "variablesReference"), 0);
                l.add(new Debug.Var(String.valueOf(Json.get(b, "name")), "", ref > 0 ? new Bereich(s, ref) : null, ref > 0));
            }
            fertig.accept(l);
        });
    }

    @Override
    public void kinder(Debug.Var v, Consumer<List<Debug.Var>> fertig) {
        if (!(v.ref instanceof Bereich b)) {
            fertig.accept(List.of());
            return;
        }
        b.s().anfrage("variables", Json.obj("variablesReference", b.ref()), r -> {
            List<Debug.Var> l = new ArrayList<>();
            for (Object x : Json.list(Json.get(r, "variables"))) {
                int ref = Json.num(Json.get(x, "variablesReference"), 0);
                l.add(new Debug.Var(String.valueOf(Json.get(x, "name")), String.valueOf(Json.get(x, "value")), ref > 0 ? new Bereich(b.s(), ref) : null, ref > 0));
            }
            fertig.accept(l);
        });
    }
}
