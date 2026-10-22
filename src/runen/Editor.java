package runen;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Der Editor: Zustand, Befehle (dieselben Namen wie die RUNENWERK-Editor-Lib, damit
 * keymap.config gleich aussieht), Tastenverarbeitung wie im RUNENWERK-Kernel und Malen.
 */
final class Editor {
    // Farben wie beim RUNENWERK-Editor
    static final int E_GRUND = 0x020702, E_RASTER = 0x061006, E_LEISTE = 0x0A2412, E_TEXT = 0x33E070, E_HELL = 0x8CFFB0, E_MATT = 0x1D7A3D,
            E_FEHLER = 0xFF3B5C, E_WARN = 0xFFB000, E_ZEILE = 0x071A0C, E_KONSOLE = 0x010401, E_INFO = 0x7FD4FF;
    /** je Klasse aus {@link Highlight} */
    static final int[] FARBEN = {0x33E070, 0x5CFFE6, 0xFF9F43, 0x1D7A3D, 0xE8FF5C, 0x7FD4FF, 0x5CFFE6, 0x8CFFB0, 0x2FB85A, 0xE8FF5C, 0xFFB000};

    interface Cmd {
        void run(String arg);
    }

    record Sprung(Path pfad, int pos) {}

    final Screen scr = new Screen();
    final Map<String, Cmd> cmds = new HashMap<>();
    final Keymap km = new Keymap();
    final Mapping mp = new Mapping();
    final Indexer index;
    final Lsp tsLsp, javaLsp;
    final FileManager fm;
    final Vim vim;
    final Werkzeug wz;
    Runnable neuMalen = () -> {}, beenden = () -> {}, vollbild = () -> {};

    // Einstellungen (config.txt neben der Jar)
    final Path konfig;
    final Konfig kf;
    Path keymapPfad, mappingPfad;
    int schriftGroesse = 15, einzug = 2, tabBreite = 4;
    String schrift = "JetBrains Mono, Cascadia Mono, Consolas, SF Mono, Menlo, DejaVu Sans Mono, Liberation Mono, Monospaced";
    boolean autoVervollst = true;
    double skala = 1;
    List<String> lspBefehl = List.of("typescript-language-server", "--stdio");
    String compileBefehl = "npm run build", compileFastBefehl = "npx tsc --noEmit", javaCompileBefehl = "";
    final List<String> startDateien = new ArrayList<>();

    /** TypeScript-Arbeitsbereich (Hauptordner) und Java-Arbeitsbereich */
    Path arbeitsordner, javaOrdner;
    final List<Doc> tabs = new ArrayList<>();
    int aktiv;
    String modus = "normal";

    // Tastenfolgen
    private String folge = "", folgeBefehl;
    private final Timer folgeUhr;
    private final Timer lspUhr;

    // Terminal
    final List<String> konsole = new ArrayList<>();
    final List<Integer> konsoleFarbe = new ArrayList<>();
    final StringBuilder eingabe = new StringBuilder();
    final List<String> verlauf = new ArrayList<>();
    int verlaufPos;
    boolean konsoleOffen;
    private String konsoleVorModus = "normal";
    private Process laufend;

    // Fenster mit Eingabe
    boolean popupOffen;
    String popupTitel = "", popupBefehl = "", popupVorModus = "normal";
    final StringBuilder popupEingabe = new StringBuilder();

    String meldung;
    int meldungFarbe = E_WARN;
    /** Hover-Text des Language Servers */
    List<String> info;

    // Vervollstaendigung
    List<Map<String, Object>> vorschlaege = List.of();
    List<Map<String, Object>> gefiltert = List.of();
    int vorschlagWahl, vorschlagAnf;
    boolean vorschlagOffen;
    private String vorschlagVorModus = "insert";

    final Deque<Sprung> spruenge = new ArrayDeque<>();
    String suche = "";
    boolean sucheZeigen;
    final Map<String, List<Doc.Diag>> diagnosen = new HashMap<>();

    private String vorGruppe, jetztGruppe;
    private int tiefe;
    /** beim Wiederholen mit Zaehler nur ein Undo-Stand */
    private boolean merkSperre;

    Editor(Path konfig) {
        this.konfig = konfig;
        kf = new Konfig(konfig.resolve("config.txt"));
        keymapPfad = konfig.resolve("keymap.config");
        mappingPfad = konfig.resolve("mapping");
        index = new Indexer(() -> SwingUtilities.invokeLater(() -> {
            Editor.this.fm.indexFertig();
            neuMalen.run();
        }));
        tsLsp = new Lsp("TS", hoerer("TS"));
        javaLsp = new Lsp("Java", hoerer("Java"));
        javaLsp.hinweis = "jdtls ist in der Jar eingebaut; diese Jar wurde ohne gebaut (oder java.lsp.home in config.txt setzen)";
        fm = new FileManager(this);
        vim = new Vim(this);
        wz = new Werkzeug(this);
        folgeUhr = new Timer(1000, e -> {
            // angefangene Folge nach 1 s allein ausfuehren
            String alt = folgeBefehl;
            folge = "";
            folgeBefehl = null;
            if (alt != null) ausfuehren(alt, 0);
            nachBefehl();
            neuMalen.run();
        });
        folgeUhr.setRepeats(false);
        lspUhr = new Timer(250, e -> lspSync());
        lspUhr.setRepeats(false);
        befehle();
        fm.befehle(cmds);
        vim.befehle(cmds);
        wz.befehle(cmds);
        tabs.add(new Doc());
    }

    private Lsp.Hoerer hoerer(String name) {
        return new Lsp.Hoerer() {
            public void diagnosen(String uri, List<Object> l) {
                diagnosenSetzen(uri, l);
                neuMalen.run();
            }

            public void meldung(String text, boolean fehler) {
                if (fehler) fehler(text);
                else print(text);
                neuMalen.run();
            }

            public void bereit() {
                print(name + " Language Server bereit");
                lspSync();
                neuMalen.run();
            }
        };
    }

    /** Language Server fuer einen Text (TS/JS oder Java), sonst null */
    Lsp lspFuer(Doc d) {
        String a = d.lspArt();
        if (a == null) return null;
        return a.equals("java") ? javaLsp : tsLsp;
    }

    Doc d() {
        return tabs.get(aktiv);
    }

    // ================================================================ Start und Einstellungen
    void start(String[] args) {
        konfigLesen();
        keymapLaden();
        modus = km.start;
        Path ziel = null;
        for (String a : args) {
            Path p = Paths.get(a).toAbsolutePath().normalize();
            if (Files.isDirectory(p) && ziel == null) ziel = p;
            else startDateien.add(p.toString());
        }
        if (ziel == null && !startDateien.isEmpty()) ziel = Paths.get(startDateien.get(0)).getParent();
        String ts = kf.get("ts.workspace", null), java = kf.get("java.workspace", null);
        if (ziel == null && ts != null) ziel = aufloesenVon(konfig, ts);
        if (java != null) {
            Path j = aufloesenVon(konfig, java);
            if (Files.isDirectory(j)) javaOrdner = j;
            else fehler("java.workspace gibt es nicht: " + j);
        }
        if (ziel != null && Files.isDirectory(ziel)) {
            arbeitsordnerSetzen(ziel);
        } else if (javaOrdner != null) {
            arbeitsbereicheAn();
        } else {
            popupOpen("TypeScript-Arbeitsbereich waehlen (wird in config.txt gespeichert):", "workdir", ts != null ? ts : System.getProperty("user.home"));
        }
        print("RUNENWERK JS - Editor fuer TypeScript, React, Java und SQL. help zeigt die Befehle. Konfiguration: " + kf.pfad);
    }

    private static Path aufloesenVon(Path basis, String s) {
        s = s.trim();
        if (s.startsWith("~")) s = System.getProperty("user.home") + s.substring(1);
        return basis.resolve(s).toAbsolutePath().normalize();
    }

    void konfigLesen() {
        kf.lesen();
        for (String f : kf.fehler) farbig(f, E_WARN);
        schriftGroesse = Math.max(6, Math.min(72, kf.zahl("font.size", 15)));
        schrift = kf.get("font", schrift);
        einzug = Math.max(1, kf.zahl("indent", 2));
        tabBreite = Math.max(1, kf.zahl("tabWidth", 4));
        autoVervollst = kf.an("autoComplete", true);
        index.maxGroesse = kf.zahl("maxFileSize", 1024) * 1024L;
        String l = kf.get("ts.lsp", "typescript-language-server --stdio");
        lspBefehl = l.isBlank() || l.equals("off") ? List.of() : List.of(l.split("\\s+"));
        keymapPfad = konfig.resolve(kf.get("keymap", "keymap.config"));
        mappingPfad = konfig.resolve(kf.get("mapping", "mapping"));
        compileBefehl = kf.get("compile", "npm run build");
        compileFastBefehl = kf.get("compileFast", "npx tsc --noEmit");
        javaCompileBefehl = kf.get("java.compile", "");
        startDateien.clear();
        startDateien.addAll(kf.alle("open"));
        wz.db.konfigurieren(kf, konfig);
        for (String f : wz.db.fehler) farbig(f, E_WARN);
    }

    /** set NAME WERT im Terminal: wie in config.txt, nur fuer diese Sitzung */
    void einstellung(String rest) {
        String[] t = rest.split("\\s+", 2);
        String wert = t.length > 1 ? t[1] : "";
        try {
            switch (t[0]) {
                case "fontSize", "font.size" -> schriftGroesse = Math.max(6, Math.min(72, Integer.parseInt(wert)));
                case "font" -> schrift = wert;
                case "indent" -> einzug = Math.max(1, Integer.parseInt(wert));
                case "tabWidth" -> tabBreite = Math.max(1, Integer.parseInt(wert));
                case "autoComplete" -> autoVervollst = wert.equals("on") || wert.equals("true");
                case "maxFileSize" -> index.maxGroesse = Long.parseLong(wert) * 1024;
                default -> {
                    fehler("unbekannte Einstellung " + t[0] + " (font.size, font, indent, tabWidth, autoComplete, maxFileSize)");
                    return;
                }
            }
        } catch (NumberFormatException e) {
            fehler("keine Zahl: " + wert);
            return;
        }
        schriftAn();
    }

    void schriftAn() {
        scr.setFont(schrift, (int) Math.round(schriftGroesse * skala));
    }

    void keymapLaden() {
        // die Vorgabe aus der Jar zuerst, keymap.config ergaenzt und ueberschreibt sie
        km.lesen(keymapPfad, Main.class.getResourceAsStream("/vorgaben/keymap.config"));
        for (String f : km.fehler) farbig(f, E_WARN);
        mp.laden(mappingPfad);
        for (String f : mp.fehler) farbig(f, E_WARN);
        print("keymap: Layout " + km.layout + ", Modes " + km.modes.size() + ", Mapping-Funktionen " + mp.funs.size());
    }

    /** TypeScript-Arbeitsbereich setzen (Index und Language Server neu) */
    void arbeitsordnerSetzen(Path p) {
        p = p.toAbsolutePath().normalize();
        if (!Files.isDirectory(p)) {
            fehler("kein Ordner: " + p);
            return;
        }
        arbeitsordner = p;
        arbeitsbereicheAn();
        if (!lspBefehl.isEmpty()) tsLsp.start(p, lspBefehl);
        for (String s : startDateien) oeffnen(aufloesen(s));
        startDateien.clear();
    }

    /** Java-Arbeitsbereich setzen */
    void javaOrdnerSetzen(Path p) {
        p = p.toAbsolutePath().normalize();
        if (!Files.isDirectory(p)) {
            fehler("kein Ordner: " + p);
            return;
        }
        javaOrdner = p;
        arbeitsbereicheAn();
    }

    /** Index ueber beide Arbeitsbereiche, Java Language Server */
    void arbeitsbereicheAn() {
        diagnosen.clear();
        for (Doc t : tabs) {
            t.lspOffen = false;
            t.diags = new ArrayList<>();
        }
        List<Indexer.Wurzel> w = new ArrayList<>();
        if (arbeitsordner != null) {
            w.add(new Indexer.Wurzel(arbeitsordner, name(arbeitsordner), List.of(kf.get("ts.ignore", "node_modules /build").split("\\s+"))));
            print("TypeScript-Arbeitsbereich: " + arbeitsordner);
        }
        if (javaOrdner != null) {
            w.add(new Indexer.Wurzel(javaOrdner, name(javaOrdner), List.of(kf.get("java.ignore", "target /build .gradle /bin /out").split("\\s+"))));
            print("Java-Arbeitsbereich: " + javaOrdner);
        }
        index.setWurzeln(w);
        fm.zuruecksetzen();
        wz.gitLesen();
        if (javaOrdner != null && !javaOrdner.equals(javaLspOrdner)) javaLspStarten();
    }

    private Path javaLspOrdner;
    /** schon einmal nach einem Abbruch neu gestartet */
    private boolean javaLspNeu;

    void javaLspStarten() {
        if (javaOrdner == null) return;
        javaLspOrdner = javaOrdner;
        javaLsp.stop();
        javaLsp.status = "startet";
        Path ordner = javaOrdner;
        long seit = System.currentTimeMillis() - 2000;
        javaLsp.logGruende = () -> wz.jdtlsLog(seit);
        boolean[] nochmal = {false};
        javaLsp.neustart = code -> {
            // Abbruch beim Start (13): alte Konfiguration/Arbeitsdaten verwerfen und einmal neu starten
            if (code != 13 || nochmal[0] || javaLspNeu) return false;
            javaLspNeu = true;
            farbig("Java Language Server brach ab (13) - neu mit dem eingebauten jdtls und frischer Konfiguration", E_WARN);
            wz.nurEingebaut = true;
            if (kf.get("java.lsp.home", null) != null) {
                farbig("java.lsp.home aus config.txt entfernt (" + kf.get("java.lsp.home", "") + " war defekt)", E_WARN);
                kf.setzen("java.lsp.home", "");
            }
            wz.jdtlsAufraeumen();
            javaLspOrdner = null;
            javaLspStarten();
            return true;
        };
        wz.javaLspVorbereiten(b -> {
            if (!ordner.equals(javaOrdner)) return;
            javaLsp.start(ordner, b, wz.javaEinstellungen(), Json.obj("settings", wz.javaEinstellungen(), "extendedClientCapabilities",
                    Json.obj("classFileContentsSupport", false)));
        });
    }

    static String name(Path p) {
        return p.getFileName() == null ? p.toString() : p.getFileName().toString();
    }

    Path aufloesen(String s) {
        s = s.trim();
        if (s.startsWith("~")) s = System.getProperty("user.home") + s.substring(1);
        Path p = Paths.get(s);
        if (!p.isAbsolute()) p = (arbeitsordner != null ? arbeitsordner : javaOrdner != null ? javaOrdner : Paths.get("")).resolve(p);
        return p.toAbsolutePath().normalize();
    }

    /** Arbeitsbereich, in dem p liegt (Java zuerst, falls verschachtelt), oder null */
    Path bereichVon(Path p) {
        if (p == null) return null;
        if (javaOrdner != null && p.startsWith(javaOrdner)) return javaOrdner;
        if (arbeitsordner != null && p.startsWith(arbeitsordner)) return arbeitsordner;
        return null;
    }

    String relativ(Path p) {
        if (p == null) return "[neu]";
        Path b = bereichVon(p);
        if (b == null) return p.toString();
        String r = b.relativize(p).toString().replace('\\', '/');
        return arbeitsordner != null && javaOrdner != null ? name(b) + "/" + r : r;
    }

    // ================================================================ Tasten (wie taste_verarbeiten im Kernel)
    void taste(String name, int code) {
        if (code > 0) {
            int m = km.zeichen(code);
            if (m != code) {
                code = m;
                name = m == 32 ? "SPACE" : new String(Character.toChars(m));
            }
        }
        meldung = null;
        if (info != null) {
            info = null;
            if (name.equals("ESC")) {
                neuMalen.run();
                return;
            }
        }
        vim.vorTaste(name, code);
        verarbeiten(name, code);
        nachBefehl();
        vim.nachTaste();
        neuMalen.run();
    }

    /** Taste aus einem Makro oder der Punkt-Wiederholung */
    void tasteIntern(String name, int code) {
        verarbeiten(name, code);
        nachBefehl();
    }

    boolean folgeLeer() {
        return folge.isEmpty();
    }

    private void verarbeiten(String name, int code) {
        if (!km.modeDa(modus)) {
            farbig("Mode " + modus + " steht nicht in keymap.config", E_WARN);
            modus = km.start;
        }
        String f = folge.isEmpty() ? name : folge + " " + name;
        String b = km.befehl(modus, f);
        if (km.vorsilbe(modus, f)) {
            folge = f;
            folgeBefehl = b;
            folgeUhr.restart();
            return;
        }
        if (b != null) {
            folge = "";
            folgeUhr.stop();
            ausfuehrenMitZaehler(b);
            return;
        }
        if (!folge.isEmpty()) {
            // angefangene Folge passt nicht weiter: sie allein ausfuehren, dann die Taste neu
            String alt = folgeBefehl;
            folge = "";
            folgeBefehl = null;
            folgeUhr.stop();
            if (alt != null) ausfuehrenMitZaehler(alt);
            verarbeiten(name, code);
            return;
        }
        if (code >= 32) {
            String a = km.befehl(modus, "ANY");
            if (a != null) ausfuehren(a, code);
        }
    }

    /**
     * Belegung ausfuehren; hat der Befehl den Zaehler (3x, 5p) nicht selbst genommen,
     * wird er so oft wiederholt.
     */
    private void ausfuehrenMitZaehler(String b) {
        String name = b.strip().split("\\s+", 2)[0];
        ausfuehren(b, 0);
        if (vim.zaehler <= 0 || name.equals("count") || name.equals("countZero")) return;
        int n = vim.zaehler;
        vim.zaehler = 0;
        merkSperre = true;
        try {
            for (int i = 1; i < n; i++) ausfuehren(b, 0);
        } finally {
            merkSperre = false;
        }
    }

    /** Befehlszeile wie im Terminal: NAME ARGUMENTE; code (ANY) kommt als Zeichen dazu */
    void ausfuehren(String zeile, int code) {
        zeile = zeile.strip();
        if (zeile.isEmpty()) return;
        if (code == 0 && vim.ersetzenBefehl(zeile)) return;
        int sp = zeile.indexOf(' ');
        String name = sp < 0 ? zeile : zeile.substring(0, sp);
        String rest = sp < 0 ? "" : zeile.substring(sp + 1).strip();
        if (code > 0) {
            String c = new String(Character.toChars(code));
            rest = rest.isEmpty() ? c : rest + " " + c;
        }
        befehl(name, rest);
    }

    void befehl(String name, String arg) {
        if (tiefe > 64) {
            fehler("Mapping zu tief verschachtelt: " + name);
            return;
        }
        tiefe++;
        try {
            Cmd c = cmds.get(name);
            Mapping.Fun f;
            if (c != null) {
                c.run(arg);
            } else if ((f = mp.funs.get(name)) != null) {
                mp.ausfuehren(f, arg, this::befehl);
            } else if (name.matches("\\d+")) {
                gotoLine(Integer.parseInt(name));
            } else if (name.equals("$")) {
                gotoLine(d().zeilen());
            } else {
                fehler("unbekannter Befehl: " + name);
            }
        } catch (RuntimeException e) {
            fehler(name + ": " + e);
            e.printStackTrace();
        } finally {
            tiefe--;
        }
    }

    private void nachBefehl() {
        vorGruppe = jetztGruppe;
        jetztGruppe = null;
        Doc d = d();
        d.cursor = Math.max(0, Math.min(d.cursor, d.t.length()));
        if (vim.visArt != 0 && !modus.equals("visual") && !modus.equals("char")) vim.visualEnde();
        // Tabelle gehoert zur Datei: nach einem Wechsel ohne Tabelle zurueck in den Normal-Mode
        if (modus.equals("result") && !wz.tabelleOffen()) modus = "normal";
        if (modus.equals("normal")) {
            // im Normal-Mode steht der Cursor auf einem Zeichen, nicht hinter der Zeile
            int z = d.zeileVon(d.cursor);
            if (d.cursor == d.ende(z) && d.cursor > d.anf(z)) d.cursor--;
        }
        if (d.lspVersion != d.version) lspUhr.restart();
    }

    /** Undo-Stand merken; gleiche Gruppe hintereinander (tippen) gibt nur einen Stand */
    void merken(String gruppe) {
        jetztGruppe = gruppe;
        if (merkSperre || (gruppe != null && gruppe.equals(vorGruppe))) return;
        d().merken();
    }

    // ================================================================ Befehle
    private void reg(String name, Cmd c) {
        cmds.put(name, c);
    }

    private void befehle() {
        reg("switchState", a -> {
            if (a.trim().equals("insert") && !modus.equals("insert")) {
                switchState(a);
                vim.insertMitZaehler();
            } else {
                switchState(a);
            }
        });
        // ------------------------------------------------ Bewegen
        reg("cursorLeft", a -> {
            Doc d = d();
            if (d.cursor > d.anf(d.zeileVon(d.cursor))) d.cursor--;
            d.spalteMerk = -1;
        });
        reg("cursorRight", a -> {
            Doc d = d();
            if (d.cursor < d.ende(d.zeileVon(d.cursor))) d.cursor++;
            d.spalteMerk = -1;
        });
        reg("cursorUp", a -> zeileWechseln(-1));
        reg("cursorDown", a -> zeileWechseln(1));
        reg("lineStart", a -> {
            Doc d = d();
            d.cursor = d.anf(d.zeileVon(d.cursor));
            d.spalteMerk = -1;
        });
        reg("lineEnd", a -> {
            Doc d = d();
            d.cursor = d.ende(d.zeileVon(d.cursor));
            d.spalteMerk = -1;
        });
        reg("firstNonBlank", a -> firstNonBlank());
        reg("wordForward", a -> {
            Doc d = d();
            d.cursor = wortVor(d, d.cursor);
            d.spalteMerk = -1;
        });
        reg("wordBack", a -> {
            Doc d = d();
            d.cursor = wortZurueck(d, d.cursor);
            d.spalteMerk = -1;
        });
        reg("gotoTop", a -> {
            springMerken();
            d().cursor = 0;
        });
        reg("gotoBottom", a -> {
            springMerken();
            Doc d = d();
            d.cursor = d.anf(d.zeilen() - 1);
        });
        reg("gotoLine", a -> gotoLine(zahl(a, 1)));
        reg("pageDown", a -> {
            Doc d = d();
            int n = textZeilen();
            d.oben = Math.min(d.zeilen() - 1, d.oben + n);
            zeileWechseln(n);
        });
        reg("pageUp", a -> {
            Doc d = d();
            int n = textZeilen();
            d.oben = Math.max(0, d.oben - n);
            zeileWechseln(-n);
        });
        // ------------------------------------------------ Aendern
        reg("insertText", a -> {
            merken(null);
            d().einfuegen(d().cursor, a);
        });
        reg("insertChar", this::insertChar);
        reg("newline", a -> newline());
        reg("backspace", a -> backspace());
        reg("deleteChar", a -> {
            Doc d = d();
            boolean ins = modus.equals("insert");
            if (d.cursor >= d.t.length() || (!ins && d.at(d.cursor) == '\n')) return;
            merken(ins ? "tippen" : null);
            d.loeschen(d.cursor, Character.isHighSurrogate(d.at(d.cursor)) ? 2 : 1);
        });
        reg("pasteClipboard", a -> {
            String s = ausZwischenablage();
            if (s == null || s.isEmpty()) return;
            merken(null);
            d().einfuegen(d().cursor, s.replace("\r\n", "\n"));
        });
        reg("openLineBelow", a -> {
            Doc d = d();
            merken(null);
            int z = d.zeileVon(d.cursor);
            String e = einrueckung(d.zeile(z));
            d.cursor = d.ende(z);
            d.einfuegen(d.cursor, "\n" + e);
            state("insert");
        });
        reg("openLineAbove", a -> {
            Doc d = d();
            merken(null);
            int z = d.zeileVon(d.cursor);
            String e = einrueckung(d.zeile(z));
            int an = d.anf(z);
            d.einfuegen(an, e + "\n");
            d.cursor = an + e.length();
            state("insert");
        });
        reg("indent", a -> {
            Doc d = d();
            merken("tippen");
            int v = vspalte(d, d.cursor);
            d.einfuegen(d.cursor, " ".repeat(einzug - v % einzug));
        });
        reg("appendAfter", a -> {
            Doc d = d();
            if (d.cursor < d.ende(d.zeileVon(d.cursor))) d.cursor++;
            state("insert");
            vim.insertMitZaehler();
        });
        reg("appendLineEnd", a -> {
            Doc d = d();
            d.cursor = d.ende(d.zeileVon(d.cursor));
            state("insert");
            vim.insertMitZaehler();
        });
        reg("insertLineStart", a -> {
            firstNonBlank();
            state("insert");
            vim.insertMitZaehler();
        });
        reg("undo", a -> {
            vim.nichtMerken = true;
            if (!d().undo()) meldung = "nichts mehr rueckgaengig zu machen";
            else d().cursor = Math.min(d().cursor, d().t.length());
        });
        reg("redo", a -> {
            vim.nichtMerken = true;
            if (!d().redo()) meldung = "nichts zu wiederholen";
        });
        // ------------------------------------------------ Suchen im Text
        reg("find", a -> popupOpen("Suchen im Text (/):", "findText", ""));
        reg("findBack", a -> popupOpen("Rueckwaerts suchen (?):", "findTextBack", ""));
        reg("findText", a -> {
            if (!a.isEmpty()) suche = a;
            vim.sucheRueck = false;
            findNext(true);
        });
        reg("findTextBack", a -> {
            if (!a.isEmpty()) suche = a;
            vim.sucheRueck = true;
            findNext(true);
        });
        reg("findNext", a -> findNext(true));
        reg("findPrev", a -> findNext(false));
        reg("clearSearch", a -> sucheZeigen = false);
        reg("findWord", a -> {
            String w = wortHier();
            if (w.isEmpty()) return;
            suche = w;
            vim.sucheRueck = false;
            findNext(true);
        });
        reg("findWordBack", a -> {
            String w = wortHier();
            if (w.isEmpty()) return;
            suche = w;
            vim.sucheRueck = true;
            findNext(true);
        });
        // ------------------------------------------------ Dateien und Tabs
        reg("open", a -> {
            if (a.isEmpty()) popupOpen("Datei oeffnen:", "open", "");
            else oeffnen(aufloesen(a));
        });
        reg("e", cmds.get("open"));
        reg("save", a -> save());
        reg("w", a -> {
            if (a.isEmpty()) save();
            else speichernUnter(aufloesen(a));
        });
        reg("saveAs", a -> {
            if (a.isEmpty()) popupOpen("Speichern unter:", "saveAs", d().pfad == null ? "" : relativ(d().pfad));
            else speichernUnter(aufloesen(a));
        });
        reg("close", a -> close(false));
        reg("q", a -> close(false));
        reg("forceClose", a -> close(true));
        reg("q!", a -> close(true));
        reg("wq", a -> {
            save();
            if (!d().geaendert) close(false);
        });
        reg("x", cmds.get("wq"));
        reg("newFile", a -> {
            tabs.add(new Doc());
            aktiv = tabs.size() - 1;
        });
        reg("nextTab", a -> aktiv = (aktiv + 1) % tabs.size());
        reg("prevTab", a -> aktiv = (aktiv + tabs.size() - 1) % tabs.size());
        reg("tab", a -> aktiv = Math.max(0, Math.min(tabs.size() - 1, zahl(a, 1) - 1)));
        reg("tabs", a -> {
            konsoleAuf();
            for (int i = 0; i < tabs.size(); i++) {
                Doc t = tabs.get(i);
                print((i == aktiv ? "> " : "  ") + (i + 1) + "  " + relativ(t.pfad) + (t.geaendert ? " [+]" : ""));
            }
        });
        reg("quit", a -> quit(false));
        reg("qa", a -> quit(false));
        reg("quit!", a -> quit(true));
        reg("qa!", a -> quit(true));
        // ------------------------------------------------ Terminal
        reg("console", a -> konsoleAuf());
        reg("consoleClose", a -> {
            konsoleOffen = false;
            if (modus.equals("console")) modus = konsoleVorModus.equals("console") ? km.start : konsoleVorModus;
        });
        reg("consoleChar", a -> eingabe.append(a));
        reg("consoleBackspace", a -> zurueck(eingabe));
        reg("consoleRun", a -> {
            String z = eingabe.toString();
            eingabe.setLength(0);
            farbig("runen> " + z, E_HELL);
            if (!z.isBlank() && (verlauf.isEmpty() || !verlauf.get(verlauf.size() - 1).equals(z))) verlauf.add(z);
            verlaufPos = verlauf.size();
            ausfuehren(z, 0);
        });
        reg("consoleUp", a -> {
            if (verlaufPos > 0) {
                verlaufPos--;
                eingabe.setLength(0);
                eingabe.append(verlauf.get(verlaufPos));
            }
        });
        reg("consoleDown", a -> {
            eingabe.setLength(0);
            if (verlaufPos < verlauf.size() - 1) eingabe.append(verlauf.get(++verlaufPos));
            else verlaufPos = verlauf.size();
        });
        reg("consoleClear", a -> {
            konsole.clear();
            konsoleFarbe.clear();
        });
        reg("consolePrint", this::print);
        reg("consoleError", this::fehler);
        reg("echo", this::print);
        reg("message", a -> meldung = a);
        reg("ls", a -> {
            konsoleAuf();
            Path p = a.isEmpty() ? arbeitsordner : aufloesen(a);
            try (var s = Files.list(p)) {
                s.sorted().forEach(x -> print((Files.isDirectory(x) ? "  /" : "   ") + x.getFileName()));
            } catch (IOException | RuntimeException e) {
                fehler("ls: " + e.getMessage());
            }
        });
        reg("help", a -> hilfe());
        reg("hilfe", a -> hilfe());
        reg("set", this::einstellung);
        reg("keymap", a -> keymapLaden());
        reg("reload", a -> {
            Path altTs = arbeitsordner, altJava = javaOrdner;
            konfigLesen();
            schriftAn();
            keymapLaden();
            String ts = kf.get("ts.workspace", null), java = kf.get("java.workspace", null);
            Path neuTs = ts == null ? altTs : aufloesenVon(konfig, ts);
            Path neuJava = java == null ? null : aufloesenVon(konfig, java);
            if (neuJava != null && !neuJava.equals(altJava)) javaOrdner = Files.isDirectory(neuJava) ? neuJava : altJava;
            if (neuTs != null && !neuTs.equals(altTs)) arbeitsordnerSetzen(neuTs);
            else arbeitsbereicheAn();
            meldung = "config.txt neu gelesen";
        });
        reg("workdir", a -> {
            if (a.isEmpty()) {
                popupOpen("TypeScript-Arbeitsbereich (wird in config.txt gespeichert):", "workdir", arbeitsordner == null ? "" : arbeitsordner.toString());
                return;
            }
            Path p = aufloesen(a);
            if (!Files.isDirectory(p)) {
                fehler("kein Ordner: " + p);
                if (arbeitsordner == null) popupOpen("TypeScript-Arbeitsbereich waehlen (den gibt es nicht):", "workdir", a);
                return;
            }
            kf.setzen("ts.workspace", p.toString());
            arbeitsordnerSetzen(p);
        });
        reg("cd", cmds.get("workdir"));
        reg("reindex", a -> {
            index.neuEinlesen();
            meldung = "Index wird neu eingelesen";
        });
        reg("fontBigger", a -> {
            schriftGroesse = Math.min(72, schriftGroesse + 1);
            schriftAn();
        });
        reg("fontSmaller", a -> {
            schriftGroesse = Math.max(6, schriftGroesse - 1);
            schriftAn();
        });
        reg("fullscreen", a -> vollbild.run());
        // ------------------------------------------------ Ausfuehren (F9, F10)
        reg("compile", a -> {
            alleSpeichern();
            if ("java".equals(d().lspArt()) && !javaCompileBefehl.isBlank()) starten(javaCompileBefehl, javaOrdner);
            else starten(compileBefehl);
        });
        reg("compileFast", a -> {
            alleSpeichern();
            starten(compileFastBefehl);
        });
        reg("run", this::starten);
        reg("stop", a -> {
            if (laufend != null) {
                laufend.descendants().forEach(ProcessHandle::destroy);
                laufend.destroy();
                fehler("abgebrochen");
            }
        });
        // ------------------------------------------------ Fenster mit Eingabe
        reg("popupOpen", a -> {
            String[] t = (a + "||").split("\\|", -1);
            popupOpen(t[0], t[1], t[2]);
        });
        reg("popupClose", a -> {
            if (arbeitsordner == null && popupBefehl.equals("workdir")) {
                meldung = "erst einen Arbeitsordner waehlen (ENTER), quit! beendet";
                return;
            }
            popupClose();
        });
        reg("popupChar", a -> popupEingabe.append(a));
        reg("popupClear", a -> popupEingabe.setLength(0));
        reg("popupBackspace", a -> zurueck(popupEingabe));
        reg("popupRun", a -> {
            String b = popupBefehl, e = popupEingabe.toString();
            popupClose();
            farbig("runen> " + b + " " + e, E_HELL);
            befehl(b, e);
        });
        reg("popupComplete", a -> pfadErgaenzen(popupEingabe));
        reg("popupPaste", a -> {
            String z = ausZwischenablage();
            if (z != null) popupEingabe.append(z.replaceAll("[\\r\\n]+", " ").strip());
        });
        // ------------------------------------------------ Language Server
        reg("gotoDefinition", a -> gotoDefinition());
        reg("goBack", a -> {
            if (spruenge.isEmpty()) {
                meldung = "keine Sprungmarke";
                return;
            }
            Sprung s = spruenge.pop();
            if (s.pfad() != null) oeffnen(s.pfad());
            d().cursor = Math.min(s.pos(), d().t.length());
        });
        reg("gotoError", a -> gotoError(true));
        reg("gotoErrorPrev", a -> gotoError(false));
        reg("hover", a -> hover());
        reg("callUnderCursor", a -> hover());
        reg("format", a -> wz.formatieren());
        reg("references", a -> references());
        reg("rename", a -> {
            String w = wortHier();
            if (w.isEmpty()) meldung = "kein Name unter dem Cursor";
            else popupOpen("Umbenennen: " + w + " in", "renameTo", w);
        });
        reg("renameTo", this::renameTo);
        reg("lspRestart", a -> {
            for (Doc t : tabs) t.lspOffen = false;
            if (arbeitsordner != null && !lspBefehl.isEmpty()) tsLsp.start(arbeitsordner, lspBefehl);
            javaLspOrdner = null;
            javaLspStarten();
        });
        reg("complete", a -> vervollstaendigen());
        reg("completeUp", a -> vorschlagWahl = Math.max(0, vorschlagWahl - 1));
        reg("completeDown", a -> vorschlagWahl = Math.min(gefiltert.size() - 1, vorschlagWahl + 1));
        reg("completePageUp", a -> vorschlagWahl = Math.max(0, vorschlagWahl - 10));
        reg("completePageDown", a -> vorschlagWahl = Math.min(gefiltert.size() - 1, vorschlagWahl + 10));
        reg("completeAccept", a -> vorschlagNehmen());
        reg("completeClose", a -> vorschlagZu());
        reg("completeChar", a -> {
            if (!a.isEmpty() && Highlight.ident(a.charAt(0))) {
                merken("tippen");
                d().einfuegen(d().cursor, a);
                filtern();
            } else {
                vorschlagZu();
                insertChar(a);
            }
        });
        reg("completeBackspace", a -> {
            backspace();
            if (d().cursor < vorschlagAnf) vorschlagZu();
            else filtern();
        });
    }

    void switchState(String m) {
        m = m.trim();
        if (m.isEmpty()) return;
        if ((modus.equals("insert") || modus.equals("replace")) && !m.equals("insert") && !m.equals("complete")) vim.insertEnde();
        if (vim.visArt != 0 && !m.equals("visual")) vim.visualEnde();
        if ((modus.equals("insert") || modus.equals("replace")) && !m.equals("insert")) {
            Doc d = d();
            if (d.cursor > d.anf(d.zeileVon(d.cursor))) d.cursor--;
        }
        if (vorschlagOffen && !m.equals("complete")) vorschlagOffen = false;
        modus = m;
    }

    private void state(String m) {
        switchState(m);
    }

    // ================================================================ Bewegen und Aendern
    int vspalte(Doc d, int pos) {
        int v = 0;
        for (int k = d.anf(d.zeileVon(pos)); k < pos; k++) v = d.t.charAt(k) == '\t' ? (v / tabBreite + 1) * tabBreite : v + 1;
        return v;
    }

    int posBeiVspalte(Doc d, int z, int vs) {
        int a = d.anf(z), e = d.ende(z), v = 0;
        for (int k = a; k < e; k++) {
            int nv = d.t.charAt(k) == '\t' ? (v / tabBreite + 1) * tabBreite : v + 1;
            if (nv > vs) return k;
            v = nv;
        }
        return e;
    }

    private void zeileWechseln(int dz) {
        Doc d = d();
        int z = d.zeileVon(d.cursor);
        if (d.spalteMerk < 0) d.spalteMerk = vspalte(d, d.cursor);
        int nz = Math.max(0, Math.min(d.zeilen() - 1, z + dz));
        d.cursor = posBeiVspalte(d, nz, d.spalteMerk);
    }

    private void firstNonBlank() {
        Doc d = d();
        int p = d.anf(d.zeileVon(d.cursor));
        while (d.at(p) == ' ' || d.at(p) == '\t') p++;
        d.cursor = p;
        d.spalteMerk = -1;
    }

    void gotoLine(int n) {
        springMerken();
        Doc d = d();
        d.cursor = d.anf(Math.max(0, Math.min(d.zeilen() - 1, n - 1)));
        firstNonBlank();
    }

    private static int art(char c) {
        if (c <= ' ') return 0;
        return Highlight.ident(c) ? 1 : 2;
    }

    private static int wortVor(Doc d, int p) {
        int n = d.t.length();
        if (p >= n) return n;
        int k = art(d.at(p));
        while (p < n && k != 0 && art(d.at(p)) == k) p++;
        while (p < n && art(d.at(p)) == 0) p++;
        return p;
    }

    private static int wortZurueck(Doc d, int p) {
        if (p <= 0) return 0;
        p--;
        while (p > 0 && art(d.at(p)) == 0) p--;
        int k = art(d.at(p));
        while (p > 0 && art(d.at(p - 1)) == k) p--;
        return p;
    }

    String wortHier() {
        Doc d = d();
        int a = d.cursor, e = d.cursor;
        while (a > 0 && Highlight.ident(d.at(a - 1))) a--;
        while (e < d.t.length() && Highlight.ident(d.at(e))) e++;
        return d.t.substring(a, e);
    }

    static String einrueckung(String zeile) {
        int k = 0;
        while (k < zeile.length() && (zeile.charAt(k) == ' ' || zeile.charAt(k) == '\t')) k++;
        return zeile.substring(0, k);
    }

    private void insertChar(String a) {
        if (a.isEmpty()) return;
        Doc d = d();
        merken("tippen");
        char c = a.charAt(0);
        int z = d.zeileVon(d.cursor);
        String vor = d.t.substring(d.anf(z), d.cursor);
        if ((c == '}' || c == ')' || c == ']') && !vor.isEmpty() && vor.isBlank() && vor.length() >= einzug) {
            // schliessende Klammer am Zeilenanfang: eine Stufe zurueck
            d.loeschen(d.cursor - einzug, einzug);
        }
        d.einfuegen(d.cursor, a);
        d.spalteMerk = -1;
        if (c == '.' && autoVervollst && ((lspFuer(d) != null && lspFuer(d).an()) || ("sql".equals(d.sprache()) && !wz.db.konten.isEmpty()))) {
            vervollstaendigen();
        }
    }

    private void newline() {
        Doc d = d();
        merken("tippen");
        int z = d.zeileVon(d.cursor);
        String e = einrueckung(d.zeile(z));
        if (d.cursor - d.anf(z) < e.length()) e = e.substring(0, d.cursor - d.anf(z));
        int p = d.cursor - 1;
        while (p >= d.anf(z) && d.at(p) == ' ') p--;
        char vor = p >= d.anf(z) ? d.at(p) : 0;
        int q = d.cursor;
        while (d.at(q) == ' ') q++;
        char nach = d.at(q);
        // Leerzeichen hinter dem Cursor fallen weg
        if (q > d.cursor) d.loeschen(d.cursor, q - d.cursor);
        if (vor == '{' || vor == '(' || vor == '[' || (vor == '>' && nach == '<' && d.at(q + 1) == '/')) {
            String innen = e + " ".repeat(einzug);
            boolean zu = (vor == '{' && nach == '}') || (vor == '(' && nach == ')') || (vor == '[' && nach == ']') || vor == '>';
            if (zu) {
                int c = d.cursor;
                d.einfuegen(c, "\n" + innen + "\n" + e);
                d.cursor = c + 1 + innen.length();
            } else {
                d.einfuegen(d.cursor, "\n" + innen);
            }
        } else {
            d.einfuegen(d.cursor, "\n" + e);
        }
        d.spalteMerk = -1;
    }

    private void backspace() {
        Doc d = d();
        if (d.cursor == 0) return;
        merken("tippen");
        int z = d.zeileVon(d.cursor);
        String vor = d.t.substring(d.anf(z), d.cursor);
        if (!vor.isEmpty() && vor.chars().allMatch(c -> c == ' ')) {
            int n = vor.length() % einzug == 0 ? einzug : vor.length() % einzug;
            d.loeschen(d.cursor - n, n);
        } else {
            int n = d.cursor >= 2 && Character.isLowSurrogate(d.at(d.cursor - 1)) ? 2 : 1;
            d.loeschen(d.cursor - n, n);
        }
        d.spalteMerk = -1;
    }

    private void findNext(boolean vor) {
        Doc d = d();
        int p = vim.suchen(d.cursor, vor != vim.sucheRueck);
        if (p < 0) return;
        springMerken();
        sucheZeigen = true;
        d.cursor = p;
        d.spalteMerk = -1;
    }

    private static void zurueck(StringBuilder b) {
        if (b.length() == 0) return;
        int n = b.length() >= 2 && Character.isLowSurrogate(b.charAt(b.length() - 1)) ? 2 : 1;
        b.setLength(b.length() - n);
    }

    private static int zahl(String s, int vorgabe) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return vorgabe;
        }
    }

    static void zwischenablage(String s) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
        } catch (RuntimeException e) {
            // ohne Zwischenablage (headless)
        }
    }

    static String ausZwischenablage() {
        try {
            return (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================ Dateien und Tabs
    void springMerken() {
        Doc d = d();
        spruenge.push(new Sprung(d.pfad, d.cursor));
        while (spruenge.size() > 100) spruenge.removeLast();
    }

    Doc oeffnen(Path p) {
        p = p.toAbsolutePath().normalize();
        for (int i = 0; i < tabs.size(); i++) {
            if (p.equals(tabs.get(i).pfad)) {
                aktiv = i;
                return tabs.get(i);
            }
        }
        if (Files.isDirectory(p)) {
            fehler("ist ein Ordner: " + p);
            return null;
        }
        Doc d;
        try {
            d = Doc.laden(p);
        } catch (IOException | RuntimeException e) {
            fehler("nicht geoeffnet: " + p + ": " + e.getMessage());
            return null;
        }
        d.diags = diagnosen.getOrDefault(schluessel(p), new ArrayList<>());
        Doc alt = d();
        if (tabs.size() == 1 && alt.pfad == null && !alt.geaendert && alt.t.length() == 0) tabs.set(0, d);
        else tabs.add(d);
        aktiv = tabs.indexOf(d);
        lspSync();
        return d;
    }

    private void save() {
        Doc d = d();
        if (d.pfad == null) {
            popupOpen("Speichern unter:", "saveAs", "");
            return;
        }
        try {
            boolean neu = !Files.exists(d.pfad);
            d.speichern();
            meldung = "gespeichert: " + relativ(d.pfad) + " (" + d.zeilen() + " Zeilen)";
            lspSync();
            if (d.lspOffen) lspFuer(d).meldung("textDocument/didSave", Json.obj("textDocument", Json.obj("uri", Lsp.uri(d.pfad))));
            if (neu) index.neuEinlesen();
        } catch (IOException e) {
            fehler("nicht gespeichert: " + e.getMessage());
        }
    }

    private void alleSpeichern() {
        int a = aktiv;
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).geaendert && tabs.get(i).pfad != null) {
                aktiv = i;
                save();
            }
        }
        aktiv = a;
    }

    private void speichernUnter(Path p) {
        Doc d = d();
        lspSchliessen(d);
        d.pfad = p;
        d.diags = new ArrayList<>();
        save();
    }

    private void close(boolean trotzdem) {
        Doc d = d();
        if (d.geaendert && !trotzdem) {
            meldung = "ungespeichert - forceClose (q!) schliesst trotzdem";
            return;
        }
        lspSchliessen(d);
        tabs.remove(aktiv);
        if (tabs.isEmpty()) tabs.add(new Doc());
        aktiv = Math.min(aktiv, tabs.size() - 1);
    }

    void quit(boolean trotzdem) {
        if (!trotzdem) {
            for (int i = 0; i < tabs.size(); i++) {
                if (tabs.get(i).geaendert) {
                    aktiv = i;
                    meldung = "ungespeicherte Aenderungen in " + tabs.get(i).name() + " - quit! beendet trotzdem";
                    return;
                }
            }
        }
        tsLsp.stop();
        javaLsp.stop();
        wz.db.trennen();
        beenden.run();
    }

    // ================================================================ Terminal
    void konsoleAuf() {
        if (!konsoleOffen || !modus.equals("console")) {
            if (!modus.equals("console")) konsoleVorModus = modus;
            konsoleOffen = true;
            modus = "console";
        }
    }

    void print(String s) {
        farbig(s, E_TEXT);
    }

    void fehler(String s) {
        farbig(s, E_FEHLER);
        meldung = s;
        meldungFarbe = E_FEHLER;
    }

    void farbig(String s, int farbe) {
        for (String z : s.split("\n", -1)) {
            konsole.add(z);
            konsoleFarbe.add(farbe);
        }
        while (konsole.size() > 2000) {
            konsole.remove(0);
            konsoleFarbe.remove(0);
        }
    }

    private void hilfe() {
        konsoleAuf();
        print("Befehle (in keymap.config belegbar, im Terminal mit Argumenten ohne Anfuehrungszeichen):");
        TreeSet<String> n = new TreeSet<>(cmds.keySet());
        n.addAll(mp.funs.keySet());
        StringBuilder b = new StringBuilder("  ");
        for (String s : n) {
            if (b.length() + s.length() > 140) {
                print(b.toString());
                b = new StringBuilder("  ");
            }
            b.append(s).append("  ");
        }
        print(b.toString());
        print("Tasten im Mode " + modus + ": siehe " + keymapPfad + "  (keymap laedt sie neu)");
    }

    /** Befehl im Arbeitsordner starten, Ausgabe ins Terminal */
    private void starten(String befehl) {
        starten(befehl, arbeitsordner != null ? arbeitsordner : javaOrdner);
    }

    private void starten(String befehl, Path ordner) {
        if (befehl == null || befehl.isBlank() || ordner == null) return;
        if (laufend != null && laufend.isAlive()) {
            fehler("laeuft noch (stop bricht ab)");
            return;
        }
        konsoleAuf();
        farbig("$ " + befehl, E_HELL);
        List<String> b = Lsp.WINDOWS ? List.of("cmd.exe", "/c", befehl) : List.of("/bin/sh", "-c", befehl);
        ProcessBuilder pb = new ProcessBuilder(b).directory(ordner.toFile()).redirectErrorStream(true);
        Lsp.umgebung(pb);
        pb.environment().put("FORCE_COLOR", "0");
        pb.environment().put("NO_COLOR", "1");
        try {
            Process p = pb.start();
            laufend = p;
            p.getOutputStream().close();
            Thread t = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String z;
                    while ((z = r.readLine()) != null) {
                        String s = z.replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", "");
                        SwingUtilities.invokeLater(() -> {
                            farbig(s, s.contains("error") || s.contains("Error") ? E_FEHLER : E_TEXT);
                            neuMalen.run();
                        });
                    }
                    int rc = p.waitFor();
                    SwingUtilities.invokeLater(() -> {
                        farbig("[Ende " + rc + "]", rc == 0 ? E_HELL : E_FEHLER);
                        neuMalen.run();
                    });
                } catch (IOException | InterruptedException e) {
                    // abgebrochen
                }
            }, "lauf");
            t.setDaemon(true);
            t.start();
        } catch (IOException e) {
            fehler("nicht gestartet: " + e.getMessage());
        }
    }

    // ================================================================ Fenster mit Eingabe
    void popupOpen(String titel, String befehl, String vorgabe) {
        if (!popupOffen) popupVorModus = modus;
        popupOffen = true;
        popupTitel = titel;
        popupBefehl = befehl;
        popupEingabe.setLength(0);
        popupEingabe.append(vorgabe);
        modus = "popup";
    }

    void popupClose() {
        popupOffen = false;
        if (modus.equals("popup")) modus = popupVorModus;
    }

    /** letzten Pfad in der Eingabe mit TAB ergaenzen */
    private void pfadErgaenzen(StringBuilder b) {
        String s = b.toString();
        String basis = s.startsWith("~") ? System.getProperty("user.home") + s.substring(1) : s;
        Path p;
        try {
            p = Paths.get(basis.isEmpty() ? "." : basis);
        } catch (InvalidPathException e) {
            return;
        }
        if (!p.isAbsolute() && arbeitsordner != null) p = arbeitsordner.resolve(p);
        boolean endetMitTrenner = s.endsWith("/") || s.endsWith("\\");
        Path ordner = endetMitTrenner ? p : p.getParent();
        String anfang = endetMitTrenner || p.getFileName() == null ? "" : p.getFileName().toString();
        if (ordner == null || !Files.isDirectory(ordner)) return;
        List<String> passend = new ArrayList<>();
        try (var st = Files.list(ordner)) {
            st.forEach(x -> {
                String n = x.getFileName().toString();
                if (n.toLowerCase().startsWith(anfang.toLowerCase())) passend.add(n + (Files.isDirectory(x) ? "/" : ""));
            });
        } catch (IOException e) {
            return;
        }
        if (passend.isEmpty()) return;
        passend.sort(String.CASE_INSENSITIVE_ORDER);
        String gemeinsam = passend.get(0);
        for (String n : passend) {
            int k = 0;
            while (k < gemeinsam.length() && k < n.length() && Character.toLowerCase(gemeinsam.charAt(k)) == Character.toLowerCase(n.charAt(k))) k++;
            gemeinsam = gemeinsam.substring(0, k);
        }
        if (passend.size() > 1 && gemeinsam.length() <= anfang.length()) {
            meldung = String.join("  ", passend.subList(0, Math.min(12, passend.size()))) + (passend.size() > 12 ? " ..." : "");
            return;
        }
        int schnitt = endetMitTrenner ? s.length() : s.length() - anfang.length();
        b.setLength(Math.max(0, schnitt));
        b.append(gemeinsam);
    }

    // ================================================================ Language Server
    static String schluessel(Path p) {
        String s = p.toAbsolutePath().normalize().toString();
        return Lsp.WINDOWS ? s.toLowerCase() : s;
    }

    static Path ausUri(String uri) {
        try {
            return Paths.get(URI.create(uri));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void diagnosenSetzen(String uri, List<Object> l) {
        Path p = ausUri(uri);
        if (p == null) return;
        List<Doc.Diag> r = new ArrayList<>();
        for (Object o : l) {
            r.add(new Doc.Diag(Json.num(Json.get(o, "range", "start", "line"), 0), Json.num(Json.get(o, "range", "start", "character"), 0),
                    Json.num(Json.get(o, "range", "end", "line"), 0), Json.num(Json.get(o, "range", "end", "character"), 0),
                    Json.num(Json.get(o, "severity"), 1), String.valueOf(Json.get(o, "message")).replace('\n', ' ')));
        }
        r.sort(Comparator.comparingInt(Doc.Diag::zeile).thenComparingInt(Doc.Diag::spalte));
        String k = schluessel(p);
        diagnosen.put(k, r);
        for (Doc t : tabs) if (t.pfad != null && schluessel(t.pfad).equals(k)) t.diags = r;
    }

    /** offene Texte an den Server melden (didOpen/didChange mit ganzem Text) */
    void lspSync() {
        for (Doc t : tabs) {
            Lsp lsp = lspFuer(t);
            if (t.pfad == null || lsp == null || !lsp.an()) continue;
            if (!t.lspOffen) {
                lsp.meldung("textDocument/didOpen", Json.obj("textDocument", Json.obj("uri", Lsp.uri(t.pfad), "languageId", t.sprache(), "version", t.version,
                        "text", t.t.toString())));
                t.lspOffen = true;
                t.lspVersion = t.version;
            } else if (t.lspVersion != t.version) {
                lsp.meldung("textDocument/didChange", Json.obj("textDocument", Json.obj("uri", Lsp.uri(t.pfad), "version", t.version),
                        "contentChanges", List.of(Json.obj("text", t.t.toString()))));
                t.lspVersion = t.version;
            }
        }
    }

    private void lspSchliessen(Doc d) {
        if (d.lspOffen && d.pfad != null && lspFuer(d) != null) lspFuer(d).meldung("textDocument/didClose", Json.obj("textDocument", Json.obj("uri", Lsp.uri(d.pfad))));
        d.lspOffen = false;
    }

    private Map<String, Object> position(Doc d) {
        int z = d.zeileVon(d.cursor);
        return Json.obj("textDocument", Json.obj("uri", Lsp.uri(d.pfad)), "position", Json.obj("line", z, "character", d.cursor - d.anf(z)));
    }

    private boolean lspBereit() {
        Doc d = d();
        Lsp lsp = lspFuer(d);
        if (d.pfad == null || lsp == null) {
            meldung = "kein TypeScript, JavaScript oder Java";
            return false;
        }
        if (!lsp.an()) {
            meldung = lsp.name + " Language Server: " + lsp.status;
            return false;
        }
        lspSync();
        return true;
    }

    private int offset(Doc d, Object pos) {
        return d.stelle(Json.num(Json.get(pos, "line"), 0), Json.num(Json.get(pos, "character"), 0));
    }

    private void gotoDefinition() {
        if (!lspBereit()) return;
        Doc d = d();
        int v = d.version;
        lspFuer(d()).anfrage("textDocument/definition", position(d), r -> {
            List<Object> l = r instanceof List<?> ? Json.list(r) : r == null ? List.of() : List.of(r);
            if (l.isEmpty()) {
                meldung = "keine Definition gefunden";
            } else {
                Object o = l.get(0);
                String uri = Json.str(Json.get(o, "uri"));
                Object range = Json.get(o, "range");
                if (uri == null) {
                    uri = Json.str(Json.get(o, "targetUri"));
                    range = Json.get(o, "targetSelectionRange");
                }
                if (d() == d && d.version == v) springen(ausUri(uri), Json.get(range, "start"));
            }
            neuMalen.run();
        });
    }

    private void springen(Path p, Object start) {
        if (p == null) return;
        springMerken();
        Doc z = oeffnen(p);
        if (z == null) return;
        z.cursor = offset(z, start);
        z.spalteMerk = -1;
    }

    private void gotoError(boolean vor) {
        Doc d = d();
        if (d.diags.isEmpty()) {
            meldung = "keine Fehler";
            return;
        }
        int z = d.zeileVon(d.cursor), sp = d.cursor - d.anf(z);
        Doc.Diag ziel = vor ? d.diags.get(0) : d.diags.get(d.diags.size() - 1);
        if (vor) {
            for (Doc.Diag g : d.diags) {
                if (g.zeile() > z || (g.zeile() == z && g.spalte() > sp)) {
                    ziel = g;
                    break;
                }
            }
        } else {
            for (int i = d.diags.size() - 1; i >= 0; i--) {
                Doc.Diag g = d.diags.get(i);
                if (g.zeile() < z || (g.zeile() == z && g.spalte() < sp)) {
                    ziel = g;
                    break;
                }
            }
        }
        springMerken();
        d.cursor = d.stelle(ziel.zeile(), ziel.spalte());
        meldung = ziel.text();
        meldungFarbe = ziel.schwere() == 1 ? E_FEHLER : E_WARN;
    }

    private void hover() {
        if (!lspBereit()) return;
        Doc d = d();
        lspFuer(d()).anfrage("textDocument/hover", position(d), r -> {
            String t = hoverText(Json.get(r, "contents"));
            if (t.isBlank()) {
                meldung = "keine Information";
            } else {
                List<String> z = new ArrayList<>();
                for (String s : t.split("\n")) {
                    if (s.startsWith("```")) continue;
                    z.add(s.replace("\t", "  "));
                }
                while (!z.isEmpty() && z.get(z.size() - 1).isBlank()) z.remove(z.size() - 1);
                while (!z.isEmpty() && z.get(0).isBlank()) z.remove(0);
                info = z;
            }
            neuMalen.run();
        });
    }

    private static String hoverText(Object c) {
        if (c == null) return "";
        if (c instanceof String s) return s;
        if (c instanceof List<?> l) {
            StringBuilder b = new StringBuilder();
            for (Object o : l) {
                if (b.length() > 0) b.append("\n");
                b.append(hoverText(o));
            }
            return b.toString();
        }
        Object v = Json.get(c, "value");
        return v == null ? "" : v.toString();
    }

    /** Formatieren ueber den Language Server (Java mit java.format.eclipse) */
    void lspFormat() {
        if (!lspBereit()) return;
        Doc d = d();
        int v = d.version;
        lspFuer(d()).anfrage("textDocument/formatting", Json.obj("textDocument", Json.obj("uri", Lsp.uri(d.pfad)),
                "options", Json.obj("tabSize", einzug, "insertSpaces", true)), r -> {
                    if (d.version != v) return;
                    List<Object> l = Json.list(r);
                    if (l.isEmpty()) {
                        meldung = "schon formatiert";
                    } else {
                        d.merken();
                        anwenden(d, l);
                        meldung = "formatiert";
                    }
                    neuMalen.run();
                });
    }

    /** TextEdits von hinten nach vorne anwenden */
    private void anwenden(Doc d, List<Object> edits) {
        List<int[]> b = new ArrayList<>();
        List<String> texte = new ArrayList<>();
        for (Object e : edits) {
            b.add(new int[] {offset(d, Json.get(e, "range", "start")), offset(d, Json.get(e, "range", "end")), texte.size()});
            texte.add(String.valueOf(Json.get(e, "newText")).replace("\r\n", "\n"));
        }
        b.sort((x, y) -> x[0] != y[0] ? y[0] - x[0] : y[1] - x[1]);
        for (int[] x : b) d.ersetzen(x[0], Math.max(x[0], x[1]), texte.get(x[2]));
    }

    private void references() {
        if (!lspBereit()) return;
        Doc d = d();
        Map<String, Object> p = position(d);
        p.put("context", Json.obj("includeDeclaration", true));
        String w = wortHier();
        lspFuer(d()).anfrage("textDocument/references", p, r -> {
            List<FileManager.Eintrag> l = new ArrayList<>();
            for (Object o : Json.list(r)) {
                Path pf = ausUri(Json.str(Json.get(o, "uri")));
                if (pf == null) continue;
                int z = Json.num(Json.get(o, "range", "start", "line"), 0);
                int sp = Json.num(Json.get(o, "range", "start", "character"), 0);
                int ez = Json.num(Json.get(o, "range", "end", "character"), sp);
                l.add(FileManager.Eintrag.treffer(pf, relativ(pf), z, sp, Math.max(1, ez - sp), zeileAus(pf, z)));
            }
            if (l.isEmpty()) meldung = "keine Referenzen";
            else fm.liste("Referenzen: " + w, l);
            neuMalen.run();
        });
    }

    /** Zeile einer Datei (aus offenem Tab oder von der Platte) */
    String zeileAus(Path p, int z) {
        for (Doc t : tabs) if (p.equals(t.pfad)) return t.zeile(z);
        try (var s = Files.lines(p, StandardCharsets.UTF_8)) {
            return s.skip(z).findFirst().orElse("");
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private void renameTo(String neu) {
        if (neu.isBlank() || !lspBereit()) return;
        Doc d = d();
        Map<String, Object> p = position(d);
        p.put("newName", neu.trim());
        lspFuer(d()).anfrage("textDocument/rename", p, r -> {
            if (r == null) {
                meldung = "nicht umbenannt";
                neuMalen.run();
                return;
            }
            Map<String, List<Object>> je = new HashMap<>();
            Object ch = Json.get(r, "changes");
            if (ch instanceof Map<?, ?> m) {
                for (var e : m.entrySet()) je.put((String) e.getKey(), Json.list(e.getValue()));
            }
            for (Object dc : Json.list(Json.get(r, "documentChanges"))) {
                String u = Json.str(Json.get(dc, "textDocument", "uri"));
                if (u != null) je.computeIfAbsent(u, k -> new ArrayList<>()).addAll(Json.list(Json.get(dc, "edits")));
            }
            int a = aktiv, stellen = 0;
            for (var e : je.entrySet()) {
                Path pf = ausUri(e.getKey());
                Doc t = pf == null ? null : oeffnen(pf);
                if (t == null) continue;
                t.merken();
                anwenden(t, e.getValue());
                stellen += e.getValue().size();
            }
            aktiv = Math.min(a, tabs.size() - 1);
            meldung = "umbenannt: " + stellen + " Stellen in " + je.size() + " Dateien (noch nicht gespeichert)";
            lspSync();
            neuMalen.run();
        });
    }

    // ---------------------------------------------------------------- Vervollstaendigung
    private void vervollstaendigen() {
        if ("sql".equals(d().sprache())) {
            wz.sqlVervollstaendigen();
            return;
        }
        if (!lspBereit()) return;
        Doc d = d();
        int a = d.cursor;
        while (a > 0 && Highlight.ident(d.at(a - 1))) a--;
        int anf = a;
        int v = d.version;
        lspFuer(d()).anfrage("textDocument/completion", position(d), r -> {
            if (d() != d || d.cursor < anf) return;
            List<Object> items = r instanceof List<?> ? Json.list(r) : Json.list(Json.get(r, "items"));
            List<Map<String, Object>> l = new ArrayList<>();
            for (Object o : items) if (o instanceof Map<?, ?>) l.add(Json.obj("item", o));
            if (l.isEmpty()) {
                if (d.version == v) meldung = "keine Vorschlaege";
                neuMalen.run();
                return;
            }
            vorschlaege = l;
            vorschlagAnf = anf;
            if (!vorschlagOffen) vorschlagVorModus = modus.equals("complete") ? "insert" : modus;
            vorschlagOffen = true;
            modus = "complete";
            filtern();
            neuMalen.run();
        });
    }

    /** fertige Vorschlaege zeigen (SQL); anf = Anfang des Wortes */
    void vorschlaegeZeigen(List<Map<String, Object>> l, int anf) {
        if (l.isEmpty()) {
            meldung = "keine Vorschlaege";
            return;
        }
        vorschlaege = l;
        vorschlagAnf = anf;
        if (!vorschlagOffen) vorschlagVorModus = modus.equals("complete") ? "insert" : modus;
        vorschlagOffen = true;
        modus = "complete";
        filtern();
    }

    private static String feld(Map<String, Object> v, String k) {
        Object o = Json.get(v.get("item"), k);
        return o == null ? null : o.toString();
    }

    private void filtern() {
        Doc d = d();
        String q = d.t.substring(Math.min(vorschlagAnf, d.cursor), d.cursor).toLowerCase();
        List<Object[]> b = new ArrayList<>();
        for (Map<String, Object> v : vorschlaege) {
            String f = feld(v, "filterText");
            if (f == null) f = feld(v, "label");
            String fl = f.toLowerCase();
            int w = q.isEmpty() ? 0 : Indexer.bewerten(fl, q, null);
            if (w < 0) continue;
            String sort = feld(v, "sortText");
            boolean vorne = fl.startsWith(q);
            b.add(new Object[] {vorne ? 0 : 1, sort == null ? f : sort, -w, v});
        }
        b.sort((x, y) -> {
            int c = Integer.compare((int) x[0], (int) y[0]);
            if (c != 0) return c;
            c = ((String) x[1]).compareTo((String) y[1]);
            return c != 0 ? c : Integer.compare((int) x[2], (int) y[2]);
        });
        List<Map<String, Object>> g = new ArrayList<>();
        for (Object[] x : b) {
            @SuppressWarnings("unchecked")
            Map<String, Object> v = (Map<String, Object>) x[3];
            g.add(v);
        }
        gefiltert = g;
        vorschlagWahl = 0;
        if (g.isEmpty()) vorschlagZu();
    }

    private void vorschlagZu() {
        vorschlagOffen = false;
        if (modus.equals("complete")) modus = vorschlagVorModus;
    }

    private void vorschlagNehmen() {
        if (gefiltert.isEmpty()) {
            vorschlagZu();
            return;
        }
        Map<String, Object> v = gefiltert.get(vorschlagWahl);
        Object item = v.get("item");
        Doc d = d();
        merken(null);
        String text = Json.str(Json.get(item, "textEdit", "newText"));
        int a = vorschlagAnf;
        if (text != null) {
            Object start = Json.get(item, "textEdit", "range", "start");
            if (start == null) start = Json.get(item, "textEdit", "replace", "start");
            if (start != null) a = Math.min(offset(d, start), d.cursor);
        } else {
            text = Json.str(Json.get(item, "insertText"));
            if (text == null) text = Json.str(Json.get(item, "label"));
        }
        if (Json.num(Json.get(item, "insertTextFormat"), 1) == 2) text = ohneSnippet(text);
        d.ersetzen(a, d.cursor, text);
        d.cursor = a + text.length();
        // automatische Importe
        List<Object> extra = Json.list(Json.get(item, "additionalTextEdits"));
        if (!extra.isEmpty()) anwenden(d, extra);
        vorschlagZu();
    }

    static String ohneSnippet(String s) {
        return s.replaceAll("\\$\\{\\d+:([^}]*)\\}", "$1").replaceAll("\\$\\{\\d+\\}", "").replaceAll("\\$\\d+", "");
    }

    // ================================================================ Maus
    void maus(int x, int y, boolean klick, int rad) {
        if (popupOffen) return;
        if (fm.offen) {
            fm.maus(x, y, klick, rad);
            neuMalen.run();
            return;
        }
        Doc d = d();
        if (rad != 0) {
            d.oben = Math.max(0, Math.min(d.zeilen() - 1, d.oben + rad * 3));
            neuMalen.run();
            return;
        }
        int oben = oben();
        if (klick && y >= oben && y < oben + textZeilen() * scr.lh) {
            int z = Math.min(d.zeilen() - 1, d.oben + (y - oben) / scr.lh);
            int sp = Math.max(0, (x - rand(d) * scr.cw) / scr.cw + d.links);
            d.cursor = posBeiVspalte(d, z, sp);
            d.spalteMerk = -1;
            info = null;
            if (vorschlagOffen) vorschlagZu();
            neuMalen.run();
        }
    }

    // ================================================================ Malen
    int oben() {
        return scr.lh + 14;
    }

    int unten() {
        return scr.lh + 10;
    }

    int textZeilen() {
        int h = scr.h - oben() - unten() - 8;
        if (konsoleOffen || wz.tabelleOffen() || wz.dbg.offen) h = (scr.h - oben() - unten()) * 3 / 5;
        return Math.max(1, h / scr.lh);
    }

    int rand(Doc d) {
        return Math.max(5, String.valueOf(d.zeilen()).length() + 2);
    }

    private void grund(int y, int h) {
        scr.rect(0, y, scr.w, h, E_GRUND);
        for (int yy = y - y % 4 + 3; yy < y + h; yy += 4) scr.rect(0, yy, scr.w, 1, E_RASTER);
    }

    void malen() {
        Doc d = d();
        int cw = scr.cw, lh = scr.lh, W = scr.w, H = scr.h;
        scr.noClip();
        scr.clear(E_GRUND);
        if (d.sprache() != null && d.farbenVersion != d.version && d.t.length() < 4_000_000) {
            d.farben = Highlight.faerben(d.t, d.sprache());
            d.farbenVersion = d.version;
        }
        // ------------------------------------------------ Tab-Leiste
        int bh = lh + 8;
        scr.rect(0, 0, W, bh, E_LEISTE);
        scr.rect(0, bh, W, 2, E_TEXT);
        int ty = 4;
        String rechts = wz.bereiche();
        int maxR = Math.max(10, W / cw / 3);
        if (rechts.length() > maxR) rechts = "~" + rechts.substring(rechts.length() - maxR + 1);
        rechts += "  ";
        int rx = W - scr.textWidth(rechts) - 8;
        int x = scr.text(8, ty, ">> ", E_MATT);
        scr.clip(0, 0, rx - 16, bh);
        for (int i = 0; i < tabs.size(); i++) {
            Doc u = tabs.get(i);
            String n = " " + u.name() + (u.geaendert ? "+" : "") + " ";
            int w = scr.textWidth(n);
            if (i == aktiv) {
                scr.rect(x, 2, w, bh - 2, E_TEXT);
                scr.text(x, ty, n, E_GRUND);
            } else {
                scr.text(x, ty, n, E_MATT);
            }
            x += w + cw;
        }
        scr.noClip();
        scr.text(rx, ty, rechts, E_MATT);
        // ------------------------------------------------ Text
        int oben = oben();
        int n = textZeilen();
        int cz = d.zeileVon(d.cursor);
        int rand = rand(d);
        int spalten = Math.max(1, W / cw - rand - 1);
        int rand3 = Math.min(3, (n - 1) / 2);
        if (cz < d.oben + rand3) d.oben = Math.max(0, cz - rand3);
        if (cz >= d.oben + n - rand3) d.oben = cz - n + rand3 + 1;
        d.oben = Math.max(0, Math.min(d.oben, d.zeilen() - 1));
        int cs = vspalte(d, d.cursor);
        if (cs < d.links) d.links = cs;
        if (cs >= d.links + spalten) d.links = cs - spalten + 1;
        grund(oben - 4, n * lh + 4);
        int tx = rand * cw;
        boolean genau = !suche.equals(suche.toLowerCase());
        String sucheK = !sucheZeigen ? "" : genau ? suche : suche.toLowerCase();
        boolean visuell = modus.equals("visual") || (modus.equals("char") && vim.visArt != 0);
        int selLo = Math.min(vim.anker, d.cursor), selHi = Math.max(vim.anker, d.cursor);
        int selZ1 = d.zeileVon(selLo), selZ2 = d.zeileVon(selHi);
        int selV1 = Math.min(vspalte(d, vim.anker), cs), selV2 = Math.max(vspalte(d, vim.anker), cs);
        for (int k = 0; k < n; k++) {
            int z = d.oben + k;
            if (z >= d.zeilen()) break;
            int y = oben + k * lh;
            if (z == cz) scr.rect(0, y, W, lh, E_ZEILE);
            if (z == wz.dbg.haltZeile(d)) scr.rect(0, y, W, lh, 0x3A3200);
            int schwer = 0;
            for (Doc.Diag g : d.diags) {
                if (g.zeile() <= z && g.endZeile() >= z && (schwer == 0 || g.schwere() < schwer)) schwer = g.schwere();
            }
            String nr = String.valueOf(z + 1);
            int nf = z == cz ? E_HELL : E_MATT;
            if (schwer == 1) {
                nf = E_FEHLER;
                scr.rect(0, y, 4, lh, E_FEHLER);
            } else if (schwer == 2) {
                nf = E_WARN;
                scr.rect(0, y, 4, lh, E_WARN);
            }
            scr.text((rand - 1 - nr.length()) * cw, y, nr, nf);
            var hp = d.pfad == null ? null : wz.dbg.punkte.get(d.pfad);
            if (hp != null && hp.contains(z)) {
                // Haltepunkt: roter Punkt im Rand
                int r = Math.max(3, lh / 4);
                scr.rect(cw / 2, y + lh / 2 - r, 2 * r, 2 * r, E_FEHLER);
            }
            if (z == wz.dbg.haltZeile(d)) scr.text(0, y, ">", E_WARN);
            int a = d.anf(z), e = d.ende(z);
            scr.clip(tx, y, W - tx, lh);
            // Auswahl (Visual)
            if (visuell && z >= selZ1 && z <= selZ2) {
                int va, ve;
                if (vim.visArt == 2) {
                    va = 0;
                    ve = vspalte(d, e) + 1;
                } else if (vim.visArt == 3) {
                    va = selV1;
                    ve = d.spalteMerk >= Vim.BIS_ENDE ? Math.max(va + 1, vspalte(d, e) + 1) : selV2 + 1;
                } else {
                    va = z == selZ1 ? vspalte(d, selLo) : 0;
                    ve = z == selZ2 ? vspalte(d, selHi) + 1 : vspalte(d, e) + 1;
                }
                scr.rect(tx + (va - d.links) * cw, y, (ve - va) * cw, lh, 0x1E5A2E);
            }
            // Suchtreffer
            if (sucheK.length() > 0) {
                String zl = genau ? d.t.substring(a, e) : d.t.substring(a, e).toLowerCase();
                for (int p = zl.indexOf(sucheK); p >= 0; p = zl.indexOf(sucheK, p + 1)) {
                    int vx = vspalte(d, a + p) - d.links;
                    scr.rect(tx + vx * cw, y, sucheK.length() * cw, lh, 0x2A4A10);
                }
            }
            int v = 0;
            for (int p = a; p < e; p++) {
                char c = d.t.charAt(p);
                int nv = c == '\t' ? (v / tabBreite + 1) * tabBreite : v + 1;
                if (nv > d.links && v - d.links < spalten && c != '\t') {
                    int farbe = d.farben != null && d.farbenVersion == d.version && p < d.farben.length ? FARBEN[d.farben[p]] : E_TEXT;
                    int cp = c;
                    if (Character.isHighSurrogate(c) && p + 1 < e) cp = Character.toCodePoint(c, d.t.charAt(p + 1));
                    if (!Character.isLowSurrogate(c)) scr.glyph(tx + (v - d.links) * cw, y, cp, farbe);
                }
                v = nv;
            }
            // Fehler unterwellen
            for (Doc.Diag g : d.diags) {
                if (g.zeile() > z || g.endZeile() < z) continue;
                int sa = g.zeile() == z ? Math.min(e, a + g.spalte()) : a;
                int se = g.endZeile() == z ? Math.min(e, a + g.endSpalte()) : e;
                int va = vspalte(d, sa), ve = Math.max(va + 1, vspalte(d, se));
                scr.wave(tx + (va - d.links) * cw, y + lh - 3, (ve - va) * cw, g.schwere() == 1 ? E_FEHLER : E_WARN);
            }
            scr.noClip();
        }
        // ------------------------------------------------ Cursor
        if (!konsoleOffen || !modus.equals("console")) {
            int cx = tx + (cs - d.links) * cw;
            int cy = oben + (cz - d.oben) * lh;
            if (modus.equals("insert") || modus.equals("complete")) {
                scr.rect(cx, cy + 1, Math.max(2, cw / 5), lh - 2, E_HELL);
            } else if (modus.equals("replace") || modus.equals("operator")) {
                scr.rect(cx, cy + lh - Math.max(3, lh / 6), cw, Math.max(3, lh / 6), E_HELL);
            } else if (!fm.offen && !popupOffen) {
                scr.rect(cx, cy + 1, cw, lh - 2, E_TEXT);
                char ch = d.at(d.cursor);
                if (ch > 32) scr.glyph(cx, cy, ch, E_GRUND);
            }
        }
        if (konsoleOffen) konsoleMalen(oben + n * lh + 6);
        else if (wz.dbg.offen && (modus.equals("debug") || !wz.tabelleOffen() || !modus.equals("result"))) wz.dbg.malen(oben + n * lh + 6);
        else if (wz.tabelleOffen()) wz.ergebnisMalen(oben + n * lh + 6);
        if (vorschlagOffen) vorschlaegeMalen(tx + (cs - d.links) * cw, oben + (cz - d.oben + 1) * lh);
        if (info != null) infoMalen(tx + (cs - d.links) * cw, oben + (cz - d.oben) * lh);
        if (fm.offen) fm.malen(scr, bh + 2, H - unten());
        if (popupOffen) popupMalen();
        if (wz.auswahlOffen) wz.auswahlMalen();
        // ------------------------------------------------ Statuszeile
        int sy = H - unten();
        scr.rect(0, sy - 2, W, 2, E_MATT);
        scr.rect(0, sy, W, unten(), E_LEISTE);
        String m = " " + modusAnzeige() + " ";
        int mw = scr.textWidth(m);
        int mf = modus.equals("insert") || modus.equals("replace") ? E_WARN : modus.equals("console") ? E_HELL : visuell ? E_INFO : E_TEXT;
        int my = sy + (unten() - lh) / 2;
        scr.rect(0, sy, mw, unten(), mf);
        scr.text(0, my, m, E_GRUND);
        String pf = relativ(d.pfad) + (d.geaendert ? " [+]" : "");
        x = scr.text(mw + cw, my, pf, E_HELL);
        int fehler = 0, warn = 0;
        for (Doc.Diag g : d.diags) {
            if (g.schwere() == 1) fehler++;
            else if (g.schwere() == 2) warn++;
        }
        String r = (vim.makroReg != 0 ? "rec @" + vim.makroReg + "   " : "") + (vim.regWahl != 0 ? "\"" + vim.regWahl + " " : "")
                + (vim.zaehler > 0 ? vim.zaehler + " " : "") + (vim.opOffen ? vim.op + " " : "") + (folge.isEmpty() ? "" : folge + "   ") + (fehler + warn > 0 ? "x" + fehler + " !" + warn + "   " : "") + wz.status() + "   "
                + (index.laeuft ? "Index..." : "Index " + index.dateien.size()) + "   " + (cz + 1) + ":" + (cs + 1) + " ";
        int rw = scr.textWidth(r);
        scr.text(W - rw - cw, my, r, E_TEXT);
        scr.clip(x + 2 * cw, sy, W - rw - 3 * cw - x, unten());
        if (meldung != null) {
            scr.text(x + 2 * cw, my, meldung, meldungFarbe);
        } else {
            for (Doc.Diag g : d.diags) {
                if (g.zeile() == cz) {
                    scr.text(x + 2 * cw, my, g.text(), g.schwere() == 1 ? E_FEHLER : E_WARN);
                    break;
                }
            }
        }
        scr.noClip();
        meldungFarbe = meldung == null ? E_WARN : meldungFarbe;
    }

    private String modusAnzeige() {
        if (modus.equals("visual") || (modus.equals("char") && vim.visArt != 0)) {
            return vim.visArt == 2 ? "V-LINE" : vim.visArt == 3 ? "V-BLOCK" : "VISUAL";
        }
        return switch (modus) {
            case "insert" -> "INSERT";
            case "replace" -> "REPLACE";
            case "normal" -> "NORMAL";
            case "operator" -> "NORMAL " + vim.op;
            case "result" -> "ERGEBNIS";
            case "debug" -> "DEBUG";
            case "picker" -> "AUSWAHL";
            default -> modus;
        };
    }

    private void konsoleMalen(int y0) {
        int W = scr.w, lh = scr.lh, cw = scr.cw;
        int h = scr.h - unten() - 6 - y0;
        scr.rect(0, y0, W, h, E_KONSOLE);
        scr.frame(4, y0, W - 8, h, E_TEXT);
        scr.frame(5, y0 + 1, W - 10, h - 2, E_MATT);
        String titel = " TERMINAL ";
        scr.rect(3 * cw, y0 - lh / 2, scr.textWidth(titel), lh, E_KONSOLE);
        scr.text(3 * cw, y0 - lh / 2, titel, E_HELL);
        int zeilen = Math.max(0, (h - lh - lh) / lh);
        int a = Math.max(0, konsole.size() - zeilen);
        scr.clip(8, y0 + 2, W - 16, h - 4);
        for (int i = a; i < konsole.size(); i++) scr.text(2 * cw, y0 + lh / 2 + (i - a) * lh, konsole.get(i), konsoleFarbe.get(i));
        int ey = y0 + h - lh - 6;
        int x = scr.text(2 * cw, ey, "runen> ", E_HELL);
        x = scr.text(x, ey, eingabe, E_TEXT);
        if (modus.equals("console")) scr.rect(x + 1, ey + 1, cw - 2, lh - 2, E_TEXT);
        scr.noClip();
    }

    private void kasten(int x, int y, int w, int h) {
        scr.rect(x + 6, y + 6, w, h, 0x000000);
        scr.rect(x, y, w, h, E_KONSOLE);
        scr.frame(x, y, w, h, E_HELL);
        scr.frame(x + 3, y + 3, w - 6, h - 6, E_MATT);
    }

    private void popupMalen() {
        int cw = scr.cw, lh = scr.lh;
        int w = Math.min(scr.w - 4 * cw, Math.max(100 * cw, scr.textWidth(popupTitel) + 6 * cw));
        int h = lh * 5 + 20;
        int x = (scr.w - w) / 2, y = (scr.h - h) / 2;
        kasten(x, y, w, h);
        scr.clip(x, y, w, h);
        scr.text(x + 2 * cw, y + lh / 2 + 4, popupTitel, E_HELL);
        int ey = y + lh * 2;
        scr.rect(x + 2 * cw, ey, w - 4 * cw, lh + 8, E_ZEILE);
        scr.frame(x + 2 * cw, ey, w - 4 * cw, lh + 8, E_MATT);
        String e = popupEingabe.toString();
        int platz = (w - 6 * cw) / cw;
        if (e.length() > platz) e = e.substring(e.length() - platz);
        int ex = scr.text(x + 3 * cw, ey + 4, e, E_TEXT);
        scr.rect(ex + 1, ey + 5, cw - 2, lh - 2, E_TEXT);
        scr.text(x + 2 * cw, y + h - lh - 8, "ENTER ausfuehren   ESC abbrechen   TAB Pfad ergaenzen   Strg+U leeren", E_MATT);
        scr.noClip();
    }

    private void vorschlaegeMalen(int x, int y) {
        int cw = scr.cw, lh = scr.lh;
        int n = Math.min(12, gefiltert.size());
        if (n == 0) return;
        int breite = 30;
        for (Map<String, Object> v : gefiltert.subList(0, Math.min(200, gefiltert.size()))) {
            String l = feld(v, "label");
            String dt = feld(v, "detail");
            breite = Math.max(breite, l.length() + (dt == null ? 0 : Math.min(40, dt.length()) + 3));
        }
        breite = Math.min(breite + 4, scr.w / cw - 4);
        int w = breite * cw, h = n * lh + 8;
        if (x + w > scr.w - 8) x = Math.max(8, scr.w - 8 - w);
        if (y + h > scr.h - unten()) y = y - lh - h;
        kasten(x, y, w, h);
        int oben = Math.max(0, Math.min(vorschlagWahl - n / 2, gefiltert.size() - n));
        scr.clip(x, y, w, h);
        for (int k = 0; k < n; k++) {
            int i = oben + k;
            Map<String, Object> v = gefiltert.get(i);
            int zy = y + 4 + k * lh;
            int f = E_TEXT;
            if (i == vorschlagWahl) {
                scr.rect(x + 3, zy, w - 6, lh, E_TEXT);
                f = E_GRUND;
            }
            int art = Json.num(Json.get(v.get("item"), "kind"), 0);
            scr.text(x + cw, zy, artZeichen(art), i == vorschlagWahl ? E_GRUND : E_INFO);
            scr.text(x + 3 * cw, zy, feld(v, "label"), f);
            String dt = feld(v, "detail");
            if (dt != null) {
                dt = dt.replace('\n', ' ');
                if (dt.length() > 40) dt = dt.substring(0, 39) + "~";
                scr.text(x + w - cw - scr.textWidth(dt), zy, dt, i == vorschlagWahl ? E_GRUND : E_MATT);
            }
        }
        scr.noClip();
    }

    private static String artZeichen(int k) {
        return switch (k) {
            case 2, 3 -> "f";
            case 4 -> "c";
            case 5, 10 -> "p";
            case 6 -> "v";
            case 7, 22 -> "C";
            case 8 -> "I";
            case 9 -> "M";
            case 13 -> "E";
            case 14 -> "k";
            case 21 -> "K";
            case 25 -> "T";
            default -> " ";
        };
    }

    private void infoMalen(int x, int y) {
        int cw = scr.cw, lh = scr.lh;
        int maxB = Math.min(120, scr.w / cw - 6);
        List<String> z = new ArrayList<>();
        for (String s : info) {
            while (s.length() > maxB) {
                z.add(s.substring(0, maxB));
                s = s.substring(maxB);
            }
            z.add(s);
        }
        if (z.size() > 24) z = z.subList(0, 24);
        int breite = 10;
        for (String s : z) breite = Math.max(breite, s.length());
        int w = (breite + 4) * cw, h = z.size() * lh + 12;
        x = Math.max(8, Math.min(x, scr.w - w - 8));
        int yy = y - h - 4;
        if (yy < oben()) yy = y + lh + 4;
        kasten(x, yy, w, h);
        for (int k = 0; k < z.size(); k++) scr.text(x + 2 * cw, yy + 6 + k * lh, z.get(k), k == 0 ? E_INFO : E_TEXT);
    }
}
