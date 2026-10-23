package runen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * keymap.config lesen (Modes und Layouts mit extends), wie beim RUNENWERK-Kernel:
 *
 * <pre>
 *   set layout custom             # aktives Layout (Standard: de_DE)
 *   set start normal              # Mode beim Start (Standard: normal)
 *   layout custom extends de_DE { z = y ... }
 *   mode normal extends base { dd = deleteLine  Ctrl-s = save  ANY = insertChar }
 * </pre>
 */
final class Keymap {
    static final class Mode {
        String eltern = "";
        final Map<String, String> bind = new HashMap<>();
        final Set<String> pre = new HashSet<>();
    }

    static final class Layout {
        String basis = "";
        final Map<Integer, Integer> map = new HashMap<>();
    }

    final Map<String, Mode> modes = new HashMap<>();
    final Map<String, Layout> layouts = new HashMap<>();
    final Map<Integer, Integer> layoutMap = new HashMap<>();
    String layout = "de_DE";
    String start = "normal";
    final List<String> fehler = new ArrayList<>();

    private static final Set<String> NAMEN = Set.of("ESC", "ENTER", "TAB", "BACKSPACE", "DELETE", "INSERT", "UP", "DOWN", "LEFT", "RIGHT",
            "HOME", "END", "PGUP", "PGDN", "SPACE", "ANY", "DIGIT");

    static boolean sonder(String w) {
        if (NAMEN.contains(w)) return true;
        int n = w.length();
        if (n >= 2 && n <= 3 && w.charAt(0) == 'F' && Character.isDigit(w.charAt(1))) return true;
        return (n > 5 && w.startsWith("Ctrl-")) || (n > 4 && w.startsWith("Alt-")) || (n > 6 && w.startsWith("Shift-"));
    }

    /** linke Seite einer Belegung als Folge von Tastennamen ("d d", "Ctrl-s", "g t") */
    static String folge(String links) {
        if (sonder(links)) return links;
        StringBuilder b = new StringBuilder();
        for (String w : links.trim().split(" +")) {
            if (w.isEmpty()) continue;
            if (sonder(w)) {
                if (b.length() > 0) b.append(' ');
                b.append(w);
                continue;
            }
            w.codePoints().forEach(cp -> {
                if (b.length() > 0) b.append(' ');
                b.appendCodePoint(cp);
            });
        }
        return b.toString();
    }

    private static int zeichenVon(String w) {
        if (w.equals("SPACE")) return 32;
        return w.isEmpty() ? 0 : w.codePointAt(0);
    }

    private static String wort(String z, int n) {
        String[] t = z.trim().split("\\s+");
        return n < t.length ? t[n] : "";
    }

    /** Vorgabe (aus der Jar) und dann die Datei, die sie ergaenzt und ueberschreibt */
    boolean lesen(Path pfad, java.io.InputStream vorgabe) {
        modes.clear();
        layouts.clear();
        fehler.clear();
        layout = "de_DE";
        start = "normal";
        if (vorgabe != null) {
            try (vorgabe) {
                zeilenLesen(new String(vorgabe.readAllBytes(), StandardCharsets.UTF_8).lines().toList(), "Vorgabe");
            } catch (IOException e) {
                fehler.add("Vorgabe-Keymap: " + e.getMessage());
            }
        }
        boolean da = Files.exists(pfad);
        if (da) {
            try {
                zeilenLesen(Files.readAllLines(pfad, StandardCharsets.UTF_8), pfad.getFileName().toString());
            } catch (IOException e) {
                fehler.add(pfad + ": " + e.getMessage());
            }
        }
        for (Map.Entry<String, Mode> e : modes.entrySet()) {
            String el = e.getValue().eltern;
            if (!el.isEmpty() && !modes.containsKey(el)) fehler.add("keymap: mode " + e.getKey() + " extends " + el + ": gibt es nicht");
        }
        layoutAn();
        return da || vorgabe != null;
    }

    boolean lesen(Path pfad) {
        return lesen(pfad, null);
    }

    private void zeilenLesen(List<String> zeilen, String datei) {
        String modus = null, lay = null;
        int nr = 0;
        for (String roh : zeilen) {
            nr++;
            String z = roh.trim();
            // "# = ..." belegt die Taste #, sonst ist # ein Kommentar
            if (z.isEmpty() || (z.startsWith("#") && !z.startsWith("# = ")) || z.startsWith("//")) continue;
            if (z.equals("}")) {
                if (modus == null && lay == null) fehler.add(datei + ":" + nr + ": } ohne Block");
                modus = null;
                lay = null;
                continue;
            }
            String w0 = wort(z, 0);
            if (modus == null && lay == null) {
                if (w0.equals("set")) {
                    switch (wort(z, 1)) {
                        case "layout" -> layout = wort(z, 2);
                        case "start" -> start = wort(z, 2);
                        default -> fehler.add(datei + ":" + nr + ": unbekannte Einstellung " + wort(z, 1));
                    }
                    continue;
                }
                if ((w0.equals("mode") || w0.equals("layout")) && z.endsWith("{")) {
                    String name = wort(z, 1);
                    String eltern = wort(z, 2).equals("extends") ? wort(z, 3) : "";
                    if (w0.equals("mode")) {
                        // zweiter Block ergaenzt den ersten
                        Mode m = modes.computeIfAbsent(name, k -> new Mode());
                        if (!eltern.isEmpty()) m.eltern = eltern;
                        modus = name;
                    } else {
                        Layout l = new Layout();
                        l.basis = eltern;
                        layouts.put(name, l);
                        lay = name;
                    }
                    continue;
                }
                fehler.add(datei + ":" + nr + ": erwartet: mode NAME { oder layout NAME { oder set, nicht " + w0);
                continue;
            }
            int q = z.indexOf(" = ");
            if (q < 0) {
                fehler.add(datei + ":" + nr + ": erwartet: TASTE = BEFEHL");
                continue;
            }
            String links = z.substring(0, q).trim();
            String rechts = z.substring(q + 3).trim();
            if (lay != null) {
                layouts.get(lay).map.put(zeichenVon(links), zeichenVon(rechts));
                continue;
            }
            String f = folge(links);
            Mode md = modes.get(modus);
            if (f.contains("DIGIT")) {
                // DIGIT steht fuer 0-9; die Ziffer kommt als letztes Argument zum Befehl
                for (char d = '0'; d <= '9'; d++) belegen(md, f.replace("DIGIT", String.valueOf(d)), rechts + " " + d);
            } else {
                belegen(md, f, rechts);
            }
        }
        if (modus != null || lay != null) fehler.add(datei + ":" + nr + ": } fehlt am Ende");
    }

    private static void belegen(Mode md, String f, String rechts) {
        for (int p = 0; p < f.length(); p++) {
            if (f.charAt(p) == ' ') md.pre.add(f.substring(0, p));
        }
        md.bind.put(f, rechts);
    }

    /** Belegung einer Folge im Mode (mit seinen Eltern) oder null */
    String befehl(String mode, String folge) {
        for (int tiefe = 0; !mode.isEmpty() && tiefe < 32; tiefe++) {
            Mode m = modes.get(mode);
            if (m == null) return null;
            String r = m.bind.get(folge);
            if (r != null) return r;
            mode = m.eltern;
        }
        return null;
    }

    /** Gibt es im Mode eine laengere Folge, die so anfaengt? */
    boolean vorsilbe(String mode, String folge) {
        for (int tiefe = 0; !mode.isEmpty() && tiefe < 32; tiefe++) {
            Mode m = modes.get(mode);
            if (m == null) return false;
            if (m.pre.contains(folge)) return true;
            mode = m.eltern;
        }
        return false;
    }

    boolean modeDa(String mode) {
        return modes.containsKey(mode);
    }

    /**
     * Layout einrichten. Das Grundlayout liefert das Betriebssystem (de_DE und en_US sind
     * deshalb nur Namen); eigene Layouts ersetzen Zeichen, Eltern zuerst.
     */
    boolean layoutAn() {
        layoutMap.clear();
        List<Layout> kette = new ArrayList<>();
        String name = layout;
        while (layouts.containsKey(name) && kette.size() < 32) {
            Layout l = layouts.get(name);
            kette.add(l);
            name = l.basis;
        }
        if (!name.equals("de_DE") && !name.equals("en_US")) {
            fehler.add("keymap.config: Layout " + name + " gibt es nicht (Grundlayouts: de_DE, en_US)");
            return false;
        }
        for (int k = kette.size() - 1; k >= 0; k--) layoutMap.putAll(kette.get(k).map);
        return true;
    }

    int zeichen(int cp) {
        return layoutMap.getOrDefault(cp, cp);
    }
}
