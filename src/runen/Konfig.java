package runen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * config.txt neben der Jar: Zeilen "schluessel = wert", # ist ein Kommentar. Ein Schluessel darf
 * mehrfach vorkommen (open, db.account.NAME). {@link #setzen} schreibt einen Wert zurueck und laesst
 * den Rest der Datei, wie er ist.
 */
final class Konfig {
    final Path pfad;
    private final Map<String, List<String>> werte = new LinkedHashMap<>();
    final List<String> fehler = new ArrayList<>();

    Konfig(Path pfad) {
        this.pfad = pfad;
    }

    void lesen() {
        werte.clear();
        fehler.clear();
        List<String> zeilen;
        try {
            zeilen = Files.readAllLines(pfad, StandardCharsets.UTF_8);
        } catch (IOException e) {
            fehler.add(pfad + " nicht lesbar: " + e.getMessage());
            return;
        }
        int nr = 0;
        for (String roh : zeilen) {
            nr++;
            String z = roh.strip();
            if (z.isEmpty() || z.startsWith("#")) continue;
            int g = z.indexOf('=');
            if (g <= 0) {
                fehler.add(pfad.getFileName() + ":" + nr + ": erwartet: schluessel = wert");
                continue;
            }
            String k = z.substring(0, g).strip();
            String v = z.substring(g + 1).strip();
            werte.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
    }

    String get(String k, String vorgabe) {
        List<String> l = werte.get(k);
        if (l == null || l.isEmpty() || l.get(l.size() - 1).isEmpty()) return vorgabe;
        return l.get(l.size() - 1);
    }

    List<String> alle(String k) {
        return werte.getOrDefault(k, List.of());
    }

    /** Schluessel mit diesem Anfang, in Reihenfolge der Datei */
    Map<String, String> mitAnfang(String anfang) {
        Map<String, String> r = new LinkedHashMap<>();
        for (var e : werte.entrySet()) {
            if (e.getKey().startsWith(anfang) && !e.getValue().isEmpty()) r.put(e.getKey().substring(anfang.length()), e.getValue().get(e.getValue().size() - 1));
        }
        return r;
    }

    int zahl(String k, int vorgabe) {
        try {
            return Integer.parseInt(get(k, String.valueOf(vorgabe)));
        } catch (NumberFormatException e) {
            fehler.add(pfad.getFileName() + ": " + k + " ist keine Zahl");
            return vorgabe;
        }
    }

    boolean an(String k, boolean vorgabe) {
        String v = get(k, vorgabe ? "on" : "off").toLowerCase();
        return v.equals("on") || v.equals("true") || v.equals("ja") || v.equals("yes") || v.equals("1");
    }

    /** einen Wert in der Datei setzen (vorhandene Zeile ersetzen, sonst anhaengen) */
    void setzen(String k, String v) {
        try {
            List<String> zeilen = Files.exists(pfad) ? new ArrayList<>(Files.readAllLines(pfad, StandardCharsets.UTF_8)) : new ArrayList<>();
            boolean ersetzt = false;
            for (int i = 0; i < zeilen.size(); i++) {
                String z = zeilen.get(i).strip();
                int g = z.indexOf('=');
                if (!z.startsWith("#") && g > 0 && z.substring(0, g).strip().equals(k)) {
                    zeilen.set(i, k + " = " + v);
                    ersetzt = true;
                    break;
                }
            }
            if (!ersetzt) zeilen.add(k + " = " + v);
            Files.write(pfad, zeilen, StandardCharsets.UTF_8);
        } catch (IOException e) {
            fehler.add(pfad + " nicht geschrieben: " + e.getMessage());
        }
        werte.put(k, new ArrayList<>(List.of(v)));
    }
}
