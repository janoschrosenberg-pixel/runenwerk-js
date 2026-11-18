package runen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.SwingUtilities;

/**
 * Dateien anlegen (Leertaste n): neben der aktuellen Datei, in einem Ordner des Projekts (Auswahlliste mit
 * Filter), Java-Typen wie in IntelliJ (Klasse, Interface, Record, Enum, Annotation, Exception, mit Paket),
 * React-Komponenten und fehlende React-Komponenten aus der JSX-Stelle unter dem Cursor
 * (Props mit Typen aus Literalen und dem Language Server, Import in der aktuellen Datei).
 */
final class Anlegen {
    private final Editor ed;

    Anlegen(Editor ed) {
        this.ed = ed;
    }

    void befehle(Map<String, Editor.Cmd> c) {
        /** @binding Neue Datei neben der aktuellen: fragt den Namen (auch mit Unterordner), legt sie an und oeffnet sie */
        c.put("fileNewHere", a -> dateiHier());
        /** @binding Neue Datei in einem Ordner des Projekts: Ordner aus einer Liste waehlen (tippen filtert), dann der Name */
        c.put("fileNewIn", a -> dateiIn());
        /** @binding Java-Typ anlegen: Klasse, Interface, Record, Enum, Annotation, Exception, abstrakte Klasse (mit Paket) */
        c.put("javaNew", a -> javaNeu());
        /** @binding React-Komponente anlegen: Ordner waehlen, Name, Datei mit Import und Rumpf */
        c.put("reactNew", a -> reactNeu());
        /** @binding Fehlende React-Komponente aus dem JSX-Tag unter dem Cursor anlegen (Props mit Typen) und importieren */
        c.put("reactFromUse", a -> ausVerwendung());
    }

    // ================================================================ Ordner
    /** Ordner der aktuellen Datei (sonst Arbeitsordner) */
    private Path hier() {
        Doc d = ed.d();
        if (d.pfad != null) return d.pfad.getParent();
        if (d.term != null && d.term.ordner() != null) return d.term.ordner();
        return projekt();
    }

    /** Projekt der aktuellen Datei: ihr Arbeitsbereich, sonst der TS-, sonst der Java-Arbeitsbereich */
    private Path projekt() {
        Doc d = ed.d();
        Path b = ed.bereichVon(d.pfad);
        if (b != null) return b;
        return ed.arbeitsordner != null ? ed.arbeitsordner : ed.javaOrdner;
    }

    private static final Set<String> AUSLASSEN = Set.of("node_modules", "target", "build", "dist", "out", "coverage", ".git", ".idea", ".vscode",
            ".gradle", ".next", ".cache", "__pycache__", "bin", ".angular", ".turbo");

    /** Ordner unter wurzel (relativ, mit / am Ende, "./" fuer die Wurzel); versteckte und Build-Ordner nicht */
    static List<String> ordner(Path wurzel) {
        List<String> r = new ArrayList<>();
        r.add("./");
        try {
            Files.walkFileTree(wurzel, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes at) {
                    if (dir.equals(wurzel)) return FileVisitResult.CONTINUE;
                    String n = dir.getFileName().toString();
                    if (AUSLASSEN.contains(n) || n.startsWith(".")) return FileVisitResult.SKIP_SUBTREE;
                    r.add(wurzel.relativize(dir).toString().replace('\\', '/') + "/");
                    return r.size() > 20000 ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // was gelesen ist, reicht
        }
        return r;
    }

    /**
     * Ordner waehlen: Liste aller Ordner des Projekts, der Ordner der aktuellen Datei vorgewaehlt (ENTER nimmt ihn),
     * tippen filtert; ein neuer Name bietet "+ neuer Ordner" an. filter (oder null) schraenkt die Liste ein.
     */
    void ordnerWaehlen(String titel, java.util.function.Predicate<String> filter, Consumer<Path> weiter) {
        ordnerWaehlen(projekt(), titel, filter, weiter);
    }

    /** wie oben im Projekt w; laesst der Filter nichts uebrig, kommen alle Ordner */
    void ordnerWaehlen(Path w, String titel, java.util.function.Predicate<String> filter, Consumer<Path> weiter) {
        if (w == null) {
            ed.fehler("kein Projekt (ts.workspace oder java.workspace in config.txt)");
            return;
        }
        Path h = hier();
        ed.meldung = "Ordner werden gelesen ...";
        Thread t = new Thread(() -> {
            List<String> l = ordner(w);
            SwingUtilities.invokeLater(() -> {
                ed.meldung = "Ordner waehlen: tippen filtert, ENTER nimmt ihn, ein neuer Name legt ihn an";
                List<String> z = new ArrayList<>();
                String vor = null;
                if (h != null && h.startsWith(w)) {
                    vor = h.equals(w) ? "./" : w.relativize(h).toString().replace('\\', '/') + "/";
                    z.add(vor);
                }
                for (String o : l) if (!o.equals(vor) && (filter == null || filter.test(o))) z.add(o);
                if (z.size() <= (vor == null ? 0 : 1)) for (String o : l) if (!z.contains(o)) z.add(o);
                ed.wz.auswahl(titel + " (" + Editor.name(w) + ")", z, vor, o -> weiter.accept(w.resolve(o).normalize()), "+ neuer Ordner: ", n -> {
                    Path p;
                    try {
                        p = w.resolve(n.replace('\\', '/')).normalize();
                    } catch (InvalidPathException e) {
                        ed.fehler("kein gueltiger Ordner: " + n);
                        return;
                    }
                    if (!p.startsWith(w)) {
                        ed.fehler("Ordner liegt nicht im Projekt: " + n);
                        return;
                    }
                    weiter.accept(p);
                });
                ed.neuMalen.run();
            });
        }, "anlegen");
        t.setDaemon(true);
        t.start();
    }

    // ================================================================ Datei
    private void dateiHier() {
        Path h = hier();
        if (h == null) {
            ed.fehler("kein Ordner: erst eine Datei oeffnen oder einen Arbeitsordner setzen");
            return;
        }
        nameFragen(h, "");
    }

    private void dateiIn() {
        ordnerWaehlen("Neue Datei in", null, o -> nameFragen(o, ""));
    }

    private void nameFragen(Path ordner, String vorgabe) {
        ed.popupFrage("Neue Datei in " + ed.relativ(ordner) + "/ - Name (auch unter/ordner/name.ext):", vorgabe, n -> {
            Path p = ziel(ordner, n);
            if (p == null) return;
            anlegenUndOeffnen(p, vorlage(p), -1);
        });
    }

    /** Pfad fuer den eingegebenen Namen (null mit Meldung) */
    private Path ziel(Path ordner, String n) {
        n = n.strip();
        if (n.isEmpty()) {
            ed.meldung = "abgebrochen (kein Name)";
            return null;
        }
        try {
            Path p = ordner.resolve(n.replace('\\', '/')).normalize();
            if (Files.exists(p)) {
                ed.fehler("gibt es schon: " + ed.relativ(p));
                ed.oeffnen(p);
                return null;
            }
            return p;
        } catch (InvalidPathException e) {
            ed.fehler("kein gueltiger Dateiname: " + n);
            return null;
        }
    }

    /** Inhalt neuer Dateien nach Endung: Java-Klasse mit Paket, sonst leer */
    private String vorlage(Path p) {
        String n = p.getFileName().toString();
        if (n.endsWith(".java")) return javaText("Klasse", paket(p.getParent()), n.substring(0, n.length() - 5));
        if ((n.endsWith(".tsx") || n.endsWith(".jsx")) && Character.isUpperCase(n.charAt(0))) {
            String k = n.substring(0, n.lastIndexOf('.'));
            return reactText(k, n.endsWith(".tsx"), new LinkedHashMap<>(), List.of(), false);
        }
        return "";
    }

    /** schreibt die Datei (mit Ordnern), oeffnet sie; cursor &lt; 0: an das Ende der ersten Rumpfzeile */
    Doc anlegenUndOeffnen(Path p, String inhalt, int cursor) {
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.writeString(p, inhalt, StandardCharsets.UTF_8);
        } catch (IOException e) {
            ed.fehler("nicht angelegt: " + p + ": " + e.getMessage());
            return null;
        }
        ed.springMerken();
        Doc d = ed.oeffnen(p);
        if (d == null) return null;
        int c = cursor >= 0 ? cursor : rumpf(inhalt);
        d.cursor = Math.min(Math.max(0, c), d.t.length());
        ed.index.neuEinlesen();
        ed.meldung = "angelegt: " + ed.relativ(p);
        return d;
    }

    /** Stelle fuer den Cursor: hinter der ersten {-Zeile des Typs bzw. der Funktion, sonst 0 */
    private static int rumpf(String s) {
        Matcher m = Pattern.compile("(?m)^(?:export |public |const ).*\\{\\s*$").matcher(s);
        if (!m.find()) return 0;
        return m.end();
    }

    // ================================================================ Java
    static final String[] JAVA_ARTEN = {"[c] Klasse", "[i] Interface", "[r] Record", "[e] Enum", "[a] Annotation", "[x] Exception", "[A] abstrakte Klasse"};

    private void javaNeu() {
        ed.wz.auswahlTasten("Java: was anlegen?", List.of(JAVA_ARTEN), w -> {
            String art = w.substring(4);
            // Java-Projekt: das der aktuellen Datei, sonst java.workspace; nur Quellordner (src/main/java/...), wenn es sie gibt
            Path wurzel = ed.d().pfad != null && ed.bereichVon(ed.d().pfad) != null && ed.d().pfad.toString().endsWith(".java") ? ed.bereichVon(ed.d().pfad)
                    : ed.javaOrdner != null ? ed.javaOrdner : projekt();
            ordnerWaehlen(wurzel, art + " anlegen in", o -> o.matches("(.*/)?src/[\\w.-]+/(java|kotlin)/.*"), o -> {
                String pk = paket(o);
                ed.popupFrage(art + " in " + (pk.isEmpty() ? ed.relativ(o) : "Paket " + pk) + " - Name:", "", n -> {
                    n = n.strip();
                    if (n.endsWith(".java")) n = n.substring(0, n.length() - 5);
                    if (!n.matches("[A-Za-z_$][\\w$]*")) {
                        ed.fehler(n.isEmpty() ? "abgebrochen (kein Name)" : "kein gueltiger Java-Name: " + n);
                        return;
                    }
                    Path p = ziel(o, n + ".java");
                    if (p != null) anlegenUndOeffnen(p, javaText(art, pk, n), -1);
                });
            });
        });
    }

    /**
     * Paket eines Ordners: hinter src/main/java, src/test/java (und anderen .../java-Wurzeln unter src),
     * sonst aus einer Java-Datei im Ordner, sonst leer
     */
    static String paket(Path ordner) {
        String s = ordner.toString().replace('\\', '/') + "/";
        Matcher m = Pattern.compile("/src/(?:[\\w.-]+/)?(?:java|kotlin)/(.*)$").matcher(s);
        if (m.find()) return m.group(1).replaceAll("/$", "").replace('/', '.');
        try (var l = Files.list(ordner)) {
            for (Path p : (Iterable<Path>) l::iterator) {
                if (!p.toString().endsWith(".java")) continue;
                Matcher pm = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(Files.readString(p, StandardCharsets.UTF_8));
                if (pm.find()) return pm.group(1);
            }
        } catch (IOException | RuntimeException e) {
            // kein Paket bekannt
        }
        return "";
    }

    static String javaText(String art, String paket, String name) {
        StringBuilder b = new StringBuilder();
        if (!paket.isEmpty()) b.append("package ").append(paket).append(";\n\n");
        switch (art) {
            case "Interface" -> b.append("public interface ").append(name).append(" {\n}\n");
            case "Record" -> b.append("public record ").append(name).append("() {\n}\n");
            case "Enum" -> b.append("public enum ").append(name).append(" {\n}\n");
            case "Annotation" -> b.append("import java.lang.annotation.ElementType;\nimport java.lang.annotation.Retention;\nimport java.lang.annotation.RetentionPolicy;\n")
                    .append("import java.lang.annotation.Target;\n\n@Retention(RetentionPolicy.RUNTIME)\n@Target(ElementType.TYPE)\npublic @interface ").append(name)
                    .append(" {\n}\n");
            case "Exception" -> b.append("public class ").append(name).append(" extends RuntimeException {\n\n    public ").append(name)
                    .append("(String message) {\n        super(message);\n    }\n\n    public ").append(name)
                    .append("(String message, Throwable cause) {\n        super(message, cause);\n    }\n}\n");
            case "abstrakte Klasse" -> b.append("public abstract class ").append(name).append(" {\n}\n");
            default -> b.append("public class ").append(name).append(" {\n}\n");
        }
        return b.toString();
    }

    // ================================================================ React
    /** TypeScript im Projekt (tsconfig.json) bzw. die aktuelle Datei ist TS */
    private boolean ts() {
        Doc d = ed.d();
        String n = d.pfad == null ? "" : d.pfad.getFileName().toString();
        if (n.endsWith(".tsx") || n.endsWith(".ts")) return true;
        if (n.endsWith(".jsx") || n.endsWith(".js")) return false;
        Path w = projekt();
        return w != null && Files.exists(w.resolve("tsconfig.json"));
    }

    private void reactNeu() {
        boolean ts = ts();
        ordnerWaehlen("React-Komponente anlegen in", null, o -> ed.popupFrage("React-Komponente in " + ed.relativ(o) + "/ - Name:", "", n -> {
            n = n.strip().replaceAll("\\.(tsx|jsx)$", "");
            if (!n.matches("[A-Za-z_$][\\w$]*")) {
                ed.fehler(n.isEmpty() ? "abgebrochen (kein Name)" : "kein gueltiger Komponentenname: " + n);
                return;
            }
            n = Character.toUpperCase(n.charAt(0)) + n.substring(1);
            Path p = ziel(o, n + (ts ? ".tsx" : ".jsx"));
            if (p != null) anlegenUndOeffnen(p, reactText(n, ts, new LinkedHashMap<>(), List.of(), false), -1);
        }));
    }

    /**
     * Komponente als Funktion mit Default-Export; props: Name -> Typ (Reihenfolge bleibt), importe: Zeilen fuer
     * Typen aus anderen Dateien, kinder: children?: React.ReactNode dazu
     */
    static String reactText(String name, boolean ts, Map<String, String> props, List<String> importe, boolean kinder) {
        StringBuilder b = new StringBuilder("import React from 'react';\n");
        for (String i : importe) b.append(i).append('\n');
        b.append('\n');
        List<String> namen = new ArrayList<>();
        for (String p : props.keySet()) if (p.matches("[A-Za-z_$][\\w$]*")) namen.add(p);
        if (kinder && !namen.contains("children")) namen.add("children");
        if (ts) {
            b.append("export interface ").append(name).append("Props {\n");
            for (Map.Entry<String, String> e : props.entrySet()) {
                String k = e.getKey().matches("[A-Za-z_$][\\w$]*") ? e.getKey() : "'" + e.getKey() + "'";
                b.append("  ").append(k).append(": ").append(e.getValue()).append(";\n");
            }
            if (kinder && !props.containsKey("children")) b.append("  children?: React.ReactNode;\n");
            b.append("}\n\n");
        }
        String args = namen.isEmpty() ? "props" : "{ " + String.join(", ", namen) + " }";
        b.append("export default function ").append(name).append("(").append(args).append(ts ? ": " + name + "Props" : "").append(") {\n");
        b.append("  return (\n    <div>\n");
        if (kinder) b.append("      {children}\n");
        b.append("    </div>\n  );\n}\n");
        return b.toString();
    }

    // ---------------------------------------------------------------- aus der Verwendung
    /** JSX-Tag: Name, Attribute (Name -> Wert wie geschrieben, null ohne Wert), Anfang des Werts, selbstschliessend */
    record Tag(String name, Map<String, String> attr, Map<String, Integer> stelle, boolean leer) {}

    /** JSX-Tag mit grossem Anfangsbuchstaben um p (auch auf dem schliessenden Tag), sonst null */
    static Tag tagBei(String t, int p) {
        int von = Math.max(0, p - 4000);
        for (int k = Math.min(p, t.length() - 1); k >= von; k--) {
            if (t.charAt(k) != '<' || k + 1 >= t.length()) continue;
            char c = t.charAt(k + 1);
            if (c == '/') {
                // </Name> unter dem Cursor: oeffnenden Tag dazu suchen
                Matcher m = Pattern.compile("</([A-Z][\\w$]*)\\s*>").matcher(t).region(k, Math.min(t.length(), k + 200));
                if (m.lookingAt() && p <= m.end()) {
                    int a = t.lastIndexOf("<" + m.group(1), k);
                    while (a >= 0) {
                        Tag tg = lesen(t, a);
                        if (tg != null) return tg;
                        a = t.lastIndexOf("<" + m.group(1), a - 1);
                    }
                }
                continue;
            }
            if (!Character.isUpperCase(c)) continue;
            Tag tg = lesen(t, k);
            if (tg == null) continue;
            int ende = (Integer) tg.stelle().get("");
            if (p <= ende) return tg;
            return null;
        }
        return null;
    }

    /** Tag ab a ("<Name ...>"); stelle[""] = Ende des Tags */
    private static Tag lesen(String t, int a) {
        int i = a + 1, n = t.length();
        int s = i;
        while (i < n && (Character.isJavaIdentifierPart(t.charAt(i)))) i++;
        String name = t.substring(s, i);
        if (name.isEmpty() || i < n && t.charAt(i) == '.') return null;
        Map<String, String> attr = new LinkedHashMap<>();
        Map<String, Integer> stelle = new LinkedHashMap<>();
        while (i < n) {
            while (i < n && Character.isWhitespace(t.charAt(i))) i++;
            if (i >= n) return null;
            char c = t.charAt(i);
            if (c == '/' && i + 1 < n && t.charAt(i + 1) == '>') {
                stelle.put("", i + 2);
                return new Tag(name, attr, stelle, true);
            }
            if (c == '>') {
                stelle.put("", i + 1);
                return new Tag(name, attr, stelle, false);
            }
            if (c == '{') {
                // {...spread}
                int e = klammerEnde(t, i);
                if (e < 0) return null;
                i = e + 1;
                continue;
            }
            int ns = i;
            while (i < n && (Character.isJavaIdentifierPart(t.charAt(i)) || t.charAt(i) == '-' || t.charAt(i) == ':')) i++;
            if (i == ns) return null;
            String an = t.substring(ns, i);
            while (i < n && Character.isWhitespace(t.charAt(i))) i++;
            if (i < n && t.charAt(i) == '=') {
                i++;
                while (i < n && Character.isWhitespace(t.charAt(i))) i++;
                if (i >= n) return null;
                char q = t.charAt(i);
                if (q == '"' || q == '\'') {
                    int e = t.indexOf(q, i + 1);
                    if (e < 0) return null;
                    attr.put(an, t.substring(i, e + 1));
                    stelle.put(an, i);
                    i = e + 1;
                } else if (q == '{') {
                    int e = klammerEnde(t, i);
                    if (e < 0) return null;
                    attr.put(an, t.substring(i, e + 1));
                    stelle.put(an, i + 1);
                    i = e + 1;
                } else {
                    return null;
                }
            } else {
                attr.put(an, null);
            }
        }
        return null;
    }

    /** schliessende } zur { bei a (Strings, Template-Strings und verschachtelte Klammern beachtet), sonst -1 */
    private static int klammerEnde(String t, int a) {
        int tiefe = 0;
        for (int i = a; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                int j = i + 1;
                while (j < t.length() && t.charAt(j) != c) j += t.charAt(j) == '\\' ? 2 : 1;
                i = j;
            } else if (c == '{') {
                tiefe++;
            } else if (c == '}' && --tiefe == 0) {
                return i;
            }
        }
        return -1;
    }

    /** Typ aus dem Wert, wenn er ohne Language Server feststeht; null = fragen (Bezeichner, Aufrufe) */
    static String typAusWert(String w) {
        if (w == null) return "boolean";
        if (w.startsWith("\"") || w.startsWith("'")) return "string";
        String e = w.substring(1, w.length() - 1).strip();
        if (e.isEmpty()) return "any";
        if (e.matches("-?\\d[\\d_]*(\\.\\d+)?([eE][+-]?\\d+)?n?|-?0[xXbBoO][\\da-fA-F_]+")) return e.endsWith("n") ? "bigint" : "number";
        if (e.equals("true") || e.equals("false")) return "boolean";
        if (e.equals("null") || e.equals("undefined")) return "any";
        if (e.matches("([\"'])(?:\\\\.|(?!\\1).)*\\1") || e.startsWith("`") && e.endsWith("`")) return "string";
        if (e.startsWith("!") && !e.startsWith("!=")) return "boolean";
        if (e.startsWith("<")) return "React.ReactNode";
        if (e.matches("(?s)(async\\s+)?(\\([^()]*\\)|[\\w$]+)\\s*(:\\s*[^=]+)?=>.*")) {
            Matcher m = Pattern.compile("^(?:async\\s+)?\\(([^()]*)\\)").matcher(e);
            boolean ohne = m.find() && m.group(1).isBlank();
            return ohne ? "() => void" : "(...args: any[]) => void";
        }
        if (e.startsWith("function")) return "(...args: any[]) => void";
        if (e.startsWith("[")) {
            String in = e.substring(1, Math.max(1, e.length() - 1)).strip();
            if (in.isEmpty()) return "any[]";
            if (in.matches("[\\d.\\s,_-]+")) return "number[]";
            if (in.matches("(\\s*(\"[^\"]*\"|'[^']*')\\s*,?)+")) return "string[]";
            return "any[]";
        }
        if (e.startsWith("{")) return "Record<string, any>";
        if (e.matches(".*(===|!==|==|!=|<=|>=).*") && !e.contains("?")) return "boolean";
        if (e.matches("(?s)([\"'`]).*") && e.contains("+")) return "string";
        if (e.matches("[\\w$]+(\\??\\.[\\w$]+)*(\\([^()]*\\))?")) return null;
        return "any";
    }

    /** Typ aus dem Hover-Text des Language Servers ("const name: string", "(property) Foo.bar?: number", Funktionen) */
    static String typAusHover(String hover, boolean aufruf) {
        if (hover == null) return null;
        StringBuilder code = new StringBuilder();
        boolean drin = false, gab = false;
        for (String z : hover.split("\n")) {
            if (z.startsWith("```")) {
                if (drin) break;
                drin = true;
                gab = true;
                continue;
            }
            if (drin) code.append(z.strip()).append(' ');
        }
        String s = (gab ? code.toString() : hover.split("\n")[0]).strip().replaceAll("\\s+", " ");
        if (s.isEmpty()) return null;
        s = s.replaceFirst("^\\((?:property|parameter|method|function|local var|var|let|const|alias|getter|setter)\\)\\s*", "");
        s = s.replaceFirst("^(?:export\\s+)?(?:declare\\s+)?(?:const|let|var|readonly)\\s+", "");
        boolean fn = s.startsWith("function ");
        if (fn) s = s.substring(9);
        // Name (auch Klasse.feld) abtrennen
        Matcher m = Pattern.compile("^[\\w$]+(?:\\.[\\w$]+)*").matcher(s);
        if (!m.find()) return null;
        String rest = s.substring(m.end()).strip();
        if (rest.startsWith("<") || rest.startsWith("(")) {
            // Funktion: name<T>(a: X): R
            int a = rest.indexOf('(');
            int e = passend(rest, a);
            if (a < 0 || e < 0) return null;
            String params = rest.substring(a, e + 1);
            String nach = rest.substring(e + 1).strip();
            String ret = nach.startsWith(":") ? nach.substring(1).strip() : "void";
            if (aufruf) return ret;
            return params + " => " + ret;
        }
        if (rest.startsWith("?:")) rest = rest.substring(2).strip();
        else if (rest.startsWith(":")) rest = rest.substring(1).strip();
        else return null;
        if (rest.isEmpty()) return null;
        if (aufruf) {
            // Aufruf einer Variablen vom Funktionstyp: (a: X) => R
            Matcher r = Pattern.compile("^\\(.*\\)\\s*=>\\s*(.+)$").matcher(rest);
            return r.find() ? r.group(1).strip() : null;
        }
        return rest;
    }

    private static int passend(String s, int a) {
        if (a < 0) return -1;
        int t = 0;
        for (int i = a; i < s.length(); i++) {
            if (s.charAt(i) == '(') t++;
            else if (s.charAt(i) == ')' && --t == 0) return i;
        }
        return -1;
    }

    private static final Set<String> GLOBAL = Set.of("React", "JSX", "Array", "ReadonlyArray", "Record", "Partial", "Required", "Readonly", "Pick", "Omit",
            "Exclude", "Extract", "NonNullable", "ReturnType", "Parameters", "Promise", "Map", "Set", "WeakMap", "WeakSet", "Date", "RegExp", "Error",
            "Function", "Object", "String", "Number", "Boolean", "Symbol", "BigInt", "Event", "MouseEvent", "KeyboardEvent", "FocusEvent", "InputEvent",
            "SubmitEvent", "DragEvent", "TouchEvent", "PointerEvent", "WheelEvent", "Element", "Node", "Document", "Window", "File", "FileList", "Blob",
            "FormData", "URL", "URLSearchParams", "Response", "Request", "Headers", "Iterable", "AsyncIterable", "Awaited", "Uppercase", "Lowercase",
            "Capitalize", "Uncapitalize", "Infinity", "NaN");

    /** Typen in t, die importiert werden muessten (gross geschrieben, nicht global, nicht hinter einem Punkt, keine Eigenschaftsnamen) */
    static Set<String> fremdeTypen(String t) {
        Set<String> r = new LinkedHashSet<>();
        String ohne = t.replaceAll("\"[^\"]*\"|'[^']*'", "");
        Matcher m = Pattern.compile("(?<![\\w$.])([A-Z][\\w$]*)(?!\\s*\\??:)").matcher(ohne);
        while (m.find()) {
            String n = m.group(1);
            if (GLOBAL.contains(n) || n.matches("HTML\\w*Element|SVG\\w*Element|\\w+Event")) continue;
            r.add(n);
        }
        return r;
    }

    /** Import einer Typ-Bezeichnung aus dem Text der aktuellen Datei als neue Zeile (nur dieser Name), sonst null */
    static String importFuer(String text, String name, boolean ts) {
        Matcher m = Pattern.compile("(?s)import\\s+(type\\s+)?([^;]*?)\\s+from\\s+(['\"][^'\"]+['\"])").matcher(text);
        while (m.find()) {
            String was = m.group(2), von = m.group(3);
            Matcher n = Pattern.compile("\\{([^}]*)\\}").matcher(was);
            if (n.find()) {
                for (String teil : n.group(1).split(",")) {
                    String x = teil.strip().replaceFirst("^type\\s+", "");
                    String[] as = x.split("\\s+as\\s+");
                    String lokal = as.length > 1 ? as[1].strip() : as[0].strip();
                    if (lokal.equals(name)) return "import " + (ts ? "type " : "") + "{ " + x + " } from " + von + ";";
                }
            }
            String vorn = was.replaceAll("\\{[^}]*\\}", "").replace(",", " ").strip();
            if (vorn.equals(name)) return "import " + (ts ? "type " : "") + name + " from " + von + ";";
            if (vorn.startsWith("* as ") && vorn.substring(5).strip().equals(name)) return "import " + (ts ? "type " : "") + "* as " + name + " from " + von + ";";
        }
        return null;
    }

    /** Stelle hinter der letzten Import-Anweisung (Anfang der naechsten Zeile); ohne Importe hinter 'use client' bzw. 0 */
    static int importStelle(String t) {
        Matcher m = Pattern.compile("(?m)^import\\b[\\s\\S]*?(?:from\\s*)?['\"][^'\"\\n]+['\"]\\s*;?[ \\t]*$").matcher(t);
        int ende = -1;
        while (m.find()) ende = m.end();
        if (ende < 0) {
            Matcher u = Pattern.compile("^\\s*(['\"])use (client|server)\\1;?[ \\t]*\\n?").matcher(t);
            return u.find() ? u.end() : 0;
        }
        int nl = t.indexOf('\n', ende);
        return nl < 0 ? t.length() : nl + 1;
    }

    private void ausVerwendung() {
        Doc d = ed.d();
        if (d.pfad == null || !d.jsx()) {
            ed.fehler("nur in React-Dateien (.tsx, .jsx, .js)");
            return;
        }
        String text = d.t.toString();
        Tag tag = tagBei(text, d.cursor);
        if (tag == null) {
            ed.fehler("kein JSX-Tag mit grossem Anfangsbuchstaben unter dem Cursor (<Adresse ... />)");
            return;
        }
        String n = d.pfad.getFileName().toString();
        boolean ts = n.endsWith(".tsx") || n.endsWith(".ts");
        Path p = d.pfad.getParent().resolve(tag.name() + (ts ? ".tsx" : ".jsx"));
        String imp = "import " + tag.name() + " from './" + tag.name() + "';";
        if (Pattern.compile("(?m)^import\\s+[^;]*\\b" + Pattern.quote(tag.name()) + "\\b[^;]*from").matcher(text).find()) {
            ed.fehler(tag.name() + " wird schon importiert - gd springt hin");
            return;
        }
        if (Files.exists(p)) {
            importEinfuegen(d, imp);
            ed.meldung = tag.name() + " gab es schon (" + ed.relativ(p) + "), Import eingefuegt";
            return;
        }
        for (Indexer.Datei x : ed.index.dateien) {
            String xn = x.pfad().getFileName().toString();
            if (xn.matches(Pattern.quote(tag.name()) + "\\.(tsx|jsx|ts|js)")) {
                ed.fehler(tag.name() + " gibt es schon in " + ed.relativ(x.pfad()) + " - Leertaste c i importiert sie");
                return;
            }
        }
        Map<String, String> typen = new LinkedHashMap<>();
        List<String> fragen = new ArrayList<>();
        for (Map.Entry<String, String> e : tag.attr().entrySet()) {
            String a = e.getKey();
            if (a.equals("key") || a.equals("ref")) continue;
            String t = ts ? typAusWert(e.getValue()) : "any";
            typen.put(a, t == null ? "any" : t);
            if (t == null) fragen.add(a);
        }
        Lsp lsp = ed.lspFuer(d);
        if (!ts || fragen.isEmpty() || lsp == null || !lsp.an()) {
            fertig(d, tag, p, ts, typen, imp);
            return;
        }
        ed.meldung = "Typen werden ermittelt ...";
        ed.lspSync();
        int[] offen = {fragen.size()};
        boolean[] fertig = {false};
        Runnable ende = () -> {
            if (fertig[0]) return;
            fertig[0] = true;
            fertig(d, tag, p, ts, typen, imp);
            ed.neuMalen.run();
        };
        // nicht ewig auf den Language Server warten
        javax.swing.Timer uhr = new javax.swing.Timer(4000, ev -> ende.run());
        uhr.setRepeats(false);
        uhr.start();
        for (String a : fragen) {
            String w = tag.attr().get(a);
            int start = tag.stelle().get(a);
            String e = w.substring(1, w.length() - 1);
            int vorn = e.length() - e.stripLeading().length();
            String kern = e.strip();
            boolean aufruf = kern.endsWith(")");
            if (aufruf) kern = kern.substring(0, kern.indexOf('('));
            // Hover auf dem letzten Bezeichner (bei a.b.c auf c)
            int pos = start + vorn + kern.length() - 1;
            int z = d.zeileVon(pos);
            Map<String, Object> par = Json.obj("textDocument", Json.obj("uri", Lsp.uri(d.pfad)), "position", Json.obj("line", z, "character", pos - d.anf(z)));
            boolean auf = aufruf;
            lsp.anfrage("textDocument/hover", par, r -> SwingUtilities.invokeLater(() -> {
                String t = typAusHover(Editor.hoverText(Json.get(r, "contents")), auf);
                if (t != null && !t.isBlank() && !t.equals("any")) typen.put(a, t);
                if (--offen[0] == 0) {
                    uhr.stop();
                    ende.run();
                }
            }));
        }
    }

    private void fertig(Doc d, Tag tag, Path p, boolean ts, Map<String, String> typen, String imp) {
        // Typen aus anderen Dateien: Import aus der aktuellen Datei uebernehmen, sonst any
        String text = d.t.toString();
        List<String> importe = new ArrayList<>();
        int unbekannt = 0;
        for (Map.Entry<String, String> e : typen.entrySet()) {
            String t = e.getValue();
            List<String> neu = new ArrayList<>();
            boolean ok = true;
            for (String f : fremdeTypen(t)) {
                String i = importFuer(text, f, ts);
                if (i == null) {
                    ok = false;
                    break;
                }
                neu.add(i);
            }
            if (!ok) {
                e.setValue("any");
                unbekannt++;
                continue;
            }
            for (String i : neu) if (!importe.contains(i)) importe.add(i);
            if (t.equals("any")) unbekannt++;
        }
        String inhalt = reactText(tag.name(), ts, typen, importe, !tag.leer());
        importEinfuegen(d, imp);
        Doc neu = anlegenUndOeffnen(p, inhalt, -1);
        if (neu == null) return;
        ed.meldung = "angelegt: " + ed.relativ(p) + " mit " + typen.size() + " Props" + (unbekannt > 0 ? " (" + unbekannt + " als any)" : "")
                + ", Import in " + d.name() + " - Strg+O zurueck";
    }

    /** Import-Zeile hinter die Importe der Datei (ein Undo-Schritt) */
    static void importEinfuegen(Doc d, String zeile) {
        String t = d.t.toString();
        if (t.contains(zeile)) return;
        int s = importStelle(t);
        d.merken();
        d.einfuegen(s, zeile + "\n" + (s == 0 && !t.isEmpty() && !t.startsWith("import") ? "\n" : ""));
    }
}
