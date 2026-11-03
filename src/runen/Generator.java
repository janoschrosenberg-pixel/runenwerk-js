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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Code-Generatoren in den pom.xml (openapi-generator-maven-plugin, swagger-codegen-maven-plugin):
 * Spezifikation (inputSpec), Ausgabeordner und der Ordner mit den erzeugten Java-Quellen. Damit
 * indiziert der Editor die erzeugten DTOs und Interfaces, obwohl target sonst ausgenommen ist.
 */
final class Generator {
    /**
     * Eine Ausfuehrung des Plugins: modul = Ordner der pom.xml, spec = inputSpec (null bei URL oder
     * Klassenpfad), ausgabe = output, quellen = output/sourceFolder; ignoriert = die pom.xml schaltet das
     * Plugin fuer Eclipse/m2e (lifecycle-mapping) ab, dann kennt jdtls die Quellen nicht.
     */
    record Ausgabe(Path modul, Path pom, String id, Path spec, Path ausgabe, Path quellen, boolean ignoriert) {}

    static final Set<String> PLUGINS = Set.of("openapi-generator-maven-plugin", "swagger-codegen-maven-plugin");
    private static final Set<String> NICHT = Set.of("target", "node_modules", ".git", ".idea", ".gradle", "build", "out", "bin");

    /** alle Generatoren unter wurzel (pom.xml bis 8 Ebenen tief, ohne target, node_modules ...) */
    static List<Ausgabe> finden(Path wurzel) {
        List<Ausgabe> r = new ArrayList<>();
        if (wurzel == null) return r;
        try {
            Files.walkFileTree(wurzel, Set.of(), 8, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                    return !d.equals(wurzel) && NICHT.contains(Editor.name(d)) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    if (Editor.name(f).equals("pom.xml")) r.addAll(lesen(f, wurzel));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // halb gesucht ist besser als nichts
        }
        return r;
    }

    /** Generatoren einer pom.xml; Konfiguration aus pluginManagement auch aus den Eltern-pom.xml bis wurzel */
    static List<Ausgabe> lesen(Path pom, Path wurzel) {
        List<Ausgabe> r = new ArrayList<>();
        String xml = text(pom);
        if (xml == null || PLUGINS.stream().noneMatch(xml::contains)) return r;
        Path modul = pom.getParent();
        Map<String, String> props = eigenschaften(pom, wurzel);
        String build = block(xml.replaceAll("(?s)<profiles>.*?</profiles>", ""), "build");
        String bauOrdner = build == null ? null : wert(build.replaceAll("(?s)<(resources|testResources|plugins|pluginManagement|extensions)>.*?</\\1>", ""), "directory");
        props.put("project.build.directory", bauOrdner == null ? modul.resolve("target").toString() : ersetzen(bauOrdner, props, modul));
        boolean ignoriert = m2eIgnoriert(pom, wurzel);
        // pluginManagement herausnehmen: dort steht nur Vorgabe-Konfiguration
        String ohneVerwaltung = xml.replaceAll("(?s)<pluginManagement>.*?</pluginManagement>", "");
        Matcher pm = Pattern.compile("(?s)<plugin>(.*?)</plugin>").matcher(ohneVerwaltung);
        while (pm.find()) {
            String p = pm.group(1);
            String art = wert(p, "artifactId");
            if (art == null || !PLUGINS.contains(art)) continue;
            String verwaltet = verwaltung(pom, wurzel, art);
            String exe = block(p, "executions");
            String pluginKonf = block(exe == null ? p : p.replace(exe, ""), "configuration");
            List<String[]> laeufe = new ArrayList<>();
            if (exe != null) {
                Matcher em = Pattern.compile("(?s)<execution>(.*?)</execution>").matcher(exe);
                while (em.find()) laeufe.add(new String[] {wert(em.group(1), "id"), block(em.group(1), "configuration")});
            }
            if (laeufe.isEmpty()) laeufe.add(new String[] {null, null});
            for (String[] l : laeufe) {
                String[] konf = {l[1], pluginKonf, verwaltet};
                if ("true".equals(ersetzen(erster(konf, "skip"), props, modul))) continue;
                String spec = erster(konf, "inputSpec");
                String out = erster(konf, "output");
                String folder = erster(konf, "sourceFolder");
                if (out == null) out = "${project.build.directory}/generated-sources/" + (art.startsWith("swagger") ? "swagger" : "openapi");
                if (folder == null) folder = "src/main/java";
                Path ausgabe = pfad(modul, ersetzen(out, props, modul));
                if (ausgabe == null) continue;
                Path quellen = pfad(ausgabe, ersetzen(folder, props, modul));
                Path sp = spec == null ? null : pfad(modul, ersetzen(spec, props, modul));
                if (sp != null && !Files.isRegularFile(sp)) sp = null;
                r.add(new Ausgabe(modul, pom, l[0] == null ? "default" : l[0], sp, ausgabe, quellen, ignoriert));
            }
        }
        return r;
    }

    private static String text(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Inhalt des ersten Elements name (ohne Verschachtelung gleichen Namens) */
    static String block(String xml, String name) {
        Matcher m = Pattern.compile("(?s)<" + name + "(?:\\s[^>]*)?>(.*?)</" + name + ">").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    static String wert(String xml, String name) {
        String b = block(xml, name);
        return b == null ? null : b.strip();
    }

    /** erster Wert aus den Konfigurationen (Ausfuehrung, Plugin, pluginManagement); auch in configOptions */
    private static String erster(String[] konf, String name) {
        for (String k : konf) {
            if (k == null) continue;
            String w = wert(k, name);
            if (w != null && !w.isEmpty()) return w;
        }
        return null;
    }

    /** properties der pom.xml und ihrer Eltern (naeher gewinnt), dazu project.basedir */
    private static Map<String, String> eigenschaften(Path pom, Path wurzel) {
        Map<String, String> r = new HashMap<>();
        List<Path> kette = kette(pom, wurzel);
        for (int i = kette.size() - 1; i >= 0; i--) {
            String x = text(kette.get(i));
            if (x == null) continue;
            String pr = block(x.replaceAll("(?s)<profiles>.*?</profiles>", ""), "properties");
            if (pr == null) continue;
            Matcher m = Pattern.compile("(?s)<([\\w.-]+)>(.*?)</\\1>").matcher(pr);
            while (m.find()) r.put(m.group(1), m.group(2).strip());
        }
        String x = text(pom);
        String ohneEltern = x == null ? "" : x.replaceAll("(?s)<parent>.*?</parent>", "").replaceAll("(?s)<(dependencies|build|profiles|dependencyManagement)>.*", "");
        String art = wert(ohneEltern, "artifactId");
        if (art != null) r.put("project.artifactId", art);
        String v = wert(ohneEltern, "version");
        if (v != null) r.put("project.version", v);
        return r;
    }

    /** pom.xml und die Eltern-pom.xml in den Ordnern darueber (bis wurzel) */
    private static List<Path> kette(Path pom, Path wurzel) {
        List<Path> r = new ArrayList<>();
        r.add(pom);
        Path d = pom.getParent().getParent();
        while (d != null && wurzel != null && d.startsWith(wurzel)) {
            if (Files.isRegularFile(d.resolve("pom.xml"))) r.add(d.resolve("pom.xml"));
            d = d.getParent();
        }
        return r;
    }

    /** Konfiguration des Plugins aus pluginManagement (diese pom.xml oder die Eltern) */
    private static String verwaltung(Path pom, Path wurzel, String art) {
        for (Path p : kette(pom, wurzel)) {
            String x = text(p);
            String v = x == null ? null : block(x, "pluginManagement");
            if (v == null) continue;
            Matcher m = Pattern.compile("(?s)<plugin>(.*?)</plugin>").matcher(v);
            while (m.find()) if (art.equals(wert(m.group(1), "artifactId"))) return m.group(1);
        }
        return null;
    }

    /** org.eclipse.m2e:lifecycle-mapping in der pom.xml (oder den Eltern) mit ignore fuer den Generator */
    private static boolean m2eIgnoriert(Path pom, Path wurzel) {
        for (Path p : kette(pom, wurzel)) {
            String x = text(p);
            if (x == null || !x.contains("lifecycle-mapping")) continue;
            Matcher m = Pattern.compile("(?s)<pluginExecution>(.*?)</pluginExecution>").matcher(x);
            while (m.find()) {
                String e = m.group(1);
                if (PLUGINS.stream().anyMatch(e::contains) && e.matches("(?s).*<action>\\s*<ignore\\s*/?>.*")) return true;
            }
        }
        return false;
    }

    /** ${...} aus den properties ersetzen; project.basedir/basedir = Ordner der pom.xml */
    private static String ersetzen(String s, Map<String, String> props, Path modul) {
        if (s == null) return null;
        for (int runde = 0; runde < 5 && s.contains("${"); runde++) {
            Matcher m = Pattern.compile("\\$\\{([^}]+)}").matcher(s);
            StringBuilder b = new StringBuilder();
            while (m.find()) {
                String k = m.group(1);
                String w = k.equals("project.basedir") || k.equals("basedir") ? modul.toString() : props.get(k);
                m.appendReplacement(b, Matcher.quoteReplacement(w == null ? m.group() : w));
            }
            m.appendTail(b);
            s = b.toString();
        }
        return s;
    }

    /** Pfad relativ zu basis; null bei URL, classpath: oder ungeloesten ${...} */
    private static Path pfad(Path basis, String s) {
        if (s == null || s.contains("${") || s.matches("^[a-zA-Z][a-zA-Z0-9+.-]+:.*") && !s.matches("^[a-zA-Z]:[\\\\/].*")) return null;
        try {
            return basis.resolve(s.strip()).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * Lifecycle-Mapping fuer jdtls (java.configuration.maven.lifecycleMappings): m2e fuehrt die Generatoren
     * beim Import aus und nimmt ihren Ausgabeordner als Quellordner. Neue Plugin-Versionen bringen das selbst
     * mit, aeltere (und swagger-codegen) nicht.
     */
    static String lifecycleMapping() {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<lifecycleMappingMetadata>\n  <pluginExecutions>\n");
        String[][] p = {{"org.openapitools", "openapi-generator-maven-plugin"}, {"io.swagger", "swagger-codegen-maven-plugin"},
                {"io.swagger.codegen.v3", "swagger-codegen-maven-plugin"}};
        for (String[] x : p) {
            b.append("    <pluginExecution>\n      <pluginExecutionFilter>\n        <groupId>").append(x[0]).append("</groupId>\n        <artifactId>")
                    .append(x[1]).append("</artifactId>\n        <versionRange>[0,)</versionRange>\n        <goals>\n          <goal>generate</goal>\n")
                    .append("        </goals>\n      </pluginExecutionFilter>\n      <action>\n        <execute>\n          <runOnIncremental>true</runOnIncremental>\n")
                    .append("          <runOnConfiguration>true</runOnConfiguration>\n        </execute>\n      </action>\n    </pluginExecution>\n");
        }
        return b.append("  </pluginExecutions>\n</lifecycleMappingMetadata>\n").toString();
    }
}
