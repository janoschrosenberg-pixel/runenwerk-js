package runen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Git eines Arbeitsbereichs: Branch aus .git/HEAD lesen, Branches auflisten und wechseln. */
final class Git {
    record Lauf(int code, String ausgabe) {}

    /** Wurzel des Repos (Ordner mit .git) ab p aufwaerts, oder null */
    static Path wurzel(Path p) {
        for (Path x = p; x != null; x = x.getParent()) if (Files.exists(x.resolve(".git"))) return x;
        return null;
    }

    /** aktueller Branch, bei abgekoppeltem HEAD die ersten 7 Zeichen des Commits; null ohne Repo */
    static String branch(Path repo) {
        if (repo == null) return null;
        try {
            Path git = repo.resolve(".git");
            if (Files.isRegularFile(git)) {
                // Worktree oder Submodul: "gitdir: PFAD"
                String z = Files.readString(git, StandardCharsets.UTF_8).trim();
                if (!z.startsWith("gitdir:")) return null;
                git = repo.resolve(z.substring(7).trim()).normalize();
            }
            String head = Files.readString(git.resolve("HEAD"), StandardCharsets.UTF_8).trim();
            if (head.startsWith("ref:")) {
                String ref = head.substring(4).trim();
                return ref.startsWith("refs/heads/") ? ref.substring(11) : ref;
            }
            return head.length() > 7 ? "(" + head.substring(0, 7) + ")" : head;
        } catch (IOException e) {
            return null;
        }
    }

    static Lauf git(Path repo, String... args) {
        List<String> b = new ArrayList<>();
        b.add("git");
        b.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder().directory(repo.toFile()).redirectErrorStream(true);
        Lsp.umgebung(pb);
        List<String> befehl = Lsp.befehl(repo, b, pb.environment());
        if (befehl == null) return new Lauf(-1, "git nicht gefunden (nicht im PATH)");
        try {
            Process p = pb.command(befehl).start();
            p.getOutputStream().close();
            String aus = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Lauf(p.waitFor(), aus.strip());
        } catch (IOException e) {
            return new Lauf(-1, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Lauf(-1, "unterbrochen");
        }
    }

    /**
     * Branches, zuletzt benutzte zuerst: Reihenfolge der Wechsel aus dem Reflog, dann die
     * uebrigen lokalen nach Datum des letzten Commits, dann entfernte ohne lokales Gegenstueck.
     */
    static List<String> branches(Path repo) {
        Lauf l = git(repo, "branch", "--sort=-committerdate", "--format=%(refname:short)");
        if (l.code() != 0) return List.of();
        Set<String> lokal = new LinkedHashSet<>();
        for (String z : l.ausgabe().split("\\R")) if (!z.isBlank() && !z.startsWith("(")) lokal.add(z.strip());
        Set<String> r = new LinkedHashSet<>();
        String jetzt = branch(repo);
        if (jetzt != null && lokal.contains(jetzt)) r.add(jetzt);
        Lauf log = git(repo, "reflog", "-n", "500", "--format=%gs");
        if (log.code() == 0) {
            for (String z : log.ausgabe().split("\\R")) {
                // "checkout: moving from A to B"
                int i = z.indexOf("moving from ");
                int j = z.lastIndexOf(" to ");
                if (!z.startsWith("checkout:") || i < 0 || j < i) continue;
                String nach = z.substring(j + 4).strip(), von = z.substring(i + 12, j).strip();
                if (lokal.contains(nach)) r.add(nach);
                if (lokal.contains(von)) r.add(von);
            }
        }
        r.addAll(lokal);
        Lauf e = git(repo, "branch", "-r", "--sort=-committerdate", "--format=%(refname:short)");
        if (e.code() == 0) {
            for (String z : e.ausgabe().split("\\R")) {
                z = z.strip();
                if (z.isEmpty() || z.endsWith("/HEAD") || !z.contains("/")) continue;
                if (!lokal.contains(z.substring(z.indexOf('/') + 1))) r.add(z);
            }
        }
        return new ArrayList<>(r);
    }

    /** Dateien mit Merge-Konflikt (relativ zum Repo) */
    static List<String> konflikte(Path repo) {
        Lauf l = git(repo, "diff", "--name-only", "--diff-filter=U");
        List<String> r = new ArrayList<>();
        if (l.code() == 0) for (String z : l.ausgabe().split("\\R")) if (!z.isBlank()) r.add(z.strip());
        return r;
    }

    /** auf einen Branch wechseln; origin/x legt den lokalen Branch x an */
    static Lauf wechseln(Path repo, String branch) {
        Lauf l = git(repo, "switch", branch);
        if (l.code() != 0 && branch.contains("/")) {
            String kurz = branch.substring(branch.indexOf('/') + 1);
            Lauf t = git(repo, "switch", "--track", branch);
            if (t.code() == 0) return t;
            Lauf k = git(repo, "switch", kurz);
            if (k.code() == 0) return k;
        }
        return l;
    }
}
