package runen;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Textobjekt Methode/Funktion (am / im, auch af / if) fuer Sprachen mit geschweiften Klammern
 * (Java, JavaScript, TypeScript): am = ganze Methode mit Javadoc, Kommentaren und Annotationen
 * direkt darueber, im = nur der Rumpf. Steht der Cursor im Kopf, in der Javadoc oder auf einer
 * Annotation, gilt die Methode darunter. Lambdas und Pfeilfunktionen zaehlen nur, wenn keine
 * Methode den Cursor umschliesst.
 */
final class Methode {
    private Methode() {}

    private static final Pattern STEUER = Pattern.compile("(if|for|while|switch|catch|synchronized|with|try|foreach)");
    /** record P(int x) { ... } ist ein Typ, keine Methode */
    private static final Pattern TYP = Pattern.compile("\\brecord\\s+[\\w$<>?,\\s]+$");
    /** Ende des Kopfs hinter der Parameterliste: throws ... (Java) oder : Rueckgabetyp (TypeScript) */
    private static final Pattern NACH_PARAMS = Pattern.compile("\\)\\s*(throws\\s+[\\w.$<>?,\\s\\[\\]]+|:\\s*[^;{}()]+)?$");
    private static final Pattern ANONYM = Pattern.compile("\\bnew\\s+[\\w.$<>?,\\s\\[\\]]+$");

    /** Methoden um p, innen nach aussen: je {Kopfanfang, oeffnende Klammer, schliessende Klammer} */
    static List<int[]> umschliessend(String t, int p) {
        boolean[] c = code(t);
        List<Integer> bloecke = new ArrayList<>();
        // Cursor im Kopf (oder in Javadoc/Annotation darueber): der Block, der als naechstes aufgeht
        int k = p;
        while (k < t.length() && !(c[k] && ";{}".indexOf(t.charAt(k)) >= 0)) k++;
        if (k < t.length() && t.charAt(k) == '{') bloecke.add(k);
        // umschliessende Bloecke
        int tiefe = 0;
        for (k = Math.min(p, t.length()) - 1; k >= 0; k--) {
            if (!c[k]) continue;
            char ch = t.charAt(k);
            if (ch == '}') tiefe++;
            else if (ch == '{' && tiefe-- == 0) {
                bloecke.add(k);
                tiefe = 0;
            }
        }
        List<int[]> methoden = new ArrayList<>(), lambdas = new ArrayList<>();
        for (int auf : bloecke) {
            int zu = zu(t, c, auf);
            if (zu < 0) continue;
            int[] kopf = new int[1];
            String art = art(t, c, auf, kopf);
            if (art.equals("methode")) methoden.add(new int[] {kopf[0], auf, zu});
            else if (art.equals("lambda")) lambdas.add(new int[] {kopf[0], auf, zu});
        }
        return methoden.isEmpty() ? lambdas : methoden;
    }

    private static int zu(String t, boolean[] c, int auf) {
        int tiefe = 0;
        for (int k = auf; k < t.length(); k++) {
            if (!c[k]) continue;
            if (t.charAt(k) == '{') tiefe++;
            else if (t.charAt(k) == '}' && --tiefe == 0) return k;
        }
        return -1;
    }

    /** "methode", "lambda" oder "block" fuer den Block ab auf; kopf[0] = erstes Code-Zeichen des Kopfs */
    private static String art(String t, boolean[] c, int auf, int[] kopf) {
        int k = auf - 1;
        while (k >= 0 && !(c[k] && ";{}".indexOf(t.charAt(k)) >= 0)) k--;
        int a = k + 1;
        while (a < auf && (!c[a] || Character.isWhitespace(t.charAt(a)))) a++;
        kopf[0] = a;
        String s = maske(t, c, a, auf).strip();
        if (s.isEmpty()) return "block";
        if (s.endsWith("->") || s.endsWith("=>")) return "lambda";
        Matcher m = NACH_PARAMS.matcher(s);
        if (!m.find()) return "block";
        int zuP = m.start(), tiefe = 0, aufP = -1;
        for (int i = zuP; i >= 0; i--) {
            char ch = s.charAt(i);
            if (ch == ')') tiefe++;
            else if (ch == '(' && --tiefe == 0) {
                aufP = i;
                break;
            }
        }
        if (aufP < 0) return "block";
        String vor = s.substring(0, aufP).stripTrailing();
        int w = vor.length();
        while (w > 0 && Character.isJavaIdentifierPart(vor.charAt(w - 1))) w--;
        String name = vor.substring(w);
        if (STEUER.matcher(name).matches() || ANONYM.matcher(vor).find() || TYP.matcher(vor).find()) return "block";
        if (name.isEmpty() && !vor.endsWith("function") && !vor.endsWith("*")) return "block";
        return "methode";
    }

    /** Text [a, e) mit Leerzeichen statt Kommentaren und Literalen */
    private static String maske(String t, boolean[] c, int a, int e) {
        StringBuilder b = new StringBuilder(e - a);
        for (int i = a; i < e; i++) b.append(c[i] ? t.charAt(i) : ' ');
        return b.toString();
    }

    /** Code-Zeichen (nicht in Kommentar, String, Zeichen, Textblock oder Template-String) */
    static boolean[] code(String t) {
        int n = t.length();
        boolean[] c = new boolean[n];
        int i = 0;
        while (i < n) {
            char ch = t.charAt(i);
            int e;
            if (ch == '"' && t.startsWith("\"\"\"", i)) {
                e = t.indexOf("\"\"\"", i + 3);
                e = e < 0 ? n : e + 3;
            } else if (ch == '"' || ch == '\'' || ch == '`') {
                int j = i + 1;
                while (j < n && t.charAt(j) != ch && (ch == '`' || t.charAt(j) != '\n')) j += t.charAt(j) == '\\' ? 2 : 1;
                e = Math.min(n, j + 1);
            } else if (ch == '/' && i + 1 < n && t.charAt(i + 1) == '/') {
                e = t.indexOf('\n', i);
                if (e < 0) e = n;
            } else if (ch == '/' && i + 1 < n && t.charAt(i + 1) == '*') {
                e = t.indexOf("*/", i + 2);
                e = e < 0 ? n : e + 2;
            } else {
                c[i++] = true;
                continue;
            }
            i = e;
        }
        return c;
    }

    /**
     * Bereich {anfang, ende, zeilenweise} der stufe-ten Methode um den Cursor (0 = innerste),
     * null wenn es keine gibt.
     */
    static int[] objekt(Doc d, int p, boolean innen, int stufe) {
        List<int[]> l = umschliessend(d.t.toString(), p);
        if (stufe >= l.size()) return null;
        int[] m = l.get(stufe);
        int kopf = m[0], auf = m[1], zu = m[2];
        if (innen) {
            int ia = auf + 1, ie = zu;
            if (ia < ie && d.at(ia) == '\n') {
                int z1 = d.zeileVon(ia + 1), z2 = d.zeileVon(ie);
                if (d.zeile(z2).substring(0, ie - d.anf(z2)).isBlank() && z1 < z2) return new int[] {d.anf(z1), d.ende(z2 - 1), 1};
            }
            return new int[] {ia, ie, 0};
        }
        int zk = d.zeileVon(kopf), ze = d.zeileVon(zu);
        boolean vorne = d.zeile(zk).substring(0, kopf - d.anf(zk)).isBlank();
        String rest = d.t.substring(zu + 1, d.ende(ze)).strip();
        boolean hinten = rest.isEmpty() || rest.equals(";") || rest.equals(",");
        if (!vorne || !hinten) return new int[] {kopf, zu + 1, 0};
        // Javadoc, Kommentare und Annotationen direkt darueber (ohne Leerzeile dazwischen)
        int z1 = zk;
        while (z1 > 0 && kommentarZeile(d.zeile(z1 - 1))) z1--;
        return new int[] {d.anf(z1), d.ende(ze), 1};
    }

    private static boolean kommentarZeile(String z) {
        String s = z.strip();
        return s.startsWith("/*") || s.startsWith("*") || s.startsWith("//") || s.startsWith("@");
    }
}
