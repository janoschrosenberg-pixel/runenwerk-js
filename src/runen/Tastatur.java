package runen;

import java.awt.event.KeyEvent;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tastaturlayouts des Editors, unabhaengig vom Layout des Betriebssystems: ein getipptes Zeichen
 * wird ueber das Systemlayout auf die Taste (Position) zurueckgerechnet und mit dem aktiven Layout
 * neu belegt. So tippt man en_US, auch wenn macOS/Windows auf Deutsch steht.
 *
 * <pre>
 *   set layouts en_US de_DE prog   # Reihenfolge fuer layoutNext, das erste gilt beim Start
 *   set system auto                # Layout des Betriebssystems: auto, de_DE oder en_US
 * </pre>
 *
 * prog: Buchstabentasten tragen nur Ziffern und Sonderzeichen (links Ziffernblock 7 8 9 / 4 5 6 /
 * 1 2 3 mit Klammern und = daneben, Shift wie auf der US-Zahlenreihe; rechts 0, ( ) " & - + / $ |).
 * Alle anderen Tasten wie en_US.
 */
final class Tastatur {
    /*
     * Positionen (48 Tasten, Reihenfolge einer ISO-Tastatur, benannt nach en_US):
     *   ` 1 2 3 4 5 6 7 8 9 0 - =
     *   q w e r t y u i o p [ ] \
     *   a s d f g h j k l ; '
     *   ISO z x c v b n m , . /      (ISO = Taste neben dem linken Shift, auf deutschen Tastaturen < >)
     */
    static final Map<String, String[]> BASIS = new HashMap<>();
    /** Zeichen mit AltGr (Windows/Linux) bzw. Option (Mac), nur fuer de_DE: Position -> {ohne Shift, mit Shift} */
    static final Map<Integer, String> ALT_DE = new HashMap<>();
    static final String EN_N = "`1234567890-=qwertyuiop[]\\asdfghjkl;'\\zxcvbnm,./";
    static final String EN_S = "~!@#$%^&*()_+QWERTYUIOP{}|ASDFGHJKL:\"|ZXCVBNM<>?";

    static {
        BASIS.put("en_US", new String[] {EN_N, EN_S});
        BASIS.put("de_DE", new String[] {"^1234567890ß´qwertzuiopü+#asdfghjklöä<yxcvbnm,.-",
                "°!\"§$%&/()=?`QWERTZUIOPÜ*'ASDFGHJKLÖÄ>YXCVBNM;:_"});
        // prog: je Buchstabentaste {ohne Shift, mit Shift}
        String[][] prog = {
                {"q", "[{"}, {"w", "7&"}, {"e", "8*"}, {"r", "9("}, {"t", "]}"},
                {"a", ";:"}, {"s", "4$"}, {"d", "5%"}, {"f", "6^"}, {"g", "=+"},
                {"z", "`~"}, {"x", "1!"}, {"c", "2@"}, {"v", "3#"}, {"b", "\\|"},
                {"y", "!?"}, {"u", "(<"}, {"i", ")>"}, {"o", "\"'"}, {"p", "&#"},
                {"h", "-_"}, {"j", "0."}, {"k", "+*"}, {"l", "/\\"},
                {"n", "$%"}, {"m", "|~"}};
        char[] n = EN_N.toCharArray(), s = EN_S.toCharArray();
        for (String[] t : prog) {
            int p = EN_N.indexOf(t[0]);
            n[p] = t[1].charAt(0);
            s[p] = t[1].charAt(1);
        }
        BASIS.put("prog", new String[] {new String(n), new String(s)});
        if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            alt("5", "[[");
            alt("6", "]]");
            alt("7", "|\\");
            alt("8", "{{");
            alt("9", "}}");
            alt("l", "@@");
            alt("n", "~~");
            alt("e", "€€");
        } else {
            alt("q", "@@");
            alt("e", "€€");
            alt("7", "{{");
            alt("8", "[[");
            alt("9", "]]");
            alt("0", "}}");
            alt("-", "\\\\");
            alt("]", "~~");
            alt("2", "²²");
            alt("3", "³³");
            alt("m", "µµ");
            ALT_DE.put(37, "||");
        }
    }

    private static void alt(String pos, String z) {
        ALT_DE.put(EN_N.indexOf(pos), z);
    }

    /** Kuerzel in der Statuszeile und eine Farbe je Grundlayout */
    static String kurz(String name) {
        return switch (name) {
            case "en_US" -> "EN";
            case "de_DE" -> "DE";
            case "prog" -> "PROG";
            default -> name.toUpperCase(Locale.ROOT);
        };
    }

    private final Keymap km;
    /** Layouts zum Durchschalten, das aktive */
    final List<String> liste = new ArrayList<>();
    String aktiv = "en_US";
    /** Grundlayout des aktiven (Ende der extends-Kette) und die eigenen Ersetzungen darauf */
    String basis = "en_US";
    private final Map<Integer, Integer> eigen = new HashMap<>();
    /** Layout des Betriebssystems (null: unbekannt, dann wird nichts uebersetzt) */
    String system = "de_DE";
    /** nach einer toten Taste (^ ´ `): das zusammengesetzte Zeichen des Systems verwerfen */
    private boolean nachTot;
    private boolean totLeer;

    Tastatur(Keymap km) {
        this.km = km;
    }

    /** nach dem Laden der Keymap: Liste pruefen, das erste Layout aktiv */
    void an(Locale os) {
        liste.clear();
        for (String l : km.layoutListe) {
            if (grund(l) == null) km.fehler.add("keymap.config: Layout " + l + " gibt es nicht (Grundlayouts: en_US, de_DE, prog)");
            else if (!liste.contains(l)) liste.add(l);
        }
        if (liste.isEmpty()) liste.add("en_US");
        systemSetzen(os);
        setzen(liste.get(0));
    }

    void systemSetzen(Locale os) {
        String s = km.system;
        if (s.equals("auto")) {
            String sp = os == null ? "" : os.getLanguage();
            s = sp.equals("de") ? "de_DE" : sp.equals("en") || sp.isEmpty() ? "en_US" : null;
        }
        system = s != null && BASIS.containsKey(s) ? s : null;
    }

    /** Grundlayout am Ende der extends-Kette, null wenn es keins gibt */
    private String grund(String name) {
        for (int i = 0; i < 32; i++) {
            if (BASIS.containsKey(name)) return name;
            Keymap.Layout l = km.layouts.get(name);
            if (l == null) return null;
            name = l.basis;
        }
        return null;
    }

    boolean setzen(String name) {
        String g = grund(name);
        if (g == null) return false;
        aktiv = name;
        basis = g;
        eigen.clear();
        List<Keymap.Layout> kette = new ArrayList<>();
        for (String n = name; km.layouts.containsKey(n) && kette.size() < 32; n = km.layouts.get(n).basis) kette.add(km.layouts.get(n));
        for (int k = kette.size() - 1; k >= 0; k--) eigen.putAll(kette.get(k).map);
        nachTot = false;
        return true;
    }

    /** naechstes Layout der Liste */
    void weiter() {
        int i = liste.indexOf(aktiv);
        setzen(liste.get((i + 1) % liste.size()));
    }

    private boolean gleich() {
        return system == null || system.equals(basis);
    }

    /** Position und Shift eines Zeichens im Systemlayout: Position*2+Shift, -1 wenn es dort nicht vorkommt */
    private int position(int c) {
        String[] t = BASIS.get(system);
        int p = t[0].indexOf(c);
        if (p >= 0) return p * 2;
        p = t[1].indexOf(c);
        return p >= 0 ? p * 2 + 1 : -1;
    }

    private int belegung(int pos) {
        return BASIS.get(basis)[pos & 1].charAt(pos >> 1);
    }

    /** getipptes Zeichen (keyTyped) im aktiven Layout; -1 = verwerfen */
    int zeichen(int c) {
        if (nachTot) {
            // das System setzt die tote Taste mit dem naechsten Zeichen zusammen (^ a -> â, ^ Leertaste -> ^)
            if ("^´`¨˜".indexOf(c) >= 0) {
                if (!totLeer) return -1;
                nachTot = false;
                return ' ';
            }
            nachTot = false;
            String z = Normalizer.normalize(String.valueOf((char) c), Normalizer.Form.NFD);
            c = z.charAt(0);
        }
        if (!gleich()) {
            int p = position(c);
            if (p >= 0) c = belegung(p);
        }
        return eigen.getOrDefault(c, c);
    }

    /** keyPressed einer toten Taste: das Zeichen im aktiven Layout, -1 = dem System ueberlassen */
    int tot(int vk) {
        if (gleich()) return -1;
        int z = switch (vk) {
            case KeyEvent.VK_DEAD_CIRCUMFLEX -> '^';
            case KeyEvent.VK_DEAD_ACUTE -> '´';
            case KeyEvent.VK_DEAD_GRAVE -> '`';
            case KeyEvent.VK_DEAD_TILDE -> '~';
            case KeyEvent.VK_DEAD_DIAERESIS -> '"';
            default -> -1;
        };
        int p = z < 0 ? -1 : position(z);
        if (p < 0) return -1;
        nachTot = true;
        totLeer = false;
        int c = belegung(p);
        return eigen.getOrDefault(c, c);
    }

    /** jede gedrueckte Taste: Leertaste direkt nach einer toten Taste ergibt ein Leerzeichen */
    void gedrueckt(int vk) {
        if (nachTot) totLeer = vk == KeyEvent.VK_SPACE;
    }

    /** Position einer Taste aus dem KeyEvent-Code, -1 = unbekannt */
    private int positionVk(int vk) {
        if (vk >= KeyEvent.VK_A && vk <= KeyEvent.VK_Z) {
            // Buchstaben-Codes folgen dem Systemlayout (deutsch: Z liegt dort, wo en_US Y hat)
            int p = position('a' + vk - KeyEvent.VK_A);
            return p < 0 ? -1 : p >> 1;
        }
        if (vk >= KeyEvent.VK_0 && vk <= KeyEvent.VK_9) return EN_N.indexOf('0' + vk - KeyEvent.VK_0);
        int c = switch (vk) {
            case KeyEvent.VK_MINUS -> '-';
            case KeyEvent.VK_EQUALS -> '=';
            case KeyEvent.VK_OPEN_BRACKET -> '[';
            case KeyEvent.VK_CLOSE_BRACKET -> ']';
            case KeyEvent.VK_SEMICOLON -> ';';
            case KeyEvent.VK_QUOTE -> '\'';
            case KeyEvent.VK_COMMA -> ',';
            case KeyEvent.VK_PERIOD -> '.';
            case KeyEvent.VK_SLASH -> '/';
            case KeyEvent.VK_BACK_QUOTE -> '`';
            default -> -1;
        };
        if (vk == KeyEvent.VK_LESS) return 37;
        return c < 0 ? -1 : EN_N.indexOf(c);
    }

    /** AltGr/Option + Taste: Zeichen der Alt-Ebene von de_DE, -1 = dem System ueberlassen */
    int alt(int vk, boolean shift) {
        if (gleich() || !basis.equals("de_DE")) return -1;
        int p = positionVk(vk);
        String z = p < 0 ? null : ALT_DE.get(p);
        if (z == null) return -1;
        int c = z.charAt(shift ? 1 : 0);
        return eigen.getOrDefault(c, c);
    }

    /**
     * Strg/Cmd + Buchstabe: der Buchstabe im aktiven Layout. Traegt die Taste dort keinen
     * Buchstaben (prog), gilt der von en_US, damit Strg-Befehle ueberall erreichbar bleiben.
     */
    int strg(int vk) {
        int roh = 'a' + vk - KeyEvent.VK_A;
        if (gleich()) return roh;
        int p = positionVk(vk);
        if (p < 0) return roh;
        int c = BASIS.get(basis)[0].charAt(p);
        c = eigen.getOrDefault(c, c);
        return c >= 'a' && c <= 'z' ? c : EN_N.charAt(p);
    }
}
