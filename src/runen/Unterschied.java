package runen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.SwingUtilities;

/**
 * Diff-Ansicht: ein unified diff in einem schreibgeschuetzten Puffer (gefaerbt), ENTER springt an die
 * Stelle in der Datei, ]c / [c von Abschnitt zu Abschnitt. Dazu: die Datei im Editor (auch ungespeichert)
 * mit einem Commit vergleichen.
 */
final class Unterschied {
    private final Editor ed;

    Unterschied(Editor ed) {
        this.ed = ed;
    }

    /** Puffer mit diesem Titel (wiederverwendet), Text setzen, Cursor auf den ersten Abschnitt */
    Doc zeigen(String titel, String text, Path wurzel) {
        Doc d = null;
        for (Doc x : ed.tabs) if (x.nurLesen && titel.equals(x.titel)) d = x;
        if (d == null) {
            d = new Doc();
            d.titel = titel;
            ed.tabs.add(d);
        }
        d.art = "diff";
        d.diffWurzel = wurzel;
        d.nurLesen = false;
        d.setText(text);
        d.nurLesen = true;
        d.geaendert = false;
        d.oben = 0;
        int h = text.startsWith("@@") ? 0 : text.indexOf("\n@@");
        d.cursor = h < 0 ? 0 : h == 0 ? 0 : h + 1;
        ed.aktiv = ed.tabs.indexOf(d);
        // gleich im Mode fuer schreibgeschuetzte Puffer (ENTER springt), auch wenn der Aufruf aus einem Hintergrund-Rueckruf kommt
        ed.modus = ed.km.modeDa("nurlesen") ? "nurlesen" : "normal";
        return d;
    }

    /** Leertaste g d: Commit waehlen (Verlauf der Datei, auch ueber Umbenennungen), dann vergleichen */
    void commitWaehlen() {
        Doc d = ed.d();
        if (d.pfad == null) {
            ed.meldung = "erst speichern - verglichen wird eine Datei";
            return;
        }
        Path repo = Git.wurzel(d.pfad);
        if (repo == null) {
            ed.meldung = d.name() + " liegt in keinem git-Repo";
            return;
        }
        String rel = rel(repo, d.pfad);
        ed.meldung = "lese den Verlauf von " + rel + " ...";
        Thread t = new Thread(() -> {
            Git.Lauf l = Git.git(repo, "log", "--follow", "-n", "300", "--date=short", "--format=%x01%h%x09%ad%x09%an%x09%s", "--name-only", "--", rel);
            Map<String, String[]> eintraege = new LinkedHashMap<>();
            eintraege.put("HEAD        letzter Commit", new String[] {"HEAD", rel});
            if (l.code() == 0) {
                String[] teile = l.ausgabe().split("\u0001");
                for (String x : teile) {
                    if (x.isBlank()) continue;
                    String[] z = x.strip().split("\\R");
                    String[] f = z[0].split("\t", 4);
                    if (f.length < 4) continue;
                    String pfad = z.length > 1 ? z[z.length - 1].strip() : rel;
                    eintraege.put(f[0] + "  " + f[1] + "  " + kurz(f[2], 16) + "  " + f[3] + (pfad.equals(rel) ? "" : "   [" + pfad + "]"), new String[] {f[0], pfad});
                }
            }
            SwingUtilities.invokeLater(() -> {
                if (l.code() != 0) ed.fehler("git log: " + l.ausgabe());
                ed.wz.auswahl(d.name() + " vergleichen mit (tippen filtert; Branch/Tag/Commit eintippen geht auch)", new ArrayList<>(eintraege.keySet()), null,
                        w -> dateiGegen(d, eintraege.get(w)[0], eintraege.get(w)[1]), "Ref: ", ref -> dateiGegen(d, ref, rel));
                ed.neuMalen.run();
            });
        }, "diff");
        t.setDaemon(true);
        t.start();
    }

    private static String kurz(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "~";
    }

    static String rel(Path repo, Path p) {
        return repo.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /** Text im Editor gegen ref:alterPfad (git diff --no-index ueber zwei Zwischendateien) */
    void dateiGegen(Doc d, String ref, String alterPfad) {
        Path repo = Git.wurzel(d.pfad);
        if (repo == null) return;
        String rel = rel(repo, d.pfad);
        String jetzt = d.t.toString();
        boolean ungespeichert = d.geaendert;
        ed.meldung = "vergleiche mit " + ref + " ...";
        Thread t = new Thread(() -> {
            Git.Lauf alt = Git.inhalt(repo, "show", ref + ":" + alterPfad);
            String text;
            if (alt.code() != 0) {
                text = null;
            } else {
                text = diff(repo, alt.ausgabe(), jetzt, alterPfad, rel);
            }
            SwingUtilities.invokeLater(() -> {
                if (text == null) {
                    ed.fehler("git show " + ref + ":" + alterPfad + ": " + Werkzeug.letzteZeile(alt.ausgabe()));
                    return;
                }
                String kopf = "# " + rel + ": " + ref + (alterPfad.equals(rel) ? "" : ":" + alterPfad) + " -> Editor" + (ungespeichert ? " (ungespeichert)" : "")
                        + "\n# ENTER springt in die Datei, ]c / [c naechster/voriger Abschnitt, q schliesst\n\n";
                if (text.isBlank()) {
                    ed.meldung = rel + " ist gleich wie in " + ref;
                    return;
                }
                zeigen("diff " + Editor.name(d.pfad) + " " + ref, kopf + text, repo);
                ed.meldung = rel + " gegen " + ref;
                ed.neuMalen.run();
            });
        }, "diff");
        t.setDaemon(true);
        t.start();
    }

    /** unified diff zweier Texte, Datei-Koepfe mit a/alt und b/neu (wie git diff) */
    static String diff(Path repo, String alt, String neu, String altPfad, String neuPfad) {
        try {
            Path a = Files.createTempFile("runen-alt", ".txt"), b = Files.createTempFile("runen-neu", ".txt");
            try {
                Files.writeString(a, alt, StandardCharsets.UTF_8);
                Files.writeString(b, neu, StandardCharsets.UTF_8);
                Git.Lauf l = Git.inhalt(repo, "diff", "--no-index", "--no-color", "--no-ext-diff", a.toString(), b.toString());
                if (l.code() == 0) return "";
                if (l.code() != 1) return null;
                StringBuilder r = new StringBuilder();
                for (String z : l.ausgabe().split("\n", -1)) {
                    if (z.startsWith("diff --git ")) z = "diff --git a/" + altPfad + " b/" + neuPfad;
                    else if (z.startsWith("--- ")) z = "--- a/" + altPfad;
                    else if (z.startsWith("+++ ")) z = "+++ b/" + neuPfad;
                    else if (z.startsWith("index ")) continue;
                    r.append(z).append('\n');
                }
                return r.toString().stripTrailing() + "\n";
            } finally {
                Files.deleteIfExists(a);
                Files.deleteIfExists(b);
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    /** ENTER im Diff-Puffer: Datei der Zeile oeffnen, Cursor auf die passende Zeile (neue Seite); false = kein Diff */
    boolean springen(Doc d) {
        if (!"diff".equals(d.art)) return false;
        int z = d.zeileVon(d.cursor);
        String ziel0 = d.ziele.get(z);
        if (ziel0 != null) {
            String[] t = ziel0.split("\t");
            stelleOeffnen(d.diffWurzel, t[0], t.length > 1 ? Integer.parseInt(t[1]) : 1);
            return true;
        }
        int spalte = Math.max(0, d.spalte(d.cursor) - 1);
        String datei = null;
        int ziel = -1;
        int zaehler = 0;
        for (int i = z; i >= 0; i--) {
            String s = d.zeile(i);
            if (ziel < 0) {
                Matcher m = HUNK.matcher(s);
                if (m.find()) {
                    ziel = Integer.parseInt(m.group(1)) + zaehler - 1;
                    continue;
                }
                // Zeilen der neuen Seite zwischen Kopf und Cursor zaehlen
                if (i < z && !s.startsWith("-") && !s.startsWith("\\")) zaehler++;
                if (s.startsWith("diff --git")) break;
            }
            if (s.startsWith("+++ ")) {
                datei = s.substring(4).strip();
                break;
            }
            if (s.startsWith("diff --git")) {
                int b = s.lastIndexOf(" b/");
                if (b > 0) datei = "b/" + s.substring(b + 3);
                break;
            }
        }
        if (datei == null || datei.equals("/dev/null")) {
            ed.meldung = "ENTER auf einer Zeile eines Abschnitts (@@) springt in die Datei";
            return true;
        }
        if (datei.startsWith("b/")) datei = datei.substring(2);
        Path wurzel = d.diffWurzel != null ? d.diffWurzel : ed.arbeitsordner;
        if (wurzel == null) return true;
        Path p = wurzel.resolve(datei).normalize();
        if (!Files.isRegularFile(p)) {
            ed.meldung = datei + " gibt es hier nicht (geloescht oder nur im anderen Branch)";
            return true;
        }
        ed.springMerken();
        Doc x = ed.oeffnen(p);
        if (x == null) return true;
        if (ed.modus.equals("openapi")) ed.modus = "normal";
        int zeile = Math.max(0, ziel < 0 ? 0 : ziel);
        x.cursor = x.stelle(zeile, d.zeile(z).startsWith("+") || d.zeile(z).startsWith(" ") ? spalte : 0);
        return true;
    }

    /**
     * Codestelle oeffnen: pfad relativ zum Repo; passt er nicht genau (anderer Wurzelordner, nur der
     * Dateiname), sucht der Index die Datei, deren Pfad am laengsten mit ihm endet.
     */
    void stelleOeffnen(Path wurzel, String pfad, int zeile) {
        String p = pfad.replace('\\', '/');
        while (p.startsWith("/") || p.startsWith("./")) p = p.substring(p.startsWith("/") ? 1 : 2);
        Path gefunden = null;
        if (wurzel != null && Files.isRegularFile(wurzel.resolve(p))) gefunden = wurzel.resolve(p);
        if (gefunden == null) {
            // vorne Ordner weglassen, bis das Ende passt (a/b/src/X.java -> src/X.java -> X.java)
            int beste = 0;
            for (Indexer.Datei x : ed.index.dateien) {
                String r = x.pfad().toString().replace('\\', '/');
                String q = p;
                while (!q.isEmpty() && !(r.endsWith("/" + q) || r.equals(q))) {
                    int s = q.indexOf('/');
                    q = s < 0 ? "" : q.substring(s + 1);
                }
                if (q.length() > beste) {
                    beste = q.length();
                    gefunden = x.pfad();
                }
            }
        }
        if (gefunden == null) {
            ed.meldung = pfad + " nicht gefunden (nicht im Arbeitsbereich)";
            return;
        }
        ed.springMerken();
        Doc x = ed.oeffnen(gefunden);
        if (x == null) return;
        if (ed.modus.equals("openapi")) ed.modus = "normal";
        int z = Math.max(0, zeile - 1);
        x.cursor = x.stelle(z, Yaml.einzug(x.zeile(Math.min(z, x.zeilen() - 1)).replace('\t', ' ')));
        ed.meldung = Editor.name(gefunden) + ":" + zeile;
    }

    /** ]k / [k: naechster bzw. voriger Kommentar (Zeilen mit Codestelle oder Kommentar-Kopf) */
    void kommentar(Doc d, int richtung) {
        int z = d.zeileVon(d.cursor);
        for (int i = z + richtung; i >= 0 && i < d.zeilen(); i += richtung) {
            String s = d.zeile(i);
            if (s.startsWith("\u00bb ") || s.startsWith("    \u00bb ") || s.startsWith("\\ \u00bb")) {
                d.cursor = d.anf(i);
                return;
            }
        }
        ed.meldung = richtung > 0 ? "kein weiterer Kommentar" : "kein Kommentar davor";
    }

    /** ]c / [c: naechster bzw. voriger Abschnitt (@@) */
    void abschnitt(Doc d, int richtung) {
        int z = d.zeileVon(d.cursor);
        for (int i = z + richtung; i >= 0 && i < d.zeilen(); i += richtung) {
            if (d.zeile(i).startsWith("@@")) {
                d.cursor = d.anf(i);
                return;
            }
        }
        ed.meldung = richtung > 0 ? "letzter Abschnitt" : "erster Abschnitt";
    }

    void befehle(Map<String, Editor.Cmd> c) {
        /**
         * @binding Vergleicht die Datei (auch ungespeichert) mit einem Commit aus ihrem Verlauf, Branch oder Tag
         * @binding-arg HEAD = mit dem letzten Commit
         */
        c.put("gitDiff", a -> {
            Doc d = ed.d();
            if (a.strip().isEmpty()) {
                commitWaehlen();
                return;
            }
            if (d.pfad == null || Git.wurzel(d.pfad) == null) {
                ed.meldung = "keine Datei in einem git-Repo";
                return;
            }
            dateiGegen(d, a.strip(), rel(Git.wurzel(d.pfad), d.pfad));
        });
        /** @binding Diff-Ansicht: springt in die Datei an die Zeile (sonst wie ENTER) */
        c.put("diffJump", a -> {
            if (!springen(ed.d())) ed.befehl("motion", "ENTER");
        });
        /** @binding Diff-Ansicht: naechster Abschnitt */
        c.put("diffNext", a -> abschnitt(ed.d(), 1));
        /** @binding Diff-Ansicht: voriger Abschnitt */
        c.put("diffPrev", a -> abschnitt(ed.d(), -1));
        /** @binding Diff-Ansicht (Pull Request): naechster Kommentar */
        c.put("diffNextComment", a -> kommentar(ed.d(), 1));
        /** @binding Diff-Ansicht (Pull Request): voriger Kommentar */
        c.put("diffPrevComment", a -> kommentar(ed.d(), -1));
    }
}
