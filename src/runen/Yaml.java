package runen;

import java.util.ArrayList;
import java.util.List;

/**
 * Aufbau einer YAML-Datei nach Zeilen (fuer die OpenAPI-Ansicht): Schluessel, Listeneintraege, Einzug
 * und die Zeilen, die ein Knoten mit seinen Kindern belegt. Werte bleiben Text; geaendert wird die Datei
 * selbst, damit Kommentare und Formatierung erhalten bleiben. Kein vollstaendiger YAML-Parser: Anker,
 * Tags und mehrzeilige Flow-Werte werden nur uebersprungen.
 */
final class Yaml {
    static final class Knoten {
        /** Schluessel ohne Anfuehrungszeichen; null bei einem Listeneintrag */
        String schluessel;
        /** erste Zeile; ende = erste Zeile danach (ohne folgende Leer- und Kommentarzeilen) */
        int zeile, ende;
        /** Spalte des Schluessels (bzw. des - bei einem Listeneintrag) */
        int einzug;
        /** Spalte des Schluessels in der Zeile (bei "- name: x" die von name) und seine Laenge im Text */
        int schluesselSpalte, schluesselLaenge;
        /** Wert hinter dem Doppelpunkt ohne Kommentar ("" ohne) */
        String wert = "";
        /** Kinder sind Listeneintraege */
        boolean liste;
        Knoten eltern;
        final List<Knoten> kinder = new ArrayList<>();

        Knoten kind(String k) {
            for (Knoten x : kinder) if (k.equals(x.schluessel)) return x;
            return null;
        }

        /** Knoten ueber einen Pfad von Schluesseln */
        Knoten pfad(String... k) {
            Knoten x = this;
            for (String s : k) {
                x = x.kind(s);
                if (x == null) return null;
            }
            return x;
        }

        /** Wert eines Kindes ohne Anfuehrungszeichen (oder null) */
        String text(String k) {
            Knoten x = kind(k);
            return x == null ? null : skalar(x.wert);
        }

        /** Wert in eckigen Klammern als Liste ([a, b]) oder die Listeneintraege der Kinder */
        List<String> werte() {
            List<String> r = new ArrayList<>();
            String w = wert.strip();
            if (w.startsWith("[") && w.endsWith("]")) {
                for (String s : w.substring(1, w.length() - 1).split(",")) if (!s.isBlank()) r.add(skalar(s));
            } else {
                for (Knoten x : kinder) if (x.schluessel == null && x.kinder.isEmpty()) r.add(skalar(x.wert));
            }
            return r;
        }

        /** Spalte der Kinder: die des ersten Kindes, sonst einzug + einheit */
        int kindEinzug(int einheit) {
            if (!kinder.isEmpty()) return kinder.get(0).einzug;
            return (schluessel == null ? schluesselSpalte : einzug) + einheit;
        }

        @Override
        public String toString() {
            return (schluessel == null ? "-" : schluessel) + "@" + (zeile + 1) + "-" + ende;
        }
    }

    /** Wurzel (Zeile -1) mit den Eintraegen der obersten Ebene */
    static Knoten lesen(String text) {
        String[] z = text.split("\n", -1);
        Knoten wurzel = new Knoten();
        wurzel.zeile = -1;
        wurzel.einzug = -1;
        wurzel.schluesselSpalte = -1;
        List<Knoten> stapel = new ArrayList<>();
        stapel.add(wurzel);
        int i = 0;
        while (i < z.length) {
            String roh = z[i].endsWith("\r") ? z[i].substring(0, z[i].length() - 1) : z[i];
            String inhalt = ohneKommentar(roh);
            int c = einzug(inhalt);
            if (inhalt.isBlank() || inhalt.startsWith("---") || inhalt.startsWith("...") || inhalt.startsWith("%")) {
                i++;
                continue;
            }
            i = eintrag(z, i, c, inhalt, stapel);
        }
        return wurzel;
    }

    /** ein Eintrag ab Spalte c in Zeile i; liefert die naechste Zeile */
    private static int eintrag(String[] z, int i, int c, String inhalt, List<Knoten> stapel) {
        String rest = inhalt.substring(c);
        boolean strich = rest.equals("-") || rest.startsWith("- ");
        // Eltern suchen: alles mit gleichem oder groesserem Einzug ist fertig; "- " darf auf Hoehe des Schluessels stehen
        while (stapel.size() > 1) {
            Knoten t = stapel.get(stapel.size() - 1);
            if (t.einzug < c) break;
            if (strich && t.einzug == c && t.schluessel != null && t.wert.isEmpty() && (t.kinder.isEmpty() || t.liste)) break;
            stapel.remove(stapel.size() - 1);
        }
        Knoten eltern = stapel.get(stapel.size() - 1);
        if (strich) {
            Knoten k = neu(eltern, null, i, c);
            eltern.liste = true;
            stapel.add(k);
            String r = rest.length() > 1 ? rest.substring(2) : "";
            int c2 = c + 2 + einzug(r);
            r = r.strip();
            k.schluesselSpalte = c2;
            if (r.isEmpty()) return i + 1;
            if (r.equals("-") || r.startsWith("- ") || schluesselEnde(r) >= 0) {
                // "- name: x" oder "- - x": der erste Eintrag des Listeneintrags steht in derselben Zeile
                String ersatz = " ".repeat(c2) + r;
                return eintrag(z, i, c2, ersatz, stapel);
            }
            k.wert = r;
            return weiter(z, i, k);
        }
        int e = schluesselEnde(rest);
        if (e < 0) {
            // Fortsetzung eines mehrzeiligen Werts
            if (eltern.zeile >= 0) erweitern(eltern, i + 1);
            return i + 1;
        }
        String roherSchluessel = rest.substring(0, e).strip();
        Knoten k = neu(eltern, skalar(roherSchluessel), i, c);
        k.schluesselSpalte = c;
        k.schluesselLaenge = roherSchluessel.length();
        k.wert = rest.substring(e + 1).strip();
        stapel.add(k);
        return weiter(z, i, k);
    }

    /** Blockwerte (| >) und mehrzeilige Flow-Werte ({ [) gehoeren zum Knoten */
    private static int weiter(String[] z, int i, Knoten k) {
        String w = k.wert;
        int j = i + 1;
        if (w.matches("[|>][-+0-9]*(\\s.*)?") || w.matches("&\\S+\\s+[|>][-+0-9]*")) {
            while (j < z.length && (z[j].isBlank() || einzug(z[j]) > k.einzug)) j++;
            while (j > i + 1 && z[j - 1].isBlank()) j--;
        } else if ((w.startsWith("{") || w.startsWith("[")) && tiefe(w) > 0) {
            int t = tiefe(w);
            while (j < z.length && t > 0) t += tiefe(ohneKommentar(z[j++]));
        }
        erweitern(k, j);
        return j;
    }

    private static Knoten neu(Knoten eltern, String schluessel, int zeile, int einzug) {
        Knoten k = new Knoten();
        k.schluessel = schluessel;
        k.zeile = zeile;
        k.einzug = einzug;
        k.eltern = eltern;
        eltern.kinder.add(k);
        erweitern(k, zeile + 1);
        return k;
    }

    private static void erweitern(Knoten k, int ende) {
        for (Knoten x = k; x != null && x.zeile >= 0; x = x.eltern) if (x.ende < ende) x.ende = ende;
    }

    private static int tiefe(String s) {
        int t = 0;
        char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (q != 0) {
                if (ch == q) q = 0;
            } else if (ch == '"' || ch == '\'') {
                q = ch;
            } else if (ch == '{' || ch == '[') {
                t++;
            } else if (ch == '}' || ch == ']') {
                t--;
            }
        }
        return t;
    }

    static int einzug(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') i++;
        return i;
    }

    /** Position des Doppelpunkts hinter dem Schluessel (": " oder am Ende), ausserhalb von Anfuehrungszeichen; -1 ohne */
    static int schluesselEnde(String s) {
        if (s.isEmpty() || s.charAt(0) == '{' || s.charAt(0) == '[' || s.charAt(0) == '|' || s.charAt(0) == '>') return -1;
        int i = 0;
        if (s.charAt(0) == '"' || s.charAt(0) == '\'') {
            char q = s.charAt(0);
            i = 1;
            while (i < s.length()) {
                if (s.charAt(i) == q) {
                    if (q == '\'' && i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                        i += 2;
                        continue;
                    }
                    break;
                }
                if (q == '"' && s.charAt(i) == '\\') i++;
                i++;
            }
            i++;
            while (i < s.length() && s.charAt(i) == ' ') i++;
            return i < s.length() && s.charAt(i) == ':' && (i + 1 == s.length() || s.charAt(i + 1) == ' ') ? i : -1;
        }
        for (; i < s.length(); i++) {
            if (s.charAt(i) == ':' && (i + 1 == s.length() || s.charAt(i + 1) == ' ' || s.charAt(i + 1) == '\t')) return i;
        }
        return -1;
    }

    /** Zeile ohne # Kommentar (ein # am Zeilenanfang oder nach Leerzeichen, ausserhalb von Anfuehrungszeichen) */
    static String ohneKommentar(String s) {
        char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (q != 0) {
                if (ch == q) q = 0;
                else if (q == '"' && ch == '\\') i++;
            } else if (ch == '#' && (i == 0 || s.charAt(i - 1) == ' ' || s.charAt(i - 1) == '\t')) {
                return s.substring(0, i).stripTrailing();
            } else if ((ch == '"' || ch == '\'') && (i == 0 || " :[{,-".indexOf(s.charAt(i - 1)) >= 0)) {
                q = ch;
            }
        }
        return s.stripTrailing();
    }

    /** Skalar ohne Anfuehrungszeichen */
    static String skalar(String s) {
        s = s.strip();
        if (s.length() >= 2 && (s.charAt(0) == '"' && s.endsWith("\"") || s.charAt(0) == '\'' && s.endsWith("'"))) {
            String in = s.substring(1, s.length() - 1);
            return s.charAt(0) == '\'' ? in.replace("''", "'") : in.replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return s;
    }

    /** Text als YAML-Skalar: in Anfuehrungszeichen, wenn er sonst etwas anderes bedeuten wuerde */
    static String zitat(String s) {
        if (s.isEmpty() || s.matches(".*(: |\\s#).*") || s.matches("^[\\s\\-?:,\\[\\]{}#&*!|>'\"%@`].*") || s.endsWith(":") || s.endsWith(" ")
                || s.matches("(?i)true|false|yes|no|on|off|null|~|[-+]?[0-9][0-9_.eE+-]*")) {
            return "'" + s.replace("'", "''") + "'";
        }
        return s;
    }

    /** Einrueckungsschritt der Datei (kleinster Einzug ueber 0), sonst 2 */
    static int einheit(String text) {
        int best = Integer.MAX_VALUE;
        for (String z : text.split("\n")) {
            String o = ohneKommentar(z);
            if (o.isBlank()) continue;
            int e = einzug(o);
            if (e > 0 && e < best) best = e;
        }
        return best == Integer.MAX_VALUE || best > 8 ? 2 : best;
    }
}
