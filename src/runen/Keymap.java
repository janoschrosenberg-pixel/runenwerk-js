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
 *   set layouts en_US de_DE prog  # Layouts fuer layoutNext, das erste gilt beim Start (Tastatur.java)
 *   set system auto               # Layout des Betriebssystems: auto, de_DE, en_US
 *   set start normal              # Mode beim Start (Standard: normal)
 *   layout custom extends de_DE { z = y ... }
 *   mode normal extends base { dd = deleteLine  Ctrl-s = save  ANY = insertChar }
 *   mode normal {
 *     menu SPACE g = Git {                 # SPACE g zeigt das Menue in der Statuszeile
 *       s = gitStatus        # Status      # Text hinter # steht im Menue
 *       menu b = Branch {                  # Untermenue: SPACE g b ...
 *         t = gitBranch ts   # TypeScript
 *       }
 *     }
 *   }
 * </pre>
 *
 * Ein Menue bleibt offen, bis ein Befehl laeuft oder ESC kommt (BACKSPACE: eine Ebene hoeher).
 */
final class Keymap {
    static final class Mode {
        String eltern = "";
        final Map<String, String> bind = new HashMap<>();
        final Set<String> pre = new HashSet<>();
        /** fuer die Uebersicht (bindings): Folge wie geschrieben (mit DIGIT) -> {Befehl, Datei}, in Dateireihenfolge */
        final Map<String, String[]> zeigen = new java.util.LinkedHashMap<>();
        /** Kommentar ueber "mode NAME {" */
        String text = "";
        /** Menues: Folge (z. B. "SPACE g") -> Menue */
        final Map<String, Menu> menus = new HashMap<>();
    }

    /** Menue einer Tastenfolge: Titel, Tasten in der Reihenfolge der Datei, Texte */
    static final class Menu {
        String titel = "";
        final List<String> reihe = new ArrayList<>();
        final Map<String, String> namen = new HashMap<>();
    }

    static final class Layout {
        String basis = "";
        final Map<Integer, Integer> map = new HashMap<>();
    }

    final Map<String, Mode> modes = new java.util.LinkedHashMap<>();
    final Map<String, Layout> layouts = new HashMap<>();
    /** set layouts: zum Durchschalten, das erste gilt beim Start */
    final List<String> layoutListe = new ArrayList<>(List.of("en_US", "de_DE", "prog"));
    String system = "auto";
    String start = "normal";
    final List<String> fehler = new ArrayList<>();

    private static final Set<String> NAMEN = Set.of("ESC", "ENTER", "TAB", "BACKSPACE", "DELETE", "INSERT", "UP", "DOWN", "LEFT", "RIGHT",
            "HOME", "END", "PGUP", "PGDN", "SPACE", "ANY", "DIGIT",
            "MAUS-LINKS", "MAUS-RAD-HOCH", "MAUS-RAD-RUNTER");

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
        layoutListe.clear();
        layoutListe.addAll(List.of("en_US", "de_DE", "prog"));
        system = "auto";
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
        return da || vorgabe != null;
    }

    /** weitere Datei (sondertasten.config) zu den gelesenen Modes dazu: erst die Vorgabe aus der Jar, dann die Datei */
    void dazuLesen(Path pfad, java.io.InputStream vorgabe) {
        if (vorgabe != null) {
            try (vorgabe) {
                zeilenLesen(new String(vorgabe.readAllBytes(), StandardCharsets.UTF_8).lines().toList(), "Vorgabe " + pfad.getFileName());
            } catch (IOException e) {
                fehler.add("Vorgabe " + pfad.getFileName() + ": " + e.getMessage());
            }
        }
        if (Files.exists(pfad)) {
            try {
                zeilenLesen(Files.readAllLines(pfad, StandardCharsets.UTF_8), pfad.getFileName().toString());
            } catch (IOException e) {
                fehler.add(pfad + ": " + e.getMessage());
            }
        }
    }

    boolean lesen(Path pfad) {
        return lesen(pfad, null);
    }

    private void zeilenLesen(List<String> zeilen, String datei) {
        String modus = null, lay = null;
        // offene Menues im Mode (innerstes zuletzt): ihre Folgen
        List<String> menuPfad = new ArrayList<>();
        int nr = 0;
        StringBuilder kommentar = new StringBuilder();
        for (String roh : zeilen) {
            nr++;
            String z = roh.trim();
            if (modus == null && lay == null) {
                // Kommentar direkt ueber "mode NAME {" beschreibt den Mode
                if (z.startsWith("#") && !z.startsWith("# = ")) kommentar.append(kommentar.length() > 0 ? " " : "").append(z.substring(1).strip());
                else if (z.isEmpty()) kommentar.setLength(0);
            }
            // "# = ..." belegt die Taste #, sonst ist # ein Kommentar
            if (z.isEmpty() || (z.startsWith("#") && !z.startsWith("# = ")) || z.startsWith("//")) continue;
            if (z.equals("}") && !menuPfad.isEmpty()) {
                menuPfad.remove(menuPfad.size() - 1);
                continue;
            }
            if (z.equals("}")) {
                kommentar.setLength(0);
                if (modus == null && lay == null) fehler.add(datei + ":" + nr + ": } ohne Block");
                modus = null;
                lay = null;
                continue;
            }
            String w0 = wort(z, 0);
            if (modus == null && lay == null) {
                if (w0.equals("set")) {
                    kommentar.setLength(0);
                    switch (wort(z, 1)) {
                        case "layouts" -> {
                            String[] t = z.trim().split("\\s+");
                            layoutListe.clear();
                            layoutListe.addAll(List.of(t).subList(2, t.length));
                        }
                        // frueher: das Layout des Betriebssystems (de_DE/en_US) oder ein eigenes; ein eigenes kommt nach vorn
                        case "layout" -> {
                            String l = wort(z, 2);
                            if (!l.equals("de_DE") && !l.equals("en_US")) {
                                layoutListe.remove(l);
                                layoutListe.add(0, l);
                            }
                        }
                        case "system" -> system = wort(z, 2);
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
                        if (kommentar.length() > 0) m.text = kommentar.toString();
                        kommentar.setLength(0);
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
            Mode md = modes.get(modus);
            String vor = menuPfad.isEmpty() ? "" : menuPfad.get(menuPfad.size() - 1) + " ";
            if (w0.equals("menu") && rechts.endsWith("{")) {
                // menu TASTEN = Titel {
                String mf = vor + folge(links.substring(4).trim());
                Menu m = md.menus.computeIfAbsent(mf, k -> new Menu());
                m.titel = rechts.substring(0, rechts.length() - 1).trim();
                // eine einfache Belegung derselben Folge wuerde das Menue verdecken
                if (md.bind.remove(mf) != null) md.zeigen.remove(mf);
                for (int p = 0; p < mf.length(); p++) if (mf.charAt(p) == ' ') md.pre.add(mf.substring(0, p));
                md.pre.add(mf);
                if (!menuPfad.isEmpty()) eintragen(md.menus.get(menuPfad.get(menuPfad.size() - 1)), folge(links.substring(4).trim()), null);
                menuPfad.add(mf);
                continue;
            }
            String name = null;
            if (!menuPfad.isEmpty()) {
                // Text fuer das Menue hinter " # "
                int h = rechts.indexOf(" # ");
                if (h >= 0) {
                    name = rechts.substring(h + 3).trim();
                    rechts = rechts.substring(0, h).trim();
                }
                eintragen(md.menus.get(menuPfad.get(menuPfad.size() - 1)), folge(links), name);
            }
            String f = vor + folge(links);
            if (md.menus.containsKey(f)) {
                fehler.add(datei + ":" + nr + ": " + f + " ist ein Menue - " + links + " = " + rechts + " gilt nicht (Zeile loeschen oder eine andere Taste nehmen)");
                continue;
            }
            // gleiche Belegung wie die Vorgabe: Quelle bleibt die Vorgabe (keymap.config ist anfangs eine Kopie)
            String[] alt = md.zeigen.get(f);
            if (alt == null || !alt[0].equals(rechts)) {
                md.zeigen.remove(f);
                md.zeigen.put(f, new String[] {rechts, datei});
            }
            ziffernBelegen(md, f, rechts);
        }
        if (modus != null || lay != null) fehler.add(datei + ":" + nr + ": } fehlt am Ende");
    }

    private static void eintragen(Menu m, String taste, String name) {
        if (!m.reihe.contains(taste)) m.reihe.add(taste);
        if (name != null) m.namen.put(taste, name);
    }

    /** Menue der Folge im Mode (mit seinen Eltern) oder null */
    Menu menu(String mode, String folge) {
        for (int tiefe = 0; !mode.isEmpty() && tiefe < 32; tiefe++) {
            Mode m = modes.get(mode);
            if (m == null) return null;
            Menu r = m.menus.get(folge);
            if (r != null) return r;
            mode = m.eltern;
        }
        return null;
    }

    /**
     * Eintraege eines Menues: {Taste, Text, "menu" oder "befehl"}; erst die Tasten aus dem Menue-Block, dann
     * andere Belegungen mit derselben Vorsilbe (alphabetisch)
     */
    List<String[]> menuEintraege(String mode, String folge) {
        Menu m = menu(mode, folge);
        List<String> tasten = new ArrayList<>(m == null ? List.of() : m.reihe);
        List<String> weitere = new ArrayList<>();
        String vor = folge + " ";
        for (String md = mode; !md.isEmpty() && modes.containsKey(md); md = modes.get(md).eltern) {
            Mode x = modes.get(md);
            for (String k : x.bind.keySet()) if (k.startsWith(vor)) weitere.add(k.substring(vor.length()).split(" ")[0]);
            for (String k : x.pre) if (k.startsWith(vor)) weitere.add(k.substring(vor.length()).split(" ")[0]);
            if (weitere.size() > 500) break;
        }
        weitere.sort(String::compareTo);
        for (String t : weitere) if (!tasten.contains(t)) tasten.add(t);
        List<String[]> r = new ArrayList<>();
        for (String t : tasten) {
            String n = vor + t;
            Menu u = menu(mode, n);
            String b = befehl(mode, n);
            String text = m == null ? null : m.namen.get(t);
            if (u != null) r.add(new String[] {t, (text != null ? text : u.titel) + " \u00bb", "menu"});
            else if (b == null && vorsilbe(mode, n)) r.add(new String[] {t, (text != null ? text : "...") + " \u00bb", "menu"});
            else if (b != null) r.add(new String[] {t, text != null ? text : b, "befehl"});
        }
        return r;
    }

    /** DIGIT steht fuer 0-9; jede Ziffer kommt der Reihe nach als Argument zum Befehl (auch mehrere DIGIT) */
    private static void ziffernBelegen(Mode md, String f, String rechts) {
        int i = f.indexOf("DIGIT");
        if (i < 0) {
            belegen(md, f, rechts);
            return;
        }
        for (char d = '0'; d <= '9'; d++) ziffernBelegen(md, f.substring(0, i) + d + f.substring(i + 5), rechts + " " + d);
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
}
