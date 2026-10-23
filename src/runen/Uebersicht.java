package runen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Tabelle aller Tastenbelegungen fuer den Befehl bindings: die Tasten kommen aus keymap.config und
 * sondertasten.config (wie gelesen), die Beschreibungen aus befehle.txt in der Jar (beim Bauen aus
 * den @binding-Javadocs erzeugt) bzw. aus den Kommentaren der Mapping-Funktionen.
 */
final class Uebersicht {
    private static Map<String, String> befehle;

    /** Beschreibungen aus der Jar (name TAB text) */
    static synchronized Map<String, String> befehle() {
        if (befehle != null) return befehle;
        befehle = new LinkedHashMap<>();
        try (InputStream in = Main.class.getResourceAsStream("/vorgaben/befehle.txt")) {
            if (in != null) {
                for (String z : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                    int t = z.indexOf('\t');
                    if (t > 0 && !z.startsWith("#")) befehle.put(z.substring(0, t), z.substring(t + 1).strip());
                }
            }
        } catch (IOException e) {
            // ohne Beschreibungen
        }
        return befehle;
    }

    /** Beschreibung eines Befehls: Editor-Befehl, sonst Mapping-Funktion */
    static String beschreibung(String befehl, Mapping mp) {
        String voll = befehl.strip().replaceAll("\\s+", " ");
        // awaitChar X: beschrieben wird X, das das getippte Zeichen bekommt
        if (voll.startsWith("awaitChar ")) return beschreibung(voll.substring(10), mp) + " (wartet auf ein Zeichen)";
        // eigene Beschreibung fuer genau dieses Argument (@binding-arg)
        String b = befehle().get(voll);
        if (b != null) return b;
        String name = voll.split(" ", 2)[0];
        b = befehle().get(name);
        if (b != null) return b;
        Mapping.Fun f = mp.funs.get(name);
        if (f != null) return (f.text().isEmpty() ? "Mapping-Funktion" : f.text()) + "  [" + f.datei() + "]";
        return "?";
    }

    /** filter: leer = alles, ein Mode-Name = nur dieser Mode, sonst Zeilen, die den Text enthalten */
    static String text(Keymap km, Mapping mp, java.util.Set<String> alleBefehle, String filter) {
        String f = filter == null ? "" : filter.strip();
        boolean nurMode = !f.isEmpty() && km.modes.containsKey(f);
        String fk = f.toLowerCase();
        StringBuilder b = new StringBuilder();
        b.append("TASTENBELEGUNGEN").append(f.isEmpty() ? "" : "  (Filter: " + f + ")").append("\n\n");
        b.append("Quellen:    keymap.config und sondertasten.config neben runenjs.jar (ergaenzen die Vorgabe aus der Jar)\n");
        b.append("Schreibweise: Ctrl-x = Strg+x, SPACE = Leertaste, DIGIT = Ziffer 0-9, ANY = jedes andere Zeichen,\n");
        b.append("            mehrere Tasten nacheinander durch Leerzeichen getrennt (SPACE g b)\n");
        b.append("Aufruf:     bindings [MODE | TEXT]   z. B. bindings normal, bindings git;  q schliesst\n");
        int kb = 18, bb = 26;
        for (Keymap.Mode m : km.modes.values()) {
            for (var e : m.zeigen.entrySet()) {
                kb = Math.max(kb, Math.min(30, e.getKey().length()));
                bb = Math.max(bb, Math.min(34, e.getValue()[0].length()));
            }
        }
        TreeSet<String> belegt = new TreeSet<>();
        for (var me : km.modes.entrySet()) {
            String name = me.getKey();
            Keymap.Mode m = me.getValue();
            for (String[] v : m.zeigen.values()) belegt.add(v[0].strip().split("\\s+", 2)[0]);
            if (nurMode && !name.equals(f)) continue;
            List<String> zeilen = new ArrayList<>();
            for (var e : m.zeigen.entrySet()) {
                String befehl = e.getValue()[0];
                String quelle = e.getValue()[1].startsWith("Vorgabe") ? "" : "  [eigene Belegung: " + e.getValue()[1] + "]";
                String z = "  " + pad(e.getKey(), kb) + "  " + pad(befehl, bb) + "  " + beschreibung(befehl, mp) + quelle;
                if (nurMode || fk.isEmpty() || z.toLowerCase().contains(fk) || name.toLowerCase().contains(fk)) zeilen.add(z);
            }
            if (zeilen.isEmpty()) continue;
            String kopf = "==== mode " + name + (m.eltern.isEmpty() ? "" : "   (erbt alles aus " + kette(km, m.eltern) + ")") + " ";
            b.append("\n").append(kopf).append("=".repeat(Math.max(4, 100 - kopf.length()))).append("\n");
            if (!m.text.isEmpty()) for (String z : umbrechen(m.text, 100)) b.append("  ").append(z).append("\n");
            b.append("\n  ").append(pad("Taste", kb)).append("  ").append(pad("Befehl", bb)).append("  Beschreibung\n");
            b.append("  ").append("-".repeat(kb)).append("  ").append("-".repeat(bb)).append("  ").append("-".repeat(50)).append("\n");
            for (String z : zeilen) b.append(z).append("\n");
        }
        if (!nurMode) {
            // Befehle ohne Taste: in der Befehlszeile (:) aufrufbar oder in keymap.config belegbar
            List<String> frei = new ArrayList<>();
            TreeSet<String> alle = new TreeSet<>(alleBefehle);
            alle.addAll(mp.funs.keySet());
            for (String n : alle) {
                if (belegt.contains(n)) continue;
                String z = "  " + pad(n, kb + bb + 2) + "  " + beschreibung(n, mp);
                if (fk.isEmpty() || z.toLowerCase().contains(fk)) frei.add(z);
            }
            if (!frei.isEmpty()) {
                b.append("\n==== Befehle ohne Taste (in der Befehlszeile : aufrufbar, in keymap.config belegbar) ").append("=".repeat(10)).append("\n\n");
                for (String z : frei) b.append(z).append("\n");
            }
        }
        return b.toString();
    }

    private static String kette(Keymap km, String eltern) {
        StringBuilder b = new StringBuilder(eltern);
        Keymap.Mode m = km.modes.get(eltern);
        for (int i = 0; m != null && !m.eltern.isEmpty() && i < 16; i++) {
            b.append(" -> ").append(m.eltern);
            m = km.modes.get(m.eltern);
        }
        return b.toString();
    }

    private static List<String> umbrechen(String t, int breite) {
        List<String> r = new ArrayList<>();
        StringBuilder z = new StringBuilder();
        for (String w : t.split(" ")) {
            if (z.length() > 0 && z.length() + 1 + w.length() > breite) {
                r.add(z.toString());
                z.setLength(0);
            }
            if (z.length() > 0) z.append(' ');
            z.append(w);
        }
        if (z.length() > 0) r.add(z.toString());
        return r;
    }

    private static String pad(String s, int n) {
        return s.length() >= n ? s : s + " ".repeat(n - s.length());
    }
}
