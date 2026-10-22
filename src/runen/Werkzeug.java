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

    Werkzeug(Editor ed) {
        this.ed = ed;
        Timer t = new Timer(2000, e -> {
            if (gitLesen()) ed.neuMalen.run();
        });
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

    private void branchWaehlen(String welcher) {
        Path repo = switch (welcher.trim()) {
            case "ts" -> tsRepo;
            case "java" -> javaRepo;
            default -> {
                Path b = ed.bereichVon(ed.d().pfad);
                if (b != null && b.equals(ed.javaOrdner)) yield javaRepo;
                yield tsRepo != null ? tsRepo : javaRepo;
            }
        };
        if (repo == null) {
            ed.meldung = "kein git-Repo im Arbeitsbereich";
            return;
        }
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

    List<String> javaLspBefehl() {
        String b = ed.kf.get("java.lsp", "jdtls");
        if (b.equals("off")) return List.of();
        Path daten = ed.konfig.resolve("jdtls-daten").resolve(Editor.name(ed.javaOrdner) + "-" + Integer.toHexString(ed.javaOrdner.toString().hashCode()));
        String home = ed.kf.get("java.lsp.home", null);
        if (home == null) {
            List<String> l = new ArrayList<>(List.of(b.split("\\s+")));
            if (!l.contains("-data")) {
                l.add("-data");
                l.add(daten.toString());
            }
            return l;
        }
        Path h = ed.konfig.resolve(home).toAbsolutePath().normalize();
        Path launcher = null;
        try (Stream<Path> s = Files.list(h.resolve("plugins"))) {
            launcher = s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("org.eclipse.equinox.launcher_") && n.endsWith(".jar");
            }).findFirst().orElse(null);
        } catch (IOException e) {
            // unten gemeldet
        }
        if (launcher == null) {
            ed.fehler("java.lsp.home: kein plugins/org.eclipse.equinox.launcher_*.jar in " + h);
            return List.of();
        }
        String os = System.getProperty("os.name").toLowerCase();
        String sys = os.contains("win") ? "win" : os.contains("mac") ? "mac" : "linux";
        boolean arm = System.getProperty("os.arch").contains("aarch64") || System.getProperty("os.arch").contains("arm");
        Path cfg = h.resolve("config_" + sys + (arm ? "_arm" : ""));
        if (!Files.isDirectory(cfg)) cfg = h.resolve("config_" + sys);
        String java = ed.kf.get("java.lsp.java", Path.of(System.getProperty("java.home"), "bin", Lsp.WINDOWS ? "java.exe" : "java").toString());
        if (ed.kf.get("java.lsp.java", null) == null && Runtime.version().feature() < 21) {
            ed.farbig("Hinweis: jdtls braucht Java 21+, der Editor laeuft mit Java " + Runtime.version().feature() + " (java.lsp.java in config.txt setzen)", E_WARN);
        }
        return new ArrayList<>(List.of(java, "-Declipse.application=org.eclipse.jdt.ls.core.id1", "-Dosgi.bundles.defaultStartLevel=4",
                "-Declipse.product=org.eclipse.jdt.ls.core.product", "-Dosgi.checkConfiguration=true", "-Dosgi.sharedConfiguration.area=" + cfg,
                "-Dosgi.sharedConfiguration.area.readOnly=true", "-Dosgi.configuration.cascaded=true", "-Xms256m", "-Xmx" + ed.kf.get("java.lsp.memory", "2G"),
                "--add-modules=ALL-SYSTEM", "--add-opens", "java.base/java.util=ALL-UNNAMED", "--add-opens", "java.base/java.lang=ALL-UNNAMED", "-jar",
                launcher.toString(), "-configuration", ed.konfig.resolve("jdtls-config").toString(), "-data", daten.toString()));
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
    private List<String> auswahlAlle = List.of(), auswahlListe = List.of();
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
    Db.Ergebnis erg;
    boolean ergebnisOffen;
    private int ez, es, eOben, eLinks;

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
            zeigen(e);
            ed.neuMalen.run();
        }));
    }

    private void zeigen(Db.Ergebnis e) {
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
            erg = e;
            ergebnisOffen = true;
            ed.konsoleOffen = false;
            ez = es = eOben = eLinks = 0;
            if (ed.modus.equals("normal") || ed.modus.equals("console")) ed.modus = "result";
        }
    }

    private void ergBewegen(int dz, int ds) {
        if (erg == null) return;
        ez = Math.max(0, Math.min(erg.zeilen().size() - 1, ez + dz));
        es = Math.max(0, Math.min(erg.spalten().size() - 1, es + ds));
    }

    private String zelle() {
        if (erg == null || erg.zeilen().isEmpty()) return null;
        String v = erg.zeilen().get(ez)[es];
        return v == null ? "" : v;
    }

    private int ergZeilen() {
        return Math.max(1, (ed.scr.h - ed.unten() - 6 - ergebnisY() - 3 * ed.scr.lh) / ed.scr.lh);
    }

    int ergebnisY() {
        return ed.oben() + ed.textZeilen() * ed.scr.lh + 6;
    }

    void ergebnisMalen(int y0) {
        Screen s = ed.scr;
        int W = s.w, lh = s.lh, cw = s.cw;
        int h = s.h - ed.unten() - 6 - y0;
        boolean fokus = ed.modus.equals("result");
        s.rect(0, y0, W, h, E_KONSOLE);
        s.frame(4, y0, W - 8, h, fokus ? E_HELL : E_TEXT);
        s.frame(5, y0 + 1, W - 10, h - 2, E_MATT);
        String titel = " ERGEBNIS [" + erg.konto() + "] " + erg.meldung() + (fokus ? "  (hjkl, y Zelle, Y Zeile, ENTER zeigen, ESC zurueck, q zu) " : " ");
        s.rect(3 * cw, y0 - lh / 2, s.textWidth(titel), lh, E_KONSOLE);
        s.text(3 * cw, y0 - lh / 2, titel, E_HELL);
        List<String> sp = erg.spalten();
        List<String[]> zl = erg.zeilen();
        int[] breite = new int[sp.size()];
        for (int c = 0; c < sp.size(); c++) {
            int b = Math.max(4, sp.get(c).length());
            for (int r = 0; r < Math.min(zl.size(), 2000); r++) {
                String v = zl.get(r)[c];
                b = Math.max(b, v == null ? 6 : Math.min(50, laenge(v)));
            }
            breite[c] = Math.min(50, b);
        }
        int nrBreite = String.valueOf(zl.size()).length() + 1;
        int x0 = 2 * cw, platz = (W - 4 * cw) / cw - nrBreite;
        // sichtbare Spalten: die gewaehlte muss drin sein
        if (es < eLinks) eLinks = es;
        while (true) {
            int summe = 0;
            for (int c = eLinks; c <= es; c++) summe += breite[c] + 3;
            if (summe <= platz || eLinks >= es) break;
            eLinks++;
        }
        int n = ergZeilen();
        if (ez < eOben) eOben = ez;
        if (ez >= eOben + n) eOben = ez - n + 1;
        int y = y0 + lh / 2 + 4;
        s.clip(8, y0 + 2, W - 16, h - 4);
        int x = x0 + nrBreite * cw;
        for (int c = eLinks; c < sp.size() && x < W - 2 * cw; c++) {
            s.text(x, y, zuschneiden(sp.get(c), breite[c]), E_WARN);
            x += (breite[c] + 3) * cw;
        }
        s.rect(x0, y + lh + 1, W - 2 * x0, 1, E_MATT);
        for (int k = 0; k < n && eOben + k < zl.size(); k++) {
            int r = eOben + k;
            int zy = y + lh + 4 + k * lh;
            if (r == ez) s.rect(x0, zy, W - 2 * x0, lh, E_ZEILE);
            s.text(x0, zy, String.valueOf(r + 1), E_MATT);
            x = x0 + nrBreite * cw;
            for (int c = eLinks; c < sp.size() && x < W - 2 * cw; c++) {
                String v = zl.get(r)[c];
                boolean zelleJa = fokus && r == ez && c == es;
                if (zelleJa) s.rect(x - cw / 2, zy, (breite[c] + 1) * cw, lh, E_TEXT);
                int f = zelleJa ? E_GRUND : v == null ? E_MATT : E_TEXT;
                s.text(x, zy, zuschneiden(v == null ? "(null)" : v.replace('\n', ' ').replace('\t', ' '), breite[c]), f);
                x += (breite[c] + 3) * cw;
            }
        }
        if (zl.isEmpty()) s.text(x0, y + lh + 4, "(keine Zeilen)", E_MATT);
        s.noClip();
    }

    private static int laenge(String s) {
        int n = s.indexOf('\n');
        return n < 0 ? s.length() : n;
    }

    private static String zuschneiden(String s, int b) {
        return s.length() <= b ? s : s.substring(0, Math.max(0, b - 1)) + "~";
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

    // ================================================================ Befehle
    void befehle(Map<String, Editor.Cmd> c) {
        c.put("gitBranch", this::branchWaehlen);
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
            if (erg == null) {
                ed.meldung = "noch kein Ergebnis";
                return;
            }
            ergebnisOffen = true;
            ed.konsoleOffen = false;
            ed.modus = "result";
        });
        c.put("resultBack", a -> ed.modus = "normal");
        c.put("resultClose", a -> {
            ergebnisOffen = false;
            if (ed.modus.equals("result")) ed.modus = "normal";
        });
        c.put("resultDown", a -> ergBewegen(ed.vim.nehmen(), 0));
        c.put("resultUp", a -> ergBewegen(-ed.vim.nehmen(), 0));
        c.put("resultLeft", a -> ergBewegen(0, -ed.vim.nehmen()));
        c.put("resultRight", a -> ergBewegen(0, ed.vim.nehmen()));
        c.put("resultPageDown", a -> ergBewegen(ergZeilen(), 0));
        c.put("resultPageUp", a -> ergBewegen(-ergZeilen(), 0));
        c.put("resultTop", a -> ergBewegen(-ez, 0));
        c.put("resultBottom", a -> ergBewegen(Integer.MAX_VALUE / 2, 0));
        c.put("resultHome", a -> ergBewegen(0, -es));
        c.put("resultEnd", a -> ergBewegen(0, Integer.MAX_VALUE / 2));
        c.put("resultYank", a -> {
            String v = zelle();
            if (v == null) return;
            Editor.zwischenablage(v);
            ed.meldung = "Zelle kopiert";
        });
        c.put("resultYankRow", a -> {
            if (erg == null || erg.zeilen().isEmpty()) return;
            Editor.zwischenablage(String.join("\t", nullLeer(erg.zeilen().get(ez))));
            ed.meldung = "Zeile kopiert (Tab-getrennt)";
        });
        c.put("resultYankAll", a -> {
            if (erg == null) return;
            StringBuilder b = new StringBuilder(String.join("\t", erg.spalten())).append('\n');
            for (String[] z : erg.zeilen()) b.append(String.join("\t", nullLeer(z))).append('\n');
            Editor.zwischenablage(b.toString());
            ed.meldung = erg.zeilen().size() + " Zeilen kopiert (Tab-getrennt, mit Kopf)";
        });
        c.put("resultShow", a -> {
            String v = zelle();
            if (v == null) return;
            List<String> z = new ArrayList<>();
            z.add(erg.spalten().get(es) + " (Zeile " + (ez + 1) + ")");
            z.addAll(List.of(v.split("\\R")));
            ed.info = z;
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
