package runen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vervollstaendigung der Terminal-Eingabezeile (TAB): Pfade, Befehle aus dem PATH, und je nach Befehl
 * git-Unterbefehle, Branches, Tags und Remotes, npm/pnpm/yarn-Skripte aus package.json, Maven-Phasen und
 * Profile aus pom.xml. cd nimmt nur Ordner.
 */
final class TermVervollst {
    /** ein Vorschlag: der ganze neue Wert des Wortes, Anzeige, Art (d Ordner, f Datei, x Befehl, b Branch ...), Zusatz; fertig = danach ein Leerzeichen */
    record Vorschlag(String wert, String zeige, char art, String detail, boolean fertig) {}

    /** Wort unter dem Cursor: Anfang in der Zeile, Wert ohne Escapes, Anfuehrungszeichen, die Woerter davor */
    record Wort(int anf, String wert, char quote, List<String> davor) {}

    private static final boolean WIN = Lsp.WINDOWS;
    private static final Set<String> TRENNER = Set.of("|", "||", "&&", ";", "&");
    private static final Set<String> VORSATZ = Set.of("sudo", "time", "nohup", "xargs", "exec", "command", "watch");
    private static final List<String> SH_EINGEBAUT = List.of("cd", "pushd", "popd", "export", "unset", "source", "alias", "echo", "exit", "history",
            "type", "which", "pwd", "set", "ulimit", "jobs", "kill", "wait", "test");
    private static final List<String> CMD_EINGEBAUT = List.of("cd", "dir", "copy", "del", "move", "ren", "md", "mkdir", "rd", "rmdir", "type",
            "cls", "set", "echo", "where", "start", "exit", "pushd", "popd", "call");
    private static final List<String> GIT = List.of("status", "add", "commit", "push", "pull", "fetch", "checkout", "switch", "branch", "merge",
            "rebase", "log", "diff", "show", "stash", "reset", "restore", "rm", "mv", "tag", "remote", "clone", "init", "cherry-pick", "revert",
            "blame", "grep", "config", "worktree", "bisect", "clean", "reflog", "submodule");
    /** git-Unterbefehle, die Branches/Tags nehmen; dazu auch Dateien */
    private static final Set<String> GIT_REFS = Set.of("checkout", "switch", "merge", "rebase", "branch", "log", "diff", "show", "cherry-pick",
            "reset", "revert", "tag", "push", "pull", "worktree", "restore");
    private static final Set<String> GIT_DATEIEN = Set.of("add", "rm", "mv", "restore", "checkout", "diff", "log", "reset", "show", "blame",
            "clean", "grep");
    private static final List<String> NPM = List.of("install", "run", "test", "start", "ci", "build", "uninstall", "update", "outdated", "audit",
            "exec", "init", "list", "publish", "version", "link");
    private static final List<String> MVN = List.of("clean", "validate", "compile", "test", "package", "verify", "install", "deploy", "site",
            "spring-boot:run", "dependency:tree", "dependency:resolve", "versions:display-dependency-updates", "-DskipTests", "-Dmaven.test.skip=true",
            "-o", "-U", "-q", "-pl", "-am", "-P");

    private static List<String> befehleCache;
    private static String befehlePfad;

    private TermVervollst() {}

    /** das Wort, in dem der Cursor steht (Shell-artig: Leerzeichen trennen, Anfuehrungszeichen und \ schuetzen) */
    static Wort wort(String zeile, int cursor) {
        List<String> woerter = new ArrayList<>();
        StringBuilder w = new StringBuilder();
        int anf = 0;
        char q = 0, ersterQ = 0;
        boolean imWort = false;
        for (int i = 0; i < cursor; i++) {
            char c = zeile.charAt(i);
            if (q != 0) {
                if (c == q) q = 0;
                else w.append(c);
                continue;
            }
            if (c == '\\' && !WIN && i + 1 < cursor) {
                if (!imWort) {
                    anf = i;
                    imWort = true;
                }
                w.append(zeile.charAt(++i));
                continue;
            }
            if (c == ' ' || c == '\t') {
                if (imWort) woerter.add(w.toString());
                w.setLength(0);
                imWort = false;
                ersterQ = 0;
                continue;
            }
            if (!imWort) {
                anf = i;
                imWort = true;
                ersterQ = c == '"' || c == '\'' ? c : 0;
            }
            if (c == '"' || c == '\'') q = c;
            else w.append(c);
        }
        if (!imWort) anf = cursor;
        return new Wort(anf, w.toString(), ersterQ, woerter);
    }

    /** Vorschlaege fuer das Wort; ordner = aktueller Ordner der Shell */
    static List<Vorschlag> vorschlaege(Wort w, Path ordner) {
        List<String> d = w.davor();
        // Befehl dieses Abschnitts (nach | && ; und sudo ...)
        int b = 0;
        for (int i = 0; i < d.size(); i++) {
            if (TRENNER.contains(d.get(i))) b = i + 1;
            else if (i == b && VORSATZ.contains(d.get(i))) b = i + 1;
        }
        String v = w.wert();
        if (b >= d.size()) {
            // Befehlsposition
            if (v.contains("/") || v.contains("\\") || v.startsWith(".") || v.startsWith("~")) return pfade(v, ordner, false, true);
            return befehle(v);
        }
        String cmd = name(d.get(b));
        List<String> args = d.subList(b + 1, d.size());
        List<Vorschlag> l = new ArrayList<>();
        switch (cmd) {
            case "cd", "pushd", "rd", "rmdir" -> {
                return pfade(v, ordner, true, false);
            }
            case "git" -> {
                List<String> pos = ohneOptionen(args);
                if (pos.isEmpty()) {
                    for (String s : GIT) if (s.startsWith(v)) l.add(new Vorschlag(s, s, 'g', "", true));
                    for (Map.Entry<String, String> e : gitAliase(ordner).entrySet()) {
                        if (e.getKey().startsWith(v)) l.add(new Vorschlag(e.getKey(), e.getKey(), 'g', "= " + e.getValue(), true));
                    }
                    return l;
                }
                String sub = pos.get(0);
                if ((sub.equals("push") || sub.equals("pull") || sub.equals("fetch")) && pos.size() == 1 && !v.startsWith("-")) {
                    for (String r : gitZeilen(ordner, "remote")) if (r.startsWith(v)) l.add(new Vorschlag(r, r, 'r', "Remote", true));
                    if (!sub.equals("fetch")) l.addAll(refs(v, ordner, true));
                    return l;
                }
                if (sub.equals("stash") && pos.size() == 1) {
                    for (String s : List.of("list", "pop", "apply", "drop", "show", "push", "clear", "branch")) if (s.startsWith(v)) l.add(new Vorschlag(s, s, 'g', "", true));
                    return l;
                }
                if (GIT_REFS.contains(sub) && !v.startsWith("-")) l.addAll(refs(v, ordner, !sub.equals("switch") && !sub.equals("branch")));
                if (GIT_DATEIEN.contains(sub) || !GIT_REFS.contains(sub)) l.addAll(pfade(v, ordner, false, false));
                return l;
            }
            case "npm", "pnpm", "yarn", "bun" -> {
                List<String> pos = ohneOptionen(args);
                boolean skript = !pos.isEmpty() && (pos.get(0).equals("run") || pos.get(0).equals("run-script")) && pos.size() == 1;
                if (pos.isEmpty() || skript) {
                    for (Map.Entry<String, String> e : skripte(ordner).entrySet()) {
                        if (e.getKey().startsWith(v)) l.add(new Vorschlag(e.getKey(), e.getKey(), 's', e.getValue(), true));
                    }
                    if (pos.isEmpty()) for (String s : NPM) if (s.startsWith(v)) l.add(new Vorschlag(s, s, 'g', "", true));
                    return l;
                }
                return pfade(v, ordner, false, false);
            }
            case "mvn", "mvnw" -> {
                String vorher = args.isEmpty() ? "" : args.get(args.size() - 1);
                if (vorher.equals("-P") || v.startsWith("-P") && v.length() > 2) {
                    String p = vorher.equals("-P") ? "" : "-P";
                    String rest = v.substring(p.length());
                    // -Pa,b: nach dem letzten Komma weiter
                    int k = rest.lastIndexOf(',');
                    String vor = p + rest.substring(0, k + 1);
                    for (String pr : profile(ordner)) if (pr.startsWith(rest.substring(k + 1))) l.add(new Vorschlag(vor + pr, pr, 'p', "Profil", true));
                    return l;
                }
                for (String s : MVN) if (s.startsWith(v)) l.add(new Vorschlag(s, s, 'g', "", !s.equals("-P") && !s.equals("-pl")));
                if (!v.startsWith("-")) l.addAll(pfade(v, ordner, false, false));
                return l;
            }
            default -> {
                return pfade(v, ordner, false, false);
            }
        }
    }

    /** Befehlsname ohne Pfad und Endung (./mvnw -> mvnw, npm.cmd -> npm) */
    private static String name(String b) {
        String n = b.substring(Math.max(b.lastIndexOf('/'), b.lastIndexOf('\\')) + 1);
        int p = n.lastIndexOf('.');
        if (p > 0 && WIN) n = n.substring(0, p);
        return n.toLowerCase(Locale.ROOT);
    }

    private static List<String> ohneOptionen(List<String> args) {
        List<String> l = new ArrayList<>();
        for (String a : args) if (!a.startsWith("-")) l.add(a);
        return l;
    }

    // ---------------------------------------------------------------- Pfade
    static List<Vorschlag> pfade(String v, Path ordner, boolean nurOrdner, boolean ausfuehrbar) {
        int s = Math.max(v.lastIndexOf('/'), WIN ? v.lastIndexOf('\\') : -1);
        String teil = v.substring(0, s + 1), pre = v.substring(s + 1);
        String basis = teil;
        if (basis.equals("~") || basis.startsWith("~/") || WIN && basis.startsWith("~\\")) basis = System.getProperty("user.home") + basis.substring(1);
        if (v.equals("~")) return List.of(new Vorschlag("~/", "~/", 'd', "", false));
        Path dir;
        try {
            dir = basis.isEmpty() ? ordner : ordner.resolve(basis);
        } catch (RuntimeException e) {
            return List.of();
        }
        List<Vorschlag> genau = new ArrayList<>(), gross = new ArrayList<>();
        String trenn = WIN ? "\\" : "/";
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (n.startsWith(".") && !pre.startsWith(".")) continue;
                boolean o = Files.isDirectory(p);
                if (nurOrdner && !o) continue;
                if (ausfuehrbar && !o && !Files.isExecutable(p)) continue;
                Vorschlag x = new Vorschlag(teil + n + (o ? trenn : ""), n + (o ? trenn : ""), o ? 'd' : 'f', "", !o);
                if (n.startsWith(pre)) genau.add(x);
                else if (n.toLowerCase(Locale.ROOT).startsWith(pre.toLowerCase(Locale.ROOT))) gross.add(x);
            }
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        List<Vorschlag> l = genau.isEmpty() ? gross : genau;
        // Ordner zuerst, dann nach Namen
        l.sort((a, b) -> a.art() != b.art() ? Character.compare(a.art(), b.art()) : a.zeige().compareToIgnoreCase(b.zeige()));
        return l;
    }

    // ---------------------------------------------------------------- Befehle aus dem PATH
    static List<Vorschlag> befehle(String v) {
        List<Vorschlag> l = new ArrayList<>();
        for (String b : WIN ? CMD_EINGEBAUT : SH_EINGEBAUT) if (b.startsWith(v)) l.add(new Vorschlag(b, b, 'x', "eingebaut", true));
        for (String b : alleBefehle()) {
            if (b.startsWith(v) && l.stream().noneMatch(x -> x.wert().equals(b))) l.add(new Vorschlag(b, b, 'x', "", true));
        }
        if (l.isEmpty() && WIN) {
            String vl = v.toLowerCase(Locale.ROOT);
            for (String b : alleBefehle()) if (b.toLowerCase(Locale.ROOT).startsWith(vl)) l.add(new Vorschlag(b, b, 'x', "", true));
        }
        l.sort((a, b) -> a.wert().compareTo(b.wert()));
        return l;
    }

    private static synchronized List<String> alleBefehle() {
        ProcessBuilder pb = new ProcessBuilder();
        Lsp.umgebung(pb);
        String path = pb.environment().getOrDefault(WIN ? pb.environment().keySet().stream().filter(k -> k.equalsIgnoreCase("PATH")).findFirst().orElse("PATH") : "PATH", "");
        if (befehleCache != null && path.equals(befehlePfad)) return befehleCache;
        java.util.TreeSet<String> s = new java.util.TreeSet<>();
        String ext = (System.getenv("PATHEXT") == null ? ".EXE;.CMD;.BAT;.COM" : System.getenv("PATHEXT")).toLowerCase(Locale.ROOT);
        for (String d : path.split(java.io.File.pathSeparator)) {
            if (d.isBlank()) continue;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(Path.of(d))) {
                for (Path p : ds) {
                    String n = p.getFileName().toString();
                    if (WIN) {
                        int k = n.lastIndexOf('.');
                        if (k > 0 && ext.contains(n.substring(k).toLowerCase(Locale.ROOT))) s.add(n.substring(0, k));
                    } else if (!n.startsWith(".") && Files.isExecutable(p) && !Files.isDirectory(p)) {
                        s.add(n);
                    }
                }
            } catch (IOException | RuntimeException e) {
                // Ordner fehlt oder nicht lesbar
            }
        }
        befehleCache = new ArrayList<>(s);
        befehlePfad = path;
        return befehleCache;
    }

    // ---------------------------------------------------------------- git, npm, Maven
    private static List<String> gitZeilen(Path ordner, String... args) {
        Git.Lauf r = Git.git(ordner, args);
        if (r.code() != 0 || r.ausgabe().isBlank()) return List.of();
        return r.ausgabe().lines().map(String::strip).filter(z -> !z.isEmpty()).toList();
    }

    private static List<Vorschlag> refs(String v, Path ordner, boolean auchTags) {
        List<Vorschlag> l = new ArrayList<>();
        List<String> args = new ArrayList<>(List.of("for-each-ref", "--format=%(refname)", "refs/heads", "refs/remotes"));
        if (auchTags) args.add("refs/tags");
        for (String r : gitZeilen(ordner, args.toArray(new String[0]))) {
            String kurz;
            String art;
            if (r.startsWith("refs/heads/")) {
                kurz = r.substring(11);
                art = "Branch";
            } else if (r.startsWith("refs/remotes/")) {
                kurz = r.substring(13);
                if (kurz.endsWith("/HEAD")) continue;
                art = "Remote-Branch";
            } else {
                kurz = r.substring(10);
                art = "Tag";
            }
            if (kurz.startsWith(v)) l.add(new Vorschlag(kurz, kurz, art.equals("Tag") ? 't' : 'b', art, true));
        }
        return l;
    }

    private static Map<String, String> gitAliase(Path ordner) {
        Map<String, String> m = new TreeMap<>();
        for (String z : gitZeilen(ordner, "config", "--get-regexp", "^alias\\.")) {
            int k = z.indexOf(' ');
            if (k > 6) m.put(z.substring(6, k), z.substring(k + 1));
        }
        return m;
    }

    /** package.json im Ordner oder darueber: Skriptname -> Befehl */
    static Map<String, String> skripte(Path ordner) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Path p = ordner; p != null; p = p.getParent()) {
            Path pj = p.resolve("package.json");
            if (!Files.isRegularFile(pj)) continue;
            try {
                Object j = Json.parse(Files.readString(pj, StandardCharsets.UTF_8));
                Object s = Json.get(j, "scripts");
                if (s instanceof Map<?, ?> sm) for (Map.Entry<?, ?> e : sm.entrySet()) m.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            } catch (IOException | RuntimeException e) {
                // kaputtes package.json: keine Skripte
            }
            break;
        }
        return m;
    }

    private static final Pattern PROFIL = Pattern.compile("<profile>\\s*(?:<!--.*?-->\\s*)*<id>\\s*([^<\\s]+)\\s*</id>", Pattern.DOTALL);

    /** Profile aus pom.xml im Ordner oder darueber */
    static List<String> profile(Path ordner) {
        List<String> l = new ArrayList<>();
        for (Path p = ordner; p != null; p = p.getParent()) {
            Path pom = p.resolve("pom.xml");
            if (!Files.isRegularFile(pom)) continue;
            try {
                Matcher m = PROFIL.matcher(Files.readString(pom, StandardCharsets.UTF_8));
                while (m.find()) if (!l.contains(m.group(1))) l.add(m.group(1));
            } catch (IOException | RuntimeException e) {
                // nicht lesbar
            }
            break;
        }
        return l;
    }

    // ---------------------------------------------------------------- Einsetzen
    /** Wert fuer die Eingabezeile: Leerzeichen und Sonderzeichen schuetzen bzw. in Anfuehrungszeichen */
    static String roh(String wert, char quote, boolean fertig) {
        if (quote != 0) return quote + wert + (fertig ? String.valueOf(quote) : "");
        if (WIN) return wert.contains(" ") ? "\"" + wert + (fertig ? "\"" : "") : wert;
        StringBuilder b = new StringBuilder();
        for (char c : wert.toCharArray()) {
            if (" \t'\"\\()&;|<>$`!*?[]{}#".indexOf(c) >= 0) b.append('\\');
            b.append(c);
        }
        return b.toString();
    }

    /** laengster gemeinsamer Anfang aller Werte */
    static String gemeinsam(List<Vorschlag> l) {
        if (l.isEmpty()) return "";
        String p = l.get(0).wert();
        for (Vorschlag v : l) {
            int i = 0;
            while (i < p.length() && i < v.wert().length() && p.charAt(i) == v.wert().charAt(i)) i++;
            p = p.substring(0, i);
        }
        return p;
    }
}
