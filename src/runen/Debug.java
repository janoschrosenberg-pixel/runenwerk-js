package runen;

import static runen.Editor.E_GRUND;
import static runen.Editor.E_HELL;
import static runen.Editor.E_INFO;
import static runen.Editor.E_KONSOLE;
import static runen.Editor.E_MATT;
import static runen.Editor.E_TEXT;
import static runen.Editor.E_WARN;
import static runen.Editor.E_ZEILE;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/**
 * Gemeinsame Oberflaeche fuer beide Debugger (Java ueber JDI, JS/TS ueber js-debug): Haltepunkte,
 * Aufrufstapel, Variablen, Schritte. Die Anbindungen melden sich ueber {@link Ui}.
 */
final class Debug {
    /** ein Eintrag im Aufrufstapel; zeile ab 0 */
    record Frame(String name, Path datei, int zeile, Object ref) {}

    /** eine Variable (aufklappbar, wenn kannAuf) */
    static final class Var {
        final String name, wert;
        final Object ref;
        final boolean kannAuf;
        boolean offen;
        List<Var> kinder;

        Var(String name, String wert, Object ref, boolean kannAuf) {
            this.name = name;
            this.wert = wert;
            this.ref = ref;
            this.kannAuf = kannAuf;
        }
    }

    /** eine Debugger-Anbindung */
    interface Backend {
        String name();

        void haltepunkte(Path datei, List<Integer> zeilen);

        void weiter();

        /** 0 drueber, 1 hinein, 2 heraus */
        void schritt(int art);

        void stop();

        void variablen(Frame f, Consumer<List<Var>> fertig);

        void kinder(Var v, Consumer<List<Var>> fertig);
    }

    /**
     * Rueckmeldungen einer Anbindung (aus beliebigen Faeden). Jede Sitzung hat ihre eigene Ui;
     * Meldungen einer abgeloesten Sitzung werden verworfen.
     */
    final class Ui {
        Backend besitzer;

        private boolean aktuell() {
            return besitzer == null || besitzer == backend;
        }

        void angehalten(List<Frame> frames, String grund) {
            SwingUtilities.invokeLater(() -> {
                if (!aktuell()) return;
                Debug.this.frames = frames;
                frameWahl = 0;
                status = "angehalten" + (grund == null || grund.isEmpty() ? "" : " (" + grund + ")");
                offen = true;
                ed.konsoleOffen = false;
                frameZeigen();
                ed.neuMalen.run();
            });
        }

        void laeuft() {
            SwingUtilities.invokeLater(() -> {
                if (!aktuell()) return;
                frames = List.of();
                vars = List.of();
                status = "laeuft";
                ed.neuMalen.run();
            });
        }

        void beendet(String grund) {
            SwingUtilities.invokeLater(() -> {
                if (besitzer != null && backend != null && besitzer != backend) return;
                backend = null;
                frames = List.of();
                vars = List.of();
                status = "aus";
                ed.meldung = "Debugger beendet" + (grund == null ? "" : ": " + grund);
                ed.farbig("[debug] beendet" + (grund == null ? "" : ": " + grund), E_MATT);
                if (ed.modus.equals("debug")) ed.modus = "normal";
                ed.neuMalen.run();
            });
        }

        void ausgabe(String text, boolean fehler) {
            SwingUtilities.invokeLater(() -> {
                for (String z : text.split("\\R")) if (!z.isEmpty()) ed.farbig(z, fehler ? Editor.E_FEHLER : E_TEXT);
                ed.neuMalen.run();
            });
        }

        void meldung(String text) {
            SwingUtilities.invokeLater(() -> {
                ed.meldung = text;
                ed.farbig("[debug] " + text, E_INFO);
                ed.neuMalen.run();
            });
        }

        void fehler(String text) {
            SwingUtilities.invokeLater(() -> {
                ed.fehler("[debug] " + text);
                ed.neuMalen.run();
            });
        }

        /** Haltepunkte, die gesetzt werden sollen (beim Verbinden) */
        Map<Path, List<Integer>> haltepunkte() {
            Map<Path, List<Integer>> m = new HashMap<>();
            for (var e : punkte.entrySet()) if (!e.getValue().isEmpty()) m.put(e.getKey(), new ArrayList<>(e.getValue()));
            return m;
        }

        /** Datei zu einem Quellpfad wie demo/Rechner.java im Java-Arbeitsbereich */
        Path quelle(String pfad) {
            String p = pfad.replace('\\', '/');
            for (Indexer.Datei d : ed.index.dateien) {
                String f = d.pfad().toString().replace('\\', '/');
                if (f.endsWith("/" + p)) return d.pfad();
            }
            return null;
        }
    }

    private final Editor ed;
    /** neue Rueckmeldung fuer eine Sitzung */
    Ui neueUi() {
        return new Ui();
    }
    Backend backend;
    String status = "aus";
    /** Haltepunkte je Datei (Zeilen ab 0) */
    final Map<Path, TreeSet<Integer>> punkte = new HashMap<>();
    List<Frame> frames = List.of();
    int frameWahl;
    List<Var> vars = List.of();
    private int varWahl;
    /** 0 Aufrufstapel, 1 Variablen */
    private int bereich = 1;
    boolean offen;
    private int stOben, vOben;

    Debug(Editor ed) {
        this.ed = ed;
    }

    boolean aktiv() {
        return backend != null;
    }

    boolean angehalten() {
        return backend != null && !frames.isEmpty();
    }

    /** aktuelle Zeile im Text (fuer die Markierung) oder -1 */
    int haltZeile(Doc d) {
        if (!angehalten() || frameWahl >= frames.size()) return -1;
        Frame f = frames.get(frameWahl);
        return f.datei() != null && f.datei().equals(d.pfad) ? f.zeile() : -1;
    }

    void starten(Backend b, Ui ui) {
        Backend alt = backend;
        ui.besitzer = b;
        backend = b;
        if (alt != null) alt.stop();
        status = "startet";
        offen = true;
    }

    void haltepunktUmschalten() {
        Doc d = ed.d();
        if (d.pfad == null) {
            ed.meldung = "erst speichern";
            return;
        }
        int z = d.zeileVon(d.cursor);
        TreeSet<Integer> s = punkte.computeIfAbsent(d.pfad, k -> new TreeSet<>());
        if (!s.remove(z)) s.add(z);
        ed.meldung = (s.contains(z) ? "Haltepunkt gesetzt: " : "Haltepunkt entfernt: ") + d.name() + ":" + (z + 1);
        if (backend != null) backend.haltepunkte(d.pfad, new ArrayList<>(s));
    }

    private void frameZeigen() {
        if (frameWahl >= frames.size()) return;
        Frame f = frames.get(frameWahl);
        if (f.datei() != null) {
            Doc d = ed.oeffnen(f.datei());
            if (d != null) {
                d.cursor = d.stelle(f.zeile(), 0);
                while (d.at(d.cursor) == ' ' || d.at(d.cursor) == '\t') d.cursor++;
            }
        } else {
            ed.meldung = "Quelle nicht gefunden: " + f.name();
        }
        vars = List.of();
        varWahl = 0;
        Backend b = backend;
        if (b != null) {
            b.variablen(f, l -> SwingUtilities.invokeLater(() -> {
                vars = l;
                // den ersten Bereich (lokale Variablen) gleich aufklappen
                if (!l.isEmpty() && l.get(0).kannAuf && l.get(0).ref instanceof DapDebug.Bereich) aufklappen(l.get(0));
                ed.neuMalen.run();
            }));
        }
    }

    private void aufklappen(Var v) {
        if (!v.kannAuf) return;
        if (v.offen) {
            v.offen = false;
            return;
        }
        v.offen = true;
        if (v.kinder != null) return;
        Backend b = backend;
        if (b == null) return;
        v.kinder = List.of(new Var("...", "", null, false));
        b.kinder(v, l -> SwingUtilities.invokeLater(() -> {
            v.kinder = l;
            ed.neuMalen.run();
        }));
    }

    /** sichtbare Variablen (aufgeklappte mit Kindern), mit Tiefe */
    private void flach(List<Var> l, int tiefe, List<Object[]> r) {
        for (Var v : l) {
            r.add(new Object[] {v, tiefe});
            if (v.offen && v.kinder != null) flach(v.kinder, tiefe + 1, r);
        }
    }

    private boolean bereit() {
        if (backend == null) {
            ed.meldung = "kein Debugger aktiv (Leertaste d d startet)";
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- Befehle
    void befehle(Map<String, Editor.Cmd> c, Werkzeug wz) {
        c.put("debugBreakpoint", a -> haltepunktUmschalten());
        c.put("debugClearBreakpoints", a -> {
            punkte.clear();
            ed.meldung = "alle Haltepunkte entfernt";
        });
        c.put("debugContinue", a -> {
            if (bereit()) backend.weiter();
        });
        c.put("debugOver", a -> {
            if (bereit()) backend.schritt(0);
        });
        c.put("debugInto", a -> {
            if (bereit()) backend.schritt(1);
        });
        c.put("debugOut", a -> {
            if (bereit()) backend.schritt(2);
        });
        c.put("debugStop", a -> {
            if (!bereit()) return;
            Backend b = backend;
            backend = null;
            b.stop();
            frames = List.of();
            status = "aus";
            if (ed.modus.equals("debug")) ed.modus = "normal";
        });
        c.put("debugPanel", a -> {
            offen = true;
            ed.konsoleOffen = false;
            ed.modus = "debug";
        });
        c.put("debugBack", a -> ed.modus = "normal");
        c.put("debugClose", a -> {
            offen = false;
            if (ed.modus.equals("debug")) ed.modus = "normal";
        });
        c.put("debugSwitch", a -> bereich = 1 - bereich);
        c.put("debugDown", a -> bewegen(1));
        c.put("debugUp", a -> bewegen(-1));
        c.put("debugEnter", a -> {
            if (bereich == 0) {
                frameZeigen();
                return;
            }
            List<Object[]> l = new ArrayList<>();
            flach(vars, 0, l);
            if (varWahl < l.size()) aufklappen((Var) l.get(varWahl)[0]);
        });
        c.put("debugYank", a -> {
            List<Object[]> l = new ArrayList<>();
            flach(vars, 0, l);
            if (varWahl < l.size()) {
                Var v = (Var) l.get(varWahl)[0];
                Editor.zwischenablage(v.wert);
                ed.meldung = "Wert kopiert";
            }
        });
    }

    private void bewegen(int d) {
        if (bereich == 0) {
            int alt = frameWahl;
            frameWahl = Math.max(0, Math.min(frames.size() - 1, frameWahl + d));
            if (frameWahl != alt) frameZeigen();
        } else {
            List<Object[]> l = new ArrayList<>();
            flach(vars, 0, l);
            varWahl = Math.max(0, Math.min(l.size() - 1, varWahl + d));
        }
    }

    // ---------------------------------------------------------------- Malen
    void malen(int y0) {
        Screen s = ed.scr;
        int W = s.w, lh = s.lh, cw = s.cw;
        int h = s.h - ed.unten() - 6 - y0;
        boolean fokus = ed.modus.equals("debug");
        s.rect(0, y0, W, h, E_KONSOLE);
        s.frame(4, y0, W - 8, h, fokus ? E_HELL : E_TEXT);
        s.frame(5, y0 + 1, W - 10, h - 2, E_MATT);
        String titel = " DEBUG " + (backend == null ? "" : backend.name() + " ") + status
                + (fokus ? "  (c weiter, n drueber, i hinein, o heraus, x stop, TAB, ENTER, ESC) " : " ");
        s.rect(3 * cw, y0 - lh / 2, s.textWidth(titel), lh, E_KONSOLE);
        s.text(3 * cw, y0 - lh / 2, titel, E_HELL);
        int teil = W * 2 / 5;
        int zeilen = Math.max(1, (h - lh * 2) / lh);
        int y = y0 + lh / 2 + 4;
        s.rect(teil, y0 + 6, 1, h - 12, E_MATT);
        // Aufrufstapel
        s.clip(8, y0 + 2, teil - 12, h - 4);
        s.text(2 * cw, y, "Aufrufstapel", bereich == 0 && fokus ? E_WARN : E_MATT);
        if (frameWahl < stOben) stOben = frameWahl;
        if (frameWahl >= stOben + zeilen) stOben = frameWahl - zeilen + 1;
        for (int k = 0; k < zeilen && stOben + k < frames.size(); k++) {
            int i = stOben + k;
            Frame f = frames.get(i);
            int zy = y + lh + 2 + k * lh;
            boolean g = i == frameWahl;
            if (g) s.rect(8, zy, teil - 16, lh, fokus && bereich == 0 ? E_TEXT : E_ZEILE);
            int farbe = g && fokus && bereich == 0 ? E_GRUND : f.datei() == null ? E_MATT : E_TEXT;
            String ort = f.datei() == null ? "" : "  " + f.datei().getFileName() + ":" + (f.zeile() + 1);
            s.text(2 * cw, zy, f.name() + ort, farbe);
        }
        s.noClip();
        // Variablen
        s.clip(teil + 4, y0 + 2, W - teil - 12, h - 4);
        int vx = teil + 2 * cw;
        s.text(vx, y, "Variablen", bereich == 1 && fokus ? E_WARN : E_MATT);
        List<Object[]> l = new ArrayList<>();
        flach(vars, 0, l);
        if (varWahl >= l.size()) varWahl = Math.max(0, l.size() - 1);
        if (varWahl < vOben) vOben = varWahl;
        if (varWahl >= vOben + zeilen) vOben = varWahl - zeilen + 1;
        for (int k = 0; k < zeilen && vOben + k < l.size(); k++) {
            int i = vOben + k;
            Var v = (Var) l.get(i)[0];
            int tiefe = (int) l.get(i)[1];
            int zy = y + lh + 2 + k * lh;
            boolean g = i == varWahl && fokus && bereich == 1;
            if (g) s.rect(teil + 4, zy, W - teil - 16, lh, E_TEXT);
            int x = vx + tiefe * 2 * cw;
            x = s.text(x, zy, v.kannAuf ? (v.offen ? "- " : "+ ") : "  ", g ? E_GRUND : E_MATT);
            x = s.text(x, zy, v.name, g ? E_GRUND : E_INFO);
            if (!v.wert.isEmpty()) {
                x = s.text(x, zy, " = ", g ? E_GRUND : E_MATT);
                s.text(x, zy, v.wert.replace('\n', ' '), g ? E_GRUND : E_TEXT);
            }
        }
        if (l.isEmpty()) s.text(vx, y + lh + 2, angehalten() ? "..." : "(laeuft - Haltepunkt setzen mit Leertaste d b)", E_MATT);
        s.noClip();
    }
}
