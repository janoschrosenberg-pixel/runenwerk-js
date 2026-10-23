package runen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mapping-Funktionen aus *.map im Mapping-Ordner. Jede Funktion ist eine Folge von Befehlen
 * des Editors (oder anderer Mapping-Funktionen) und laesst sich in keymap.config belegen:
 *
 * <pre>
 * // Zeile auskommentieren
 * fun kommentar() {
 *   firstNonBlank()
 *   insertText("// ")
 * }
 * fun stempel(text:str) { insertText(text); message("Stempel gesetzt") }
 * </pre>
 *
 * Argumente sind Texte in "", Zahlen oder Parameter der Funktion.
 */
final class Mapping {
    record Arg(String text, String param) {}

    record Aufruf(String name, List<Arg> args, int zeile) {}

    /** text: die Kommentarzeilen (//) direkt ueber "fun", fuer die Uebersicht (bindings) */
    record Fun(String name, List<String> params, List<Aufruf> rumpf, String datei, String text) {}

    final Map<String, Fun> funs = new HashMap<>();
    final List<String> fehler = new ArrayList<>();

    void laden(Path ordner) {
        funs.clear();
        fehler.clear();
        if (!Files.isDirectory(ordner)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(ordner, "*.map")) {
            for (Path p : ds) {
                try {
                    lesen(p.getFileName().toString(), Files.readString(p, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    fehler.add(p + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            fehler.add(ordner + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- Lesen
    private String s, datei;
    private int i, zeile;

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\n') zeile++;
            if (c <= ' ') {
                i++;
            } else if (s.startsWith("//", i) || c == '#') {
                while (i < s.length() && s.charAt(i) != '\n') i++;
            } else {
                break;
            }
        }
    }

    private String name() {
        ws();
        int a = i;
        while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '!')) i++;
        if (a == i) throw new IllegalStateException("Name erwartet");
        return s.substring(a, i);
    }

    private void zeichen(char c) {
        ws();
        if (i >= s.length() || s.charAt(i) != c) throw new IllegalStateException("'" + c + "' erwartet");
        i++;
    }

    private boolean ist(char c) {
        ws();
        return i < s.length() && s.charAt(i) == c;
    }

    void lesen(String datei, String text) {
        this.s = text;
        this.datei = datei;
        i = 0;
        zeile = 1;
        try {
            while (true) {
                ws();
                if (i >= s.length()) break;
                String doku = kommentarDavor(i);
                if (!name().equals("fun")) throw new IllegalStateException("fun erwartet");
                String n = name();
                List<String> params = new ArrayList<>();
                zeichen('(');
                while (!ist(')')) {
                    params.add(name());
                    if (ist(':')) {
                        i++;
                        name(); // Typ wie in mila, hier egal
                    }
                    if (ist(',')) i++;
                }
                i++;
                zeichen('{');
                List<Aufruf> rumpf = new ArrayList<>();
                while (!ist('}')) {
                    int z = zeile;
                    String b = name();
                    List<Arg> args = new ArrayList<>();
                    zeichen('(');
                    while (!ist(')')) {
                        if (ist('"')) {
                            args.add(new Arg(textLesen(), null));
                        } else {
                            int a = i;
                            while (i < s.length() && ",) \t\n".indexOf(s.charAt(i)) < 0) i++;
                            String w = s.substring(a, i);
                            args.add(params.contains(w) ? new Arg(null, w) : new Arg(w, null));
                        }
                        if (ist(',')) i++;
                    }
                    i++;
                    if (ist(';')) i++;
                    rumpf.add(new Aufruf(b, args, z));
                }
                i++;
                funs.put(n, new Fun(n, params, rumpf, datei, doku));
            }
        } catch (RuntimeException e) {
            fehler.add(datei + ":" + zeile + ": " + e.getMessage());
        }
    }

    /** zusammenhaengende //-Zeilen direkt vor pos */
    private String kommentarDavor(int pos) {
        List<String> z = new ArrayList<>();
        int e = s.lastIndexOf('\n', pos - 1);
        while (e > 0) {
            int a = s.lastIndexOf('\n', e - 1);
            String zeile = s.substring(a + 1, e).strip();
            if (!zeile.startsWith("//")) break;
            z.add(0, zeile.substring(2).strip());
            e = a;
        }
        return String.join(" ", z);
    }

    private String textLesen() {
        i++;
        StringBuilder b = new StringBuilder();
        while (i < s.length() && s.charAt(i) != '"') {
            char c = s.charAt(i++);
            if (c == '\\' && i < s.length()) {
                char e = s.charAt(i++);
                b.append(switch (e) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    default -> e;
                });
            } else {
                b.append(c);
            }
        }
        i++;
        return b.toString();
    }

    // ---------------------------------------------------------------- Ausfuehren
    interface Ausfuehrer {
        void befehl(String name, String arg);
    }

    /** Argumentzeile auf die Parameter verteilen: der letzte bekommt den Rest */
    static Map<String, String> verteilen(Fun f, String arg) {
        Map<String, String> werte = new HashMap<>();
        String rest = arg == null ? "" : arg.trim();
        for (int k = 0; k < f.params().size(); k++) {
            if (k == f.params().size() - 1) {
                werte.put(f.params().get(k), rest);
            } else {
                int sp = rest.indexOf(' ');
                werte.put(f.params().get(k), sp < 0 ? rest : rest.substring(0, sp));
                rest = sp < 0 ? "" : rest.substring(sp + 1).trim();
            }
        }
        return werte;
    }

    void ausfuehren(Fun f, String arg, Ausfuehrer a) {
        Map<String, String> werte = verteilen(f, arg);
        for (Aufruf c : f.rumpf()) {
            StringBuilder b = new StringBuilder();
            for (Arg x : c.args()) {
                if (b.length() > 0) b.append(' ');
                b.append(x.param() != null ? werte.getOrDefault(x.param(), "") : x.text());
            }
            a.befehl(c.name(), b.toString());
        }
    }
}
