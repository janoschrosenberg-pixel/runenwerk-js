package runen;

import static runen.Editor.E_FEHLER;
import static runen.Editor.E_GRUND;
import static runen.Editor.E_HELL;
import static runen.Editor.E_INFO;
import static runen.Editor.E_KONSOLE;
import static runen.Editor.E_MATT;
import static runen.Editor.E_TEXT;
import static runen.Editor.E_WARN;
import static runen.Editor.E_ZEILE;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Was ueber das Bearbeiten hinausgeht: Git-Branches, Formatieren mit prettier/IntelliJ,
 * Java Language Server (jdtls), Auswahlliste, Datenbank mit Ergebnistabelle, Methodensprung.
 */
final class Werkzeug {
    private final Editor ed;
    final Db db = new Db();
    final Debug dbg;
    final Merge merge;

    Werkzeug(Editor ed) {
        this.ed = ed;
        dbg = new Debug(ed);
        merge = new Merge(ed);
        Timer t = new Timer(2000, e -> {
            if (gitLesen()) ed.neuMalen.run();
        });
        // Blame nach Aenderungen am Text nachziehen
        Timer bl = new Timer(700, e -> {
            Doc d = ed.d();
            if (d.blame != null && d.blame.version != d.version && !blameLaeuft) blameLesen(d, false);
        });
        bl.start();
        t.start();
    }

    // ================================================================ Git
    private Path tsRepo, javaRepo;
    private String tsBranch, javaBranch;

    /** Branches neu lesen; true, wenn sich etwas geaendert hat */
    boolean gitLesen() {
        Path tr = ed.arbeitsordner == null ? null : Git.wurzel(ed.arbeitsordner);
        Path jr = ed.javaOrdner == null ? null : Git.wurzel(ed.javaOrdner);
        String tb = Git.branch(tr), jb = Git.branch(jr);
        boolean anders = !java.util.Objects.equals(tb, tsBranch) || !java.util.Objects.equals(jb, javaBranch) || !java.util.Objects.equals(tr, tsRepo)
                || !java.util.Objects.equals(jr, javaRepo);
        tsRepo = tr;
        javaRepo = jr;
        tsBranch = tb;
        javaBranch = jb;
        return anders;
    }

    /** rechts in der Tab-Leiste: Repo und Branch je Arbeitsbereich */
    String bereiche() {
        StringBuilder b = new StringBuilder();
        if (ed.arbeitsordner != null) b.append("TS ").append(repoText(ed.arbeitsordner, tsRepo, tsBranch));
        if (ed.javaOrdner != null) b.append(b.length() > 0 ? "   " : "").append("Java ").append(repoText(ed.javaOrdner, javaRepo, javaBranch));
        return b.length() == 0 ? "kein Arbeitsbereich" : b.toString();
    }

    private static String repoText(Path ordner, Path repo, String branch) {
        if (repo == null) return Editor.name(ordner) + " (kein git)";
        return Editor.name(repo) + ":" + branch;
    }

    /** Repo zu ts, java oder (leer) dem der aktuellen Datei; null mit Meldung */
    private Path repoFuer(String welcher) {
        Path repo = switch (welcher.trim()) {
            case "ts" -> tsRepo;
            case "java" -> javaRepo;
            default -> {
                Doc d = ed.d();
                Path p = d.pfad != null ? d.pfad : d.term != null ? d.term.ordner() : null;
                Path eigen = p == null ? null : Git.wurzel(p);
                if (eigen != null) yield eigen;
                Path b = ed.bereichVon(p);
                if (b != null && b.equals(ed.javaOrdner)) yield javaRepo;
                yield tsRepo != null ? tsRepo : javaRepo;
            }
        };
        if (repo == null) ed.meldung = "kein git-Repo im Arbeitsbereich";
        return repo;
    }

    private void branchWaehlen(String welcher) {
        Path repo = repoFuer(welcher);
        if (repo == null) return;
        String aktuell = Git.branch(repo);
        ed.meldung = "Branches werden gelesen ...";
        Path r = repo;
        hintergrund(() -> {
            List<String> l = Git.branches(r);
            SwingUtilities.invokeLater(() -> {
                ed.meldung = null;
                if (l.isEmpty()) {
                    ed.fehler("keine Branches (git branch fehlgeschlagen) in " + r);
                } else {
                    auswahl("Branch in " + Editor.name(r) + " (jetzt " + aktuell + ")", l, aktuell, b -> wechseln(r, b));
                }
                ed.neuMalen.run();
            });
        });
    }

    private void wechseln(Path repo, String branch) {
        for (Doc t : ed.tabs) {
            if (t.geaendert && t.pfad != null && t.pfad.startsWith(repo)) {
                // nach Undo oft wieder gleich wie auf der Platte
                if (gleichWiePlatte(t)) {
                    t.geaendert = false;
                    continue;
                }
                ed.fehler("erst speichern: " + ed.relativ(t.pfad) + " ist geaendert");
                return;
            }
        }
        ed.meldung = "git switch " + branch + " ...";
        hintergrund(() -> {
            Git.Lauf l = Git.wechseln(repo, branch);
            SwingUtilities.invokeLater(() -> {
                ed.farbig("$ git switch " + branch + " (" + Editor.name(repo) + ")", E_HELL);
                if (!l.ausgabe().isEmpty()) ed.farbig(l.ausgabe(), l.code() == 0 ? E_TEXT : E_FEHLER);
                if (l.code() != 0) {
                    ed.fehler("Branch nicht gewechselt: " + letzteZeile(l.ausgabe()));
                } else {
                    neuLaden(repo);
                    ed.index.neuEinlesen();
                    gitLesen();
                    ed.meldung = Editor.name(repo) + " ist jetzt auf " + Git.branch(repo);
                }
                ed.neuMalen.run();
            });
        });
    }

    private static boolean gleichWiePlatte(Doc t) {
        try {
            return Files.exists(t.pfad) && Doc.laden(t.pfad).t.toString().contentEquals(t.t);
        } catch (IOException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- pull, push, commit, reset
    private Path gitRepo;

    /** git-Befehl im Hintergrund, Ausgabe ins Terminal, danach fertig (im Swing-Faden) */
    private void gitLauf(Path repo, String titel, String[] args, java.util.function.Consumer<Git.Lauf> fertig) {
        ed.meldung = "git " + titel + " (" + Editor.name(repo) + ") ...";
        hintergrund(() -> {
            Git.Lauf l = Git.git(repo, args);
            SwingUtilities.invokeLater(() -> {
                ed.farbig("$ git " + String.join(" ", args) + "   (" + Editor.name(repo) + ")", E_HELL);
                if (!l.ausgabe().isEmpty()) ed.farbig(l.ausgabe(), l.code() == 0 ? E_TEXT : Editor.E_FEHLER);
                fertig.accept(l);
                gitLesen();
                if (statusOffen && repo.equals(statusRepo)) statusLesen();
                ed.neuMalen.run();
            });
        });
    }

    private void pull() {
        Path repo = repoFuer("");
        if (repo == null) return;
        // ohne eigene Einstellung (pull.rebase / pull.ff) wie klassisches git: mergen
        boolean eigen = !Git.git(repo, "config", "--get", "pull.rebase").ausgabe().isBlank() || !Git.git(repo, "config", "--get", "pull.ff").ausgabe().isBlank();
        gitLauf(repo, "pull", eigen ? new String[] {"pull"} : new String[] {"pull", "--no-rebase"}, l -> {
            neuLaden(repo);
            ed.index.neuEinlesen();
            if (l.code() == 0) {
                ed.meldung = "pull: " + letzteZeile(l.ausgabe().isEmpty() ? "fertig" : l.ausgabe());
            } else if (!Git.konflikte(repo).isEmpty()) {
                ed.fehler("pull: Konflikte in " + Git.konflikte(repo).size() + " Dateien - Leertaste g m oeffnet den Merge-Editor");
            } else {
                ed.fehler("pull fehlgeschlagen: " + letzteZeile(l.ausgabe()));
            }
        });
    }

    private void push() {
        Path repo = repoFuer("");
        if (repo == null) return;
        gitLauf(repo, "push", new String[] {"push"}, l -> {
            if (l.code() == 0) {
                ed.meldung = "push: " + letzteZeile(l.ausgabe().isEmpty() ? "fertig" : l.ausgabe());
            } else if (l.ausgabe().contains("no upstream") || l.ausgabe().contains("set-upstream")) {
                // neuer Branch: Upstream anlegen
                gitLauf(repo, "push -u", new String[] {"push", "-u", "origin", "HEAD"}, l2 -> {
                    if (l2.code() == 0) ed.meldung = "push: Branch " + Git.branch(repo) + " auf origin angelegt";
                    else ed.fehler("push fehlgeschlagen: " + letzteZeile(l2.ausgabe()));
                });
            } else {
                ed.fehler("push fehlgeschlagen: " + letzteZeile(l.ausgabe()));
            }
        });
    }

    private void commitStarten() {
        Path repo = repoFuer("");
        if (repo == null) return;
        alleSpeichern(repo);
        Git.Lauf st = Git.git(repo, "status", "--porcelain");
        if (st.code() == 0 && st.ausgabe().isBlank()) {
            ed.meldung = "nichts zu committen in " + Editor.name(repo);
            return;
        }
        gitRepo = repo;
        boolean vorgemerkt = Git.git(repo, "diff", "--cached", "--quiet").code() != 0;
        int n = st.ausgabe().split("\\R").length;
        ed.popupOpen("Commit in " + Editor.name(repo) + ":" + Git.branch(repo) + " (" + (vorgemerkt ? "vorgemerkte Aenderungen" : "alle " + n + " Aenderungen")
                + ") - Nachricht:", "gitCommitDo", "");
    }

    private void commit(String nachricht) {
        Path repo = gitRepo;
        if (repo == null || nachricht.isBlank()) {
            ed.meldung = "Commit abgebrochen (keine Nachricht)";
            return;
        }
        hintergrund(() -> {
            boolean vorgemerkt = Git.git(repo, "diff", "--cached", "--quiet").code() != 0;
            if (!vorgemerkt) Git.git(repo, "add", "-A");
            SwingUtilities.invokeLater(() -> gitLauf(repo, "commit", new String[] {"commit", "-m", nachricht.strip()}, l -> {
                if (l.code() == 0) ed.meldung = "committet: " + l.ausgabe().split("\\R")[0];
                else ed.fehler("commit fehlgeschlagen: " + letzteZeile(l.ausgabe()));
            }));
        });
    }

    private void resetStarten() {
        Path repo = repoFuer("");
        if (repo == null) return;
        gitRepo = repo;
        ed.popupOpen("git reset --hard in " + Editor.name(repo) + ":" + Git.branch(repo) + " - ALLE nicht committeten Aenderungen gehen verloren. ja eintippen und ENTER:",
                "gitResetDo", "");
    }

    private void reset(String antwort) {
        Path repo = gitRepo;
        if (repo == null || !antwort.strip().equalsIgnoreCase("ja")) {
            ed.meldung = "reset abgebrochen";
            return;
        }
        // offene Texte im Repo gelten danach als unveraendert
        for (Doc t : ed.tabs) if (t.pfad != null && t.pfad.startsWith(repo)) t.geaendert = false;
        gitLauf(repo, "reset --hard", new String[] {"reset", "--hard"}, l -> {
            neuLaden(repo);
            ed.index.neuEinlesen();
            if (l.code() == 0) ed.meldung = "reset --hard: " + letzteZeile(l.ausgabe());
            else ed.fehler("reset fehlgeschlagen: " + letzteZeile(l.ausgabe()));
        });
    }

    private void alleSpeichern(Path repo) {
        int a = ed.aktiv;
        for (int i = 0; i < ed.tabs.size(); i++) {
            Doc t = ed.tabs.get(i);
            if (t.geaendert && t.pfad != null && t.pfad.startsWith(repo)) {
                ed.aktiv = i;
                ed.befehl("save", "");
            }
        }
        ed.aktiv = a;
    }

    // ---------------------------------------------------------------- git status (Panel unten)
    boolean statusOffen;
    private Path statusRepo;
    private List<String> statusZeilen = List.of();
    private int statusWahl, statusOben;

    private void statusOeffnen() {
        Path repo = repoFuer("");
        if (repo == null) return;
        statusRepo = repo;
        statusOffen = true;
        ed.konsoleOffen = false;
        ed.modus = "gitstatus";
        statusLesen();
    }

    private void statusLesen() {
        Path repo = statusRepo;
        hintergrund(() -> {
            Git.Lauf l = Git.git(repo, "status", "--short", "--branch");
            List<String> z = new ArrayList<>();
            if (l.code() != 0) z.add("git status fehlgeschlagen: " + l.ausgabe());
            else for (String x : l.ausgabe().split("\\R")) if (!x.isEmpty()) z.add(x);
            if (z.size() == 1 && z.get(0).startsWith("##")) z.add("   (sauber - nichts zu committen)");
            // git sieht nur die Platte: ungespeicherte Texte dazuschreiben
            for (Doc t : ed.tabs) if (t.geaendert && t.pfad != null && t.pfad.startsWith(repo)) z.add("   (ungespeichert im Editor: " + repo.relativize(t.pfad) + ")");
            SwingUtilities.invokeLater(() -> {
                statusZeilen = z;
                statusWahl = Math.min(Math.max(statusWahl, z.size() > 1 ? 1 : 0), z.size() - 1);
                ed.neuMalen.run();
            });
        });
    }

    /** Datei der gewaehlten Zeile (" M src/a.ts" -> Pfad), oder null */
    private Path statusDatei() {
        if (statusWahl <= 0 || statusWahl >= statusZeilen.size()) return null;
        String z = statusZeilen.get(statusWahl);
        if (z.length() < 4 || z.startsWith("##") || z.startsWith("   (")) return null;
        String n = z.substring(3);
        if (n.contains(" -> ")) n = n.substring(n.indexOf(" -> ") + 4);
        n = n.replace("\"", "");
        return statusRepo.resolve(n);
    }

    void statusMalen(int y0) {
        Screen s = ed.scr;
        int W = s.w, lh = s.lh, cw = s.cw;
        int h = s.h - ed.unten() - 6 - y0;
        boolean fokus = ed.modus.equals("gitstatus");
        s.rect(0, y0, W, h, E_KONSOLE);
        s.frame(4, y0, W - 8, h, fokus ? E_HELL : E_TEXT);
        s.frame(5, y0 + 1, W - 10, h - 2, E_MATT);
        String titel = " GIT STATUS " + Editor.name(statusRepo) + ":" + Git.branch(statusRepo)
                + (fokus ? "  (j/k, ENTER oeffnen, s vormerken, u zuruecknehmen, c commit, m merge, g neu, ESC zu) " : " ");
        s.rect(3 * cw, y0 - lh / 2, s.textWidth(titel), lh, E_KONSOLE);
        s.text(3 * cw, y0 - lh / 2, titel, E_HELL);
        int n = Math.max(1, (h - lh - 8) / lh);
        if (statusWahl < statusOben) statusOben = statusWahl;
        if (statusWahl >= statusOben + n) statusOben = statusWahl - n + 1;
        s.clip(8, y0 + 2, W - 16, h - 4);
        for (int k = 0; k < n && statusOben + k < statusZeilen.size(); k++) {
            int i = statusOben + k;
            String z = statusZeilen.get(i);
            int zy = y0 + lh / 2 + 4 + k * lh;
            boolean g = fokus && i == statusWahl;
            if (g) s.rect(8, zy, W - 16, lh, E_TEXT);
            int f = E_TEXT;
            if (z.startsWith("##")) f = E_HELL;
            else if (z.startsWith("??")) f = E_MATT;
            else if (z.startsWith("UU") || z.startsWith("AA") || z.startsWith("DD")) f = Editor.E_FEHLER;
            else if (z.length() > 1 && z.charAt(0) != ' ' && z.charAt(1) == ' ') f = E_INFO;
            else if (z.length() > 1) f = E_WARN;
            s.text(2 * cw, zy, z, g ? E_GRUND : f);
        }
        s.noClip();
    }

    // ---------------------------------------------------------------- git blame
    /** je Zeile: Commit (kurz), Autor, Datum, Nachricht; fuer die Textversion version */
    static final class Blame {
        final String[] kurz, autor, datum, nachricht;
        final int version;

        Blame(int n, int version) {
            kurz = new String[n];
            autor = new String[n];
            datum = new String[n];
            nachricht = new String[n];
            this.version = version;
        }
    }

    /** Breite der Blame-Spalte in Zeichen */
    static final int BLAME_BREITE = 36;
    private boolean blameLaeuft;

    private void blameUmschalten() {
        Doc d = ed.d();
        if (d.blame != null) {
            d.blame = null;
            ed.meldung = "Blame aus";
            return;
        }
        if (d.pfad == null || Git.wurzel(d.pfad) == null) {
            ed.meldung = "Datei liegt in keinem git-Repo";
            return;
        }
        blameLesen(d, true);
    }

    /** git blame ueber den Text im Editor (--contents -), damit ungespeicherte Zeilen passen */
    private void blameLesen(Doc d, boolean melden) {
        Path repo = Git.wurzel(d.pfad);
        if (repo == null || blameLaeuft) return;
        blameLaeuft = true;
        String text = d.t.toString();
        int version = d.version;
        String rel = repo.relativize(d.pfad).toString().replace('\\', '/');
        hintergrund(() -> {
            Blame b = null;
            String fehler = null;
            try {
                ProcessBuilder pb = new ProcessBuilder().directory(repo.toFile());
                Lsp.umgebung(pb);
                List<String> befehl = Lsp.befehl(repo, List.of("git", "blame", "--porcelain", "--contents", "-", "--", rel), pb.environment());
                if (befehl == null) throw new IOException("git nicht gefunden");
                Process p = pb.command(befehl).start();
                Thread schreiber = new Thread(() -> {
                    try (OutputStream o = p.getOutputStream()) {
                        o.write(text.getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        // git liest nicht mehr
                    }
                });
                schreiber.start();
                String aus = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                if (p.waitFor() != 0) throw new IOException(letzteZeile(err.isBlank() ? "git blame fehlgeschlagen" : err));
                b = blameZerlegen(aus, (int) text.chars().filter(c -> c == '\n').count() + 1, version);
            } catch (IOException | InterruptedException e) {
                fehler = e.getMessage();
            }
            Blame fertig = b;
            String f = fehler;
            SwingUtilities.invokeLater(() -> {
                blameLaeuft = false;
                if (f != null) {
                    if (melden) ed.fehler("git blame: " + f);
                    d.blame = null;
                } else {
                    d.blame = fertig;
                    if (melden) ed.meldung = "Blame an (Leertaste g B schaltet aus)";
                }
                ed.neuMalen.run();
            });
        });
    }

    private static Blame blameZerlegen(String aus, int zeilen, int version) {
        Blame b = new Blame(zeilen, version);
        Map<String, String[]> commits = new java.util.HashMap<>();
        String sha = null;
        int ziel = -1;
        String[] info = null;
        java.time.format.DateTimeFormatter df = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");
        for (String z : aus.split("\n")) {
            if (z.startsWith("\t")) {
                if (sha != null && ziel >= 0 && ziel < zeilen) {
                    String[] c = commits.get(sha);
                    boolean offen = sha.startsWith("0000000");
                    b.kurz[ziel] = offen ? "-------" : sha.substring(0, 7);
                    b.autor[ziel] = offen ? "nicht committet" : c[0];
                    b.datum[ziel] = offen ? "" : c[1];
                    b.nachricht[ziel] = offen ? "noch nicht committet" : c[2];
                }
                continue;
            }
            String[] t = z.split(" ");
            if (t.length >= 3 && t[0].length() == 40 && t[0].matches("[0-9a-f]+")) {
                sha = t[0];
                ziel = Integer.parseInt(t[2]) - 1;
                info = commits.computeIfAbsent(sha, k -> new String[] {"", "", ""});
            } else if (info != null && z.startsWith("author ")) {
                info[0] = z.substring(7);
            } else if (info != null && z.startsWith("author-time ")) {
                long sek = Long.parseLong(z.substring(12).strip());
                info[1] = df.format(java.time.Instant.ofEpochSecond(sek).atZone(java.time.ZoneId.systemDefault()));
            } else if (info != null && z.startsWith("summary ")) {
                info[2] = z.substring(8);
            }
        }
        return b;
    }

    /** Text der Blame-Spalte fuer Zeile z (nur in der ersten Zeile eines Commit-Blocks) */
    static String blameText(Blame b, int z) {
        if (b == null || z < 0 || z >= b.kurz.length || b.kurz[z] == null) return "";
        if (z > 0 && b.kurz[z].equals(b.kurz[z - 1])) return "";
        String a = b.autor[z].length() > 14 ? b.autor[z].substring(0, 13) + "~" : b.autor[z];
        return String.format("%s %-14s %s", b.kurz[z], a, b.datum[z]);
    }

    private static String letzteZeile(String s) {
        String[] z = s.strip().split("\\R");
        return z[z.length - 1];
    }

    /** offene, ungeaenderte Texte im Repo von der Platte neu lesen (nach einem Branchwechsel) */
    private void neuLaden(Path repo) {
        for (Doc t : ed.tabs) {
            if (t.pfad == null || !t.pfad.startsWith(repo) || t.geaendert) continue;
            if (!Files.exists(t.pfad)) {
                ed.farbig("  gibt es auf diesem Branch nicht: " + ed.relativ(t.pfad), E_WARN);
                continue;
            }
            textNeuLesen(t);
        }
        ed.lspSync();
    }

    private void textNeuLesen(Doc t) {
        try {
            Doc neu = Doc.laden(t.pfad);
            String s = neu.t.toString();
            if (s.contentEquals(t.t)) return;
            int z = t.zeileVon(t.cursor), sp = t.cursor - t.anf(z);
            t.merken();
            t.setText(s);
            t.cursor = t.stelle(z, sp);
            t.geaendert = false;
        } catch (IOException e) {
            ed.fehler("nicht neu gelesen: " + t.pfad);
        }
    }

    private static void hintergrund(Runnable r) {
        Thread t = new Thread(r, "werkzeug");
        t.setDaemon(true);
        t.start();
    }

    // ================================================================ Formatieren
    void formatieren() {
        Doc d = ed.d();
        String art = d.lspArt();
        if ("ts".equals(art)) {
            String b = ed.kf.get("ts.format", "prettier --stdin-filepath {file}");
            if (!b.equals("lsp")) {
                if (d.pfad == null) {
                    ed.meldung = "erst speichern";
                    return;
                }
                List<String> worte = new ArrayList<>();
                for (String w : b.trim().split("\\s+")) worte.add(w.replace("{file}", d.pfad.toString()));
                extern(d, worte, true);
                return;
            }
        }
        if ("java".equals(art) && ed.kf.get("java.format.intellij", null) != null) {
            intellij(d);
            return;
        }
        ed.lspFormat();
    }

    /** Formatierer mit dem Text auf stdin (prettier) oder auf der Datei (IntelliJ) */
    private void extern(Doc d, List<String> worte, boolean stdin) {
        Path bereich = ed.bereichVon(d.pfad);
        Path ordner = bereich != null ? bereich : d.pfad.getParent();
        ProcessBuilder pb = new ProcessBuilder().directory(ordner.toFile());
        Lsp.umgebung(pb);
        List<String> b = Lsp.befehl(ordner, worte, pb.environment());
        if (b == null) {
            ed.fehler(worte.get(0) + " nicht gefunden" + (stdin ? " -> npm install -D prettier (im TS-Arbeitsbereich)" : "") + "; formatiere mit dem Language Server");
            ed.lspFormat();
            return;
        }
        String text = d.t.toString();
        int v = d.version;
        ed.meldung = "formatiere ...";
        hintergrund(() -> {
            String fehler = null, aus = null;
            try {
                Process p = pb.command(b).start();
                StringBuilder err = new StringBuilder();
                Thread t = new Thread(() -> {
                    try (InputStream in = p.getErrorStream()) {
                        err.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        // egal
                    }
                });
                t.start();
                try (OutputStream o = p.getOutputStream()) {
                    if (stdin) o.write(text.getBytes(StandardCharsets.UTF_8));
                }
                aus = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int rc = p.waitFor();
                t.join(2000);
                if (rc != 0) fehler = "Exit-Code " + rc + ": " + (err.toString().isBlank() ? aus : letzteZeile(err.toString()));
            } catch (IOException | InterruptedException e) {
                fehler = e.getMessage();
            }
            String f = fehler, a = aus;
            SwingUtilities.invokeLater(() -> {
                if (f != null) {
                    ed.fehler("Formatieren fehlgeschlagen: " + f);
                } else if (d.version != v) {
                    ed.meldung = "nicht formatiert: der Text hat sich inzwischen geaendert";
                } else if (stdin) {
                    String neu = a.replace("\r\n", "\n");
                    if (neu.equals(text)) {
                        ed.meldung = "schon formatiert";
                    } else {
                        int z = d.zeileVon(d.cursor), sp = d.cursor - d.anf(z);
                        d.merken();
                        d.setText(neu);
                        d.cursor = d.stelle(z, sp);
                        ed.meldung = "formatiert (prettier)";
                    }
                } else {
                    textNeuLesen(d);
                    ed.meldung = "formatiert (IntelliJ)";
                }
                ed.lspSync();
                ed.neuMalen.run();
            });
        });
    }

    private void intellij(Doc d) {
        String cmd = ed.kf.get("java.format.intellij.cmd", null);
        if (cmd == null) {
            ed.fehler("java.format.intellij.cmd fehlt in config.txt (z. B. C:\\Program Files\\JetBrains\\IntelliJ IDEA\\bin\\format.bat)");
            return;
        }
        if (d.pfad == null || d.geaendert) ed.befehl("save", "");
        if (d.pfad == null || d.geaendert) return;
        String xml = ed.konfig.resolve(ed.kf.get("java.format.intellij", "")).toAbsolutePath().toString();
        extern(d, List.of(ed.konfig.resolve(cmd).toString(), "-s", xml, "-allowDefaults", d.pfad.toString()), false);
    }

    // ================================================================ Java Language Server (jdtls)
    Map<String, Object> javaEinstellungen() {
        Map<String, Object> format = Json.obj("enabled", true);
        String xml = ed.kf.get("java.format.eclipse", null);
        if (xml != null) {
            Path p = ed.konfig.resolve(xml).toAbsolutePath().normalize();
            if (!Files.isRegularFile(p)) ed.fehler("java.format.eclipse: Datei fehlt: " + p);
            Map<String, Object> s = Json.obj("url", p.toUri().toString());
            String profil = ed.kf.get("java.format.profile", null);
            if (profil != null) s.put("profile", profil);
            format.put("settings", s);
        }
        return Json.obj("java", Json.obj("format", format, "configuration", Json.obj("updateBuildConfiguration", "automatic"),
                "import", Json.obj("maven", Json.obj("enabled", true), "gradle", Json.obj("enabled", true)), "autobuild", Json.obj("enabled", true)));
    }

    /**
     * Startbefehl fuer jdtls im Hintergrund ermitteln: eigener Ordner (java.lsp.home), sonst der
     * eingebaute (beim ersten Mal aus der Jar entpackt), sonst jdtls im PATH. Dazu ein Java 21+.
     */
    /** nach einem Abbruch: nur noch das eingebaute jdtls */
    boolean nurEingebaut;

    void javaLspVorbereiten(Consumer<List<String>> fertig) {
        String eigen = nurEingebaut ? null : ed.kf.get("java.lsp.home", null);
        String javaEinst = ed.kf.get("java.lsp.java", null);
        hintergrund(() -> {
            String fehler = null;
            List<String> b = List.of();
            try {
                Path home = null;
                if (eigen != null) {
                    Path h = ed.konfig.resolve(eigen).toAbsolutePath().normalize();
                    if (vollstaendig(h)) {
                        home = h;
                    } else {
                        String t = "java.lsp.home (" + h + ") ist kein vollstaendiges jdtls - nehme das eingebaute";
                        SwingUtilities.invokeLater(() -> {
                            ed.farbig(t, E_WARN);
                            ed.kf.setzen("java.lsp.home", "");
                        });
                    }
                }
                if (home == null) home = eingebautesJdtls();
                String java = javaEinst;
                if (java == null) {
                    Path j = Laden.javaAb(21);
                    if (j != null) java = j.toString();
                }
                if (home != null) {
                    if (java == null) {
                        fehler = "Der Java Language Server braucht Java 21 oder neuer; gefunden wurde nur Java " + Runtime.version().feature()
                                + ". JDK 21 installieren (z. B. winget install EclipseAdoptium.Temurin.21.JDK) oder java.lsp.java in config.txt setzen.";
                    } else {
                        b = javaLspBefehl(home, java);
                        Path jh = Path.of(java).getParent() == null ? null : Path.of(java).getParent().getParent();
                        int v = jh == null ? 0 : Laden.version(jh);
                        String info = "Java Language Server: jdtls aus " + home + " mit Java " + (v == 0 ? "?" : v) + " (" + java + ")";
                        Main.log(info);
                        SwingUtilities.invokeLater(() -> ed.print(info));
                        if (v > 0 && v < 21) fehler = "jdtls braucht Java 21+, " + java + " ist Java " + v + " (java.lsp.java in config.txt)";
                    }
                } else {
                    String c = ed.kf.get("java.lsp", "jdtls");
                    if (!c.equals("off")) {
                        List<String> l = new ArrayList<>(List.of(c.split("\\s+")));
                        if (!l.contains("-data")) {
                            l.add("-data");
                            l.add(datenOrdner().toString());
                        }
                        b = l;
                    }
                }
            } catch (IOException | RuntimeException e) {
                fehler = "jdtls nicht vorbereitet: " + e;
            }
            String f = fehler;
            List<String> befehl = b;
            SwingUtilities.invokeLater(() -> {
                if (ed.meldung != null && ed.meldung.startsWith("Java Language Server wird entpackt")) ed.meldung = null;
                if (f != null) {
                    ed.javaLsp.status = "fehlt";
                    ed.fehler(f);
                } else if (!befehl.isEmpty()) {
                    fertig.accept(befehl);
                }
                ed.neuMalen.run();
            });
        });
    }

    /** Fehler aus den Eclipse-Logs nach einem Abbruch von jdtls (Exit-Code 13) */
    List<String> jdtlsLog(long seit) {
        List<Path> logs = new ArrayList<>();
        if (ed.javaOrdner != null) logs.add(datenOrdner().resolve(".metadata").resolve(".log"));
        // Eclipse schreibt den Fehler in den Konfigurationsordner (je Version ein Unterordner)
        try (Stream<Path> st = Files.walk(ed.konfig.resolve("jdtls-config"), 2)) {
            st.filter(p -> p.getFileName().toString().endsWith(".log")).forEach(logs::add);
        } catch (IOException e) {
            // keiner
        }
        List<String> r = new ArrayList<>();
        for (Path p : logs) {
            try {
                if (Files.getLastModifiedTime(p).toMillis() < seit) continue;
                List<String> z = Files.readAllLines(p, StandardCharsets.UTF_8);
                for (int i = Math.max(0, z.size() - 300); i < z.size(); i++) {
                    String t = z.get(i).strip();
                    if (t.startsWith("!MESSAGE") || t.startsWith("Caused by:") || (t.matches("^[a-z][\\w.]*(Exception|Error)\\b.*") && !t.startsWith("at "))) {
                        String x = t.replace("!MESSAGE ", "");
                        if (!r.contains(x)) r.add(x);
                    }
                }
            } catch (IOException | RuntimeException e) {
                // nicht lesbar
            }
        }
        return r.size() > 8 ? r.subList(r.size() - 8, r.size()) : r;
    }

    /** Eclipse-Konfiguration je jdtls-Version: ein alter Zwischenspeicher kann nicht stoeren */
    private Path konfigOrdner(Path launcher) {
        // nach der jdtls-Version (Kern-Plugin), der Launcher bleibt ueber Versionen oft gleich
        String v = "jdtls";
        try (Stream<Path> st = Files.list(launcher.getParent())) {
            v = st.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("org.eclipse.jdt.ls.core_")).findFirst().orElse("jdtls")
                    .replace("org.eclipse.jdt.ls.core_", "").replace(".jar", "");
        } catch (IOException e) {
            // Name reicht
        }
        return ed.konfig.resolve("jdtls-config").resolve(v + "-" + Integer.toHexString(launcher.toAbsolutePath().toString().hashCode()));
    }

    /** nach einem Abbruch: Eclipse-Konfiguration und Arbeitsdaten verwerfen (beim Neustart neu angelegt) */
    void jdtlsAufraeumen() {
        try {
            loeschen(ed.konfig.resolve("jdtls-config"));
            if (ed.javaOrdner != null) loeschen(datenOrdner());
        } catch (IOException e) {
            ed.farbig("jdtls-Ordner nicht ganz geloescht (laeuft noch ein alter jdtls?): " + e.getMessage(), E_WARN);
        }
    }

    private Path datenOrdner() {
        return ed.konfig.resolve("jdtls-daten").resolve(Editor.name(ed.javaOrdner) + "-" + Integer.toHexString(ed.javaOrdner.toString().hashCode()));
    }

    /** Launcher, Kern-Plugin und eine Konfiguration muessen da sein */
    private static boolean vollstaendig(Path home) {
        if (launcher(home) == null) return false;
        try (Stream<Path> s = Files.list(home.resolve("plugins"))) {
            if (s.noneMatch(p -> p.getFileName().toString().startsWith("org.eclipse.jdt.ls.core_"))) return false;
        } catch (IOException e) {
            return false;
        }
        try (Stream<Path> s = Files.list(home)) {
            return s.anyMatch(p -> p.getFileName().toString().startsWith("config_") && Files.isDirectory(p));
        } catch (IOException e) {
            return false;
        }
    }

    private static Path launcher(Path home) {
        try (Stream<Path> s = Files.list(home.resolve("plugins"))) {
            return s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("org.eclipse.equinox.launcher_") && n.endsWith(".jar");
            }).findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** jdtls aus der Jar nach jdtls-eingebaut entpacken (nur wenn die Version neu ist); null ohne */
    private Path eingebautesJdtls() throws IOException {
        Path quelle;
        try {
            quelle = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            return null;
        }
        if (Files.isDirectory(quelle)) {
            // beim Entwickeln: out/jdtls direkt
            Path d = quelle.resolve("jdtls");
            return vollstaendig(d) ? d : null;
        }
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(quelle.toFile())) {
            java.util.zip.ZipEntry v = z.getEntry("jdtls/VERSION");
            if (v == null) return null;
            String version;
            try (InputStream in = z.getInputStream(v)) {
                version = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            }
            Path ziel = ed.konfig.resolve("jdtls-eingebaut");
            Path marke = ziel.resolve("VERSION");
            if (Files.exists(marke) && Files.readString(marke).strip().equals(version) && vollstaendig(ziel)) return ziel;
            SwingUtilities.invokeLater(() -> {
                ed.javaLsp.status = "entpackt";
                ed.meldung = "Java Language Server wird entpackt (einmalig) ...";
                ed.neuMalen.run();
            });
            loeschen(ziel);
            var eintraege = z.entries();
            while (eintraege.hasMoreElements()) {
                java.util.zip.ZipEntry e = eintraege.nextElement();
                String n = e.getName();
                if (!n.startsWith("jdtls/") || n.equals("jdtls/VERSION")) continue;
                Path p = ziel.resolve(n.substring(6)).normalize();
                if (!p.startsWith(ziel)) continue;
                if (e.isDirectory()) {
                    Files.createDirectories(p);
                } else {
                    Files.createDirectories(p.getParent());
                    try (InputStream in = z.getInputStream(e)) {
                        Files.copy(in, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            Files.writeString(marke, version);
            return ziel;
        }
    }

    private static void loeschen(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(x);
        }
    }

    private List<String> javaLspBefehl(Path h, String java) {
        Path launcher = launcher(h);
        String os = System.getProperty("os.name").toLowerCase();
        String sys = os.contains("win") ? "win" : os.contains("mac") ? "mac" : "linux";
        boolean arm = System.getProperty("os.arch").contains("aarch64") || System.getProperty("os.arch").contains("arm");
        Path cfg = h.resolve("config_" + sys + (arm ? "_arm" : ""));
        if (!Files.isDirectory(cfg)) cfg = h.resolve("config_" + sys);
        return new ArrayList<>(List.of(java, "-Declipse.application=org.eclipse.jdt.ls.core.id1", "-Dosgi.bundles.defaultStartLevel=4",
                "-Declipse.product=org.eclipse.jdt.ls.core.product", "-Dosgi.checkConfiguration=true", "-Dosgi.sharedConfiguration.area=" + cfg,
                "-Dosgi.sharedConfiguration.area.readOnly=true", "-Dosgi.configuration.cascaded=true", "-Xms256m", "-Xmx" + ed.kf.get("java.lsp.memory", "2G"),
                "--add-modules=ALL-SYSTEM", "--add-opens", "java.base/java.util=ALL-UNNAMED", "--add-opens", "java.base/java.lang=ALL-UNNAMED", "-jar",
                launcher.toString(), "-configuration", konfigOrdner(launcher).toString(), "-data", datenOrdner().toString()));
    }

    /** rechts in der Statuszeile */
    String status() {
        StringBuilder b = new StringBuilder();
        Doc d = ed.d();
        if ("sql".equals(d.sprache())) {
            String k = konto(d);
            b.append("DB ").append(k == null ? "-" : k).append(db.beschaeftigt ? " laeuft" : "").append("   ");
        }
        if (ed.arbeitsordner != null) b.append("TS ").append(ed.tsLsp.status);
        if (ed.javaOrdner != null) b.append(b.length() > 0 ? "  " : "").append("Java ").append(ed.javaLsp.status);
        return b.toString();
    }

    // ================================================================ Auswahlliste
    boolean auswahlOffen;
    private String auswahlTitel = "", auswahlVorModus = "normal";
    private List<String> auswahlAlle = List.of();
    List<String> auswahlListe = List.of();
    private int auswahlWahl;
    private final StringBuilder auswahlFilter = new StringBuilder();
    private Consumer<String> auswahlAktion;

    void auswahl(String titel, List<String> eintraege, String vorwahl, Consumer<String> aktion) {
        if (!auswahlOffen) auswahlVorModus = ed.modus;
        auswahlOffen = true;
        auswahlTitel = titel;
        auswahlAlle = List.copyOf(eintraege);
        auswahlAktion = aktion;
        auswahlFilter.setLength(0);
        filtern();
        int i = vorwahl == null ? -1 : auswahlListe.indexOf(vorwahl);
        auswahlWahl = Math.max(0, i);
        ed.modus = "picker";
    }

    private void filtern() {
        String q = auswahlFilter.toString().toLowerCase();
        if (q.isEmpty()) {
            auswahlListe = auswahlAlle;
        } else {
            List<String> l = new ArrayList<>();
            List<int[]> w = new ArrayList<>();
            for (int k = 0; k < auswahlAlle.size(); k++) {
                int b = Indexer.bewerten(auswahlAlle.get(k).toLowerCase(), q, null);
                if (b >= 0) w.add(new int[] {b, k});
            }
            w.sort((x, y) -> y[0] != x[0] ? y[0] - x[0] : x[1] - y[1]);
            for (int[] x : w) l.add(auswahlAlle.get(x[1]));
            auswahlListe = l;
        }
        auswahlWahl = 0;
    }

    private void auswahlZu() {
        auswahlOffen = false;
        if (ed.modus.equals("picker")) ed.modus = auswahlVorModus;
    }

    void auswahlMalen() {
        Screen s = ed.scr;
        int cw = s.cw, lh = s.lh;
        int n = Math.min(15, Math.max(1, auswahlListe.size()));
        int breite = auswahlTitel.length() + 4;
        for (String e : auswahlListe) breite = Math.max(breite, e.length() + 6);
        int w = Math.min(s.w - 4 * cw, Math.max(60, breite) * cw);
        int h = (n + 3) * lh + 16;
        int x = (s.w - w) / 2, y = (s.h - h) / 3;
        s.rect(x + 6, y + 6, w, h, 0);
        s.rect(x, y, w, h, E_KONSOLE);
        s.frame(x, y, w, h, E_HELL);
        s.frame(x + 3, y + 3, w - 6, h - 6, E_MATT);
        s.clip(x, y, w, h);
        s.text(x + 2 * cw, y + 8, auswahlTitel, E_HELL);
        int fy = y + 8 + lh + 4;
        int fx = s.text(x + 2 * cw, fy, "> " + auswahlFilter, E_TEXT);
        s.rect(fx + 1, fy + 1, cw - 2, lh - 2, E_TEXT);
        int oben = Math.max(0, Math.min(auswahlWahl - n / 2, auswahlListe.size() - n));
        for (int k = 0; k < n && oben + k < auswahlListe.size(); k++) {
            int i = oben + k;
            int zy = fy + lh + 6 + k * lh;
            boolean g = i == auswahlWahl;
            if (g) s.rect(x + 4, zy, w - 8, lh, E_TEXT);
            s.text(x + 2 * cw, zy, auswahlListe.get(i), g ? E_GRUND : E_TEXT);
        }
        if (auswahlListe.isEmpty()) s.text(x + 2 * cw, fy + lh + 6, "(nichts passt)", E_MATT);
        s.noClip();
    }

    // ================================================================ Datenbank
    private String letztesKonto;

    /** Ergebnistabelle einer SQL-Datei: Zeilen, Filter, Sortierung und Position */
    static final class Tabelle {
        final Db.Ergebnis erg;
        boolean offen = true;
        /** gewaehlte Zeile (-1 = Kopfzeile) und Spalte, Bildlauf */
        int ez, es, oben, links;
        /** je Spalte die Filter: {art, wert} */
        final Map<Integer, List<String[]>> filter = new java.util.TreeMap<>();
        int sortSpalte = -1;
        boolean sortAuf = true;
        private List<String[]> sicht;

        Tabelle(Db.Ergebnis erg) {
            this.erg = erg;
        }

        /** sichtbare Zeilen nach Filter und Sortierung */
        List<String[]> zeilen() {
            if (sicht != null) return sicht;
            List<String[]> l = new ArrayList<>();
            for (String[] z : erg.zeilen()) {
                boolean ok = true;
                for (var f : filter.entrySet()) for (String[] x : f.getValue()) ok &= passt(z[f.getKey()], x[0], x[1]);
                if (ok) l.add(z);
            }
            if (sortSpalte >= 0) {
                int c = sortSpalte;
                java.util.Comparator<String[]> v = (p, q) -> vergleichen(p[c], q[c]);
                l.sort(sortAuf ? v : v.reversed());
            }
            sicht = l;
            return l;
        }

        void geaendert() {
            sicht = null;
            ez = Math.min(ez, Math.max(0, zeilen().size() - 1));
            oben = 0;
        }

        static int vergleichen(String a, String b) {
            if (a == null || b == null) return a == null ? (b == null ? 0 : -1) : 1;
            try {
                return Double.compare(Double.parseDouble(a.replace(',', '.')), Double.parseDouble(b.replace(',', '.')));
            } catch (NumberFormatException e) {
                return a.compareToIgnoreCase(b);
            }
        }

        static boolean passt(String v, String art, String w) {
            String a = v == null ? "" : v.toLowerCase(), b = w == null ? "" : w.toLowerCase();
            return switch (art) {
                case "enthaelt" -> a.contains(b);
                case "enthaeltNicht" -> !a.contains(b);
                case "gleich" -> v != null && vergleichen(v, w) == 0 && (a.equals(b) || istZahl(v));
                case "ungleich" -> v == null || !(vergleichen(v, w) == 0 && (a.equals(b) || istZahl(v)));
                case "beginnt" -> a.startsWith(b);
                case "endet" -> a.endsWith(b);
                case "groesser" -> v != null && vergleichen(v, w) > 0;
                case "kleiner" -> v != null && vergleichen(v, w) < 0;
                case "leer" -> v == null || v.isEmpty();
                case "nichtLeer" -> v != null && !v.isEmpty();
                default -> true;
            };
        }

        private static boolean istZahl(String v) {
            try {
                Double.parseDouble(v.replace(',', '.'));
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
    }

    /** Tabelle der aktuellen Datei (oder null) */
    Tabelle tabelle() {
        return ed.d().tabelle;
    }

    boolean tabelleOffen() {
        Tabelle t = tabelle();
        return t != null && t.offen;
    }

    String konto(Doc d) {
        if (d.dbKonto != null && db.konten.containsKey(d.dbKonto)) return d.dbKonto;
        if (letztesKonto != null && db.konten.containsKey(letztesKonto)) return letztesKonto;
        return db.konten.isEmpty() ? null : db.konten.keySet().iterator().next();
    }

    private boolean sqlDatei(Doc d) {
        if ("sql".equals(d.sprache())) return true;
        ed.meldung = "keine SQL-Datei (.sql)";
        return false;
    }

    private void sqlLaufen(boolean ganz) {
        Doc d = ed.d();
        if (!sqlDatei(d)) return;
        String k = konto(d);
        if (k == null) {
            ed.fehler("kein Datenbankkonto: db.account.NAME = URL | BENUTZER | PASSWORT in config.txt");
            return;
        }
        List<String> sqls = new ArrayList<>();
        if (ed.modus.equals("visual")) {
            int[] r = ed.vim.auswahl();
            ed.vim.visualEnde();
            String t = d.t.substring(r[0], r[1]);
            for (Db.Bereich b : Db.statements(t)) sqls.add(t.substring(b.anf(), b.ende()));
        } else if (ganz) {
            for (Db.Bereich b : Db.statements(d.t)) sqls.add(d.t.substring(b.anf(), b.ende()));
        } else {
            Db.Bereich b = Db.amCursor(d.t, d.cursor);
            if (b != null) sqls.add(d.t.substring(b.anf(), b.ende()));
        }
        if (sqls.isEmpty()) {
            ed.meldung = "kein Statement gefunden";
            return;
        }
        if (db.beschaeftigt) {
            ed.fehler("die Datenbank arbeitet noch (sqlCancel bricht ab)");
            return;
        }
        d.dbKonto = k;
        letztesKonto = k;
        ed.meldung = "[" + k + "] " + sqls.size() + (sqls.size() == 1 ? " Statement" : " Statements") + " laufen ...";
        ed.meldungFarbe = E_WARN;
        db.ausfuehren(k, sqls, e -> SwingUtilities.invokeLater(() -> {
            zeigen(d, e);
            ed.neuMalen.run();
        }));
    }

    /** Ergebnis gehoert zu der Datei, in der es ausgefuehrt wurde */
    private void zeigen(Doc d, Db.Ergebnis e) {
        String erste = e.sql().strip().split("\\R", 2)[0];
        if (erste.length() > 100) erste = erste.substring(0, 100) + " ...";
        ed.farbig("SQL [" + e.konto() + "] " + erste, E_HELL);
        for (String z : e.ausgabe()) ed.farbig("  " + z, E_INFO);
        if (e.fehler()) {
            ed.fehler("[" + e.konto() + "] " + e.meldung().replace('\n', ' '));
            return;
        }
        ed.farbig("  " + e.meldung(), E_TEXT);
        ed.meldung = "[" + e.konto() + "] " + e.meldung() + (e.ausgabe().isEmpty() ? "" : "  (DBMS_OUTPUT im Terminal)");
        ed.meldungFarbe = E_WARN;
        if (e.spalten() != null) {
            d.tabelle = new Tabelle(e);
            if (d == ed.d()) {
                ed.konsoleOffen = false;
                if (ed.modus.equals("normal") || ed.modus.equals("console") || ed.modus.equals("result")) ed.modus = "result";
            }
        }
    }

    private void ergBewegen(int dz, int ds) {
        Tabelle t = tabelle();
        if (t == null) return;
        int n = t.zeilen().size();
        // ueber der ersten Zeile liegt die Kopfzeile (-1)
        t.ez = Math.max(-1, Math.min(n - 1, t.ez + dz));
        t.es = Math.max(0, Math.min(t.erg.spalten().size() - 1, t.es + ds));
    }

    private String zelle() {
        Tabelle t = tabelle();
        if (t == null || t.ez < 0 || t.ez >= t.zeilen().size()) return null;
        String v = t.zeilen().get(t.ez)[t.es];
        return v == null ? "" : v;
    }

    private int ergZeilen() {
        return Math.max(1, (ed.scr.h - ed.unten() - 6 - ergebnisY() - 3 * ed.scr.lh) / ed.scr.lh);
    }

    int ergebnisY() {
        return ed.oben() + ed.textZeilen() * ed.scr.lh + 6;
    }

    void ergebnisMalen(int y0) {
        Tabelle t = tabelle();
        Db.Ergebnis erg = t.erg;
        Screen s = ed.scr;
        int W = s.w, lh = s.lh, cw = s.cw;
        int h = s.h - ed.unten() - 6 - y0;
        boolean fokus = ed.modus.equals("result");
        s.rect(0, y0, W, h, E_KONSOLE);
        s.frame(4, y0, W - 8, h, fokus ? E_HELL : E_TEXT);
        s.frame(5, y0 + 1, W - 10, h - 2, E_MATT);
        List<String[]> zl = t.zeilen();
        boolean gefiltert = !t.filter.isEmpty();
        String anzahl = gefiltert ? zl.size() + " von " + erg.zeilen().size() + " Zeilen (gefiltert)" : erg.meldung();
        String titel = " ERGEBNIS [" + erg.konto() + "] " + anzahl
                + (fokus ? "  (hjkl, K Kopfzeile, ENTER Filter/Wert, y Zelle, Y Zeile, ESC zurueck, q zu) " : "  (F4: hinein, Leertaste O: zu) ");
        s.rect(3 * cw, y0 - lh / 2, s.textWidth(titel), lh, E_KONSOLE);
        s.text(3 * cw, y0 - lh / 2, titel, E_HELL);
        List<String> sp = erg.spalten();
        int[] breite = new int[sp.size()];
        for (int c = 0; c < sp.size(); c++) {
            int b = Math.max(4, kopf(t, c).length());
            for (int r = 0; r < Math.min(zl.size(), 2000); r++) {
                String v = zl.get(r)[c];
                b = Math.max(b, v == null ? 6 : Math.min(50, laenge(v)));
            }
            breite[c] = Math.min(50, b);
        }
        int nrBreite = String.valueOf(Math.max(1, zl.size())).length() + 1;
        int x0 = 2 * cw, platz = (W - 4 * cw) / cw - nrBreite;
        if (t.es < t.links) t.links = t.es;
        while (true) {
            int summe = 0;
            for (int c = t.links; c <= t.es; c++) summe += breite[c] + 3;
            if (summe <= platz || t.links >= t.es) break;
            t.links++;
        }
        int n = ergZeilen();
        if (t.ez >= 0 && t.ez < t.oben) t.oben = t.ez;
        if (t.ez >= t.oben + n) t.oben = t.ez - n + 1;
        int y = y0 + lh / 2 + 4;
        s.clip(8, y0 + 2, W - 16, h - 4);
        int x = x0 + nrBreite * cw;
        for (int c = t.links; c < sp.size() && x < W - 2 * cw; c++) {
            boolean kopfJa = fokus && t.ez < 0 && c == t.es;
            if (kopfJa) s.rect(x - cw / 2, y, (breite[c] + 1) * cw, lh, E_WARN);
            boolean mitFilter = t.filter.containsKey(c) || t.sortSpalte == c;
            s.text(x, y, zuschneiden(kopf(t, c), breite[c]), kopfJa ? E_GRUND : mitFilter ? E_HELL : E_WARN);
            x += (breite[c] + 3) * cw;
        }
        s.rect(x0, y + lh + 1, W - 2 * x0, 1, E_MATT);
        for (int k = 0; k < n && t.oben + k < zl.size(); k++) {
            int r = t.oben + k;
            int zy = y + lh + 4 + k * lh;
            if (r == t.ez) s.rect(x0, zy, W - 2 * x0, lh, E_ZEILE);
            s.text(x0, zy, String.valueOf(r + 1), E_MATT);
            x = x0 + nrBreite * cw;
            for (int c = t.links; c < sp.size() && x < W - 2 * cw; c++) {
                String v = zl.get(r)[c];
                boolean zelleJa = fokus && r == t.ez && c == t.es;
                if (zelleJa) s.rect(x - cw / 2, zy, (breite[c] + 1) * cw, lh, E_TEXT);
                int f = zelleJa ? E_GRUND : v == null ? E_MATT : E_TEXT;
                s.text(x, zy, zuschneiden(v == null ? "(null)" : v.replace('\n', ' ').replace('\t', ' '), breite[c]), f);
                x += (breite[c] + 3) * cw;
            }
        }
        if (zl.isEmpty()) s.text(x0, y + lh + 4, gefiltert ? "(kein Treffer fuer den Filter)" : "(keine Zeilen)", E_MATT);
        s.noClip();
    }

    /** Spaltenkopf mit Zeichen fuer Filter (*) und Sortierung (^ v) */
    private static String kopf(Tabelle t, int c) {
        String k = t.erg.spalten().get(c);
        if (t.filter.containsKey(c)) k += "*";
        if (t.sortSpalte == c) k += t.sortAuf ? " ^" : " v";
        return k;
    }

    private static int laenge(String s) {
        int n = s.indexOf('\n');
        return n < 0 ? s.length() : n;
    }

    private static String zuschneiden(String s, int b) {
        return s.length() <= b ? s : s.substring(0, Math.max(0, b - 1)) + "~";
    }

    // ---------------------------------------------------------------- SQL-Vervollstaendigung
    /** Metadaten je Konto: Tabelle -> Spalten */
    private final Map<String, Map<String, List<String>>> meta = new java.util.HashMap<>();
    private final java.util.Set<String> metaLaedt = new java.util.HashSet<>();
    /** "tabelle alias" an jeder Wortgrenze (ueberlappend: "from kunde k" liefert auch "kunde k") */
    private static final Pattern ALIAS = Pattern.compile("(?<![\\w$#\"])(?=([\\w$#\"]+)\\s+(?:as\\s+)?([\\w$#]+))", Pattern.CASE_INSENSITIVE);

    private static boolean sqlZeichen(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }

    private void metaLaden(String k, Runnable danach) {
        if (!metaLaedt.add(k)) return;
        ed.meldung = "[" + k + "] Metadaten werden geladen ...";
        db.metadaten(k, m -> SwingUtilities.invokeLater(() -> {
            metaLaedt.remove(k);
            meta.put(k, m);
            int spalten = 0;
            for (List<String> l : m.values()) spalten += l.size();
            ed.meldung = "[" + k + "] " + m.size() + " Tabellen/Views, " + spalten + " Spalten geladen";
            if (danach != null) danach.run();
            ed.neuMalen.run();
        }), f -> SwingUtilities.invokeLater(() -> {
            metaLaedt.remove(k);
            ed.fehler("[" + k + "] Metadaten: " + f);
            ed.neuMalen.run();
        }));
    }

    void sqlVervollstaendigen() {
        Doc d = ed.d();
        String k = konto(d);
        if (k == null) {
            ed.meldung = "kein Datenbankkonto (db.account.NAME in config.txt)";
            return;
        }
        Map<String, List<String>> m = meta.get(k);
        if (m == null) {
            int cur = d.cursor, ver = d.version;
            metaLaden(k, () -> {
                if (ed.d() == d && d.cursor == cur && d.version == ver) sqlVervollstaendigen();
            });
            return;
        }
        int anf = d.cursor;
        while (anf > 0 && sqlZeichen(d.at(anf - 1))) anf--;
        String wort = d.t.substring(anf, d.cursor);
        boolean klein = !wort.isEmpty() && wort.equals(wort.toLowerCase());
        // Statement um den Cursor: Tabellen und Aliase daraus
        Db.Bereich b = Db.amCursor(d.t, Math.max(0, anf - 1));
        String stmt = b == null ? d.t.toString() : d.t.substring(b.anf(), Math.min(d.t.length(), Math.max(b.ende(), d.cursor)));
        Map<String, String> aliase = new java.util.HashMap<>();
        java.util.Set<String> benutzt = new java.util.LinkedHashSet<>();
        Matcher mm = ALIAS.matcher(stmt);
        while (mm.find()) {
            String tab = mm.group(1).replace("\"", ""), al = mm.group(2);
            if (tab.contains(".")) tab = tab.substring(tab.lastIndexOf('.') + 1);
            if (m.containsKey(tab)) {
                benutzt.add(tab);
                if (!Highlight.SQL.contains(al.toLowerCase())) aliase.put(al.toLowerCase(), tab);
            }
        }
        for (String w : stmt.split("[^\\w$#]+")) if (m.containsKey(w)) benutzt.add(w);
        List<Map<String, Object>> l = new ArrayList<>();
        if (anf > 0 && d.at(anf - 1) == '.') {
            // nach alias. oder tabelle.: die Spalten dieser Tabelle
            int q = anf - 1;
            while (q > 0 && sqlZeichen(d.at(q - 1))) q--;
            String vor = d.t.substring(q, anf - 1);
            String tab = m.containsKey(vor) ? vor : aliase.get(vor.toLowerCase());
            if (tab == null) {
                ed.meldung = "unbekannte Tabelle oder Alias: " + vor;
                return;
            }
            for (String sp : m.get(tab)) l.add(vorschlag(sp, 5, "Spalte von " + tab, klein));
        } else {
            for (String tab : benutzt) for (String sp : m.get(tab)) l.add(vorschlag(sp, 5, "Spalte von " + tab, klein));
            for (String tab : m.keySet()) l.add(vorschlag(tab, 7, "Tabelle (" + m.get(tab).size() + " Spalten)", klein));
            for (String w : new java.util.TreeSet<>(Highlight.SQL)) l.add(vorschlag(w.toUpperCase(), 14, "", klein));
        }
        ed.vorschlaegeZeigen(l, anf);
    }

    private static Map<String, Object> vorschlag(String name, int art, String detail, boolean klein) {
        return Json.obj("item", Json.obj("label", name, "kind", art, "detail", detail, "insertText", klein ? name.toLowerCase() : name, "filterText", name,
                "sortText", (art == 5 ? "1" : art == 7 ? "2" : "3") + name));
    }

    // ---------------------------------------------------------------- Filtermenue der Kopfzeile
    private static final String[][] FILTER = {
            {"enthaelt ...", "enthaelt"}, {"enthaelt nicht ...", "enthaeltNicht"}, {"ist gleich ...", "gleich"}, {"ist ungleich ...", "ungleich"},
            {"beginnt mit ...", "beginnt"}, {"endet mit ...", "endet"}, {"groesser als ...", "groesser"}, {"kleiner als ...", "kleiner"},
            {"ist leer (NULL)", "leer"}, {"ist nicht leer", "nichtLeer"}, {"aufsteigend sortieren", "auf"}, {"absteigend sortieren", "ab"},
            {"Filter dieser Spalte entfernen", "weg"}, {"alle Filter und Sortierung entfernen", "alleWeg"}, {"Spalte kopieren (sichtbare Werte)", "kopieren"}};
    private String filterArt;

    private void filterMenue() {
        Tabelle t = tabelle();
        if (t == null) return;
        String spalte = t.erg.spalten().get(t.es);
        List<String> l = new ArrayList<>();
        for (String[] f : FILTER) l.add(f[0]);
        auswahl("Spalte " + spalte + ": filtern / sortieren", l, null, w -> {
            String art = null;
            for (String[] f : FILTER) if (f[0].equals(w)) art = f[1];
            if (art == null) return;
            switch (art) {
                case "auf", "ab" -> {
                    t.sortSpalte = t.es;
                    t.sortAuf = art.equals("auf");
                    t.geaendert();
                }
                case "weg" -> {
                    t.filter.remove(t.es);
                    if (t.sortSpalte == t.es) t.sortSpalte = -1;
                    t.geaendert();
                }
                case "alleWeg" -> {
                    t.filter.clear();
                    t.sortSpalte = -1;
                    t.geaendert();
                }
                case "leer", "nichtLeer" -> filterSetzen(t, art, "");
                case "kopieren" -> {
                    StringBuilder b = new StringBuilder();
                    for (String[] z : t.zeilen()) b.append(z[t.es] == null ? "" : z[t.es]).append('\n');
                    Editor.zwischenablage(b.toString());
                    ed.meldung = t.zeilen().size() + " Werte von " + spalte + " kopiert";
                }
                default -> {
                    filterArt = art;
                    String vorgabe = t.ez >= 0 && t.ez < t.zeilen().size() && t.zeilen().get(t.ez)[t.es] != null ? t.zeilen().get(t.ez)[t.es] : "";
                    ed.popupOpen(spalte + " " + w.replace(" ...", "") + ":", "resultFilterValue", art.equals("enthaelt") ? "" : vorgabe);
                }
            }
        });
    }

    private void filterSetzen(Tabelle t, String art, String wert) {
        t.filter.computeIfAbsent(t.es, k -> new ArrayList<>()).add(new String[] {art, wert});
        t.geaendert();
        ed.meldung = t.zeilen().size() + " von " + t.erg.zeilen().size() + " Zeilen" + (t.erg.mehr() ? " (gefiltert wird ueber die geladenen Zeilen, db.maxRows)" : "");
        if (ed.modus.equals("normal")) ed.modus = "result";
    }

    // ================================================================ Von Methode zu Methode
    private void methode(boolean vor) {
        int n = ed.vim.nehmen();
        Doc d = ed.d();
        Lsp l = ed.lspFuer(d);
        if (l != null && l.an() && d.pfad != null) {
            ed.lspSync();
            l.anfrage("textDocument/documentSymbol", Json.obj("textDocument", Json.obj("uri", Lsp.uri(d.pfad))), r -> {
                TreeSet<Integer> z = new TreeSet<>();
                symbole(d, Json.list(r), z);
                if (z.isEmpty()) heuristik(d, z);
                springen(d, z, vor, n);
                ed.neuMalen.run();
            });
            return;
        }
        TreeSet<Integer> z = new TreeSet<>();
        heuristik(d, z);
        springen(d, z, vor, n);
    }

    private static void symbole(Doc d, List<Object> l, TreeSet<Integer> z) {
        for (Object o : l) {
            int art = Json.num(Json.get(o, "kind"), 0);
            Object start = Json.get(o, "selectionRange", "start");
            if (start == null) start = Json.get(o, "location", "range", "start");
            if (start == null) start = Json.get(o, "range", "start");
            int zeile = Json.num(Json.get(start, "line"), -1);
            boolean nimm = art == 6 || art == 9 || art == 12;
            if ((art == 13 || art == 14 || art == 7) && zeile >= 0 && zeile < d.zeilen()) {
                // const f = () => ... und f: function() ...
                String t = d.zeile(zeile);
                nimm = t.contains("=>") || t.contains("function");
            }
            if (nimm && zeile >= 0) z.add(zeile);
            symbole(d, Json.list(Json.get(o, "children")), z);
        }
    }

    private static final Pattern METHODE = Pattern.compile(
            "^\\s*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|async|export|override|function)\\s+)+[^=;()]*\\(.*|"
                    + "^\\s*(?:export\\s+)?(?:const|let|var)\\s+\\w+\\s*=\\s*(?:async\\s*)?(?:\\([^)]*\\)|\\w+)\\s*(?::[^=]+)?=>.*|"
                    + "^\\s*(?:create\\s+(?:or\\s+replace\\s+)?)?(?:procedure|function|trigger|package(?:\\s+body)?)\\s+\\S+.*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern KEIN = Pattern.compile("^\\s*(if|for|while|switch|catch|return|new|else|do|try)\\b.*");

    /** ohne Language Server: Zeilen, die nach Methode/Funktion aussehen */
    private static void heuristik(Doc d, TreeSet<Integer> z) {
        for (int i = 0; i < d.zeilen(); i++) {
            String t = d.zeile(i);
            if (t.length() < 400 && METHODE.matcher(t).matches() && !KEIN.matcher(t).matches()) z.add(i);
        }
    }

    private void springen(Doc d, TreeSet<Integer> zeilen, boolean vor, int n) {
        int z = d.zeileVon(d.cursor);
        Integer ziel = z;
        for (int i = 0; i < n; i++) {
            Integer x = vor ? zeilen.higher(ziel) : zeilen.lower(ziel);
            if (x == null) break;
            ziel = x;
        }
        if (ziel == z) {
            ed.meldung = vor ? "keine Methode mehr darunter" : "keine Methode mehr darueber";
            return;
        }
        int p = d.anf(ziel);
        while (d.at(p) == ' ' || d.at(p) == '\t') p++;
        d.cursor = p;
        d.spalteMerk = -1;
    }

    // ================================================================ Herunterladen (jdtls, js-debug)
    private volatile boolean laedt;

    private void installieren(String was, String ziel, Runnable fertig) {
        if (laedt) {
            ed.meldung = "es wird schon etwas geladen";
            return;
        }
        laedt = true;
        Path ordner = ed.konfig.resolve(ziel);
        ed.print("lade " + was + " nach " + ordner + " ...");
        ed.meldung = "lade " + was + " ...";
        hintergrund(() -> {
            String fehler = null;
            try {
                String url = jsDebugUrl();
                Laden.tarGz(url, ordner, mb -> SwingUtilities.invokeLater(() -> {
                    ed.meldung = was + ": " + mb;
                    ed.neuMalen.run();
                }));
            } catch (Exception e) {
                fehler = e.getMessage();
            }
            String f = fehler;
            SwingUtilities.invokeLater(() -> {
                laedt = false;
                if (f != null) {
                    ed.fehler(was + " nicht geladen: " + f);
                } else {
                    ed.print(was + " ist da: " + ordner);
                    fertig.run();
                }
                ed.neuMalen.run();
            });
        });
    }

    /** neueste js-debug-dap-Version von GitHub */
    private static String jsDebugUrl() throws IOException, InterruptedException {
        String j = Laden.text("https://api.github.com/repos/microsoft/vscode-js-debug/releases/latest");
        for (Object a : Json.list(Json.get(Json.parse(j), "assets"))) {
            String n = Json.str(Json.get(a, "name"));
            if (n != null && n.startsWith("js-debug-dap") && n.endsWith(".tar.gz")) return Json.str(Json.get(a, "browser_download_url"));
        }
        throw new IOException("kein js-debug-dap-*.tar.gz im neuesten Release");
    }

    // ================================================================ Spring Boot
    private Process app;
    private boolean appDebug;

    /** Profile aus application-*.yml/.properties und spring.profiles in config.txt */
    private List<String> profile() {
        TreeSet<String> p = new TreeSet<>(List.of(ed.kf.get("spring.profiles", "dev").split("[\\s,]+")));
        if (ed.javaOrdner != null) {
            for (Indexer.Datei d : ed.index.dateien) {
                String n = d.pfad().getFileName().toString();
                if (!d.pfad().startsWith(ed.javaOrdner) || !n.startsWith("application-")) continue;
                int e = n.lastIndexOf('.');
                String art = e < 0 ? "" : n.substring(e + 1);
                if (art.equals("yml") || art.equals("yaml") || art.equals("properties")) p.add(n.substring(12, e));
            }
        }
        p.remove("");
        return new ArrayList<>(p);
    }

    /** Spring-Boot-Anwendung: Modul (Ordner mit pom.xml/build.gradle) und Klasse mit main */
    record BootApp(Path modul, String klasse) {}

    /** Klassen mit @SpringBootApplication unter src/main im Java-Arbeitsbereich */
    private List<BootApp> bootApps() {
        List<BootApp> r = new ArrayList<>();
        String modul = ed.kf.get("spring.module", null), klasse = ed.kf.get("spring.mainClass", null);
        if (modul != null || klasse != null) {
            r.add(new BootApp(modul == null ? ed.javaOrdner : ed.javaOrdner.resolve(modul).normalize(), klasse));
            return r;
        }
        for (Indexer.Datei d : ed.index.dateien) {
            Path p = d.pfad();
            String n = p.toString().replace('\\', '/');
            if (!p.startsWith(ed.javaOrdner) || !n.endsWith(".java") || !n.contains("/src/main/")) continue;
            String t;
            try {
                t = Files.readString(p, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                continue;
            }
            if (!t.contains("@SpringBootApplication")) continue;
            Matcher m = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(t);
            String name = p.getFileName().toString().replace(".java", "");
            String fq = m.find() ? m.group(1) + "." + name : name;
            Path mod = p.getParent();
            while (mod != null && mod.startsWith(ed.javaOrdner) && !Files.exists(mod.resolve("pom.xml")) && !Files.exists(mod.resolve("build.gradle"))
                    && !Files.exists(mod.resolve("build.gradle.kts"))) {
                mod = mod.getParent();
            }
            r.add(new BootApp(mod == null || !mod.startsWith(ed.javaOrdner) ? ed.javaOrdner : mod, fq));
        }
        return r;
    }

    /**
     * Startbefehl: im Modul der Anwendung, mit ihrer Main-Klasse. Liegt sie in einem Untermodul,
     * werden zuerst die Module gebaut, von denen sie abhaengt.
     */
    private String springBefehl(String profil, boolean debug, BootApp app) {
        int port = ed.kf.zahl("debug.java.port", 5005);
        String agent = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:" + port;
        String eigen = ed.kf.get(debug ? "spring.debug" : "spring.run", null);
        if (eigen != null) return eigen.replace("{profile}", profil).replace("{port}", String.valueOf(port));
        Path j = ed.javaOrdner;
        boolean win = Lsp.WINDOWS;
        Path modul = app == null ? j : app.modul();
        String rel = j.relativize(modul).toString().replace('\\', '/');
        if (Files.exists(j.resolve("pom.xml"))) {
            String mvn = Files.exists(j.resolve(win ? "mvnw.cmd" : "mvnw")) ? (win ? "mvnw.cmd" : "./mvnw") : "mvn";
            String start = " spring-boot:run -Dspring-boot.run.profiles=" + profil
                    + (app != null && app.klasse() != null ? " -Dspring-boot.run.main-class=" + app.klasse() + " -Dstart-class=" + app.klasse() : "")
                    + (debug ? " \"-Dspring-boot.run.jvmArguments=" + agent + "\"" : "");
            if (rel.isEmpty()) return mvn + start;
            return mvn + " -q install -DskipTests -pl " + rel + " -am && " + mvn + " -f " + rel + "/pom.xml" + start;
        }
        if (Files.exists(j.resolve("build.gradle")) || Files.exists(j.resolve("build.gradle.kts")) || Files.exists(j.resolve("settings.gradle"))
                || Files.exists(j.resolve("settings.gradle.kts"))) {
            String g = Files.exists(j.resolve(win ? "gradlew.bat" : "gradlew")) ? (win ? "gradlew.bat" : "./gradlew") : "gradle";
            String aufgabe = rel.isEmpty() ? "bootRun" : ":" + rel.replace('/', ':') + ":bootRun";
            return g + " " + aufgabe + " --args=--spring.profiles.active=" + profil + (debug ? " --debug-jvm" : "");
        }
        return null;
    }

    private void springStarten(String profil, boolean debug) {
        if (ed.javaOrdner == null) {
            ed.fehler("kein java.workspace in config.txt");
            return;
        }
        if (app != null && app.isAlive()) {
            springStarten(profil, debug, null);
            return;
        }
        List<BootApp> apps = bootApps();
        if (apps.size() > 1) {
            List<String> namen = new ArrayList<>();
            for (BootApp a : apps) namen.add(a.klasse() + "   (" + ed.relativ(a.modul()) + ")");
            auswahl("Welche Spring-Boot-Anwendung? (fest: spring.mainClass in config.txt)", namen, null,
                    w -> springStarten(profil, debug, apps.get(namen.indexOf(w))));
            return;
        }
        springStarten(profil, debug, apps.isEmpty() ? null : apps.get(0));
    }

    private void springStarten(String profil, boolean debug, BootApp bootApp) {
        if (ed.javaOrdner == null) {
            ed.fehler("kein java.workspace in config.txt");
            return;
        }
        if (app != null && app.isAlive()) {
            if (debug && appDebug) {
                javaVerbinden();
                return;
            }
            ed.fehler("die Anwendung laeuft schon (Leertaste a x stoppt sie)");
            return;
        }
        String b = springBefehl(profil, debug, bootApp);
        if (b == null) {
            ed.fehler("weder pom.xml noch build.gradle in " + ed.javaOrdner + " (oder spring.run in config.txt setzen)");
            return;
        }
        ed.konsoleAuf();
        ed.farbig("$ " + b + "   [Profil " + profil + (debug ? ", Debug" : "") + (bootApp != null && bootApp.klasse() != null ? ", " + bootApp.klasse() : "") + "]", E_HELL);
        List<String> befehl = Lsp.WINDOWS ? List.of("cmd.exe", "/c", b) : List.of("/bin/sh", "-c", b);
        ProcessBuilder pb = new ProcessBuilder(befehl).directory(ed.javaOrdner.toFile()).redirectErrorStream(true);
        Lsp.umgebung(pb);
        try {
            Process p = pb.start();
            app = p;
            appDebug = debug;
            p.getOutputStream().close();
            hintergrund(() -> {
                try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String z;
                    while ((z = r.readLine()) != null) {
                        String t = z.replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", "");
                        SwingUtilities.invokeLater(() -> {
                            ed.farbig(t, t.contains(" ERROR ") || t.startsWith("Caused by") ? Editor.E_FEHLER : E_TEXT);
                            ed.neuMalen.run();
                        });
                    }
                    int rc = p.waitFor();
                    SwingUtilities.invokeLater(() -> {
                        ed.farbig("[Anwendung beendet: " + rc + "]", rc == 0 ? E_HELL : Editor.E_FEHLER);
                        ed.neuMalen.run();
                    });
                } catch (IOException | InterruptedException e) {
                    // gestoppt
                }
            });
            ed.meldung = "Spring Boot startet mit Profil " + profil;
            if (debug) javaVerbinden();
        } catch (IOException e) {
            ed.fehler("nicht gestartet: " + e.getMessage());
        }
    }

    private void springStoppen() {
        if (app == null || !app.isAlive()) {
            ed.meldung = "keine Anwendung laeuft";
            return;
        }
        if (dbg.aktiv() && dbg.backend instanceof JavaDebug) ed.befehl("debugStop", "");
        app.descendants().forEach(ProcessHandle::destroy);
        app.destroy();
        ed.meldung = "Anwendung gestoppt";
    }

    // ================================================================ Debugger starten
    private void javaVerbinden() {
        int port = ed.kf.zahl("debug.java.port", 5005);
        String host = ed.kf.get("debug.java.host", "localhost");
        JavaDebug j;
        Debug.Ui ui = dbg.neueUi();
        try {
            j = new JavaDebug(ui);
        } catch (LinkageError e) {
            ed.fehler("Java-Debugger braucht ein JDK (jdk.jdi fehlt): " + e);
            return;
        }
        dbg.starten(j, ui);
        j.verbinden(host, port, 180);
    }

    /** Leertaste d d: je nach Datei Java (Spring Boot im Debug-Modus) oder JS/TS (js-debug) */
    private void debugStarten(boolean anhaengen) {
        Doc d = ed.d();
        // Java-Datei: Java; TS/JS-Datei: JS/TS; sonst Java, wenn es nur den Java-Arbeitsbereich gibt
        boolean java = "java".equals(d.sprache()) || (!"ts".equals(d.lspArt()) && ed.arbeitsordner == null);
        if (java) {
            if (anhaengen) javaVerbinden();
            else springStarten(ed.kf.get("spring.profile", "dev"), true);
            return;
        }
        Path server = ed.konfig.resolve("js-debug").resolve("js-debug").resolve("src").resolve("dapDebugServer.js");
        if (!Files.isRegularFile(server)) {
            ed.popupOpen("JS/TS-Debugger (js-debug von Microsoft) fehlt. ENTER laedt ihn herunter (ca. 10 MB), ESC nicht:", "installJsDebug", "");
            return;
        }
        String typ = ed.kf.get("debug.ts.type", "node");
        Map<String, Object> k;
        if (typ.equals("chrome")) {
            k = Json.obj("type", "pwa-chrome", "name", "Chrome", "request", anhaengen ? "attach" : "launch", "url",
                    ed.kf.get("debug.chrome.url", "http://localhost:5173"), "webRoot", String.valueOf(ed.arbeitsordner), "port",
                    ed.kf.zahl("debug.chrome.port", 9222));
            if (!anhaengen) k.remove("port");
        } else if (anhaengen) {
            k = Json.obj("type", "pwa-node", "name", "Node", "request", "attach", "port", ed.kf.zahl("debug.node.port", 9229), "cwd",
                    String.valueOf(ed.arbeitsordner));
        } else {
            if (d.pfad == null) {
                ed.meldung = "erst speichern";
                return;
            }
            String prog = ed.kf.get("debug.node.program", "{file}").replace("{file}", d.pfad.toString());
            k = Json.obj("type", "pwa-node", "name", "Node", "request", "launch", "program", ed.konfig.resolve(prog).toString(), "cwd",
                    String.valueOf(ed.arbeitsordner != null ? ed.arbeitsordner : d.pfad.getParent()), "console", "internalConsole",
                    "sourceMaps", true, "skipFiles", List.of("<node_internals>/**"));
            String args = ed.kf.get("debug.node.args", null);
            if (args != null) k.put("args", List.of(args.split("\\s+")));
            String rt = ed.kf.get("debug.node.runtime", null);
            if (rt != null) {
                String[] t = rt.split("\\s+");
                k.put("runtimeExecutable", t[0]);
                if (t.length > 1) k.put("runtimeArgs", List.of(t).subList(1, t.length));
            }
        }
        // js-debug-Server auf einem freien Port starten
        int port;
        try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
            port = ss.getLocalPort();
        } catch (IOException e) {
            ed.fehler("kein freier Port: " + e.getMessage());
            return;
        }
        ProcessBuilder pb = new ProcessBuilder().redirectErrorStream(true);
        Lsp.umgebung(pb);
        List<String> b = Lsp.befehl(ed.arbeitsordner != null ? ed.arbeitsordner : ed.konfig, List.of("node", server.toString(), String.valueOf(port), "127.0.0.1"),
                pb.environment());
        if (b == null) {
            ed.fehler("node nicht gefunden (fuer den JS/TS-Debugger)");
            return;
        }
        try {
            Process p = pb.command(b).start();
            hintergrund(() -> {
                try (InputStream in = p.getInputStream()) {
                    in.transferTo(OutputStream.nullOutputStream());
                } catch (IOException e) {
                    // egal
                }
            });
            Debug.Ui ui = dbg.neueUi();
            DapDebug dap = new DapDebug(ui, p, port);
            dbg.starten(dap, ui);
            dap.starten(String.valueOf(k.get("request")), k);
            ed.meldung = "JS/TS-Debugger startet (" + k.get("type") + ", " + k.get("request") + ")";
        } catch (IOException e) {
            ed.fehler("js-debug nicht gestartet: " + e.getMessage());
        }
    }

    // ================================================================ Call Hierarchy
    private void aufrufer() {
        Doc d = ed.d();
        Lsp l = ed.lspFuer(d);
        if (l == null || !l.an() || d.pfad == null) {
            ed.meldung = l == null ? "kein TypeScript, JavaScript oder Java" : l.name + " Language Server: " + l.status;
            return;
        }
        ed.lspSync();
        int z = d.zeileVon(d.cursor);
        Map<String, Object> pos = Json.obj("textDocument", Json.obj("uri", Lsp.uri(d.pfad)), "position", Json.obj("line", z, "character", d.cursor - d.anf(z)));
        ed.meldung = "Aufrufer werden gesucht ...";
        l.anfrage("textDocument/prepareCallHierarchy", pos, r -> {
            List<Object> items = Json.list(r);
            if (items.isEmpty()) {
                ed.meldung = "keine Funktion/Methode unter dem Cursor";
                ed.neuMalen.run();
                return;
            }
            Object item = items.get(0);
            String name = String.valueOf(Json.get(item, "name"));
            l.anfrage("callHierarchy/incomingCalls", Json.obj("item", item), a -> {
                List<String> namen = new ArrayList<>();
                List<Object[]> ziele = new ArrayList<>();
                for (Object call : Json.list(a)) {
                    Object von = Json.get(call, "from");
                    Path p = Editor.ausUri(Json.str(Json.get(von, "uri")));
                    if (p == null) continue;
                    List<Object> stellen = Json.list(Json.get(call, "fromRanges"));
                    if (stellen.isEmpty()) stellen = List.of(Json.get(von, "selectionRange"));
                    for (Object st : stellen) {
                        int zl = Json.num(Json.get(st, "start", "line"), 0), sp = Json.num(Json.get(st, "start", "character"), 0);
                        String text = ed.zeileAus(p, zl).strip();
                        if (text.length() > 80) text = text.substring(0, 80) + "~";
                        namen.add(ed.relativ(p) + ":" + (zl + 1) + "  " + Json.get(von, "name") + "  |  " + text);
                        ziele.add(new Object[] {p, zl, sp});
                    }
                }
                ed.meldung = null;
                if (namen.isEmpty()) {
                    ed.meldung = "keine Aufrufer von " + name;
                } else {
                    auswahl("Aufrufer von " + name + " (" + namen.size() + ")", namen, null, w -> {
                        Object[] zi = ziele.get(namen.indexOf(w));
                        ed.springMerken();
                        Doc t = ed.oeffnen((Path) zi[0]);
                        if (t != null) t.cursor = t.stelle((int) zi[1], (int) zi[2]);
                    });
                }
                ed.neuMalen.run();
            });
        });
    }

    // ================================================================ Befehle
    void befehle(Map<String, Editor.Cmd> c) {
        dbg.befehle(c, this);
        c.put("installJsDebug", a -> installieren("js-debug", "js-debug", () -> ed.meldung = "js-debug ist da: Leertaste d d startet den Debugger"));
        c.put("springRun", a -> springStarten(a.isBlank() ? ed.kf.get("spring.profile", "dev") : a.trim(), false));
        c.put("springDebug", a -> springStarten(a.isBlank() ? ed.kf.get("spring.profile", "dev") : a.trim(), true));
        c.put("springProfile", a -> auswahl("Spring Boot starten mit Profil", profile(), ed.kf.get("spring.profile", "dev"), p -> springStarten(p, false)));
        c.put("springStop", a -> springStoppen());
        c.put("debugStart", a -> debugStarten(false));
        c.put("debugAttach", a -> debugStarten(true));
        c.put("callHierarchy", a -> aufrufer());
        c.put("sqlScratch", a -> {
            String zeit = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path p = ed.konfig.resolve("sql").resolve("abfrage-" + zeit + ".sql");
            Doc d = ed.oeffnen(p);
            if (d == null) return;
            if (letztesKonto != null) d.dbKonto = letztesKonto;
            ed.befehl("switchState", "insert");
            ed.meldung = "neue SQL-Datei (Konto " + konto(d) + "): Strg+Enter fuehrt aus, :w speichert in " + p.getParent();
        });
        c.put("gitBranch", this::branchWaehlen);
        merge.befehle(c);
        c.put("gitMerge", a -> {
            Doc d = ed.d();
            if (Merge.hatKonflikte(d.t)) {
                merge.starten(d);
                return;
            }
            Path repo = repoFuer("");
            if (repo == null) return;
            List<String> l = Git.konflikte(repo);
            if (l.isEmpty()) {
                ed.meldung = "keine Dateien mit Konflikten in " + Editor.name(repo);
                return;
            }
            Consumer<String> los = n -> {
                Doc t = ed.oeffnen(repo.resolve(n));
                if (t != null) merge.starten(t);
            };
            if (l.size() == 1) los.accept(l.get(0));
            else auswahl("Konflikte in " + Editor.name(repo) + " (" + l.size() + " Dateien)", l, null, los);
        });
        c.put("gitBlame", a -> blameUmschalten());
        c.put("gitPull", a -> pull());
        c.put("gitPush", a -> push());
        c.put("gitCommit", a -> commitStarten());
        c.put("gitCommitDo", this::commit);
        c.put("gitReset", a -> resetStarten());
        c.put("gitResetDo", this::reset);
        c.put("gitStatus", a -> statusOeffnen());
        c.put("gitStatusClose", a -> {
            statusOffen = false;
            if (ed.modus.equals("gitstatus")) ed.modus = "normal";
        });
        c.put("gitStatusDown", a -> statusWahl = Math.min(statusZeilen.size() - 1, statusWahl + 1));
        c.put("gitStatusUp", a -> statusWahl = Math.max(0, statusWahl - 1));
        c.put("gitStatusRefresh", a -> statusLesen());
        c.put("gitStatusOpen", a -> {
            Path p = statusDatei();
            if (p == null || !Files.isRegularFile(p)) return;
            statusOffen = false;
            ed.modus = "normal";
            ed.oeffnen(p);
        });
        c.put("gitStatusStage", a -> {
            Path p = statusDatei();
            if (p != null) gitLauf(statusRepo, "add", new String[] {"add", "--", statusRepo.relativize(p).toString()}, l -> {});
        });
        c.put("gitStatusUnstage", a -> {
            Path p = statusDatei();
            if (p != null) gitLauf(statusRepo, "restore --staged", new String[] {"restore", "--staged", "--", statusRepo.relativize(p).toString()}, l -> {});
        });
        c.put("gitRefresh", a -> gitLesen());
        c.put("javaWorkdir", a -> {
            if (a.isBlank()) {
                ed.popupOpen("Java-Arbeitsbereich (wird in config.txt gespeichert):", "javaWorkdir", ed.javaOrdner == null ? "" : ed.javaOrdner.toString());
                return;
            }
            Path p = ed.aufloesen(a);
            if (!Files.isDirectory(p)) {
                ed.fehler("kein Ordner: " + p);
                return;
            }
            ed.kf.setzen("java.workspace", p.toString());
            ed.javaOrdnerSetzen(p);
        });
        c.put("methodNext", a -> methode(true));
        c.put("methodPrev", a -> methode(false));
        // Auswahlliste
        c.put("pickerChar", a -> {
            auswahlFilter.append(a);
            filtern();
        });
        c.put("pickerBackspace", a -> {
            if (auswahlFilter.length() > 0) auswahlFilter.setLength(auswahlFilter.length() - 1);
            filtern();
        });
        c.put("pickerUp", a -> auswahlWahl = Math.max(0, auswahlWahl - 1));
        c.put("pickerDown", a -> auswahlWahl = Math.min(auswahlListe.size() - 1, auswahlWahl + 1));
        c.put("pickerClose", a -> auswahlZu());
        c.put("pickerAccept", a -> {
            if (auswahlListe.isEmpty()) return;
            String w = auswahlListe.get(auswahlWahl);
            Consumer<String> ak = auswahlAktion;
            auswahlZu();
            ak.accept(w);
        });
        // Datenbank
        c.put("sqlRun", a -> sqlLaufen(false));
        c.put("sqlRunFile", a -> sqlLaufen(true));
        c.put("sqlAccount", a -> {
            Doc d = ed.d();
            if (!sqlDatei(d)) return;
            if (db.konten.isEmpty()) {
                ed.fehler("keine Konten: db.account.NAME = URL | BENUTZER | PASSWORT in config.txt");
                return;
            }
            Consumer<String> setzen = k -> {
                d.dbKonto = k;
                letztesKonto = k;
                Db.Konto ko = db.konten.get(k);
                ed.meldung = "Konto fuer " + d.name() + ": " + k + " (" + ko.benutzer() + " @ " + ko.url() + ")";
            };
            if (!a.isBlank() && db.konten.containsKey(a.trim())) setzen.accept(a.trim());
            else auswahl("Datenbankkonto fuer " + d.name(), new ArrayList<>(db.konten.keySet()), konto(d), setzen);
        });
        c.put("sqlCommit", a -> commit(true));
        c.put("sqlRollback", a -> commit(false));
        c.put("sqlCancel", a -> {
            db.abbrechen();
            ed.meldung = "Abbruch gesendet";
        });
        c.put("sqlDisconnect", a -> {
            db.trennen();
            ed.meldung = "Verbindungen getrennt";
        });
        // Ergebnistabelle
        c.put("resultFocus", a -> {
            Tabelle t = tabelle();
            if (t == null) {
                ed.meldung = "keine Ergebnistabelle zu dieser Datei";
                return;
            }
            t.offen = true;
            ed.konsoleOffen = false;
            ed.modus = "result";
        });
        c.put("resultBack", a -> ed.modus = "normal");
        c.put("resultClose", a -> {
            Tabelle t = tabelle();
            if (t != null) t.offen = false;
            if (ed.modus.equals("result")) ed.modus = "normal";
        });
        c.put("resultDown", a -> ergBewegen(ed.vim.nehmen(), 0));
        c.put("resultUp", a -> ergBewegen(-ed.vim.nehmen(), 0));
        c.put("resultLeft", a -> ergBewegen(0, -ed.vim.nehmen()));
        c.put("resultRight", a -> ergBewegen(0, ed.vim.nehmen()));
        c.put("resultPageDown", a -> ergBewegen(ergZeilen(), 0));
        c.put("resultPageUp", a -> ergBewegen(-ergZeilen(), 0));
        c.put("resultHeader", a -> {
            Tabelle t = tabelle();
            if (t != null) t.ez = -1;
        });
        c.put("resultTop", a -> {
            Tabelle t = tabelle();
            if (t != null) t.ez = 0;
        });
        c.put("resultBottom", a -> ergBewegen(Integer.MAX_VALUE / 2, 0));
        c.put("resultHome", a -> ergBewegen(0, -Integer.MAX_VALUE / 2));
        c.put("resultEnd", a -> ergBewegen(0, Integer.MAX_VALUE / 2));
        c.put("resultYank", a -> {
            String v = zelle();
            if (v == null) return;
            Editor.zwischenablage(v);
            ed.meldung = "Zelle kopiert";
        });
        c.put("resultYankRow", a -> {
            Tabelle t = tabelle();
            if (t == null || t.ez < 0 || t.ez >= t.zeilen().size()) return;
            Editor.zwischenablage(String.join("\t", nullLeer(t.zeilen().get(t.ez))));
            ed.meldung = "Zeile kopiert (Tab-getrennt)";
        });
        c.put("resultYankAll", a -> {
            Tabelle t = tabelle();
            if (t == null) return;
            StringBuilder b = new StringBuilder(String.join("\t", t.erg.spalten())).append('\n');
            for (String[] z : t.zeilen()) b.append(String.join("\t", nullLeer(z))).append('\n');
            Editor.zwischenablage(b.toString());
            ed.meldung = t.zeilen().size() + " Zeilen kopiert (Tab-getrennt, mit Kopf)";
        });
        // ENTER: auf der Kopfzeile das Filtermenue, sonst den ganzen Wert
        c.put("resultEnter", a -> {
            Tabelle t = tabelle();
            if (t == null) return;
            if (t.ez < 0) {
                filterMenue();
                return;
            }
            String v = zelle();
            if (v == null) return;
            List<String> z = new ArrayList<>();
            z.add(t.erg.spalten().get(t.es) + " (Zeile " + (t.ez + 1) + ")");
            z.addAll(List.of(v.split("\\R")));
            ed.info = z;
        });
        c.put("resultShow", c.get("resultEnter"));
        c.put("resultFilter", a -> filterMenue());
        c.put("resultFilterValue", a -> {
            Tabelle t = tabelle();
            if (t == null || filterArt == null) return;
            filterSetzen(t, filterArt, a);
            filterArt = null;
        });
        c.put("sqlMetaRefresh", a -> {
            String k = konto(ed.d());
            if (k == null) return;
            meta.remove(k);
            metaLaden(k, null);
        });
        c.put("resultUnfilter", a -> {
            Tabelle t = tabelle();
            if (t == null) return;
            t.filter.clear();
            t.sortSpalte = -1;
            t.geaendert();
        });
    }

    private static String[] nullLeer(String[] z) {
        String[] r = new String[z.length];
        for (int i = 0; i < z.length; i++) r[i] = z[i] == null ? "" : z[i];
        return r;
    }

    private void commit(boolean ja) {
        String k = konto(ed.d());
        if (k == null) return;
        db.commit(k, ja, m -> SwingUtilities.invokeLater(() -> {
            ed.meldung = m;
            ed.farbig(m, E_TEXT);
            ed.neuMalen.run();
        }));
    }
}
