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
                inspekt = null;
                frameZeigen();
                modusAn();
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
                modusAus();
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
    /** im Panel mit ENTER geoeffnete Variable: voller Wert im Kasten */
    private Var inspekt;

    /** Tasten in der Statuszeile: im Code (Mode debugstep) und im Panel (Mode debug); {Taste, Funktion, kurz} */
    static final String[][] TASTEN_CODE = {
            {"n", "drueber", "drue"}, {"i", "hinein", "hin"}, {"o", "heraus", "her"}, {"c", "weiter", "wei"},
            {"p", "Haltepunkt", "HP"}, {"x", "stop", "x"}, {"TAB", "Variablen", "Var"}, {"S-TAB", "Stapel", "Stap"},
            {"hjkl", "umsehen", "seh"}, {"ESC", "bearbeiten", "bearb"}};
    static final String[][] TASTEN_VARS = {
            {"ENTER", "inspizieren", "insp"}, {"j/k", "waehlen", "wae"}, {"TAB", "Stapel", "Stap"}, {"S-TAB", "Code", "Code"},
            {"y", "kopieren", "kop"}, {"n", "drueber", "drue"}, {"i", "hinein", "hin"}, {"o", "heraus", "her"},
            {"c", "weiter", "wei"}, {"x", "stop", "x"}, {"ESC", "Code", "Code"}};
    static final String[][] TASTEN_STAPEL = {
            {"ENTER", "Frame zeigen", "Frame"}, {"j/k", "waehlen", "wae"}, {"TAB", "Code", "Code"}, {"S-TAB", "Variablen", "Var"},
            {"n", "drueber", "drue"}, {"i", "hinein", "hin"}, {"o", "heraus", "her"}, {"c", "weiter", "wei"},
            {"x", "stop", "x"}, {"ESC", "Code", "Code"}};
    static final String[][] TASTEN_INSPEKT = {{"ENTER", "schliessen", "zu"}, {"ESC", "schliessen", "zu"}, {"y", "kopieren", "kop"}};

    /** Tasten fuer die Statuszeile im aktuellen Mode, null ausserhalb des Debuggers */
    String[][] tastenJetzt() {
        return switch (ed.modus) {
            case "debugstep" -> TASTEN_CODE;
            case "debug" -> inspekt != null ? TASTEN_INSPEKT : bereich == 0 ? TASTEN_STAPEL : TASTEN_VARS;
            default -> null;
        };
    }

    /** Anzeige des Modes in der Statuszeile */
    String modusName() {
        return ed.modus.equals("debugstep") ? "DEBUG" : bereich == 0 ? "DEBUG STAPEL" : "DEBUG VARIABLEN";
    }

    /** angehalten: in den Debug-Modus (aus Insert/Visual sauber heraus); offene Dialoge bleiben */
    private void modusAn() {
        switch (ed.modus) {
            case "insert", "replace" -> ed.befehl("switchState", "normal");
            case "visual", "operator", "char" -> ed.vim.visualEnde();
            case "normal", "debugstep", "debug", "nurlesen" -> {
            }
            default -> {
                ed.meldung = "Debugger angehalten - Leertaste d s fuer den Debug-Modus";
                return;
            }
        }
        if (!ed.modus.equals("debug")) ed.modus = "debugstep";
    }

    private void modusAus() {
        inspekt = null;
        if (ed.modus.equals("debug") || ed.modus.equals("debugstep")) ed.modus = "normal";
    }
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
        /** @binding Haltepunkt in der Zeile an/aus */
        c.put("debugBreakpoint", a -> haltepunktUmschalten());
        /** @binding Entfernt alle Haltepunkte */
        c.put("debugClearBreakpoints", a -> {
            punkte.clear();
            ed.meldung = "alle Haltepunkte entfernt";
        });
        /** @binding Debugger: weiter */
        c.put("debugContinue", a -> {
            if (bereit()) backend.weiter();
        });
        /** @binding Debugger: naechste Zeile (step over) */
        c.put("debugOver", a -> {
            if (bereit()) backend.schritt(0);
        });
        /** @binding Debugger: hinein (step into) */
        c.put("debugInto", a -> {
            if (bereit()) backend.schritt(1);
        });
        /** @binding Debugger: heraus (step out) */
        c.put("debugOut", a -> {
            if (bereit()) backend.schritt(2);
        });
        /** @binding Debugger: beenden */
        c.put("debugStop", a -> {
            if (!bereit()) return;
            Backend b = backend;
            backend = null;
            b.stop();
            frames = List.of();
            status = "aus";
            modusAus();
        });
        /** @binding Oeffnet das Debug-Panel (Aufrufstapel und Variablen) */
        c.put("debugPanel", a -> {
            offen = true;
            ed.konsoleOffen = false;
            ed.modus = "debug";
        });
        /** @binding Debug-Panel: zurueck in den Code (Debug-Modus, ohne Debugger in den Normal-Mode); schliesst erst die geoeffnete Variable */
        c.put("debugBack", a -> {
            if (inspekt != null) inspekt = null;
            else ed.modus = aktiv() ? "debugstep" : "normal";
        });
        /** @binding Debug-Panel: schliessen */
        c.put("debugClose", a -> {
            offen = false;
            inspekt = null;
            if (ed.modus.equals("debug")) ed.modus = aktiv() ? "debugstep" : "normal";
        });
        /** @binding In den Debug-Modus: Schritte mit einzelnen Tasten, Tasten in der Statuszeile */
        c.put("debugMode", a -> {
            if (!bereit()) return;
            offen = true;
            ed.konsoleOffen = false;
            ed.modus = "debugstep";
        });
        /** @binding Debug-Modus verlassen und den Text bearbeiten (der Debugger bleibt stehen; Leertaste d s zurueck) */
        c.put("debugLeave", a -> {
            ed.modus = "normal";
            ed.meldung = "Debug-Modus verlassen (Leertaste d s zurueck)";
        });
        /**
         * @binding Fenster weiter: Code -> Variablen -> Aufrufstapel -> Code
         * @binding-arg 1 = vorwaerts
         * @binding-arg -1 = rueckwaerts
         */
        c.put("debugSwitch", a -> {
            int r = a.trim().equals("-1") ? -1 : 1;
            // Reihenfolge: 0 Code, 1 Variablen, 2 Stapel
            int jetzt = !ed.modus.equals("debug") ? 0 : bereich == 1 ? 1 : 2;
            int neu = Math.floorMod(jetzt + r, 3);
            inspekt = null;
            if (neu == 0) {
                ed.modus = aktiv() ? "debugstep" : "normal";
                return;
            }
            offen = true;
            ed.konsoleOffen = false;
            bereich = neu == 1 ? 1 : 0;
            ed.modus = "debug";
        });
        /** @binding Debug-Panel: Eintrag runter */
        c.put("debugDown", a -> bewegen(1));
        /** @binding Debug-Panel: Eintrag hoch */
        c.put("debugUp", a -> bewegen(-1));
        /** @binding Debug-Panel: Stapelrahmen zeigen bzw. Variable aufklappen */
        c.put("debugEnter", a -> {
            if (inspekt != null) {
                inspekt = null;
                return;
            }
            if (bereich == 0) {
                frameZeigen();
                return;
            }
            List<Object[]> l = new ArrayList<>();
            flach(vars, 0, l);
            if (varWahl >= l.size()) return;
            Var v = (Var) l.get(varWahl)[0];
            // Objekte/Listen klappen auf, einfache Werte zeigen den vollen Wert
            if (v.kannAuf) aufklappen(v);
            else inspekt = v;
        });
        /** @binding Debug-Panel: voller Wert der Variable im Kasten (auch fuer Objekte) */
        c.put("debugInspect", a -> {
            List<Object[]> l = new ArrayList<>();
            flach(vars, 0, l);
            inspekt = inspekt == null && bereich == 1 && varWahl < l.size() ? (Var) l.get(varWahl)[0] : null;
        });
        /** @binding Debug-Panel: kopiert den Wert der Variable */
        c.put("debugYank", a -> {
            String w = kopierText();
            if (w != null) {
                Editor.zwischenablage(w);
                ed.meldung = "Wert kopiert";
            }
        });
    }

    /** Wert der gewaehlten Variable (Strg+C) */
    String kopierText() {
        if (inspekt != null) return inspekt.wert;
        List<Object[]> l = new ArrayList<>();
        flach(vars, 0, l);
        return varWahl < l.size() ? ((Var) l.get(varWahl)[0]).wert : null;
    }

    private void bewegen(int d) {
        inspekt = null;
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
        boolean fokus = ed.modus.equals("debug"), schritt = ed.modus.equals("debugstep");
        s.rect(0, y0, W, h, E_KONSOLE);
        s.frame(4, y0, W - 8, h, fokus || schritt ? E_HELL : E_TEXT);
        s.frame(5, y0 + 1, W - 10, h - 2, E_MATT);
        // die Tasten stehen im Debug-Modus in der Statuszeile
        String titel = " DEBUG " + (backend == null ? "" : backend.name() + " ") + status
                + (fokus || schritt ? " " : "  (Leertaste d s: Debug-Modus) ");
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
        if (inspekt != null && fokus) inspektMalen(teil + 2 * cw, y + lh + 2, W - teil - 4 * cw, h - 3 * lh);
    }

    /** Kasten mit Name und vollem Wert der Variable (umbrochen) ueber der Variablenliste */
    private void inspektMalen(int x, int y, int w, int h) {
        Screen s = ed.scr;
        int lh = s.lh, cw = s.cw;
        int spalten = Math.max(10, w / cw - 4);
        List<String> z = new ArrayList<>();
        for (String teil : inspekt.wert.split("\\R", -1)) {
            if (teil.isEmpty()) z.add("");
            for (int i = 0; i < teil.length(); i += spalten) z.add(teil.substring(i, Math.min(teil.length(), i + spalten)));
        }
        int max = Math.max(1, h / lh - 3);
        boolean mehr = z.size() > max;
        if (mehr) z = new ArrayList<>(z.subList(0, max - 1));
        int kh = (z.size() + (mehr ? 3 : 2)) * lh + 8;
        s.rect(x + 6, y + 6, w, kh, 0x000000);
        s.rect(x, y, w, kh, E_KONSOLE);
        s.frame(x, y, w, kh, E_HELL);
        s.clip(x + 2, y + 2, w - 4, kh - 4);
        s.text(x + cw, y + 4, inspekt.name + "  (" + inspekt.wert.length() + " Zeichen)", E_WARN);
        for (int i = 0; i < z.size(); i++) s.text(x + cw, y + 4 + (i + 1) * lh, z.get(i), E_TEXT);
        if (mehr) s.text(x + cw, y + 4 + (z.size() + 1) * lh, "... (y kopiert den ganzen Wert)", E_MATT);
        s.noClip();
    }
}
