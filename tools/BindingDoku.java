import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Laeuft beim Bauen (build.sh / build.bat): liest aus den Quellen das Javadoc mit dem Tag @binding,
 * das direkt ueber jeder Befehlsregistrierung steht (reg("name", ...) bzw. c.put("name", ...)), und
 * schreibt die Beschreibungen als "name TAB text" in eine Datei, die mit in die Jar kommt. Mit
 * "@binding-arg ARG = Text" bekommen einzelne Argumente eine eigene Beschreibung ("name ARG TAB text"). Der
 * Befehl bindings im Editor zeigt sie neben den Tasten aus keymap.config und sondertasten.config.
 *
 * Aufruf: java tools/BindingDoku.java QUELLORDNER ZIELDATEI
 */
public class BindingDoku {
    private static final Pattern REG = Pattern.compile("/\\*\\*((?:(?!\\*/|/\\*\\*).)*?)\\*/\\s*(?:reg|c\\.put)\\(\"([^\"]+)\"", Pattern.DOTALL);
    private static final Pattern OHNE = Pattern.compile("^\\s*(?:reg|c\\.put)\\(\"([^\"]+)\"", Pattern.MULTILINE);

    public static void main(String[] a) throws IOException {
        Path quellen = Path.of(a[0]), ziel = Path.of(a[1]);
        Map<String, String> doku = new TreeMap<>();
        List<String> fehlt = new ArrayList<>();
        List<Path> dateien;
        try (Stream<Path> st = Files.walk(quellen)) {
            dateien = st.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        for (Path f : dateien) {
            String t = Files.readString(f, StandardCharsets.UTF_8);
            Matcher m = REG.matcher(t);
            while (m.find()) {
                String text = tag(m.group(1));
                if (text != null) doku.put(m.group(2), text);
                for (String z : m.group(1).split("\\R")) {
                    z = z.strip();
                    if (z.startsWith("*")) z = z.substring(1).strip();
                    int g = z.indexOf(" = ");
                    if (z.startsWith("@binding-arg ") && g > 13) doku.put(m.group(2) + " " + z.substring(13, g).strip(), z.substring(g + 3).strip());
                }
            }
            Matcher o = OHNE.matcher(t);
            while (o.find()) if (!doku.containsKey(o.group(1))) fehlt.add(f.getFileName() + ": " + o.group(1));
        }
        StringBuilder b = new StringBuilder("# erzeugt beim Bauen aus den @binding-Javadocs (tools/BindingDoku.java)\n");
        for (var e : doku.entrySet()) b.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        Files.createDirectories(ziel.getParent());
        Files.writeString(ziel, b.toString(), StandardCharsets.UTF_8);
        System.out.println("Befehlsbeschreibungen: " + doku.size() + " -> " + ziel);
        for (String f : fehlt) System.out.println("  ohne @binding: " + f);
    }

    /** Text hinter @binding bis zum naechsten Tag, ohne die * am Zeilenanfang */
    private static String tag(String kommentar) {
        StringBuilder b = new StringBuilder();
        boolean drin = false;
        for (String z : kommentar.split("\\R")) {
            z = z.strip();
            if (z.startsWith("*")) z = z.substring(1).strip();
            if (z.startsWith("@binding") && !z.startsWith("@binding-arg")) {
                drin = true;
                z = z.substring(8).strip();
            } else if (z.startsWith("@")) {
                drin = false;
            }
            if (drin && !z.isEmpty()) b.append(b.length() > 0 ? " " : "").append(z);
        }
        return drin || b.length() > 0 ? b.toString() : null;
    }
}
