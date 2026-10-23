package runen;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Vim-Grammatik ueber die Modes der keymap.config: Zaehler (count), Operatoren (Mode operator),
 * Bewegungen (motion), Textobjekte, Warten auf ein Zeichen (Mode char), Visual (v, V, Block),
 * Register, Marken, Makros, Punkt-Wiederholung und :s.
 */
final class Vim {
    static final int EXKL = 0, INKL = 1, ZEILE = 2;
    /** Registerarten */
    static final int ZEICHEN = 0, ZEILEN = 1, BLOCK = 2;
    /** gewuenschte Spalte nach $: immer das Zeilenende */
    static final int BIS_ENDE = 1 << 20;

    record Ziel(int pos, int art) {}

    record Reg(String text, int art) {}

    record Marke(Path pfad, int zeile, int spalte) {}

    private final Editor ed;

    int zaehler;
    // Operator wartet auf eine Bewegung
    boolean opOffen;
    String op = "";
    private int opZaehler = 1;
    // Befehl wartet auf ein Zeichen (f, t, r, m, ", q, @)
    String wartet;
    private String zeichenVorModus = "normal";
    private int zeichenZaehler;
    // Register
    final Map<Character, Reg> regs = new HashMap<>();
    char regWahl;
    /** Speicherbaenke fuer Strg+C/Strg+V mit Ziffer; 0 ist die Systemzwischenablage */
    private final Reg[] baenke = new Reg[10];
    /** naechstes Schreiben bzw. Lesen eines Registers geht an diese Bank (-1: normal) */
    int bankZiel = -1, bankQuelle = -1;
    // f/t/F/T fuer ; und ,
    private String letztesF;
    private String letztesFZeichen;
    // Visual: 0 aus, 1 v, 2 V, 3 Block
    int visArt;
    int anker;
    private int[] letzteAuswahl;
    private Doc letzteAuswahlDoc;
    // Block einfuegen (Strg+V I/A/c)
    private int blockStart = -1, blockZ1, blockZ2, blockSpalte;
    private boolean blockAnhaengen;
    private Doc blockDoc;
    // 3ifoo<ESC>: getippten Text wiederholen
    private int einfStart = -1, einfN;
    private Doc einfDoc;
    final Map<Character, Marke> marken = new TreeMap<>();
    // Makros
    char makroReg;
    private List<String[]> makroAufnahme;
    private final Map<Character, List<String[]>> makros = new HashMap<>();
    private char letztesMakro;
    private int spieltTiefe;
    // Punkt
    private List<String[]> punktAufnahme, letzteAenderung;
    private Doc punktDoc;
    private int punktVersion;
    boolean nichtMerken;
    boolean sucheRueck;

    Vim(Editor ed) {
        this.ed = ed;
    }

    private Doc d() {
        return ed.d();
    }

    int nehmen() {
        int n = Math.max(1, zaehler);
        zaehler = 0;
        return n;
    }

    // ================================================================ Tasten aufnehmen (Makros, Punkt)
    private boolean ruhig() {
        return ed.modus.equals("normal") && ed.folgeLeer() && zaehler == 0 && !opOffen && wartet == null && regWahl == 0;
    }

    void vorTaste(String name, int code) {
        if (spieltTiefe > 0) return;
        String[] k = {name, String.valueOf(code)};
        if (makroReg != 0) makroAufnahme.add(k);
        if (punktAufnahme == null && ruhig()) {
            punktAufnahme = new ArrayList<>();
            punktDoc = d();
            punktVersion = d().version;
            nichtMerken = false;
        }
        if (punktAufnahme != null) punktAufnahme.add(k);
    }

    void nachTaste() {
        if (spieltTiefe > 0 || punktAufnahme == null || !ruhig()) return;
        if (!nichtMerken && d() == punktDoc && d().version != punktVersion) letzteAenderung = punktAufnahme;
        punktAufnahme = null;
    }

    private void abspielen(List<String[]> tasten) {
        if (tasten == null || spieltTiefe > 20) return;
        spieltTiefe++;
        try {
            for (String[] k : tasten) ed.tasteIntern(k[0], Integer.parseInt(k[1]));
        } finally {
            spieltTiefe--;
        }
    }

    // ================================================================ Register
    void bankSetzen(int n, Reg g) {
        baenke[n] = g;
        if (n == 0) Editor.zwischenablage(g.text());
    }

    Reg bank(int n) {
        if (n != 0) return baenke[n];
        String t = Editor.ausZwischenablage();
        if (t == null) return null;
        t = t.replace("\r\n", "\n");
        // selbst kopiert: Art (Zeilen, Block) behalten
        if (baenke[0] != null && baenke[0].text().equals(t)) return baenke[0];
        return new Reg(t, t.endsWith("\n") ? ZEILEN : ZEICHEN);
    }

    void regSchreiben(String text, int art, boolean yank) {
        if (bankZiel >= 0) {
            int b = bankZiel;
            bankZiel = -1;
            regWahl = 0;
            bankSetzen(b, new Reg(text, art));
            return;
        }
        char r = regWahl;
        regWahl = 0;
        if (r == '_') return;
        Reg g = new Reg(text, art);
        if (r >= 'A' && r <= 'Z') {
            char k = Character.toLowerCase(r);
            Reg alt = regs.get(k);
            if (alt != null) g = new Reg(alt.text() + text, alt.art() == ZEILEN || art == ZEILEN ? ZEILEN : art);
            regs.put(k, g);
        } else if (r != 0 && r != '"') {
            regs.put(r, g);
        }
        regs.put('"', g);
        if (r == 0) {
            if (yank) {
                regs.put('0', g);
            } else if (art == ZEILEN || text.contains("\n")) {
                for (char c = '9'; c > '1'; c--) {
                    Reg v = regs.get((char) (c - 1));
                    if (v != null) regs.put(c, v);
                }
                regs.put('1', g);
            } else {
                regs.put('-', g);
            }
        }
        Editor.zwischenablage(text);
    }

    Reg regLesen() {
        if (bankQuelle >= 0) {
            int b = bankQuelle;
            bankQuelle = -1;
            regWahl = 0;
            return bank(b);
        }
        char r = regWahl;
        regWahl = 0;
        if (r == '+' || r == '*') {
            String t = Editor.ausZwischenablage();
            if (t == null) return null;
            t = t.replace("\r\n", "\n");
            return new Reg(t, t.endsWith("\n") ? ZEILEN : ZEICHEN);
        }
        return regs.get(r == 0 ? '"' : Character.toLowerCase(r));
    }

    // ================================================================ Woerter
    private static int klasse(char c, boolean gross) {
        if (Character.isWhitespace(c)) return 0;
        if (gross) return 1;
        return Highlight.ident(c) ? 1 : 2;
    }

    static int wortVor(Doc d, int p, boolean gross) {
        int n = d.t.length();
        if (p >= n) return n;
        int k = klasse(d.at(p), gross);
        while (p < n && k != 0 && klasse(d.at(p), gross) == k) p++;
        while (p < n && klasse(d.at(p), gross) == 0) {
            // leere Zeile zaehlt als Wort
            if (d.at(p) == '\n' && p + 1 < n && d.at(p + 1) == '\n') return p + 1;
            p++;
        }
        return p;
    }

    static int wortZurueck(Doc d, int p, boolean gross) {
        if (p <= 0) return 0;
        p--;
        while (p > 0 && klasse(d.at(p), gross) == 0) p--;
        int k = klasse(d.at(p), gross);
        while (p > 0 && klasse(d.at(p - 1), gross) == k) p--;
        return p;
    }

    private static int wortEnde(Doc d, int p, boolean gross) {
        int n = d.t.length();
        if (p >= n - 1) return Math.max(0, n - 1);
        p++;
        while (p < n - 1 && klasse(d.at(p), gross) == 0) p++;
        int k = klasse(d.at(p), gross);
        while (p + 1 < n && klasse(d.at(p + 1), gross) == k) p++;
        return p;
    }

    private static int wortEndeZurueck(Doc d, int p, boolean gross) {
        if (p <= 0) return 0;
        int k = klasse(d.at(p), gross);
        while (p > 0 && k != 0 && klasse(d.at(p), gross) == k) p--;
        while (p > 0 && klasse(d.at(p), gross) == 0) p--;
        return p;
    }

    private int ersteNichtLeere(Doc d, int z) {
        int p = d.anf(z);
        while (d.at(p) == ' ' || d.at(p) == '\t') p++;
        return p;
    }

    private static boolean leer(Doc d, int z) {
        return d.zeile(z).isBlank();
    }

    // ================================================================ Bewegungen
    Ziel bewegung(String name, int n, boolean hatN, String arg, boolean imOp) {
        Doc d = d();
        int p = d.cursor, z = d.zeileVon(p), len = d.t.length(), zn = d.zeilen();
        switch (name) {
            case "h", "LEFT", "BACKSPACE":
                return new Ziel(Math.max(d.anf(z), p - n), EXKL);
            case "l", "RIGHT", "SPACE": {
                int e = d.ende(z);
                int max = imOp ? e : Math.max(d.anf(z), e - 1);
                return new Ziel(Math.max(p, Math.min(max, p + n)), EXKL);
            }
            case "j", "DOWN", "k", "UP", "ENTER", "+", "-": {
                boolean runter = name.equals("j") || name.equals("DOWN") || name.equals("ENTER") || name.equals("+");
                int nz = Math.max(0, Math.min(zn - 1, z + (runter ? n : -n)));
                if (imOp && nz == z && n > 0 && (runter ? z == zn - 1 : z == 0)) return null;
                if (name.equals("ENTER") || name.equals("+") || name.equals("-")) return new Ziel(ersteNichtLeere(d, nz), ZEILE);
                if (d.spalteMerk < 0) d.spalteMerk = ed.vspalte(d, p);
                return new Ziel(ed.posBeiVspalte(d, nz, d.spalteMerk), ZEILE);
            }
            case "w", "W": {
                for (int k = 0; k < n; k++) p = wortVor(d, p, name.equals("W"));
                return new Ziel(p, EXKL);
            }
            case "b", "B": {
                for (int k = 0; k < n; k++) p = wortZurueck(d, p, name.equals("B"));
                return new Ziel(p, EXKL);
            }
            case "e", "E": {
                for (int k = 0; k < n; k++) p = wortEnde(d, p, name.equals("E"));
                return new Ziel(p, INKL);
            }
            case "ge", "gE": {
                for (int k = 0; k < n; k++) p = wortEndeZurueck(d, p, name.equals("gE"));
                return new Ziel(p, INKL);
            }
            case "0", "HOME":
                return new Ziel(d.anf(z), EXKL);
            case "^":
                return new Ziel(ersteNichtLeere(d, z), EXKL);
            case "$", "END":
                return new Ziel(d.ende(Math.min(zn - 1, z + n - 1)), EXKL);
            case "_":
                return new Ziel(ersteNichtLeere(d, Math.min(zn - 1, z + n - 1)), ZEILE);
            case "gg":
                return new Ziel(ersteNichtLeere(d, hatN ? Math.min(zn - 1, n - 1) : 0), ZEILE);
            case "G":
                return new Ziel(ersteNichtLeere(d, hatN ? Math.min(zn - 1, n - 1) : zn - 1), ZEILE);
            case "H":
                return new Ziel(ersteNichtLeere(d, Math.min(zn - 1, d.oben + (hatN ? n - 1 : 0))), ZEILE);
            case "L":
                return new Ziel(ersteNichtLeere(d, Math.max(d.oben, Math.min(zn - 1, d.oben + ed.textZeilen() - 1) - (hatN ? n - 1 : 0))), ZEILE);
            case "M":
                return new Ziel(ersteNichtLeere(d, d.oben + (Math.min(zn, d.oben + ed.textZeilen()) - 1 - d.oben) / 2), ZEILE);
            case "f", "t", "F", "T": {
                if (arg == null || arg.isEmpty()) return null;
                letztesF = name;
                letztesFZeichen = arg;
                return suchenInZeile(name, arg.charAt(0), n, false);
            }
            case ";", ",": {
                if (letztesF == null) return null;
                String f = letztesF;
                if (name.equals(",")) f = switch (f) {
                    case "f" -> "F";
                    case "F" -> "f";
                    case "t" -> "T";
                    default -> "t";
                };
                return suchenInZeile(f, letztesFZeichen.charAt(0), n, true);
            }
            case "%": {
                if (hatN) return new Ziel(ersteNichtLeere(d, Math.min(zn - 1, (zn * n + 99) / 100 - 1)), ZEILE);
                int e = d.ende(z), q = p;
                while (q < e && "()[]{}".indexOf(d.at(q)) < 0) q++;
                if (q >= e) return null;
                int m = passend(d, q);
                return m < 0 ? null : new Ziel(m, INKL);
            }
            case "}": {
                int k = z;
                for (int i = 0; i < n; i++) {
                    while (k < zn - 1 && leer(d, k)) k++;
                    while (k < zn - 1 && !leer(d, k)) k++;
                }
                return new Ziel(k >= zn - 1 && !leer(d, k) ? len : d.anf(k), EXKL);
            }
            case "{": {
                int k = z;
                for (int i = 0; i < n; i++) {
                    while (k > 0 && leer(d, k)) k--;
                    while (k > 0 && !leer(d, k)) k--;
                }
                return new Ziel(d.anf(k), EXKL);
            }
            case "n", "N": {
                boolean vor = name.equals("n") != sucheRueck;
                int q = p;
                for (int i = 0; i < n; i++) {
                    int r = suchen(q, vor);
                    if (r < 0) return null;
                    q = r;
                }
                ed.sucheZeigen = true;
                return new Ziel(q, EXKL);
            }
            default:
                ed.fehler("unbekannte Bewegung: " + name);
                return null;
        }
    }

    private Ziel suchenInZeile(String art, char c, int n, boolean wiederholt) {
        Doc d = d();
        int p = d.cursor, z = d.zeileVon(p), a = d.anf(z), e = d.ende(z);
        boolean vor = art.equals("f") || art.equals("t");
        int q = p;
        for (int i = 0; i < n; i++) {
            int start = q + (vor ? 1 : -1);
            // ; nach t/T springt nicht auf der Stelle
            if (wiederholt && i == 0 && (art.equals("t") || art.equals("T"))) start += vor ? 1 : -1;
            q = -1;
            for (int k = start; vor ? k < e : k >= a; k += vor ? 1 : -1) {
                if (d.at(k) == c) {
                    q = k;
                    break;
                }
            }
            if (q < 0) return null;
        }
        return switch (art) {
            case "f" -> new Ziel(q, INKL);
            case "t" -> new Ziel(q - 1, INKL);
            case "F" -> new Ziel(q, EXKL);
            default -> new Ziel(q + 1, EXKL);
        };
    }

    static int passend(Doc d, int q) {
        char c = d.at(q);
        int i = "([{)]}".indexOf(c);
        if (i < 0) return -1;
        char auf = "([{([{".charAt(i), zu = ")]})]}".charAt(i);
        int tiefe = 0, len = d.t.length();
        if (i < 3) {
            for (int k = q; k < len; k++) {
                if (d.at(k) == auf) tiefe++;
                else if (d.at(k) == zu && --tiefe == 0) return k;
            }
        } else {
            for (int k = q; k >= 0; k--) {
                if (d.at(k) == zu) tiefe++;
                else if (d.at(k) == auf && --tiefe == 0) return k;
            }
        }
        return -1;
    }

    /** Suchtext ab p; gross geschrieben = genau */
    int suchen(int p, boolean vor) {
        String s = ed.suche;
        if (s.isEmpty()) return -1;
        Doc d = d();
        boolean genau = !s.equals(s.toLowerCase());
        String t = genau ? d.t.toString() : d.t.toString().toLowerCase();
        String q = genau ? s : s.toLowerCase();
        int r = vor ? t.indexOf(q, p + 1) : t.lastIndexOf(q, p - 1);
        if (r < 0) {
            r = vor ? t.indexOf(q) : t.lastIndexOf(q);
            if (r >= 0) ed.meldung = vor ? "Suche oben weiter" : "Suche unten weiter";
        }
        if (r < 0) ed.meldung = "nicht gefunden: " + s;
        return r;
    }

    private static boolean sprung(String name) {
        return switch (name) {
            case "gg", "G", "%", "{", "}", "n", "N", "H", "M", "L" -> true;
            default -> false;
        };
    }

    void motion(String arg) {
        String name = arg, ch = null;
        int sp = arg.indexOf(' ');
        if (sp > 0) {
            name = arg.substring(0, sp);
            ch = arg.substring(sp + 1);
        }
        boolean hatN = zaehler > 0;
        int n = nehmen();
        Doc d = d();
        if (opOffen) {
            hatN |= opZaehler > 1;
            n *= opZaehler;
            if (op.equals("c") && (name.equals("w") || name.equals("W")) && !Character.isWhitespace(d.at(d.cursor))) name = name.equals("w") ? "e" : "E";
            Ziel z = bewegung(name, n, hatN, ch, true);
            if (z == null) {
                abbrechen();
                return;
            }
            int a = d.cursor, e = z.pos();
            boolean zeilen = z.art() == ZEILE;
            if (!zeilen) {
                if (a > e) {
                    int t = a;
                    a = e;
                    e = t;
                }
                if (z.art() == INKL) {
                    e = Math.min(e + 1, d.t.length());
                } else if (e > a && d.zeileVon(e) > d.zeileVon(a) && e == d.anf(d.zeileVon(e))) {
                    // dw am Zeilenende nimmt den Zeilenumbruch nicht mit
                    int pe = d.ende(d.zeileVon(e) - 1);
                    if (pe > a) e = pe;
                }
            }
            if (name.equals("%") || name.equals("n") || name.equals("N")) ed.springMerken();
            opAnwenden(op, a, e, zeilen);
            return;
        }
        Ziel z = bewegung(name, n, hatN, ch, false);
        if (z == null) return;
        if (sprung(name)) ed.springMerken();
        d.cursor = Math.max(0, Math.min(z.pos(), d.t.length()));
        switch (name) {
            case "j", "k", "UP", "DOWN" -> {
            }
            case "$", "END" -> d.spalteMerk = BIS_ENDE;
            default -> d.spalteMerk = -1;
        }
    }

    // ================================================================ Operatoren
    void operator(String o) {
        opOffen = true;
        op = o.trim();
        opZaehler = nehmen();
        ed.modus = "operator";
    }

    void abbrechen() {
        opOffen = false;
        wartet = null;
        zaehler = 0;
        regWahl = 0;
        if (ed.modus.equals("operator") || ed.modus.equals("char")) ed.modus = visArt != 0 ? "visual" : "normal";
    }

    /** dd, cc, yy, >>, guu ...: n Zeilen ab dem Cursor */
    void operatorZeile(String o) {
        if (!o.isBlank()) {
            op = o.trim();
            opZaehler = 1;
        }
        int n = opZaehler * nehmen();
        Doc d = d();
        int z1 = d.zeileVon(d.cursor);
        int z2 = Math.min(d.zeilen() - 1, z1 + n - 1);
        opAnwenden(op, d.anf(z1), d.ende(z2), true);
    }

    /** x = opMotion d l, D = opMotion d $ ... */
    void opMotion(String arg) {
        String[] t = arg.trim().split("\\s+", 2);
        if (t.length < 2) return;
        int n = nehmen();
        opOffen = true;
        op = t[0];
        opZaehler = n;
        motion(t[1]);
    }

    void opAnwenden(String o, int a, int e, boolean zeilen) {
        Doc d = d();
        opOffen = false;
        ed.modus = "normal";
        int len = d.t.length();
        if (a > e) {
            int t = a;
            a = e;
            e = t;
        }
        a = Math.max(0, Math.min(a, len));
        e = Math.max(a, Math.min(e, len));
        int z1 = d.zeileVon(a), z2 = d.zeileVon(zeilen ? e : Math.max(a, e - 1));
        if (zeilen) {
            a = d.anf(z1);
            e = d.ende(z2);
        }
        if (!o.equals("y")) ed.merken(null);
        String text = d.t.substring(a, e);
        switch (o) {
            case "d" -> {
                if (zeilen) {
                    regSchreiben(text + "\n", ZEILEN, false);
                    if (e < d.t.length()) d.loeschen(a, e - a + 1);
                    else if (a > 0) d.loeschen(a - 1, e - a + 1);
                    else d.loeschen(a, e - a);
                    d.cursor = ersteNichtLeere(d, Math.min(z1, d.zeilen() - 1));
                } else {
                    regSchreiben(text, ZEICHEN, false);
                    d.loeschen(a, e - a);
                    d.cursor = a;
                }
            }
            case "c" -> {
                if (zeilen) {
                    regSchreiben(text + "\n", ZEILEN, false);
                    String ein = Editor.einrueckung(d.zeile(z1));
                    d.ersetzen(a, e, ein);
                    d.cursor = a + ein.length();
                } else {
                    regSchreiben(text, ZEICHEN, false);
                    d.loeschen(a, e - a);
                    d.cursor = a;
                }
                ed.modus = "insert";
            }
            case "y" -> {
                regSchreiben(zeilen ? text + "\n" : text, zeilen ? ZEILEN : ZEICHEN, true);
                if (!zeilen) d.cursor = a;
                else if (d.zeileVon(d.cursor) > z1) d.cursor = ed.posBeiVspalte(d, z1, ed.vspalte(d, d.cursor));
                ed.meldung = zeilen ? (z2 - z1 + 1) + " Zeilen kopiert" : text.length() + " Zeichen kopiert";
            }
            case ">", "<" -> {
                einruecken(z1, z2, o.equals(">"));
                d.cursor = ersteNichtLeere(d, z1);
            }
            case "gu", "gU", "g~", "~" -> {
                d.ersetzen(a, e, fall(o, text));
                d.cursor = a;
            }
            case "J" -> verbinden(z1, Math.max(z2, z1 + 1), true);
            default -> ed.fehler("unbekannter Operator: " + o);
        }
        d.spalteMerk = -1;
    }

    private static String fall(String o, String s) {
        if (o.equals("gu")) return s.toLowerCase();
        if (o.equals("gU")) return s.toUpperCase();
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) b.append(Character.isUpperCase(c) ? Character.toLowerCase(c) : Character.toUpperCase(c));
        return b.toString();
    }

    private void einruecken(int z1, int z2, boolean rein) {
        Doc d = d();
        int ein = ed.einzug;
        for (int z = z2; z >= z1; z--) {
            int a = d.anf(z);
            if (rein) {
                if (!leer(d, z)) d.einfuegen(a, " ".repeat(ein));
            } else {
                int k = 0;
                while (k < ein && (d.at(a + k) == ' ')) k++;
                if (k == 0 && d.at(a) == '\t') k = 1;
                d.loeschen(a, k);
            }
        }
    }

    void verbinden(int z1, int z2, boolean leerzeichen) {
        Doc d = d();
        z2 = Math.min(z2, d.zeilen() - 1);
        int c = d.cursor;
        for (int k = z1; k < z2; k++) {
            int e = d.ende(z1);
            int q = e + 1;
            while (leerzeichen && (d.at(q) == ' ' || d.at(q) == '\t')) q++;
            boolean naechsteLeer = d.at(q) == '\n' || q >= d.t.length();
            String zw = !leerzeichen || naechsteLeer || d.at(q) == ')' || (e > d.anf(z1) && d.at(e - 1) == ' ') ? "" : " ";
            d.ersetzen(e, q, zw);
            c = e;
        }
        d.cursor = c;
    }

    // ================================================================ Textobjekte
    /** {anfang, ende, zeilenweise} oder null */
    int[] textObjekt(String name) {
        Doc d = d();
        int p = d.cursor;
        if (name.length() < 2) return null;
        boolean innen = name.charAt(0) == 'i';
        String art = name.substring(1);
        switch (art) {
            case "w", "W": {
                boolean gross = art.equals("W");
                int z = d.zeileVon(p), za = d.anf(z), ze = d.ende(z);
                if (ze == za) return null;
                p = Math.min(p, ze - 1);
                int k = klasse(d.at(p), gross);
                int a = p, e = p;
                while (a > za && klasse(d.at(a - 1), gross) == k) a--;
                while (e < ze && klasse(d.at(e), gross) == k) e++;
                if (!innen) {
                    int e2 = e;
                    while (e2 < ze && klasse(d.at(e2), gross) == 0) e2++;
                    if (e2 > e && k != 0) e = e2;
                    else while (a > za && klasse(d.at(a - 1), gross) == 0) a--;
                }
                return new int[] {a, e, 0};
            }
            case "\"", "'", "`": {
                char q = art.charAt(0);
                int z = d.zeileVon(p), za = d.anf(z), ze = d.ende(z);
                List<Integer> l = new ArrayList<>();
                for (int k = za; k < ze; k++) if (d.at(k) == q && (k == za || d.at(k - 1) != '\\')) l.add(k);
                for (int k = 0; k + 1 < l.size(); k += 2) {
                    int a = l.get(k), e = l.get(k + 1);
                    if (p <= e) {
                        if (innen) return new int[] {a + 1, e, 0};
                        int e2 = e + 1;
                        while (e2 < ze && d.at(e2) == ' ') e2++;
                        return new int[] {a, e2, 0};
                    }
                }
                return null;
            }
            case "(", ")", "b", "{", "}", "B", "[", "]", "<", ">": {
                char auf = switch (art) {
                    case "(", ")", "b" -> '(';
                    case "{", "}", "B" -> '{';
                    case "[", "]" -> '[';
                    default -> '<';
                };
                char zu = auf == '(' ? ')' : auf == '{' ? '}' : auf == '[' ? ']' : '>';
                int a = -1, tiefe = 0;
                for (int k = d.at(p) == zu ? p - 1 : p; k >= 0; k--) {
                    char c = d.at(k);
                    if (c == zu) tiefe++;
                    else if (c == auf) {
                        if (tiefe == 0) {
                            a = k;
                            break;
                        }
                        tiefe--;
                    }
                }
                if (a < 0) return null;
                int e = -1;
                tiefe = 0;
                for (int k = a; k < d.t.length(); k++) {
                    char c = d.at(k);
                    if (c == auf) tiefe++;
                    else if (c == zu && --tiefe == 0) {
                        e = k;
                        break;
                    }
                }
                if (e < 0) return null;
                if (!innen) return new int[] {a, e + 1, 0};
                int ia = a + 1, ie = e;
                if (d.at(ia) == '\n') {
                    // mehrzeiliger Block: nur die inneren Zeilen
                    ia++;
                    int nl = ie - 1;
                    while (nl > ia && (d.at(nl) == ' ' || d.at(nl) == '\t')) nl--;
                    if (d.at(nl) == '\n' && nl >= ia) return new int[] {ia, nl, 1};
                }
                return new int[] {ia, ie, 0};
            }
            case "t": {
                int[] r = tag(d, p);
                if (r == null) return null;
                return innen ? new int[] {r[1], r[2], 0} : new int[] {r[0], r[3], 0};
            }
            case "p": {
                int z = d.zeileVon(p), zn = d.zeilen();
                boolean l = leer(d, z);
                int z1 = z, z2 = z;
                while (z1 > 0 && leer(d, z1 - 1) == l) z1--;
                while (z2 < zn - 1 && leer(d, z2 + 1) == l) z2++;
                if (!innen) while (z2 < zn - 1 && leer(d, z2 + 1)) z2++;
                return new int[] {d.anf(z1), d.ende(z2), 1};
            }
            default:
                return null;
        }
    }

    private static final Pattern TAG = Pattern.compile("<(/?)([A-Za-z][\\w.:-]*)?((?:[^<>\"'{}]|\"[^\"]*\"|'[^']*'|\\{(?:[^{}]|\\{[^{}]*\\})*\\})*?)(/?)>");

    /** innerstes Tag-Paar um p: {auf-anfang, auf-ende, zu-anfang, zu-ende} */
    private static int[] tag(Doc d, int p) {
        Matcher m = TAG.matcher(d.t);
        List<int[]> stapel = new ArrayList<>();
        List<String> namen = new ArrayList<>();
        int[] best = null;
        while (m.find()) {
            String name = m.group(2) == null ? "" : m.group(2);
            if (!m.group(4).isEmpty()) continue;
            if (m.group(1).isEmpty()) {
                stapel.add(new int[] {m.start(), m.end()});
                namen.add(name);
                continue;
            }
            for (int k = stapel.size() - 1; k >= 0; k--) {
                if (namen.get(k).equals(name)) {
                    int[] o = stapel.get(k);
                    if (o[0] <= p && p < m.end() && (best == null || o[0] >= best[0])) best = new int[] {o[0], o[1], m.start(), m.end()};
                    while (stapel.size() > k) {
                        stapel.remove(stapel.size() - 1);
                        namen.remove(namen.size() - 1);
                    }
                    break;
                }
            }
        }
        return best;
    }

    void textObjektBefehl(String name) {
        zaehler = 0;
        int[] r = textObjekt(name.trim());
        if (r == null) {
            abbrechen();
            return;
        }
        if (opOffen) {
            opAnwenden(op, r[0], r[1], r[2] == 1);
            return;
        }
        if (visArt != 0) {
            anker = r[0];
            d().cursor = Math.max(r[0], r[1] - 1);
            if (r[2] == 1) visArt = 2;
        }
    }

    // ================================================================ Auf ein Zeichen warten
    void warteAufZeichen(String befehl) {
        wartet = befehl.trim();
        zeichenVorModus = ed.modus;
        zeichenZaehler = zaehler;
        zaehler = 0;
        ed.modus = "char";
    }

    void zeichen(String c) {
        if (wartet == null || c.isEmpty()) return;
        String b = wartet;
        wartet = null;
        ed.modus = zeichenVorModus;
        zaehler = zeichenZaehler;
        int sp = b.indexOf(' ');
        String name = sp < 0 ? b : b.substring(0, sp);
        String rest = sp < 0 ? "" : b.substring(sp + 1);
        ed.befehl(name, rest.isEmpty() ? c : rest + " " + c);
    }

    // ================================================================ Visual
    void visual(String a) {
        int art = a.trim().equals("V") ? 2 : a.trim().equals("b") ? 3 : 1;
        zaehler = 0;
        if (visArt == art) {
            visualEnde();
            return;
        }
        if (visArt == 0) anker = d().cursor;
        visArt = art;
        ed.modus = "visual";
    }

    void visualEnde() {
        if (visArt != 0) {
            letzteAuswahl = new int[] {anker, d().cursor, visArt};
            letzteAuswahlDoc = d();
            Doc d = d();
            marken.put('<', new Marke(d.pfad, d.zeileVon(Math.min(anker, d.cursor)), 0));
            marken.put('>', new Marke(d.pfad, d.zeileVon(Math.max(anker, d.cursor)), 0));
        }
        visArt = 0;
        if (ed.modus.equals("visual")) ed.modus = "normal";
    }

    void wiederAuswaehlen() {
        if (letzteAuswahl == null || letzteAuswahlDoc != d()) return;
        int len = d().t.length();
        anker = Math.min(letzteAuswahl[0], len);
        d().cursor = Math.min(letzteAuswahl[1], len);
        visArt = letzteAuswahl[2];
        ed.modus = "visual";
    }

    void tauschen() {
        Doc d = d();
        if (visArt == 3) {
            // im Block: auf die andere Ecke derselben Zeile
            int za = d.zeileVon(anker), zc = d.zeileVon(d.cursor);
            int va = ed.vspalte(d, anker), vc = ed.vspalte(d, d.cursor);
            anker = ed.posBeiVspalte(d, za, vc);
            d.cursor = ed.posBeiVspalte(d, zc, va);
            return;
        }
        int t = anker;
        anker = d.cursor;
        d.cursor = t;
    }

    /** Auswahl als {a, e} (e exklusiv); bei V ganze Zeilen */
    int[] auswahl() {
        Doc d = d();
        int lo = Math.min(anker, d.cursor), hi = Math.max(anker, d.cursor);
        if (visArt == 2) return new int[] {d.anf(d.zeileVon(lo)), d.ende(d.zeileVon(hi))};
        return new int[] {lo, Math.min(hi + 1, d.t.length())};
    }

    void visualOp(String o) {
        o = o.trim();
        int art = visArt;
        if (art == 0) return;
        Doc d = d();
        int[] r = auswahl();
        int z1 = d.zeileVon(Math.min(anker, d.cursor)), z2 = d.zeileVon(Math.max(anker, d.cursor));
        int va = ed.vspalte(d, anker), vc = ed.vspalte(d, d.cursor);
        // Strg+V mit $: jede Zeile bis zu ihrem Ende
        boolean bisEnde = art == 3 && d.spalteMerk >= BIS_ENDE;
        visualEnde();
        ed.modus = "normal";
        zaehler = 0;
        if (art == 3 && blockOp(o, z1, z2, Math.min(va, vc), bisEnde ? BIS_ENDE : Math.max(va, vc) + 1)) return;
        boolean zeilen = art == 2;
        switch (o.isEmpty() ? "" : o.startsWith("r ") ? "r" : o) {
            case "d", "x", "DELETE" -> opAnwenden("d", r[0], r[1], zeilen);
            case "D", "X" -> opAnwenden("d", r[0], r[1], true);
            case "y" -> opAnwenden("y", r[0], r[1], zeilen);
            case "Y" -> opAnwenden("y", r[0], r[1], true);
            case "c", "s" -> opAnwenden("c", r[0], r[1], zeilen);
            case "C", "S", "R" -> opAnwenden("c", r[0], r[1], true);
            case ">", "<" -> opAnwenden(o, r[0], r[1], true);
            case "~", "u", "U" -> opAnwenden(o.equals("u") ? "gu" : o.equals("U") ? "gU" : "g~", r[0], r[1], zeilen);
            case "J" -> {
                ed.merken(null);
                verbinden(z1, Math.max(z2, z1 + 1), true);
            }
            case "gJ" -> {
                ed.merken(null);
                verbinden(z1, Math.max(z2, z1 + 1), false);
            }
            case "p", "P" -> ersetzenDurchRegister(r[0], r[1], zeilen);
            case "r" -> {
                ed.merken(null);
                char c = o.charAt(2);
                StringBuilder b = new StringBuilder(d.t.substring(r[0], r[1]));
                for (int k = 0; k < b.length(); k++) if (b.charAt(k) != '\n') b.setCharAt(k, c);
                d.ersetzen(r[0], r[1], b.toString());
                d.cursor = r[0];
            }
            case "I" -> {
                d.cursor = zeilen ? ersteNichtLeere(d, z1) : r[0];
                ed.modus = "insert";
            }
            case "A" -> {
                d.cursor = zeilen ? d.ende(z2) : r[1];
                ed.modus = "insert";
            }
            default -> ed.fehler("unbekannt im Visual-Mode: " + o);
        }
    }

    private void ersetzenDurchRegister(int a, int e, boolean zeilen) {
        Reg r = regLesen();
        if (r == null) return;
        Doc d = d();
        ed.merken(null);
        String alt = d.t.substring(a, e);
        String neu = r.text();
        if (zeilen) {
            if (neu.endsWith("\n")) neu = neu.substring(0, neu.length() - 1);
        } else if (r.art() == ZEILEN) {
            neu = "\n" + neu;
        }
        d.ersetzen(a, e, neu);
        d.cursor = a;
        Reg g = new Reg(zeilen ? alt + "\n" : alt, zeilen ? ZEILEN : ZEICHEN);
        regs.put('"', g);
    }

    /** Strg+V-Auswahl: Spalten v1..v2 (exklusiv) in den Zeilen z1..z2 */
    private boolean blockOp(String o, int z1, int z2, int v1, int v2) {
        Doc d = d();
        String art = o.startsWith("r ") ? "r" : o;
        switch (art) {
            case "d", "x", "DELETE", "y", "c", "s", "r", "~", "u", "U", "I", "A":
                break;
            default:
                return false;
        }
        if (!art.equals("y")) ed.merken(null);
        List<String> teile = new ArrayList<>();
        for (int z = z1; z <= z2; z++) teile.add(d.t.substring(ed.posBeiVspalte(d, z, v1), ed.posBeiVspalte(d, z, v2)));
        int oben = ed.posBeiVspalte(d, z1, v1);
        switch (art) {
            case "y" -> {
                regSchreiben(String.join("\n", teile), BLOCK, true);
                d.cursor = oben;
            }
            case "d", "x", "DELETE", "c", "s" -> {
                regSchreiben(String.join("\n", teile), BLOCK, false);
                for (int z = z2; z >= z1; z--) {
                    int a = ed.posBeiVspalte(d, z, v1);
                    d.loeschen(a, ed.posBeiVspalte(d, z, v2) - a);
                }
                d.cursor = ed.posBeiVspalte(d, z1, v1);
                if (art.equals("c") || art.equals("s")) blockEinfuegenStart(z1, z2, v1, false);
            }
            case "r", "~", "u", "U" -> {
                for (int z = z2; z >= z1; z--) {
                    int a = ed.posBeiVspalte(d, z, v1), e = ed.posBeiVspalte(d, z, v2);
                    String s = d.t.substring(a, e);
                    String neu = art.equals("r") ? String.valueOf(o.charAt(2)).repeat(s.length())
                            : fall(art.equals("u") ? "gu" : art.equals("U") ? "gU" : "g~", s);
                    d.ersetzen(a, e, neu);
                }
                d.cursor = ed.posBeiVspalte(d, z1, v1);
            }
            case "I" -> {
                d.cursor = oben;
                blockEinfuegenStart(z1, z2, v1, false);
            }
            case "A" -> {
                if (v2 >= BIS_ENDE) {
                    d.cursor = d.ende(z1);
                    blockEinfuegenStart(z1, z2, -1, true);
                    return true;
                }
                int e = d.ende(z1);
                int fehlt = v2 - ed.vspalte(d, e);
                if (fehlt > 0) d.einfuegen(e, " ".repeat(fehlt));
                d.cursor = ed.posBeiVspalte(d, z1, v2);
                blockEinfuegenStart(z1, z2, v2, true);
            }
            default -> {
            }
        }
        return true;
    }

    private void blockEinfuegenStart(int z1, int z2, int spalte, boolean anhaengen) {
        blockStart = d().cursor;
        blockDoc = d();
        blockZ1 = z1;
        blockZ2 = z2;
        blockSpalte = spalte;
        blockAnhaengen = anhaengen;
        ed.modus = "insert";
    }

    /** Insert-Mode mit Zaehler beginnen (i, a, A, I) */
    void insertMitZaehler() {
        einfN = nehmen();
        einfStart = einfN > 1 ? d().cursor : -1;
        einfDoc = d();
    }

    /** beim Verlassen des Insert-Modes: Zaehler und Block-Einfuegen ausfuehren */
    void insertEnde() {
        if (einfStart >= 0) {
            Doc d = d();
            int s = einfStart;
            einfStart = -1;
            if (d == einfDoc && d.cursor > s) {
                String t = d.t.substring(s, d.cursor);
                d.einfuegen(d.cursor, t.repeat(einfN - 1));
            }
        }
        if (blockStart < 0) return;
        Doc d = d();
        int s = blockStart;
        blockStart = -1;
        if (d != blockDoc || d.cursor < s || d.zeileVon(d.cursor) != d.zeileVon(s)) return;
        String t = d.t.substring(s, d.cursor);
        if (t.isEmpty()) return;
        for (int z = blockZ2; z > blockZ1; z--) {
            if (z >= d.zeilen()) continue;
            int e = d.ende(z);
            if (blockSpalte < 0) {
                d.einfuegen(e, t);
                continue;
            }
            int fehlt = blockSpalte - ed.vspalte(d, e);
            if (fehlt > 0) {
                if (!blockAnhaengen) continue;
                d.einfuegen(e, " ".repeat(fehlt));
            }
            d.einfuegen(ed.posBeiVspalte(d, z, blockSpalte), t);
        }
    }

    // ================================================================ Einfuegen aus Registern
    void paste(boolean davor) {
        Reg r = regLesen();
        int n = nehmen();
        if (r == null || r.text().isEmpty()) {
            ed.meldung = "Register leer";
            return;
        }
        Doc d = d();
        ed.merken(null);
        int z = d.zeileVon(d.cursor);
        switch (r.art()) {
            case ZEILEN -> {
                String t = r.text().endsWith("\n") ? r.text() : r.text() + "\n";
                t = t.repeat(n);
                if (davor) {
                    int an = d.anf(z);
                    d.einfuegen(an, t);
                    d.cursor = ersteNichtLeere(d, z);
                } else {
                    int en = d.ende(z);
                    if (en >= d.t.length()) d.einfuegen(en, "\n" + t.substring(0, t.length() - 1));
                    else d.einfuegen(en + 1, t);
                    d.cursor = ersteNichtLeere(d, z + 1);
                }
            }
            case BLOCK -> {
                String[] teile = r.text().split("\n", -1);
                int sp = ed.vspalte(d, d.cursor) + (davor || d.ende(z) == d.anf(z) ? 0 : 1);
                for (int i = 0; i < teile.length; i++) {
                    int zz = z + i;
                    if (zz >= d.zeilen()) d.einfuegen(d.t.length(), "\n");
                    int e = d.ende(zz);
                    int fehlt = sp - ed.vspalte(d, e);
                    if (fehlt > 0) d.einfuegen(e, " ".repeat(fehlt));
                    d.einfuegen(ed.posBeiVspalte(d, zz, sp), teile[i].repeat(n));
                }
                d.cursor = ed.posBeiVspalte(d, z, sp);
            }
            default -> {
                String t = r.text().repeat(n);
                int p = d.cursor;
                if (!davor && p < d.ende(z)) p++;
                d.einfuegen(p, t);
                d.cursor = Math.max(p, p + t.length() - 1);
            }
        }
        d.spalteMerk = -1;
    }

    // ================================================================ Kleinere Befehle
    void zeichenErsetzen(String c) {
        int n = nehmen();
        Doc d = d();
        int e = d.ende(d.zeileVon(d.cursor));
        if (c.isEmpty() || d.cursor + n > e) return;
        ed.merken(null);
        if (c.equals("\n") || c.equals("\r")) {
            d.ersetzen(d.cursor, d.cursor + n, "\n");
            return;
        }
        d.ersetzen(d.cursor, d.cursor + n, c.repeat(n));
        d.cursor += n - 1;
    }

    void ueberschreiben(String c) {
        Doc d = d();
        ed.merken("tippen");
        if (d.cursor < d.ende(d.zeileVon(d.cursor))) {
            d.ersetzen(d.cursor, d.cursor + 1, c);
            d.cursor += c.length();
        } else {
            d.einfuegen(d.cursor, c);
        }
    }

    void grossKlein() {
        int n = nehmen();
        Doc d = d();
        int e = d.ende(d.zeileVon(d.cursor));
        int b = Math.min(e, d.cursor + n);
        if (b <= d.cursor) return;
        ed.merken(null);
        int a = d.cursor;
        d.ersetzen(a, b, fall("g~", d.t.substring(a, b)));
        d.cursor = Math.min(b, Math.max(a, e - 1));
    }

    void zahlAendern(int richtung) {
        int n = nehmen() * richtung;
        Doc d = d();
        int z = d.zeileVon(d.cursor);
        String zl = d.zeile(z);
        int a = d.anf(z);
        Matcher m = Pattern.compile("-?\\d+").matcher(zl);
        int sp = d.cursor - a;
        while (m.find()) {
            if (m.end() <= sp) continue;
            long wert;
            try {
                wert = Long.parseLong(m.group()) + n;
            } catch (NumberFormatException ex) {
                return;
            }
            ed.merken(null);
            String neu = String.valueOf(wert);
            d.ersetzen(a + m.start(), a + m.end(), neu);
            d.cursor = a + m.start() + neu.length() - 1;
            return;
        }
    }

    void marke(String c) {
        if (c.isEmpty()) return;
        Doc d = d();
        int z = d.zeileVon(d.cursor);
        marken.put(c.charAt(0), new Marke(d.pfad, z, d.cursor - d.anf(z)));
    }

    void zurMarke(String arg, boolean genau) {
        if (arg.isEmpty()) return;
        char c = arg.charAt(0);
        if (c == '\'' || c == '`') {
            ed.befehl("goBack", "");
            return;
        }
        Marke m = marken.get(c);
        if (m == null) {
            ed.meldung = "Marke nicht gesetzt: " + c;
            return;
        }
        int n = zaehler;
        zaehler = 0;
        Doc d = d();
        if (m.pfad() != null && !m.pfad().equals(d.pfad)) {
            ed.springMerken();
            d = ed.oeffnen(m.pfad());
            if (d == null) return;
        } else if (!opOffen) {
            ed.springMerken();
        }
        int ziel = genau ? d.stelle(m.zeile(), m.spalte()) : ersteNichtLeere(d, Math.min(m.zeile(), d.zeilen() - 1));
        if (opOffen) {
            int a = Math.min(d.cursor, ziel), e = Math.max(d.cursor, ziel);
            opAnwenden(op, a, e, !genau);
            return;
        }
        zaehler = n;
        d.cursor = ziel;
    }

    void makroQ() {
        if (makroReg != 0) {
            // das q zum Beenden gehoert nicht dazu
            if (!makroAufnahme.isEmpty()) makroAufnahme.remove(makroAufnahme.size() - 1);
            makros.put(makroReg, makroAufnahme);
            ed.meldung = "Makro @" + makroReg + " aufgenommen (" + makroAufnahme.size() + " Tasten)";
            makroReg = 0;
            makroAufnahme = null;
            return;
        }
        warteAufZeichen("macroStart");
    }

    void makroStart(String c) {
        if (c.isEmpty() || !Character.isLetterOrDigit(c.charAt(0))) return;
        makroReg = Character.toLowerCase(c.charAt(0));
        makroAufnahme = new ArrayList<>();
    }

    void makroSpielen(String c) {
        if (c.isEmpty()) return;
        char r = c.charAt(0) == '@' ? letztesMakro : Character.toLowerCase(c.charAt(0));
        if (r == 0) return;
        letztesMakro = r;
        int n = nehmen();
        List<String[]> m = makros.get(r);
        if (m == null) {
            ed.meldung = "kein Makro @" + r;
            return;
        }
        for (int i = 0; i < n; i++) abspielen(m);
    }

    void punkt() {
        int n = nehmen();
        nichtMerken = true;
        if (letzteAenderung == null) return;
        List<String[]> l = letzteAenderung;
        for (int i = 0; i < n; i++) abspielen(l);
        nichtMerken = true;
    }

    /** nur die Zeile z wortweise waagerecht verschieben; verlaesst der Cursor sie, gilt wieder der normale Versatz */
    private void zeileVerschieben(Doc d, int z, boolean rechts, int n) {
        int a = d.anf(z), e = d.ende(z);
        if (d.versatzZeile != z) d.zeileVersatz = 0;
        d.versatzZeile = z;
        for (int i = 0; i < n; i++) {
            int jetzt = d.links + d.zeileVersatz, ziel = rechts ? -1 : 0;
            for (int p = a; p < e; p++) {
                char c = d.t.charAt(p);
                if (Character.isWhitespace(c)) continue;
                if (p > a && !Character.isWhitespace(d.t.charAt(p - 1)) && Character.isLetterOrDigit(c) == Character.isLetterOrDigit(d.t.charAt(p - 1))) continue;
                int v = ed.vspalte(d, p);
                if (rechts && v > jetzt) {
                    ziel = v;
                    break;
                }
                if (!rechts && v < jetzt) ziel = v;
            }
            if (ziel < 0) break;
            d.zeileVersatz = Math.max(0, ziel - d.links);
        }
        if (d.zeileVersatz == 0) d.versatzZeile = -1;
    }

    void rollen(String art) {
        Doc d = d();
        int h = ed.textZeilen();
        int z = d.zeileVon(d.cursor);
        int n = zaehler > 0 ? nehmen() : 0;
        switch (art) {
            case "halfDown", "halfUp" -> {
                int s = n > 0 ? n : Math.max(1, h / 2);
                int dz = art.equals("halfDown") ? s : -s;
                d.oben = Math.max(0, Math.min(d.zeilen() - 1, d.oben + dz));
                d.cursor = ersteNichtLeere(d, Math.max(0, Math.min(d.zeilen() - 1, z + dz)));
            }
            case "pageDown", "pageUp" -> {
                int s = Math.max(1, h - 2) * Math.max(1, n);
                int dz = art.equals("pageDown") ? s : -s;
                d.oben = Math.max(0, Math.min(d.zeilen() - 1, d.oben + dz));
                d.cursor = ersteNichtLeere(d, Math.max(0, Math.min(d.zeilen() - 1, z + dz)));
            }
            case "lineDown" -> {
                d.oben = Math.min(d.zeilen() - 1, d.oben + Math.max(1, n));
                if (z < d.oben + 3) d.cursor = ed.posBeiVspalte(d, Math.min(d.zeilen() - 1, d.oben + 3), ed.vspalte(d, d.cursor));
            }
            case "lineUp" -> {
                d.oben = Math.max(0, d.oben - Math.max(1, n));
                if (z > d.oben + h - 4) d.cursor = ed.posBeiVspalte(d, Math.max(0, d.oben + h - 4), ed.vspalte(d, d.cursor));
            }
            case "lineRight", "lineLeft" -> {
                zeileVerschieben(d, z, art.equals("lineRight"), Math.max(1, n));
                return;
            }
            case "center" -> d.oben = Math.max(0, z - h / 2);
            case "top" -> d.oben = Math.max(0, z - 3);
            case "bottom" -> d.oben = Math.max(0, z - h + 4);
            default -> ed.fehler("scroll: halfDown halfUp pageDown pageUp lineDown lineUp lineRight lineLeft center top bottom");
        }
        d.spalteMerk = -1;
    }

    // ---------------------------------------------------------------- Insert-Mode
    void insertWortZurueck() {
        Doc d = d();
        int a = d.anf(d.zeileVon(d.cursor)), p = d.cursor;
        if (p == a) {
            ed.befehl("backspace", "");
            return;
        }
        while (p > a && (d.at(p - 1) == ' ' || d.at(p - 1) == '\t')) p--;
        if (p > a) {
            int k = klasse(d.at(p - 1), false);
            while (p > a && klasse(d.at(p - 1), false) == k) p--;
        }
        ed.merken("tippen");
        d.loeschen(p, d.cursor - p);
    }

    void insertZeileZurueck() {
        Doc d = d();
        int z = d.zeileVon(d.cursor);
        int a = ersteNichtLeere(d, z);
        if (a >= d.cursor) a = d.anf(z);
        if (a >= d.cursor) return;
        ed.merken("tippen");
        d.loeschen(a, d.cursor - a);
    }

    void insertEinruecken(boolean rein) {
        Doc d = d();
        int z = d.zeileVon(d.cursor);
        ed.merken("tippen");
        einruecken(z, z, rein);
    }

    // ================================================================ Ex: :s, :%s, :'<,'>s
    private static final Pattern SUB = Pattern.compile("^(%|'<,'>|(\\d+|\\.)(?:,(\\d+|\\$|\\.))?)?s([/#|!:,])(.*)$");

    boolean ersetzenBefehl(String zeile) {
        Matcher m = SUB.matcher(zeile.strip());
        if (!m.matches()) return false;
        char t = m.group(4).charAt(0);
        List<String> teile = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        String rest = m.group(5);
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '\\' && i + 1 < rest.length() && rest.charAt(i + 1) == t) {
                b.append(t);
                i++;
            } else if (c == t && teile.size() < 2) {
                teile.add(b.toString());
                b.setLength(0);
            } else {
                b.append(c);
            }
        }
        teile.add(b.toString());
        while (teile.size() < 3) teile.add("");
        String muster = teile.get(0).isEmpty() ? ed.suche : teile.get(0);
        String ersatz = javaErsatz(teile.get(1));
        String flags = teile.get(2);
        if (muster.isEmpty()) {
            ed.fehler(":s ohne Muster");
            return true;
        }
        Doc d = d();
        int z1, z2;
        int cz = d.zeileVon(d.cursor);
        String bereich = m.group(1);
        if (bereich == null) {
            z1 = z2 = cz;
        } else if (bereich.equals("%")) {
            z1 = 0;
            z2 = d.zeilen() - 1;
        } else if (bereich.equals("'<,'>")) {
            Marke a = marken.get('<'), e = marken.get('>');
            if (a == null || e == null) {
                ed.fehler("keine Auswahl");
                return true;
            }
            z1 = a.zeile();
            z2 = e.zeile();
        } else {
            z1 = m.group(2).equals(".") ? cz : Integer.parseInt(m.group(2)) - 1;
            String b2 = m.group(3);
            z2 = b2 == null ? z1 : b2.equals("$") ? d.zeilen() - 1 : b2.equals(".") ? cz : Integer.parseInt(b2) - 1;
        }
        z1 = Math.max(0, Math.min(z1, d.zeilen() - 1));
        z2 = Math.max(z1, Math.min(z2, d.zeilen() - 1));
        Pattern p;
        try {
            p = Pattern.compile(javaMuster(muster), flags.contains("i") ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
        } catch (PatternSyntaxException e) {
            ed.fehler("Muster: " + e.getDescription());
            return true;
        }
        boolean alle = flags.contains("g");
        int a = d.anf(z1), e = d.ende(z2);
        String[] zeilen = d.t.substring(a, e).split("\n", -1);
        int anzahl = 0, geaendert = 0, letzte = -1;
        for (int i = 0; i < zeilen.length; i++) {
            Matcher mm = p.matcher(zeilen[i]);
            StringBuilder sb = new StringBuilder();
            int k = 0;
            while (mm.find()) {
                mm.appendReplacement(sb, ersatz);
                k++;
                if (!alle) break;
            }
            if (k > 0) {
                mm.appendTail(sb);
                zeilen[i] = sb.toString();
                anzahl += k;
                geaendert++;
                letzte = z1 + i;
            }
        }
        ed.suche = muster;
        if (anzahl == 0) {
            ed.fehler("Muster nicht gefunden: " + muster);
            return true;
        }
        ed.merken(null);
        d.ersetzen(a, e, String.join("\n", zeilen));
        d.cursor = ersteNichtLeere(d, Math.min(letzte, d.zeilen() - 1));
        ed.meldung = anzahl + " Ersetzungen in " + geaendert + " Zeilen";
        ed.meldungFarbe = Editor.E_WARN;
        return true;
    }

    /** Vim-Muster (magic) nach Java: \( \) \| \+ \? \{ werden Gruppen/Operatoren, \< \> Wortgrenzen */
    static String javaMuster(String v) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length()) {
                char n = v.charAt(++i);
                if ("()|+?{}".indexOf(n) >= 0) b.append(n);
                else if (n == '<' || n == '>') b.append("\\b");
                else b.append('\\').append(n);
            } else if ("()|+?{}".indexOf(c) >= 0) {
                b.append('\\').append(c);
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** Vim-Ersatz nach Java: & und \0-\9 sind Treffer, \r ist ein Zeilenumbruch */
    static String javaErsatz(String v) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length()) {
                char n = v.charAt(++i);
                if (Character.isDigit(n)) b.append('$').append(n);
                else if (n == 'r' || n == 'n') b.append('\n');
                else if (n == 't') b.append('\t');
                else if (n == '&') b.append('&');
                else if (n == '$') b.append("\\$");
                else b.append(Matcher.quoteReplacement(String.valueOf(n)));
            } else if (c == '&') {
                b.append("$0");
            } else if (c == '$') {
                b.append("\\$");
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    // ================================================================ Befehle
    void befehle(Map<String, Editor.Cmd> c) {
        /** @binding Zaehler fuer den naechsten Befehl (Ziffer) */
        c.put("count", a -> {
            if (!a.isEmpty() && Character.isDigit(a.charAt(0))) zaehler = Math.min(99999, zaehler * 10 + (a.charAt(0) - '0'));
        });
        /** @binding 0: Zeilenanfang, nach einer Ziffer Teil des Zaehlers */
        c.put("countZero", a -> {
            if (zaehler > 0) zaehler = Math.min(99999, zaehler * 10);
            else motion("0");
        });
        /**
         * @binding Vim-Bewegung (w, b, e, f, t, %, gg, G ...)
         * @binding-arg h = links
         * @binding-arg j = runter
         * @binding-arg k = hoch
         * @binding-arg l = rechts
         * @binding-arg w = naechster Wortanfang
         * @binding-arg W = naechstes WORT (nur Leerraum trennt)
         * @binding-arg b = voriger Wortanfang
         * @binding-arg B = voriges WORT
         * @binding-arg e = Wortende
         * @binding-arg E = Ende des WORTs
         * @binding-arg ge = Ende des vorigen Worts
         * @binding-arg gE = Ende des vorigen WORTs
         * @binding-arg 0 = Zeilenanfang
         * @binding-arg ^ = erstes Zeichen der Zeile, das kein Leerraum ist
         * @binding-arg _ = erstes Zeichen der Zeile (mit Zaehler N-1 Zeilen tiefer)
         * @binding-arg $ = Zeilenende
         * @binding-arg + = Anfang der naechsten Zeile
         * @binding-arg - = Anfang der vorigen Zeile
         * @binding-arg ENTER = Anfang der naechsten Zeile
         * @binding-arg gg = erste Zeile (mit Zaehler: Zeile N)
         * @binding-arg G = letzte Zeile (mit Zaehler: Zeile N)
         * @binding-arg H = oberste sichtbare Zeile
         * @binding-arg M = mittlere sichtbare Zeile
         * @binding-arg L = unterste sichtbare Zeile
         * @binding-arg % = passende Klammer
         * @binding-arg { = voriger Absatz
         * @binding-arg } = naechster Absatz
         * @binding-arg n = naechster Suchtreffer
         * @binding-arg N = voriger Suchtreffer
         * @binding-arg ; = letztes f/t/F/T wiederholen
         * @binding-arg , = letztes f/t/F/T in Gegenrichtung
         * @binding-arg f = vorwaerts auf das naechste getippte Zeichen
         * @binding-arg t = vorwaerts vor das naechste getippte Zeichen
         * @binding-arg F = rueckwaerts auf das getippte Zeichen
         * @binding-arg T = rueckwaerts hinter das getippte Zeichen
         */
        c.put("motion", this::motion);
        /**
         * @binding Vim-Operator (d, c, y, >, <, gu ...), wartet auf eine Bewegung
         * @binding-arg d = loeschen, wartet auf eine Bewegung (dw, d$, dd ...)
         * @binding-arg c = aendern: loeschen und Insert-Mode, wartet auf eine Bewegung
         * @binding-arg y = kopieren, wartet auf eine Bewegung (yw, yy ...)
         * @binding-arg > = einruecken, wartet auf eine Bewegung
         * @binding-arg < = ausruecken, wartet auf eine Bewegung
         * @binding-arg gu = in Kleinbuchstaben, wartet auf eine Bewegung
         * @binding-arg gU = in Grossbuchstaben, wartet auf eine Bewegung
         * @binding-arg g~ = Gross/Klein umdrehen, wartet auf eine Bewegung
         */
        c.put("operator", this::operator);
        /** @binding Vim-Operator auf ganze Zeilen (dd, cc, yy ...) */
        c.put("operatorLine", this::operatorZeile);
        /** @binding Vim-Operator auf ganze Zeilen (wie operatorLine) */
        c.put("opLine", this::operatorZeile);
        /**
         * @binding Vim-Operator mit Bewegung in einem Schritt (z. B. d w)
         * @binding-arg d l = loescht das Zeichen unter dem Cursor (x)
         * @binding-arg d h = loescht das Zeichen vor dem Cursor (X)
         * @binding-arg d $ = loescht bis zum Zeilenende (D)
         * @binding-arg c $ = aendert bis zum Zeilenende (C)
         * @binding-arg c l = ersetzt das Zeichen unter dem Cursor (s)
         */
        c.put("opMotion", this::opMotion);
        /**
         * @binding Vim-Textobjekt (iw, a", i(, it, ip ...)
         * @binding-arg iw = das Wort (innen)
         * @binding-arg iW = das WORT (innen)
         * @binding-arg ip = der Absatz (innen)
         * @binding-arg it = der HTML/XML-Tag (innen)
         * @binding-arg i" = Text in "..." (innen)
         * @binding-arg i' = Text in '...' (innen)
         * @binding-arg i` = Text in `...` (innen)
         * @binding-arg i( = Inhalt von (...) (innen)
         * @binding-arg i) = Inhalt von (...) (innen)
         * @binding-arg ib = Inhalt von (...) (innen)
         * @binding-arg i{ = Inhalt von {...} (innen)
         * @binding-arg i} = Inhalt von {...} (innen)
         * @binding-arg iB = Inhalt von {...} (innen)
         * @binding-arg i[ = Inhalt von [...] (innen)
         * @binding-arg i] = Inhalt von [...] (innen)
         * @binding-arg i< = Inhalt von <...> (innen)
         * @binding-arg i> = Inhalt von <...> (innen)
         * @binding-arg aw = das Wort mit Rand bzw. Leerraum
         * @binding-arg aW = das WORT mit Rand bzw. Leerraum
         * @binding-arg ap = der Absatz mit Rand bzw. Leerraum
         * @binding-arg at = der HTML/XML-Tag mit Rand bzw. Leerraum
         * @binding-arg a" = Text in "..." mit Rand bzw. Leerraum
         * @binding-arg a' = Text in '...' mit Rand bzw. Leerraum
         * @binding-arg a` = Text in `...` mit Rand bzw. Leerraum
         * @binding-arg a( = Inhalt von (...) mit Rand bzw. Leerraum
         * @binding-arg a) = Inhalt von (...) mit Rand bzw. Leerraum
         * @binding-arg ab = Inhalt von (...) mit Rand bzw. Leerraum
         * @binding-arg a{ = Inhalt von {...} mit Rand bzw. Leerraum
         * @binding-arg a} = Inhalt von {...} mit Rand bzw. Leerraum
         * @binding-arg aB = Inhalt von {...} mit Rand bzw. Leerraum
         * @binding-arg a[ = Inhalt von [...] mit Rand bzw. Leerraum
         * @binding-arg a] = Inhalt von [...] mit Rand bzw. Leerraum
         * @binding-arg a< = Inhalt von <...> mit Rand bzw. Leerraum
         * @binding-arg a> = Inhalt von <...> mit Rand bzw. Leerraum
         */
        c.put("textObject", this::textObjektBefehl);
        /** @binding Wartet auf ein Zeichen und gibt es an den Befehl weiter (f, t, r, m ...) */
        c.put("awaitChar", this::warteAufZeichen);
        /** @binding Uebergibt das getippte Zeichen an den wartenden Befehl */
        c.put("charArg", this::zeichen);
        /** @binding Bricht den wartenden Befehl ab */
        c.put("cancel", a -> {
            abbrechen();
            ed.sucheZeigen = false;
        });
        /** @binding Waehlt ein Register fuer den naechsten Befehl ("a) */
        c.put("selectRegister", a -> {
            if (!a.isEmpty()) regWahl = a.charAt(0);
        });
        /**
         * @binding Visual-Mode: v Zeichen, V Zeilen, b Block
         * @binding-arg v = Visual-Mode: Zeichen markieren
         * @binding-arg V = Visual-Mode: ganze Zeilen markieren
         * @binding-arg b = Visual-Mode: Block (Spalten) markieren
         */
        c.put("visual", this::visual);
        /** @binding Verlaesst den Visual-Mode */
        c.put("visualExit", a -> visualEnde());
        /** @binding Visual: Cursor ans andere Ende der Auswahl */
        c.put("visualSwap", a -> tauschen());
        /**
         * @binding Visual: wendet den Operator auf die Auswahl an
         * @binding-arg d = loescht die Auswahl
         * @binding-arg x = loescht die Auswahl
         * @binding-arg D = loescht die Zeilen der Auswahl
         * @binding-arg X = loescht die Zeilen der Auswahl
         * @binding-arg y = kopiert die Auswahl
         * @binding-arg Y = kopiert die Zeilen der Auswahl
         * @binding-arg c = aendert die Auswahl
         * @binding-arg s = aendert die Auswahl
         * @binding-arg C = aendert die Zeilen der Auswahl
         * @binding-arg S = aendert die Zeilen der Auswahl
         * @binding-arg R = aendert die Zeilen der Auswahl
         * @binding-arg p = ersetzt die Auswahl durch das Register
         * @binding-arg P = ersetzt die Auswahl durch das Register
         * @binding-arg > = rueckt die Auswahl ein
         * @binding-arg < = rueckt die Auswahl aus
         * @binding-arg u = Auswahl in Kleinbuchstaben
         * @binding-arg U = Auswahl in Grossbuchstaben
         * @binding-arg ~ = Gross/Klein der Auswahl umdrehen
         * @binding-arg J = verbindet die Zeilen der Auswahl
         * @binding-arg gJ = verbindet die Zeilen der Auswahl ohne Leerzeichen
         * @binding-arg I = Insert am Anfang der Auswahl (Block: in jeder Zeile)
         * @binding-arg A = Insert am Ende der Auswahl (Block: in jeder Zeile)
         * @binding-arg r = ersetzt jedes Zeichen der Auswahl durch das getippte
         */
        c.put("visualOp", this::visualOp);
        /** @binding Visual: Befehlszeile fuer den markierten Bereich (:'<,'>) */
        c.put("visualConsole", a -> {
            visualEnde();
            ed.konsoleAuf();
            ed.eingabe.setLength(0);
            ed.eingabe.append("'<,'>");
        });
        /** @binding Waehlt die letzte Auswahl wieder aus (gv) */
        c.put("reselect", a -> wiederAuswaehlen());
        /** @binding Ersetzt das Zeichen unter dem Cursor (r) */
        c.put("replaceChar", this::zeichenErsetzen);
        /** @binding Replace-Mode: ueberschreibt mit dem getippten Zeichen */
        c.put("replaceTyped", this::ueberschreiben);
        /** @binding Gross-/Kleinschreibung des Zeichens umdrehen (~) */
        c.put("toggleCase", a -> grossKlein());
        /** @binding Verbindet Zeilen mit Leerzeichen (J) */
        c.put("join", a -> {
            int n = Math.max(2, nehmen());
            ed.merken(null);
            int z = d().zeileVon(d().cursor);
            verbinden(z, z + n - 1, true);
        });
        /** @binding Verbindet Zeilen ohne Leerzeichen (gJ) */
        c.put("joinNoSpace", a -> {
            int n = Math.max(2, nehmen());
            ed.merken(null);
            int z = d().zeileVon(d().cursor);
            verbinden(z, z + n - 1, false);
        });
        /** @binding Zahl unter dem Cursor erhoehen */
        c.put("increment", a -> zahlAendern(1));
        /** @binding Zahl unter dem Cursor verringern */
        c.put("decrement", a -> zahlAendern(-1));
        /** @binding Fuegt das Register hinter dem Cursor ein (p) */
        c.put("paste", a -> paste(false));
        /** @binding Fuegt das Register vor dem Cursor ein (P) */
        c.put("pasteBefore", a -> paste(true));
        // Namen der RUNENWERK-Editor-Lib
        /** @binding Kopiert die Zeile (yy) */
        c.put("yankLine", a -> operatorZeile("y"));
        /** @binding Loescht die Zeile (dd) */
        c.put("deleteLine", a -> operatorZeile("d"));
        /** @binding Loescht bis zum naechsten Wort (dw) */
        c.put("deleteWord", a -> opMotion("d w"));
        /** @binding Ersetzt bis zum Wortende (cw) */
        c.put("changeWord", a -> opMotion("c w"));
        /** @binding Loescht bis zum Zeilenende (D) */
        c.put("deleteToLineEnd", a -> opMotion("d $"));
        /** @binding Ersetzt bis zum Zeilenende (C) */
        c.put("changeToLineEnd", a -> opMotion("c $"));
        /** @binding Setzt eine Marke (m) */
        c.put("setMark", this::marke);
        /** @binding Springt zur Marke, genau an die Stelle (`) */
        c.put("gotoMark", a -> zurMarke(a, true));
        /** @binding Springt zur Zeile der Marke (') */
        c.put("gotoMarkLine", a -> zurMarke(a, false));
        /** @binding Makroaufnahme starten bzw. beenden (q) */
        c.put("macroQ", a -> makroQ());
        /** @binding Startet die Makroaufnahme in das Register */
        c.put("macroStart", this::makroStart);
        /** @binding Spielt das Makro aus dem Register ab (@) */
        c.put("macroPlay", this::makroSpielen);
        /** @binding Wiederholt die letzte Aenderung (.) */
        c.put("dot", a -> punkt());
        /**
         * @binding Bildlauf: halfDown halfUp pageDown pageUp lineDown lineUp lineRight lineLeft center top bottom
         * @binding-arg halfDown = halbe Seite runter
         * @binding-arg halfUp = halbe Seite hoch
         * @binding-arg pageDown = ganze Seite runter
         * @binding-arg pageUp = ganze Seite hoch
         * @binding-arg lineDown = Text eine Zeile hochrollen, Cursor bleibt
         * @binding-arg lineUp = Text eine Zeile runterrollen, Cursor bleibt
         * @binding-arg lineRight = nur die aktuelle Zeile ein Wort nach rechts schieben
         * @binding-arg lineLeft = nur die aktuelle Zeile ein Wort nach links schieben
         * @binding-arg center = Zeile mit dem Cursor in die Mitte (zz)
         * @binding-arg top = Zeile mit dem Cursor nach oben (zt)
         * @binding-arg bottom = Zeile mit dem Cursor nach unten (zb)
         */
        c.put("scroll", this::rollen);
        /** @binding Insert: loescht das Wort vor dem Cursor */
        c.put("insertDeleteWord", a -> insertWortZurueck());
        /** @binding Insert: loescht bis zum Zeilenanfang */
        c.put("insertDeleteLine", a -> insertZeileZurueck());
        /** @binding Insert: Zeile einruecken */
        c.put("insertIndent", a -> insertEinruecken(true));
        /** @binding Insert: Zeile ausruecken */
        c.put("insertOutdent", a -> insertEinruecken(false));
        /** @binding Hebt die Suchtreffer nicht mehr hervor */
        c.put("noh", a -> ed.sucheZeigen = false);
        /** @binding Hebt die Suchtreffer nicht mehr hervor */
        c.put("nohlsearch", a -> ed.sucheZeigen = false);
        /** @binding Listet die Register */
        c.put("reg", a -> {
            ed.konsoleAuf();
            ed.print("Register:");
            for (var e : new TreeMap<>(regs).entrySet()) {
                String t = e.getValue().text().replace("\n", "^J");
                if (t.length() > 100) t = t.substring(0, 100) + "~";
                ed.print("  \"" + e.getKey() + "  " + t);
            }
        });
        /** @binding Listet die Register */
        c.put("registers", c.get("reg"));
        /** @binding Listet die Register */
        c.put("display", c.get("reg"));
        /** @binding Listet die Marken */
        c.put("marks", a -> {
            ed.konsoleAuf();
            ed.print("Marken:");
            for (var e : marken.entrySet()) {
                Marke m = e.getValue();
                ed.print("  " + e.getKey() + "  " + (m.zeile() + 1) + ":" + (m.spalte() + 1) + "  " + ed.relativ(m.pfad()));
            }
        });
    }
}
