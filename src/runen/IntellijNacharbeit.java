package runen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Was der Eclipse-Formatter nicht so kann wie IntelliJ, erledigt der Editor danach: von Hand umbrochene
 * Zeilen bleiben umbrochen (KEEP_LINE_BREAKS) und Javadoc wird wie von IntelliJ formatiert (Einzeiler
 * aufklappen, Sterne, Absaetze mit p, Leerzeile vor den Tags, @param/@throws ausrichten, Umbruch am Rand).
 */
final class IntellijNacharbeit {
    private final Map<String, String> w;
    private final boolean behalten, tabs;
    private final int tab, einzugBreite, fortsetzung, rand, leerzeilen;
    /** Import-Layout: je Eintrag {Paket, mit Unterpaketen 1/0, static 1/0, Abschnitt (Leerzeilen davor)} */
    private final List<String[]> importLayout = new ArrayList<>();

    IntellijNacharbeit(String intellijXml) throws Exception {
        w = IntellijStil.lesen(intellijXml);
        behalten = an("KEEP_LINE_BREAKS", true);
        tabs = an("USE_TAB_CHARACTER", false);
        tab = zahl("TAB_SIZE", 4);
        einzugBreite = zahl("INDENT_SIZE", 4);
        importLayoutLesen(intellijXml);
        fortsetzung = zahl("CONTINUATION_INDENT_SIZE", 8);
        rand = zahl("RIGHT_MARGIN", 120);
        leerzeilen = zahl("KEEP_BLANK_LINES_IN_CODE", 2);
    }

    /**
     * Text nach den Edits des Formatters (Bereiche {anfang, ende} im alten Text, aufsteigend, ohne Ueberlappung),
     * danach Javadoc.
     */
    String anwenden(String alt, List<int[]> bereiche, List<String> texte) {
        altCode = null;
        nochmal = false;
        StringBuilder r = new StringBuilder(alt.length() + 256);
        int pos = 0;
        for (int i = 0; i < bereiche.size(); i++) {
            int a = bereiche.get(i)[0], e = bereiche.get(i)[1];
            if (a < pos) continue;
            String neu = texte.get(i);
            r.append(alt, pos, a);
            String vorher = alt.substring(a, e);
            if (vorher.indexOf('\n') >= 0 && neu.indexOf('\n') < 0 && vorher.isBlank() && neu.isBlank() && !zusammenWieIntellij(alt, a, e)
                    && (behalten || steuerKopf(alt, a).equals("for"))) {
                // ohne KEEP_LINE_BREAKS zieht IntelliJ if/while-Koerper hoch, for-Koerper nicht
                neu = umbruchBehalten(alt, a, vorher, r);
            } else if (zeilen(neu) > zeilen(vorher) && zeilen(vorher) >= 1 && vorher.isBlank() && neu.isBlank() && kopfOhneLeerzeile(alt, a)) {
                // BLANK_LINES_AFTER_CLASS_HEADER gilt bei IntelliJ nicht fuer anonyme Klassen und enum
                int n = Math.max(zeilen(vorher), 1 + zahl("BLANK_LINES_AFTER_ANONYMOUS_CLASS_HEADER", 0));
                if (n < zeilen(neu)) neu = "\n".repeat(n) + neu.substring(neu.lastIndexOf('\n') + 1);
            } else if (vorher.indexOf('\n') < 0 && neu.indexOf('\n') >= 0 && vorher.isBlank() && neu.isBlank() && inEinfachemBlock(alt, a, e, bereiche, texte)) {
                // Eclipse klappt einfache Bloecke z. B. bei if/else trotzdem auf
                neu = vorher;
            } else if (vorher.indexOf('\n') < 0 && neu.indexOf('\n') >= 0 && vorher.isBlank() && neu.isBlank() && e < alt.length() && alt.charAt(e) == '{'
                    && bleibtEinzeilig(alt, e, bereiche, texte, i)) {
                // einzeiliger Block bleibt bei IntelliJ hinter dem Kopf, auch wenn Klammern sonst auf die naechste Zeile kommen
                neu = " ";
            } else if (vorher.indexOf('\n') < 0 && neu.indexOf('\n') >= 0 && vorher.isBlank() && neu.isBlank() && e < alt.length() && alt.charAt(e) != '{'
                    && !steuerKopf(alt, a).isEmpty() && an("KEEP_CONTROL_STATEMENT_IN_ONE_LINE", true)) {
                // umbrochene Bedingung: IntelliJ laesst den Koerper hinter ")", solange die Zeile passt
                int rz = r.lastIndexOf("\n") + 1, ende = alt.indexOf('\n', e);
                String rest = alt.substring(e, ende < 0 ? alt.length() : ende).strip();
                if (spalten(r, rz) + 1 + rest.length() <= rand) neu = " ";
            }
            r.append(neu);
            pos = e;
        }
        r.append(alt, pos, alt.length());
        String t = importGruppen(r.toString());
        t = einzeiligeBloecke(t);
        if (!an("INDENT_CASE_FROM_SWITCH", true)) t = switchAusdruecke(t);
        if (!an("SPACE_WITHIN_BRACES", false)) t = enumKlammern(t);
        return an("ENABLE_JAVADOC_FORMATTING", true) ? javadoc(t) : t;
    }

    /**
     * Steht vor a der Kopf von if/for/while oder else/do? IntelliJ laesst den Koerper dann auf seiner Zeile
     * (auch ohne KEEP_LINE_BREAKS); Eclipse zieht ihn mit keep_*_on_same_line hoch.
     */
    private static boolean steuerKoerper(String t, int a) {
        return !steuerKopf(t, a).isEmpty();
    }

    /** if, for, while, else oder do, wenn deren Kopf direkt vor a endet, sonst "" */
    private static String steuerKopf(String t, int a) {
        int v = a - 1;
        while (v >= 0 && Character.isWhitespace(t.charAt(v))) v--;
        if (v < 0) return "";
        if (t.charAt(v) != ')') {
            String w = wortVor(t, a);
            return w.equals("else") || w.equals("do") ? w : "";
        }
        int tiefe = 0;
        for (int i = v; i >= 0; i--) {
            char c = t.charAt(i);
            if (c == ')') tiefe++;
            else if (c == '(' && --tiefe == 0) {
                String w = wortVor(t, i);
                return w.equals("if") || w.equals("for") || w.equals("while") ? w : "";
            }
        }
        return "";
    }

    /** Spaltenbreite von r ab von bis zum Ende (Tabs auf Tabstopps) */
    private int spalten(CharSequence r, int von) {
        int b = 0;
        for (int i = von; i < r.length(); i++) b = r.charAt(i) == '\t' ? b + tab - b % tab : b + 1;
        return b;
    }

    // ================================================================ Imports

    private void importLayoutLesen(String xml) {
        java.util.regex.Matcher tab = java.util.regex.Pattern.compile("(?s)name=\"IMPORT_LAYOUT_TABLE\">\\s*<value>(.*?)</value>").matcher(xml);
        String layout = tab.find() ? tab.group(1)
                : "<package name=\"\" withSubpackages=\"true\" static=\"false\"/><emptyLine/><package name=\"javax\" withSubpackages=\"true\" static=\"false\"/>"
                        + "<package name=\"java\" withSubpackages=\"true\" static=\"false\"/><emptyLine/><package name=\"\" withSubpackages=\"true\" static=\"true\"/>";
        int abschnitt = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<(package|emptyLine)\\b([^>]*)>").matcher(layout);
        while (m.find()) {
            if (m.group(1).equals("emptyLine")) {
                abschnitt++;
                continue;
            }
            importLayout.add(new String[] {attr(m.group(2), "name"), attr(m.group(2), "withSubpackages").equals("true") ? "1" : "0",
                    attr(m.group(2), "static").equals("true") ? "1" : "0", String.valueOf(abschnitt)});
        }
    }

    private static String attr(String s, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(name + "=\"([^\"]*)\"").matcher(s);
        return m.find() ? m.group(1) : "";
    }

    /** Abschnitt eines Imports im Layout: der laengste passende Paketeintrag; -1 = keiner */
    private int abschnitt(String name, boolean statisch) {
        boolean getrennt = an("LAYOUT_STATIC_IMPORTS_SEPARATELY", true);
        int best = -1, laenge = -1;
        for (String[] e : importLayout) {
            if (getrennt && (e[2].equals("1")) != statisch) continue;
            String pk = e[0];
            boolean passt = pk.isEmpty() || name.startsWith(pk + ".") && (e[1].equals("1") || name.indexOf('.', pk.length() + 1) < 0 || name.endsWith(".*"));
            if (passt && pk.length() > laenge) {
                best = Integer.parseInt(e[3]);
                laenge = pk.length();
            }
        }
        return best;
    }

    /** Leerzeile zwischen zwei direkt aufeinanderfolgenden Imports aus verschiedenen Abschnitten des Layouts */
    private String importGruppen(String t) {
        String[] z = t.split("\n", -1);
        java.util.regex.Pattern imp = java.util.regex.Pattern.compile("import\\s+(static\\s+)?([\\w.]+(?:\\.\\*)?)\\s*;.*");
        StringBuilder b = new StringBuilder(t.length() + 16);
        int vorher = Integer.MIN_VALUE;
        for (int i = 0; i < z.length; i++) {
            java.util.regex.Matcher m = imp.matcher(z[i]);
            if (m.matches()) {
                int a = abschnitt(m.group(2), m.group(1) != null);
                if (vorher != Integer.MIN_VALUE && a != vorher) b.append('\n');
                vorher = a;
            } else {
                vorher = Integer.MIN_VALUE;
                if (z[i].startsWith("public ") || z[i].startsWith("class ") || z[i].startsWith("@")) {
                    for (int k = i; k < z.length; k++) b.append(z[k]).append(k + 1 < z.length ? "\n" : "");
                    return b.toString();
                }
            }
            b.append(z[i]).append(i + 1 < z.length ? "\n" : "");
        }
        return b.toString();
    }

    // ================================================================ switch-Ausdruecke, enum

    /** Code-Zeichen (nicht in Kommentar, String, Zeichen oder Textblock) */
    private static boolean[] code(String t) {
        boolean[] c = new boolean[t.length()];
        int i = 0, n = t.length();
        while (i < n) {
            char ch = t.charAt(i);
            int e;
            if (ch == '"' || ch == '\'') e = literalEnde(t, i);
            else if (ch == '/' && i + 1 < n && t.charAt(i + 1) == '/') e = t.indexOf('\n', i) < 0 ? n : t.indexOf('\n', i);
            else if (ch == '/' && i + 1 < n && t.charAt(i + 1) == '*') e = t.indexOf("*/", i + 2) < 0 ? n : t.indexOf("*/", i + 2) + 2;
            else {
                c[i++] = true;
                continue;
            }
            i = e;
        }
        return c;
    }

    /**
     * Ohne "case einruecken" rueckt IntelliJ nur switch-Anweisungen nicht ein; switch-Ausdruecke (x = switch ...)
     * schon. Eclipse unterscheidet das nicht: deren Rumpf eine Stufe weiter.
     */
    private String switchAusdruecke(String t) {
        boolean[] c = code(t);
        String[] zeilen = t.split("\n", -1);
        int[] anf = new int[zeilen.length];
        for (int k = 1; k < zeilen.length; k++) anf[k] = anf[k - 1] + zeilen[k - 1].length() + 1;
        int[] mehr = new int[zeilen.length];
        boolean etwas = false;
        for (int i = t.indexOf("switch"); i >= 0; i = t.indexOf("switch", i + 6)) {
            if (!c[i] || i > 0 && Character.isJavaIdentifierPart(t.charAt(i - 1)) || i + 6 < t.length() && Character.isJavaIdentifierPart(t.charAt(i + 6))) continue;
            int v = i - 1;
            while (v >= 0 && Character.isWhitespace(t.charAt(v))) v--;
            String davor = wortVor(t, i);
            boolean anweisung = v < 0 || ";{}:".indexOf(t.charAt(v)) >= 0 || t.charAt(v) == '>' && v > 0 && t.charAt(v - 1) == '-' || davor.equals("else")
                    || t.charAt(v) == ')' && steuerKoerper(t, i);
            if (anweisung) continue;
            int p = t.indexOf('(', i), tiefe = 0, k = p;
            if (p < 0) break;
            for (; k < t.length(); k++) {
                if (!c[k]) continue;
                if (t.charAt(k) == '(') tiefe++;
                else if (t.charAt(k) == ')' && --tiefe == 0) break;
            }
            int auf = k + 1;
            while (auf < t.length() && Character.isWhitespace(t.charAt(auf))) auf++;
            if (auf >= t.length() || t.charAt(auf) != '{') continue;
            int zu = auf;
            tiefe = 0;
            for (; zu < t.length(); zu++) {
                if (!c[zu]) continue;
                if (t.charAt(zu) == '{') tiefe++;
                else if (t.charAt(zu) == '}' && --tiefe == 0) break;
            }
            int za = zeileVon(anf, auf), ze = zeileVon(anf, Math.min(zu, t.length() - 1));
            for (int z = za + 1; z <= ze; z++) {
                if (z == ze && zeilen[z].strip().startsWith("}")) break;
                mehr[z]++;
                etwas = true;
            }
        }
        if (!etwas) return t;
        StringBuilder b = new StringBuilder(t.length() + 256);
        for (int z = 0; z < zeilen.length; z++) {
            if (mehr[z] > 0 && !zeilen[z].isBlank()) {
                int w = breite(zeilen[z], 0, zeilen[z].length()), ws = 0;
                while (ws < zeilen[z].length() && (zeilen[z].charAt(ws) == ' ' || zeilen[z].charAt(ws) == '\t')) ws++;
                b.append(einzug(w + mehr[z] * einzugBreite)).append(zeilen[z], ws, zeilen[z].length());
            } else {
                b.append(zeilen[z]);
            }
            if (z + 1 < zeilen.length) b.append('\n');
        }
        return b.toString();
    }

    private static int zeileVon(int[] anf, int pos) {
        int lo = 0, hi = anf.length - 1;
        while (lo < hi) {
            int m = (lo + hi + 1) >>> 1;
            if (anf[m] <= pos) lo = m;
            else hi = m - 1;
        }
        return lo;
    }

    /**
     * Einzeilige Bloecke: ohne SPACE_WITHIN_BRACES schreibt IntelliJ {x;} statt { x; }; wird die Zeile damit zu
     * lang, kommt der Block bei Klammern auf der naechsten Zeile (BRACE_STYLE) unter den Kopf. Dazu enum {A, B}
     * auf eigener Zeile und Leerzeilen in leeren Klassenruempfen (BLANK_LINES_AFTER_CLASS_HEADER).
     */
    private String einzeiligeBloecke(String t) {
        boolean innen = an("SPACE_WITHIN_BRACES", false), naechste = zahl("BRACE_STYLE", 1) == 2;
        int nachKopf = zahl("BLANK_LINES_AFTER_CLASS_HEADER", 0);
        List<String> aus = new ArrayList<>();
        boolean[] c = code(t);
        int off = 0;
        for (String zeile : t.split("\n", -1)) {
            int hier = off;
            off += zeile.length() + 1;
            String s = zeile.strip();
            int erstes = zeile.length() - zeile.stripLeading().length();
            // Zeilen in Kommentaren und Textbloecken bleiben, wie sie sind
            if (s.isEmpty() || !c[hier + erstes]) {
                aus.add(zeile);
                continue;
            }
            // zu lang mit Block am Ende: Block unter den Kopf (nur bei Klammern auf der naechsten Zeile)
            int auf = naechste ? blockAmEnde(zeile, c, hier) : -1;
            String gesetzt = innen ? zeile : blockInnen(zeile, c, hier);
            if (auf > 0 && spalten(gesetzt, 0) > rand && !zeile.substring(0, auf).isBlank()) {
                String kopf = zeile.substring(0, auf), block = zeile.substring(auf);
                aus.add((innen ? kopf : blockInnen(kopf, c, hier)).stripTrailing());
                // Einrueckung der Anweisung, nicht die ihrer Fortsetzungszeile
                gesetzt = anweisungsEinzug(aus) + (innen ? block : blockInnen(block, c, hier + auf));
            }
            zeile = gesetzt;
            if (!innen && s.startsWith("{ ") && s.endsWith(" }") && s.indexOf(';') < 0 && !aus.isEmpty() && (" " + aus.get(aus.size() - 1) + " ").contains(" enum ")) {
                zeile = zeile.substring(0, zeile.indexOf('{')) + "{" + s.substring(2, s.length() - 2) + "}";
            }
            aus.add(zeile);
            if (nachKopf > 0 && s.equals("}") && aus.size() >= 2 && aus.get(aus.size() - 2).stripTrailing().endsWith("{") && typKopf(aus, aus.size() - 2)) {
                for (int k = 0; k < nachKopf; k++) aus.add(aus.size() - 1, "");
            }
        }
        String r = String.join("\n", aus);
        for (int runde = 0; runde < 200; runde++) {
            String weiter = halbeBloecke(r);
            if (weiter.equals(r)) break;
            r = weiter;
        }
        return r;
    }

    /** Einrueckung der ersten Zeile der Anweisung, die mit der letzten Zeile in aus endet */
    private static String anweisungsEinzug(List<String> aus) {
        int i = aus.size() - 1;
        while (i > 0) {
            String v = aus.get(i - 1).strip();
            if (v.isEmpty() || v.endsWith(";") || v.endsWith("{") || v.endsWith("}") || v.startsWith("//") || v.startsWith("*") || v.startsWith("@")) break;
            i--;
        }
        String z = aus.get(Math.max(0, i));
        return z.substring(0, z.length() - z.stripLeading().length());
    }

    /**
     * Block, dessen { Inhalt auf derselben Zeile hat, der aber ueber mehrere Zeilen geht ({ r.put(a\n .b()); }):
     * IntelliJ klappt ihn ganz auf.
     */
    private String halbeBloecke(String t) {
        boolean[] c = code(t);
        List<int[]> auf = new ArrayList<>();
        java.util.ArrayDeque<Integer> stapel = new java.util.ArrayDeque<>();
        for (int k = 0; k < t.length(); k++) {
            if (!c[k]) continue;
            if (t.charAt(k) == '{') stapel.push(k);
            else if (t.charAt(k) == '}' && !stapel.isEmpty()) {
                int o = stapel.pop();
                String innen = t.substring(o + 1, k);
                if (innen.indexOf('\n') < 0 || innen.isBlank()) continue;
                int nl = innen.indexOf('\n');
                boolean vorneVoll = !innen.substring(0, nl).isBlank(), hintenVoll = !innen.substring(innen.lastIndexOf('\n') + 1).isBlank();
                int v = o - 1;
                while (v >= 0 && Character.isWhitespace(t.charAt(v))) v--;
                boolean werte = v >= 0 && "=],(".indexOf(t.charAt(v)) >= 0;
                if (vorneVoll && !werte && innen.strip().endsWith(";")) auf.add(new int[] {o, k, hintenVoll ? 1 : 0});
            }
        }
        if (auf.isEmpty()) return t;
        StringBuilder b = new StringBuilder(t);
        auf.sort((x, y) -> y[0] - x[0]);
        // ein Block je Durchlauf, damit die Positionen bei verschachtelten stimmen
        for (int[] x : auf.subList(0, 1)) {
            int o = x[0], k = x[1];
            int zs = t.lastIndexOf('\n', o) + 1;
            String ein = t.substring(zs, o);
            ein = ein.substring(0, ein.length() - ein.stripLeading().length());
            if (x[2] == 1) {
                int v = k;
                while (v > o && t.charAt(v - 1) == ' ') v--;
                b.replace(v, k, "\n" + ein);
            }
            // die Fortsetzungszeilen darin ruecken beim zweiten Durchlauf richtig ein (nochmal)
            int n = o + 1;
            while (n < t.length() && t.charAt(n) == ' ') n++;
            b.replace(o + 1, n, "\n" + ein + einzug(einzugBreite));
            nochmal = true;
        }
        return b.toString();
    }

    /** { x; } -> {x;} in einer Zeile (nur Code, Inhalt endet mit ;) */
    private static String blockInnen(String z, boolean[] c, int off) {
        StringBuilder b = new StringBuilder(z);
        for (int i = z.length() - 1; i >= 0; i--) {
            if (!c[off + i] || z.charAt(i) != '{' || i + 1 >= z.length() || z.charAt(i + 1) != ' ') continue;
            int tiefe = 0;
            for (int k = i; k < z.length(); k++) {
                if (!c[off + k]) continue;
                if (z.charAt(k) == '{') tiefe++;
                else if (z.charAt(k) == '}' && --tiefe == 0) {
                    if (k - 2 > i && z.charAt(k - 1) == ' ' && z.charAt(k - 2) == ';') {
                        b.deleteCharAt(k - 1);
                        b.deleteCharAt(i + 1);
                    }
                    break;
                }
            }
        }
        return b.toString();
    }

    /** Anfang eines einzeiligen Blocks {...;} am Zeilenende, sonst -1 */
    private static int blockAmEnde(String z, boolean[] c, int off) {
        String s = z.stripTrailing();
        if (!s.endsWith(";}") && !s.endsWith("; }")) return -1;
        int tiefe = 0;
        for (int k = s.length() - 1; k >= 0; k--) {
            if (!c[off + k]) continue;
            if (s.charAt(k) == '}') tiefe++;
            else if (s.charAt(k) == '{' && --tiefe == 0) return k;
        }
        return -1;
    }

    /** endet in Zeile i (mit {) ein Kopf von class, interface oder record (nicht enum, nicht anonym)? */
    private static boolean typKopf(List<String> z, int i) {
        StringBuilder kopf = new StringBuilder();
        for (int k = i; k >= 0 && k > i - 4; k--) {
            String s = z.get(k).strip();
            if (k < i && (s.endsWith(";") || s.endsWith("}") || s.endsWith("{") || s.isEmpty())) break;
            kopf.insert(0, " " + s);
        }
        String h = kopf.toString().replaceAll("\\s+", " ") + " ";
        return h.contains(" class ") || h.contains(" interface ") || h.contains(" record ");
    }

    /**
     * Vor dem Formatieren: fehlende Klammern um Koerper von if/else, for, while und do ergaenzen (IF_BRACE_FORCE
     * usw.: 1 = wenn mehrzeilig, 3 = immer). Das kann der Eclipse-Formatter nicht; Lage und Einrueckung macht er.
     */
    String vorbereiten(String t) {
        int fIf = zahl("IF_BRACE_FORCE", 0), fFor = zahl("FOR_BRACE_FORCE", 0), fWhile = zahl("WHILE_BRACE_FORCE", 0), fDo = zahl("DOWHILE_BRACE_FORCE", 0);
        if (fIf + fFor + fWhile + fDo == 0) return t;
        boolean[] c = code(t);
        List<int[]> rein = new ArrayList<>();
        for (int i = 0; i < t.length(); i++) {
            if (!c[i] || !Character.isJavaIdentifierStart(t.charAt(i)) || i > 0 && Character.isJavaIdentifierPart(t.charAt(i - 1))) continue;
            String w = wortAb(t, i);
            int kraft = switch (w) {
                case "if", "else" -> fIf;
                case "for" -> fFor;
                case "while" -> fWhile;
                case "do" -> fDo;
                default -> 0;
            };
            int nach = i + w.length();
            if (kraft == 0) {
                i = nach - 1;
                continue;
            }
            int s = nach;
            if (!w.equals("else") && !w.equals("do")) {
                s = klammerEnde(t, c, nach);
                if (s < 0) continue;
            }
            int k = naechstesCode(t, c, s);
            if (k >= t.length() || t.charAt(k) == '{' || t.charAt(k) == ';') continue;
            if (w.equals("else") && wortAb(t, k).equals("if")) continue;
            // while hinter dem Rumpf von do
            if (w.equals("while")) {
                int v = i - 1;
                while (v >= 0 && (Character.isWhitespace(t.charAt(v)) || !c[v])) v--;
                if (v >= 0 && t.charAt(v) == '}' && t.charAt(k) == ';') continue;
            }
            int e = anweisungEnde(t, c, k);
            if (e < 0) continue;
            if (kraft == 1 && t.substring(i, e).indexOf('\n') < 0) continue;
            // Zeilenkommentar hinter der Anweisung bleibt in der Klammer
            int zende = t.indexOf('\n', e);
            int zu = e;
            String rest = t.substring(e, zende < 0 ? t.length() : zende);
            if (rest.strip().startsWith("//")) zu = zende < 0 ? t.length() : zende;
            // wie IntelliJ direkt um die Anweisung; Lage der Klammern macht dann der Formatter
            rein.add(new int[] {k, 0});
            rein.add(new int[] {zu, zu > e ? 1 : 2});
        }
        if (rein.isEmpty()) return t;
        rein.sort((x, y) -> y[0] != x[0] ? y[0] - x[0] : y[1] - x[1]);
        StringBuilder b = new StringBuilder(t);
        for (int[] r : rein) b.insert(r[0], r[1] == 0 ? "{" : r[1] == 1 ? "\n}" : "}");
        return b.toString();
    }

    /** hinter der Klammer ( ... ) ab pos, oder -1 */
    private static int klammerEnde(String t, boolean[] c, int pos) {
        int k = naechstesCode(t, c, pos);
        if (k >= t.length() || t.charAt(k) != '(') return -1;
        int tiefe = 0;
        for (; k < t.length(); k++) {
            if (!c[k]) continue;
            if (t.charAt(k) == '(') tiefe++;
            else if (t.charAt(k) == ')' && --tiefe == 0) return k + 1;
        }
        return -1;
    }

    private static int naechstesCode(String t, boolean[] c, int pos) {
        while (pos < t.length() && (!c[pos] || Character.isWhitespace(t.charAt(pos)))) pos++;
        return pos;
    }

    /** Ende der Anweisung ab k (hinter ; oder }), -1 wenn unklar */
    private static int anweisungEnde(String t, boolean[] c, int k) {
        k = naechstesCode(t, c, k);
        if (k >= t.length()) return -1;
        if (t.charAt(k) == '{') {
            int tiefe = 0;
            for (; k < t.length(); k++) {
                if (!c[k]) continue;
                if (t.charAt(k) == '{') tiefe++;
                else if (t.charAt(k) == '}' && --tiefe == 0) return k + 1;
            }
            return -1;
        }
        String w = wortAb(t, k);
        switch (w) {
            case "if", "for", "while" -> {
                int s = klammerEnde(t, c, k + w.length());
                if (s < 0) return -1;
                int e = anweisungEnde(t, c, s);
                if (e < 0 || !w.equals("if")) return e;
                int n = naechstesCode(t, c, e);
                return wortAb(t, n).equals("else") ? anweisungEnde(t, c, n + 4) : e;
            }
            case "do" -> {
                int e = anweisungEnde(t, c, k + 2);
                if (e < 0) return -1;
                int n = naechstesCode(t, c, e);
                if (!wortAb(t, n).equals("while")) return -1;
                return anweisungEnde(t, c, n);
            }
            case "try", "switch", "synchronized", "class", "record", "interface", "enum" -> {
                return -1;
            }
            default -> {
            }
        }
        int tiefe = 0;
        for (; k < t.length(); k++) {
            if (!c[k]) continue;
            char ch = t.charAt(k);
            if (ch == '(' || ch == '{' || ch == '[') tiefe++;
            else if (ch == ')' || ch == '}' || ch == ']') tiefe--;
            else if (ch == ';' && tiefe == 0) return k + 1;
            if (tiefe < 0) return -1;
        }
        return -1;
    }

    private static final java.util.regex.Pattern ENUM_ZEILE = java.util.regex.Pattern.compile("(?m)^([ \\t]*(?:[\\w@]+[ \\t]+)*enum[ \\t]+\\w+[^{\\n]*\\{) ([^{}\\n]*[^ {}\\n]) (\\}[ \\t]*)$");

    /** enum A { X, Y } auf einer Zeile: IntelliJ ohne Leerzeichen innen ({X, Y}) */
    private static String enumKlammern(String t) {
        return ENUM_ZEILE.matcher(t).replaceAll("$1$2$3");
    }

    /** schliessende } zum { bei auf in alt, wenn beide auf derselben Zeile stehen, sonst -1 */
    private int einzeiligZu(String alt, int auf) {
        if (altCode == null) altCode = code(alt);
        int tiefe = 0;
        for (int k = auf; k < alt.length() && alt.charAt(k) != '\n'; k++) {
            if (!altCode[k]) continue;
            if (alt.charAt(k) == '{') tiefe++;
            else if (alt.charAt(k) == '}' && --tiefe == 0) return k;
        }
        return -1;
    }

    /**
     * Art des Blocks, der bei auf beginnt: "typ" (class, enum, ...), "methode", "lambda" oder "block" - und ob
     * IntelliJ ihn einzeilig laesst (KEEP_SIMPLE_*).
     */
    private boolean einfachBehalten(String t, int auf) {
        int v = auf - 1;
        while (v >= 0 && Character.isWhitespace(t.charAt(v))) v--;
        if (v < 0) return false;
        if (v > 0 && t.charAt(v) == '>' && t.charAt(v - 1) == '-') return an("KEEP_SIMPLE_LAMBDAS_IN_ONE_LINE", false);
        int k = v;
        while (k >= 0 && ";{}".indexOf(t.charAt(k)) < 0) k--;
        String kopf = " " + t.substring(k + 1, v + 1).replaceAll("\\s+", " ") + " ";
        if (kopf.matches(".*\\b(class|enum|interface|record)\\b.*") || kopf.contains(" new ") && t.charAt(v) == ')') return false;
        if (t.charAt(v) == ')' && steuerKopf(t, auf).isEmpty() && !kopf.matches(".*\\b(catch|synchronized|switch|try)\\b.*"))
            return an("KEEP_SIMPLE_METHODS_IN_ONE_LINE", false);
        return an("KEEP_SIMPLE_BLOCKS_IN_ONE_LINE", false);
    }

    /** Block ab { in alt auf einer Zeile, der einzeilig bleibt (bei IntelliJ oder weil der Formatter ihn so laesst)? */
    private boolean bleibtEinzeilig(String alt, int auf, List<int[]> bereiche, List<String> texte, int i) {
        int zu = einzeiligZu(alt, auf);
        if (zu < 0) return false;
        int v = auf - 1;
        while (v >= 0 && Character.isWhitespace(alt.charAt(v))) v--;
        int k = v;
        while (k >= 0 && ";{}".indexOf(alt.charAt(k)) < 0) k--;
        if (k + 1 <= v && (" " + alt.substring(k + 1, v + 1).replaceAll("\\s+", " ") + " ").matches(".*\\b(class|enum|interface|record)\\b.*")) return false;
        boolean einfach = einfachBehalten(alt, auf);
        for (int j = i + 1; j < bereiche.size() && bereiche.get(j)[0] <= zu; j++) {
            if (texte.get(j).indexOf('\n') < 0) continue;
            // an den Klammern nimmt die Nacharbeit den Umbruch zurueck (inEinfachemBlock), innen nicht
            if (!einfach || bereiche.get(j)[0] != auf + 1 && bereiche.get(j)[1] != zu) return false;
        }
        return true;
    }

    private boolean[] altCode;

    /** nach anwenden: der Text sollte noch einmal durch den Formatter (aufgeklappte Bloecke) */
    boolean nochmal;

    /**
     * Steht [a, e) direkt hinter { oder vor } eines einzeiligen Blocks in alt (eine Anweisung), den IntelliJ
     * einzeilig laesst, und bricht der Formatter innen sonst nichts um?
     */
    private boolean inEinfachemBlock(String alt, int a, int e, List<int[]> bereiche, List<String> texte) {
        if (altCode == null) altCode = code(alt);
        int zs = alt.lastIndexOf('\n', a - 1) + 1;
        int tiefe = 0;
        for (int k = a - 1; k >= zs; k--) {
            if (!altCode[k]) continue;
            if (alt.charAt(k) == '}') tiefe++;
            else if (alt.charAt(k) == '{' && tiefe-- == 0) {
                int zu = einzeiligZu(alt, k);
                if (zu < e) return false;
                String innen = alt.substring(k + 1, zu).strip();
                if (!innen.endsWith(";") || innen.indexOf(';') != innen.length() - 1 || !einfachBehalten(alt, k)) return false;
                int erst = k + 1, letzt = zu;
                if (a != erst && e != letzt) return false;
                for (int j = 0; j < bereiche.size(); j++) {
                    int[] x = bereiche.get(j);
                    if (x[0] > erst && x[1] < letzt && texte.get(j).indexOf('\n') >= 0) return false;
                }
                return true;
            }
        }
        return false;
    }

    private static int zeilen(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    /** endet direkt vor a der Kopf einer anonymen Klasse (new X() {) oder eines enum? */
    private static boolean kopfOhneLeerzeile(String t, int a) {
        int v = a - 1;
        while (v >= 0 && Character.isWhitespace(t.charAt(v))) v--;
        if (v < 0 || t.charAt(v) != '{') return false;
        int k = v - 1;
        while (k >= 0 && ";{}".indexOf(t.charAt(k)) < 0) k--;
        String kopf = " " + t.substring(k + 1, v).replaceAll("\\s+", " ") + " ";
        return kopf.contains(" enum ") || kopf.contains(" new ") && kopf.strip().endsWith(")");
    }

    /** Zeilen, die auch IntelliJ zusammenzieht: Klammer {, else, catch, finally, while hinter }, else if */
    private static boolean zusammenWieIntellij(String t, int a, int e) {
        if (e < t.length() && t.charAt(e) == '{') return true;
        String danach = wortAb(t, e);
        if (danach.equals("else") || danach.equals("catch") || danach.equals("finally")) return true;
        int v = a - 1;
        while (v >= 0 && Character.isWhitespace(t.charAt(v))) v--;
        if (danach.equals("while") && v >= 0 && t.charAt(v) == '}') return true;
        return danach.equals("if") && wortVor(t, a).equals("else");
    }

    /** Zeilenumbruch behalten; die Zeile rueckt so weit ein wie vorher relativ zu ihrer Vorgaengerzeile */
    private String umbruchBehalten(String alt, int a, String vorher, StringBuilder r) {
        int nl = (int) vorher.chars().filter(c -> c == '\n').count();
        int zeile = alt.lastIndexOf('\n', a - 1) + 1;
        int altVor = breite(alt, zeile, a);
        int altHier = breite(vorher, vorher.lastIndexOf('\n') + 1, vorher.length());
        int rz = r.lastIndexOf("\n") + 1;
        int neuVor = breite(r, rz, r.length());
        return "\n".repeat(Math.min(nl, leerzeilen + 1)) + einzug(Math.max(0, neuVor + altHier - altVor));
    }

    /** Breite der fuehrenden Leerzeichen/Tabs ab von (bis hoechstens bis) */
    private int breite(CharSequence s, int von, int bis) {
        int b = 0;
        for (int i = von; i < bis; i++) {
            char c = s.charAt(i);
            if (c == ' ') b++;
            else if (c == '\t') b += tab - b % tab;
            else break;
        }
        return b;
    }

    private String einzug(int n) {
        return tabs ? "\t".repeat(n / tab) + " ".repeat(n % tab) : " ".repeat(n);
    }

    private static String wortAb(String t, int i) {
        int e = i;
        while (e < t.length() && Character.isJavaIdentifierPart(t.charAt(e))) e++;
        return t.substring(i, e);
    }

    private static String wortVor(String t, int i) {
        int e = i;
        while (e > 0 && Character.isWhitespace(t.charAt(e - 1))) e--;
        int a = e;
        while (a > 0 && Character.isJavaIdentifierPart(t.charAt(a - 1))) a--;
        return t.substring(a, e);
    }

    // ================================================================ Javadoc

    /** alle Javadoc-Kommentare, die allein auf ihren Zeilen stehen, wie IntelliJ formatieren */
    String javadoc(String t) {
        StringBuilder r = new StringBuilder(t.length() + 256);
        int pos = 0, i = 0, n = t.length();
        while (i < n) {
            char c = t.charAt(i);
            if (c == '"' || c == '\'') {
                i = literalEnde(t, i);
            } else if (c == '/' && i + 1 < n && t.charAt(i + 1) == '/') {
                int e = t.indexOf('\n', i);
                i = e < 0 ? n : e;
            } else if (c == '/' && i + 1 < n && t.charAt(i + 1) == '*') {
                int e = t.indexOf("*/", i + 2);
                int ende = e < 0 ? n : e + 2;
                int zeile = t.lastIndexOf('\n', i - 1) + 1;
                int nachEnde = t.indexOf('\n', ende);
                boolean allein = t.substring(zeile, i).isBlank() && t.substring(ende, nachEnde < 0 ? n : nachEnde).isBlank();
                if (e >= 0 && allein && i + 2 < n && t.charAt(i + 2) == '*' && e > i + 2) {
                    String neu = kommentar(t.substring(i + 3, e), t.substring(zeile, i));
                    if (neu != null) {
                        r.append(t, pos, i).append(neu);
                        pos = ende;
                    }
                }
                i = ende;
            } else {
                i++;
            }
        }
        return r.append(t, pos, n).toString();
    }

    /** Ende eines String-, Zeichen- oder Textblock-Literals ab i */
    private static int literalEnde(String t, int i) {
        char q = t.charAt(i);
        if (q == '"' && t.startsWith("\"\"\"", i)) {
            int e = t.indexOf("\"\"\"", i + 3);
            while (e > 0 && t.charAt(e - 1) == '\\') e = t.indexOf("\"\"\"", e + 1);
            return e < 0 ? t.length() : e + 3;
        }
        int j = i + 1;
        while (j < t.length() && t.charAt(j) != q && t.charAt(j) != '\n') j += t.charAt(j) == '\\' ? 2 : 1;
        return Math.min(t.length(), j + 1);
    }

    private static final Set<String> MIT_NAME = Set.of("@param", "@throws", "@exception");

    /** Inhalt zwischen und * / neu setzen; null = unveraendert lassen */
    private String kommentar(String innen, String einzug) {
        boolean einzeilig = innen.indexOf('\n') < 0;
        if (einzeilig && an("JD_DO_NOT_WRAP_ONE_LINE_COMMENTS", false)) {
            String s = innen.strip();
            return "/** " + s + " */";
        }
        // Zeilen ohne Stern; in <pre> bleibt die Einrueckung hinter "* "
        List<String> roh = new ArrayList<>();
        String[] zs = innen.split("\n", -1);
        for (int k = 0; k < zs.length; k++) {
            String z = zs[k];
            String s = z.stripLeading();
            if (s.startsWith("*")) {
                s = s.substring(1);
                if (s.startsWith(" ")) s = s.substring(1);
            } else if (k == 0 && s.startsWith(" ")) {
                s = s.substring(1);
            }
            roh.add(s.stripTrailing());
        }
        while (!roh.isEmpty() && roh.get(0).isBlank()) roh.remove(0);
        while (!roh.isEmpty() && roh.get(roh.size() - 1).isBlank()) roh.remove(roh.size() - 1);

        // Beschreibung und Tags trennen
        List<String> beschreibung = new ArrayList<>();
        List<List<String>> tags = new ArrayList<>();
        boolean pre = false;
        for (String z : roh) {
            String s = z.strip();
            boolean tag = !pre && s.length() > 1 && s.charAt(0) == '@' && Character.isLetter(s.charAt(1));
            if (tag) tags.add(new ArrayList<>(List.of(s)));
            else if (!tags.isEmpty()) tags.get(tags.size() - 1).add(z);
            else beschreibung.add(z);
            pre = preNach(pre, s);
        }

        List<String> aus = new ArrayList<>(beschreibungSetzen(beschreibung, einzug));
        while (!aus.isEmpty() && aus.get(aus.size() - 1).isEmpty()) aus.remove(aus.size() - 1);
        List<String> tagZeilen = tagsSetzen(tags, einzug);
        if (!aus.isEmpty() && !tagZeilen.isEmpty() && an("JD_ADD_BLANK_AFTER_DESCRIPTION", true)) aus.add("");
        aus.addAll(tagZeilen);

        StringBuilder b = new StringBuilder("/**\n");
        if (aus.isEmpty()) aus.add("");
        for (String z : aus) b.append(einzug).append(" *").append(z.isEmpty() ? "" : " " + z).append('\n');
        return b.append(einzug).append(" */").toString();
    }

    private static boolean preNach(boolean pre, String s) {
        String k = s.toLowerCase();
        int auf = k.lastIndexOf("<pre"), zu = k.lastIndexOf("</pre>");
        if (auf >= 0 && auf > zu) return true;
        if (zu >= 0 && zu > auf) return false;
        return pre;
    }

    private List<String> beschreibungSetzen(List<String> zeilen, String einzug) {
        boolean leerBehalten = an("JD_KEEP_EMPTY_LINES", true), p = an("JD_P_AT_EMPTY_LINES", true);
        List<String> aus = new ArrayList<>();
        StringBuilder absatz = new StringBuilder();
        boolean pre = false;
        for (int k = 0; k < zeilen.size(); k++) {
            String z = zeilen.get(k), s = z.strip();
            boolean html = s.startsWith("<");
            if (pre || html) {
                absatzAus(absatz, aus, einzug, 0);
                aus.add(pre ? z : z.stripTrailing());
            } else if (s.isEmpty()) {
                absatzAus(absatz, aus, einzug, 0);
                if (!leerBehalten) continue;
                String naechste = "";
                for (int j = k + 1; j < zeilen.size() && naechste.isEmpty(); j++) naechste = zeilen.get(j).strip();
                aus.add(p && !naechste.isEmpty() && !naechste.startsWith("<") ? "<p>" : "");
            } else if (umbrechen() && !an("JD_PRESERVE_LINE_FEEDS", false)) {
                absatz.append(absatz.length() > 0 ? " " : "").append(s);
            } else {
                absatz.append(s);
                absatzAus(absatz, aus, einzug, 0);
            }
            pre = preNach(pre, s);
        }
        absatzAus(absatz, aus, einzug, 0);
        return aus;
    }

    private boolean umbrechen() {
        return an("WRAP_COMMENTS", false);
    }

    /** Absatz ausgeben, bei WRAP_COMMENTS am Rand umbrochen (folgende Zeilen mit haengend Leerzeichen davor) */
    private void absatzAus(StringBuilder absatz, List<String> aus, String einzug, int haengend) {
        if (absatz.length() == 0) return;
        String text = absatz.toString();
        absatz.setLength(0);
        if (!umbrechen()) {
            aus.add(text);
            return;
        }
        int platz = Math.max(20, rand - breite(einzug, 0, einzug.length()) - 3);
        // Woerter mit den Leerzeichen davor, wie sie stehen (Ausrichtung, {@code a  b}); am Umbruch fallen sie weg
        StringBuilder z = new StringBuilder();
        boolean erste = true;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\S+").matcher(text);
        int vorher = 0;
        while (m.find()) {
            String luecke = text.substring(vorher, m.start()), wort = m.group();
            vorher = m.end();
            int grenze = erste ? platz : platz - haengend;
            if (z.length() > 0 && z.length() + luecke.length() + wort.length() > grenze) {
                aus.add((erste ? "" : " ".repeat(haengend)) + z);
                z.setLength(0);
                erste = false;
                luecke = "";
            }
            z.append(z.length() > 0 ? luecke : "").append(wort);
        }
        if (z.length() > 0) aus.add((erste ? "" : " ".repeat(haengend)) + z);
    }

    private List<String> tagsSetzen(List<List<String>> tags, String einzug) {
        boolean param = an("JD_ALIGN_PARAM_COMMENTS", true), ausnahme = an("JD_ALIGN_EXCEPTION_COMMENTS", true);
        boolean neueZeile = an("JD_PARAM_DESCRIPTION_ON_NEW_LINE", false), weiter = an("JD_INDENT_ON_CONTINUATION", false);
        // Tag, Name (bei @param/@throws), Beschreibung (erste Zeile), Fortsetzungszeilen
        List<String[]> kopf = new ArrayList<>();
        List<List<String>> rest = new ArrayList<>();
        int breiteParam = 0, breiteAusnahme = 0;
        for (List<String> t : tags) {
            String[] w = t.get(0).split("\\s+", 2);
            String tag = w[0], name = "", text = w.length > 1 ? w[1].strip() : "";
            if (MIT_NAME.contains(tag) && !text.isEmpty()) {
                String[] nw = text.split("\\s+", 2);
                name = nw[0];
                text = nw.length > 1 ? nw[1].strip() : "";
            }
            List<String> fort = new ArrayList<>();
            for (String z : t.subList(1, t.size())) if (!z.isBlank()) fort.add(z.strip());
            boolean leer = text.isEmpty() && fort.isEmpty();
            if (leer && (tag.equals("@param") && !an("JD_KEEP_EMPTY_PARAMETER", true) || tag.equals("@return") && !an("JD_KEEP_EMPTY_RETURN", true)
                    || (tag.equals("@throws") || tag.equals("@exception")) && !an("JD_KEEP_EMPTY_EXCEPTION", true))) continue;
            kopf.add(new String[] {tag, name, text});
            rest.add(fort);
            int lang = tag.length() + (name.isEmpty() ? 0 : 1 + name.length());
            if (tag.equals("@param")) breiteParam = Math.max(breiteParam, lang);
            else if (!name.isEmpty()) breiteAusnahme = Math.max(breiteAusnahme, lang);
        }
        List<String> aus = new ArrayList<>();
        for (int k = 0; k < kopf.size(); k++) {
            String tag = kopf.get(k)[0], name = kopf.get(k)[1], text = kopf.get(k)[2];
            List<String> fort = rest.get(k);
            String anfang = tag + (name.isEmpty() ? "" : " " + name);
            int spalte = -1;
            if (tag.equals("@param") && param) spalte = breiteParam + 1;
            else if (!name.isEmpty() && !tag.equals("@param") && ausnahme) spalte = breiteAusnahme + 1;
            int haengend = !name.isEmpty() && neueZeile ? (weiter ? fortsetzung : 0) : spalte > 0 ? spalte : weiter ? fortsetzung : 0;
            if (!name.isEmpty() && neueZeile) {
                aus.add(anfang);
                if (!text.isEmpty()) fort.add(0, text);
                text = "";
            }
            if (umbrechen() && !an("JD_PRESERVE_LINE_FEEDS", false)) {
                StringBuilder ganz = new StringBuilder(text);
                for (String f : fort) ganz.append(ganz.length() > 0 ? " " : "").append(f);
                text = ganz.toString();
                fort = List.of();
            }
            if (!text.isEmpty() || aus.isEmpty() || !aus.get(aus.size() - 1).equals(anfang)) {
                String erste = text.isEmpty() ? anfang : spalte > 0 && !(!name.isEmpty() && neueZeile) ? pad(anfang, spalte) + text : anfang + " " + text;
                if (umbrechen()) {
                    List<String> z = new ArrayList<>();
                    absatzAus(new StringBuilder(erste), z, einzug, haengend);
                    aus.addAll(z);
                } else {
                    aus.add(erste);
                }
            }
            for (String f : fort) aus.add(" ".repeat(haengend) + f);
            boolean weitere = k + 1 < kopf.size();
            if (weitere && tag.equals("@param") && !kopf.get(k + 1)[0].equals("@param") && an("JD_ADD_BLANK_AFTER_PARM_COMMENTS", false)) aus.add("");
            if (weitere && tag.equals("@return") && an("JD_ADD_BLANK_AFTER_RETURN", false)) aus.add("");
        }
        return aus;
    }

    private static String pad(String s, int n) {
        return s.length() >= n ? s + " " : s + " ".repeat(n - s.length());
    }

    private int zahl(String k, int vorgabe) {
        try {
            return w.containsKey(k) ? Integer.parseInt(w.get(k).strip()) : vorgabe;
        } catch (NumberFormatException e) {
            return vorgabe;
        }
    }

    private boolean an(String k, boolean vorgabe) {
        return w.containsKey(k) ? Boolean.parseBoolean(w.get(k).strip()) : vorgabe;
    }
}
