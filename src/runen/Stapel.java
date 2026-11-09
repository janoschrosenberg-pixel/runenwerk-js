package runen;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Codestellen in Ausgaben (Terminal, Logs): Stacktrace-Zeilen von Java/Kotlin
 * (at demo.Kasse.summe(Kasse.java:21)), Node/TypeScript (at fn (/pfad/x.ts:12:5), file:///...),
 * Compilerfehler (Datei.java:[12,5]) und allgemein pfad:zeile[:spalte]. gd springt dorthin,
 * wenn die Datei zum Projekt gehoert.
 */
final class Stapel {
    private Stapel() {}

    /** eine Stelle in der Zeile: Datei wie geschrieben, Klasse (nur Java-Frames, sonst null), Zeile/Spalte ab 1, Bereich in der Zeile */
    record Stelle(String datei, String klasse, int zeile, int spalte, int von, int bis) {}

    /** at paket.Klasse$Innen.methode(Datei.java:12) */
    private static final Pattern JAVA = Pattern.compile("([\\w$]+(?:\\.[\\w$]+)*)\\.[\\w$<>-]+\\(([\\w$-]+\\.(?:java|kt|kts|groovy|scala)):(\\d+)\\)");
    /** pfad:zeile[:spalte] oder pfad:[zeile,spalte] (Maven), auch in Klammern und als file:// */
    private static final Pattern PFAD = Pattern.compile(
            "(?:file://)?((?:[A-Za-z]:[\\\\/]|/|\\./|\\.\\./)?[\\w@.~+-]+(?:[\\\\/][\\w@.~+ -]*[\\w@.~+-])*\\.[A-Za-z][\\w]{0,9})(?::\\[(\\d+)(?:,(\\d+))?\\]|:(\\d+)(?::(\\d+))?|\\((\\d+),(\\d+)\\))");

    /** alle Stellen der Zeile, Java-Frames zuerst gefunden (in Zeilenreihenfolge) */
    static List<Stelle> stellen(String zeile) {
        List<Stelle> r = new ArrayList<>();
        Matcher m = JAVA.matcher(zeile);
        while (m.find()) r.add(new Stelle(m.group(2), m.group(1), Integer.parseInt(m.group(3)), 1, m.start(), m.end()));
        m = PFAD.matcher(zeile);
        while (m.find()) {
            boolean schon = false;
            for (Stelle s : r) schon |= m.start() < s.bis() && s.von() < m.end();
            if (schon) continue;
            String z = m.group(2) != null ? m.group(2) : m.group(4) != null ? m.group(4) : m.group(6);
            String sp = m.group(3) != null ? m.group(3) : m.group(5) != null ? m.group(5) : m.group(7);
            if (z == null) continue;
            // Uhrzeiten und Versionen (12:30, 1.2.3:4) haben keinen Dateinamen mit Buchstaben-Endung - das prueft schon das Muster
            r.add(new Stelle(m.group(1), null, Integer.parseInt(z), sp == null ? 1 : Integer.parseInt(sp), m.start(), m.end()));
        }
        r.sort((a, b) -> a.von() - b.von());
        return r;
    }

    /** die Stelle unter dem Cursor (Spalte in der Zeile), sonst die erste lokale der Zeile */
    static Stelle wahl(List<Stelle> l, int spalte) {
        for (Stelle s : l) if (spalte >= s.von() && spalte < s.bis()) return s;
        return l.isEmpty() ? null : l.get(0);
    }

    /**
     * Dateien im Projekt zur Stelle: Java-Frame ueber Paket und Dateinamen (paket/Datei.java),
     * Pfade absolut (wenn vorhanden und nicht in node_modules) oder als Pfadende im Index bzw. relativ
     * zum Ordner. Leer = nicht im Projekt (JDK, Bibliotheken).
     */
    static List<Path> dateien(Stelle s, List<Indexer.Datei> index, Path ordner) {
        List<Path> r = new ArrayList<>();
        String datei = s.datei().replace('\\', '/');
        if (s.klasse() != null) {
            String k = s.klasse();
            int d = k.lastIndexOf('.');
            String paket = d < 0 ? "" : k.substring(0, d);
            // Klasse in einer Klasse (a.b.Aussen.Innen ohne $): Paket ist kleingeschrieben, Klassen gross
            while (!paket.isEmpty()) {
                int e = paket.lastIndexOf('.');
                String letzt = paket.substring(e + 1);
                if (letzt.isEmpty() || !Character.isUpperCase(letzt.charAt(0))) break;
                paket = e < 0 ? "" : paket.substring(0, e);
            }
            String ende = (paket.isEmpty() ? "" : paket.replace('.', '/') + "/") + datei;
            for (Indexer.Datei x : index) if (("/" + x.rel().replace('\\', '/')).endsWith("/" + ende)) r.add(x.pfad());
            return r;
        }
        if (datei.contains("/node_modules/") || datei.startsWith("node_modules/") || datei.startsWith("node:") || datei.startsWith("internal/")) return r;
        try {
            Path p = Path.of(datei);
            if (p.isAbsolute()) {
                if (Files.isRegularFile(p)) r.add(p.normalize());
                if (!r.isEmpty()) return r;
            } else if (ordner != null && Files.isRegularFile(ordner.resolve(p))) {
                r.add(ordner.resolve(p).normalize());
                return r;
            }
        } catch (InvalidPathException e) {
            return r;
        }
        // Pfadende im Index (Ausgabe aus einem anderen Ordner, Container-Pfade, Source Maps)
        String rest = datei.replaceFirst("^(?:[A-Za-z]:)?/+", "").replaceFirst("^(?:\\.\\.?/)+", "");
        while (!rest.isEmpty()) {
            for (Indexer.Datei x : index) if (("/" + x.rel().replace('\\', '/')).endsWith("/" + rest)) r.add(x.pfad());
            if (!r.isEmpty() || !rest.contains("/")) return r;
            rest = rest.substring(rest.indexOf('/') + 1);
        }
        return r;
    }
}
