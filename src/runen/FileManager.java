package runen;

import static runen.Editor.E_FEHLER;
import static runen.Editor.E_GRUND;
import static runen.Editor.E_HELL;
import static runen.Editor.E_KONSOLE;
import static runen.Editor.E_MATT;
import static runen.Editor.E_TEXT;
import static runen.Editor.E_WARN;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.swing.SwingUtilities;

/**
 * Dateimanager (Strg+P) mit zwei Bereichen wie der Norton Commander und einem Suchfeld:
 * links passende Dateinamen, rechts Treffer im Inhalt der Projektdateien.
 */
final class FileManager {
    static final class Eintrag {
        String name;
        boolean ordner;
        long groesse = -1;
        Path pfad;
        /** Treffer im Inhalt: Zeile (ab 0), sonst -1 */
        int zeile = -1, spalte, laenge;
        String text;
        /** passende Zeichen im Namen (Namenssuche) */
        int[] wo;

        static Eintrag treffer(Path p, String rel, int z, int sp, int len, String text) {
            Eintrag e = new Eintrag();
            e.pfad = p;
            e.name = rel;
            e.zeile = z;
            e.spalte = sp;
            e.laenge = len;
            e.text = text;
            return e;
        }
    }

    static final class Panel {
        Path ordner;
        String titel = "";
        final List<Eintrag> e = new ArrayList<>();
        int cursor, oben;
        // zuletzt gemalt (fuer die Maus)
        int x, y, w, h, zeilenY;
    }

    private final Editor ed;
    boolean offen;
    int aktiv;
    private String vorModus = "normal";
    private final Panel[] ordner = {new Panel(), new Panel()};
    private final Panel[] erg = {new Panel(), new Panel()};
    /** Ergebnisse statt Ordner zeigen */
    private boolean ergebnis;
    /** eigene Liste (z. B. Referenzen) in einem breiten Bereich */
    private String listenTitel;
    final StringBuilder suche = new StringBuilder();
    private boolean sucht;

    FileManager(Editor ed) {
        this.ed = ed;
    }

    private Panel panel(int k) {
        if (listenTitel != null) return erg[0];
        return ergebnis ? erg[k] : ordner[k];
    }

    private Panel akt() {
        return panel(aktiv);
    }

    private Eintrag auswahl() {
        Panel p = akt();
        return p.cursor >= 0 && p.cursor < p.e.size() ? p.e.get(p.cursor) : null;
    }

    void zuruecksetzen() {
        ordner[0].ordner = null;
        ordner[1].ordner = null;
        suche.setLength(0);
        ergebnis = false;
        listenTitel = null;
    }

    private static boolean fmModus(String m) {
        return m.equals("commander") || m.equals("fmsearch");
    }

    // ---------------------------------------------------------------- Ordner lesen
    private void laden(Panel p) {
        String alt = p.cursor < p.e.size() ? p.e.get(p.cursor).name : null;
        p.e.clear();
        if (p.ordner == null) return;
        if (p.ordner.getParent() != null) {
            Eintrag e = new Eintrag();
            e.name = "..";
            e.ordner = true;
            e.pfad = p.ordner.getParent();
            p.e.add(e);
        }
        List<Eintrag> ordnerL = new ArrayList<>(), dateien = new ArrayList<>();
        try (Stream<Path> s = Files.list(p.ordner)) {
            s.forEach(x -> {
                Eintrag e = new Eintrag();
                e.name = x.getFileName().toString();
                e.pfad = x;
                e.ordner = Files.isDirectory(x);
                if (!e.ordner) {
                    try {
                        e.groesse = Files.size(x);
                    } catch (IOException ex) {
                        e.groesse = 0;
                    }
                }
                (e.ordner ? ordnerL : dateien).add(e);
            });
        } catch (IOException | RuntimeException ex) {
            ed.fehler("nicht lesbar: " + p.ordner);
        }
        Comparator<Eintrag> c = (a, b) -> a.name.compareToIgnoreCase(b.name);
        ordnerL.sort(c);
        dateien.sort(c);
        p.e.addAll(ordnerL);
        p.e.addAll(dateien);
        p.cursor = 0;
        for (int k = 0; k < p.e.size(); k++) if (p.e.get(k).name.equals(alt)) p.cursor = k;
        p.titel = p.ordner.toString();
    }

    private void neuLaden() {
        laden(ordner[0]);
        laden(ordner[1]);
    }

    // ---------------------------------------------------------------- Suche
    private void sucheGeaendert() {
        String q = suche.toString().trim();
        listenTitel = null;
        if (q.isEmpty()) {
            ergebnis = false;
            sucht = false;
            ed.index.inhaltSuchen("", 0, (r, f) -> {});
            return;
        }
        ergebnis = true;
        String ql = q.toLowerCase().replace('\\', '/').replace(" ", "");
        Panel l = erg[0];
        l.e.clear();
        l.cursor = 0;
        l.oben = 0;
        for (Indexer.Datei d : ed.index.namenSuchen(q, 2000)) {
            Eintrag e = new Eintrag();
            e.name = d.rel();
            e.pfad = d.pfad();
            e.groesse = d.groesse();
            e.wo = new int[ql.length()];
            Indexer.bewerten(d.relKlein(), ql, e.wo);
            l.e.add(e);
        }
        l.titel = "Dateien";
        Panel r = erg[1];
        r.titel = "Inhalt";
        sucht = true;
        ed.index.inhaltSuchen(q, 5000, (treffer, fertig) -> SwingUtilities.invokeLater(() -> {
            if (!q.equals(suche.toString().trim()) || listenTitel != null) return;
            Eintrag alt = r.cursor < r.e.size() ? r.e.get(r.cursor) : null;
            r.e.clear();
            for (Indexer.Treffer t : treffer) r.e.add(Eintrag.treffer(t.pfad(), t.rel(), t.zeile(), t.spalte(), t.laenge(), t.text()));
            r.cursor = 0;
            if (alt != null) {
                for (int k = 0; k < r.e.size(); k++) {
                    Eintrag e = r.e.get(k);
                    if (e.pfad.equals(alt.pfad) && e.zeile == alt.zeile) r.cursor = k;
                }
            }
            if (fertig) sucht = false;
            ed.neuMalen.run();
        }));
    }

    /** Index fertig gelesen: laufende Suche mit allen Dateien wiederholen */
    void indexFertig() {
        if (offen && ergebnis && listenTitel == null) sucheGeaendert();
    }

    /** eigene Ergebnisliste zeigen (Referenzen) */
    void liste(String titel, List<Eintrag> l) {
        if (!offen) oeffnen("commander");
        listenTitel = titel;
        ergebnis = true;
        erg[0].e.clear();
        erg[0].e.addAll(l);
        erg[0].cursor = 0;
        erg[0].oben = 0;
        erg[0].titel = titel;
        aktiv = 0;
        ed.modus = "commander";
    }

    private void oeffnen(String art) {
        if (ordner[0].ordner == null || ordner[1].ordner == null) {
            Path w = ed.arbeitsordner != null ? ed.arbeitsordner : Path.of(System.getProperty("user.home"));
            Path p = ed.d().pfad;
            ordner[0].ordner = p != null && p.getParent() != null ? p.getParent() : w;
            ordner[1].ordner = ed.javaOrdner != null ? ed.javaOrdner : w;
        }
        offen = true;
        if (!fmModus(ed.modus)) vorModus = ed.modus;
        ed.modus = art.equals("search") ? "fmsearch" : "commander";
        neuLaden();
        if (ergebnis && listenTitel == null) sucheGeaendert();
        if (ed.modus.equals("fmsearch") && ergebnis) aktiv = 0;
    }

    // ---------------------------------------------------------------- Befehle
    void befehle(Map<String, Editor.Cmd> c) {
        c.put("fileManager", a -> {
            if (offen) schliessen();
            else oeffnen(a.trim());
        });
        c.put("fmClose", a -> schliessen());
        c.put("fmUp", a -> bewegen(-1));
        c.put("fmDown", a -> bewegen(1));
        c.put("fmPageUp", a -> bewegen(-zeilen()));
        c.put("fmPageDown", a -> bewegen(zeilen()));
        c.put("fmHome", a -> akt().cursor = 0);
        c.put("fmEnd", a -> akt().cursor = Math.max(0, akt().e.size() - 1));
        c.put("fmSwitch", a -> {
            if (listenTitel == null) aktiv = 1 - aktiv;
        });
        c.put("fmLeft", a -> {
            if (listenTitel == null) aktiv = 0;
        });
        c.put("fmRight", a -> {
            if (listenTitel == null) aktiv = 1;
        });
        c.put("fmEnter", a -> enter());
        c.put("fmOpen", a -> enter());
        c.put("fmView", a -> enter());
        c.put("fmParent", a -> eltern());
        c.put("fmFind", a -> ed.modus = "fmsearch");
        c.put("fmSearchChar", a -> {
            suche.append(a);
            sucheGeaendert();
        });
        c.put("fmSearchBackspace", a -> {
            if (suche.length() == 0) return;
            suche.setLength(suche.length() - 1);
            sucheGeaendert();
        });
        c.put("fmSearchClear", a -> {
            suche.setLength(0);
            sucheGeaendert();
        });
        c.put("fmSearchDone", a -> ed.modus = "commander");
        c.put("fmSelect", a -> {
            Panel p = akt();
            for (int k = 0; k < p.e.size(); k++) if (p.e.get(k).name.equals(a.trim())) p.cursor = k;
        });
        c.put("fmRefresh", a -> {
            neuLaden();
            ed.index.neuEinlesen();
            ed.meldung = "neu eingelesen";
        });
        c.put("fmWorkdir", a -> {
            Path p = akt().ordner;
            if (ergebnis || p == null) return;
            ed.arbeitsordnerSetzen(p);
        });
        c.put("fmCopy", a -> {
            Eintrag e = quelle();
            if (e != null) ed.popupOpen("Kopieren: " + e.name + " nach", "fmCopyTo", zielVorgabe());
        });
        c.put("fmMove", a -> {
            Eintrag e = quelle();
            if (e != null) ed.popupOpen("Verschieben: " + e.name + " nach", "fmMoveTo", zielVorgabe());
        });
        c.put("fmRename", a -> {
            Eintrag e = quelle();
            if (e != null) ed.popupOpen("Umbenennen: " + e.name + " in", "fmRenameTo", e.pfad.getFileName().toString());
        });
        c.put("fmMkdir", a -> ed.popupOpen("Neuer Ordner in " + hier() + ":", "fmMkdirIn", ""));
        c.put("fmNewFile", a -> ed.popupOpen("Neue Datei in " + hier() + ":", "fmNewFileIn", ""));
        c.put("fmDelete", a -> {
            Eintrag e = quelle();
            if (e != null) ed.popupOpen("Loeschen: " + e.pfad + (e.ordner ? " (mit Inhalt!)" : "") + " - ENTER loescht", "fmDeleteYes", "");
        });
        c.put("fmCopyTo", a -> uebertragen(a, false));
        c.put("fmMoveTo", a -> uebertragen(a, true));
        c.put("fmRenameTo", a -> {
            Eintrag e = quelle();
            if (e == null || a.isBlank()) return;
            Path z = e.pfad.resolveSibling(a.trim());
            try {
                Files.move(e.pfad, z);
                umziehen(e.pfad, z);
                ergebnisMelden(true, "umbenannt: " + z.getFileName());
            } catch (IOException ex) {
                ergebnisMelden(false, "nicht umbenannt: " + ex.getMessage());
            }
        });
        c.put("fmMkdirIn", a -> {
            if (a.isBlank()) return;
            try {
                Files.createDirectories(hier().resolve(a.trim()));
                ergebnisMelden(true, "Ordner angelegt: " + a.trim());
            } catch (IOException | RuntimeException ex) {
                ergebnisMelden(false, "nicht angelegt: " + ex.getMessage());
            }
        });
        c.put("fmNewFileIn", a -> {
            if (a.isBlank()) return;
            Path z = hier().resolve(a.trim());
            try {
                if (z.getParent() != null) Files.createDirectories(z.getParent());
                if (!Files.exists(z)) Files.createFile(z);
                ed.index.neuEinlesen();
                schliessen();
                ed.oeffnen(z);
            } catch (IOException | RuntimeException ex) {
                ergebnisMelden(false, "nicht angelegt: " + ex.getMessage());
            }
        });
        c.put("fmDeleteYes", a -> {
            Eintrag e = quelle();
            if (e == null) return;
            try {
                loeschen(e.pfad);
                ergebnisMelden(true, "geloescht: " + e.name);
                if (ergebnis) sucheGeaendert();
            } catch (IOException ex) {
                ergebnisMelden(false, "nicht geloescht: " + ex.getMessage());
            }
        });
        c.put("fmHelp", a -> {
            ed.konsoleAuf();
            ed.print("  Dateimanager: Pfeile/PgUp/PgDn bewegen, TAB anderer Bereich, ENTER oeffnen, BACKSPACE hoch (bei Ergebnissen: zurueck zu den Ordnern),");
            ed.print("  F2 neue Datei, F3/F4 oeffnen, F5 kopieren, F6 verschieben, r umbenennen, F7 Ordner, F8 loeschen, Strg+W Arbeitsordner hierher, Strg+R neu einlesen,");
            ed.print("  / oder Strg+F ins Suchfeld: links Dateinamen (Teilfolge, z. B. apcmp -> App/Component.tsx), rechts Inhalt (klein = ohne Gross/Klein).");
            ed.print("  Im Suchfeld: ESC zu den Ergebnissen, Strg+U leeren. ESC/F10 Ende.");
        });
    }

    private void schliessen() {
        offen = false;
        if (fmModus(ed.modus)) ed.modus = vorModus;
    }

    private int zeilen() {
        Panel p = akt();
        return Math.max(1, (p.h - 3 * ed.scr.lh) / ed.scr.lh);
    }

    private void bewegen(int d) {
        Panel p = akt();
        p.cursor = Math.max(0, Math.min(p.e.size() - 1, p.cursor + d));
    }

    private void enter() {
        Eintrag e = auswahl();
        if (e == null) return;
        if (e.ordner) {
            if (e.name.equals("..")) {
                eltern();
                return;
            }
            Panel p = akt();
            if (ergebnis) return;
            p.ordner = e.pfad;
            p.cursor = 0;
            p.oben = 0;
            laden(p);
            return;
        }
        schliessen();
        Doc d = ed.oeffnen(e.pfad);
        if (d != null && e.zeile >= 0) {
            ed.springMerken();
            d.cursor = d.stelle(e.zeile, e.spalte);
            d.spalteMerk = -1;
            if (e.laenge > 0 && listenTitel == null && !suche.isEmpty()) {
                ed.suche = suche.toString().trim();
                ed.sucheZeigen = true;
            }
        }
    }

    private void eltern() {
        if (ergebnis) {
            suche.setLength(0);
            sucheGeaendert();
            return;
        }
        Panel p = akt();
        if (p.ordner == null || p.ordner.getParent() == null) return;
        String alt = p.ordner.getFileName().toString();
        p.ordner = p.ordner.getParent();
        p.e.clear();
        laden(p);
        for (int k = 0; k < p.e.size(); k++) if (p.e.get(k).name.equals(alt)) p.cursor = k;
    }

    private Eintrag quelle() {
        Eintrag e = auswahl();
        if (e == null || e.name.equals("..")) {
            ed.meldung = "nichts ausgewaehlt";
            return null;
        }
        return e;
    }

    private Path hier() {
        Path p = ergebnis ? null : akt().ordner;
        return p != null ? p : ed.arbeitsordner;
    }

    private String zielVorgabe() {
        Path p = listenTitel != null || ergebnis ? ed.arbeitsordner : ordner[1 - aktiv].ordner;
        return p == null ? "" : p.toString();
    }

    private void uebertragen(String ziel, boolean verschieben) {
        Eintrag e = quelle();
        if (e == null || ziel.isBlank()) return;
        Path z = ed.aufloesen(ziel);
        if (Files.isDirectory(z)) z = z.resolve(e.pfad.getFileName().toString());
        try {
            if (verschieben) {
                Files.move(e.pfad, z);
                umziehen(e.pfad, z);
            } else {
                kopieren(e.pfad, z);
            }
            ergebnisMelden(true, (verschieben ? "verschoben: " : "kopiert: ") + z);
        } catch (IOException ex) {
            ergebnisMelden(false, (verschieben ? "nicht verschoben: " : "nicht kopiert: ") + ex.getMessage());
        }
    }

    /** offene Tabs zeigen nach Umbenennen/Verschieben auf den neuen Ort */
    private void umziehen(Path alt, Path neu) {
        for (Doc t : ed.tabs) if (t.pfad != null && t.pfad.startsWith(alt)) t.pfad = neu.resolve(alt.relativize(t.pfad));
    }

    private static void kopieren(Path q, Path z) throws IOException {
        if (!Files.isDirectory(q)) {
            Files.copy(q, z, StandardCopyOption.COPY_ATTRIBUTES);
            return;
        }
        Files.walkFileTree(q, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
                Files.createDirectories(z.resolve(q.relativize(d).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.copy(f, z.resolve(q.relativize(f).toString()), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void loeschen(Path p) throws IOException {
        if (!Files.isDirectory(p) || Files.isSymbolicLink(p)) {
            Files.delete(p);
            return;
        }
        Files.walkFileTree(p, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.delete(f);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void ergebnisMelden(boolean ok, String was) {
        ed.meldung = was;
        ed.meldungFarbe = ok ? E_WARN : E_FEHLER;
        if (!ok) ed.farbig(was, E_FEHLER);
        neuLaden();
        ed.index.neuEinlesen();
    }

    // ---------------------------------------------------------------- Maus
    void maus(int x, int y, boolean klick, int rad) {
        for (int k = 0; k < (listenTitel != null ? 1 : 2); k++) {
            Panel p = panel(k);
            if (x < p.x || x >= p.x + p.w || y < p.y || y >= p.y + p.h) continue;
            if (rad != 0) {
                p.cursor = Math.max(0, Math.min(p.e.size() - 1, p.cursor + rad * 3));
            } else if (klick) {
                aktiv = k;
                int i = p.oben + (y - p.zeilenY) / ed.scr.lh;
                if (y >= p.zeilenY && i >= 0 && i < p.e.size()) {
                    if (i == p.cursor) enter();
                    else p.cursor = i;
                }
                if (ed.modus.equals("fmsearch")) ed.modus = "commander";
            }
        }
    }

    // ---------------------------------------------------------------- Malen
    private static final String[] TASTEN = {"Hilfe", "Neu", "Ansehen", "Oeffnen", "Kopieren", "Verschieb", "Ordner", "Loeschen", "", "Ende"};

    void malen(Screen s, int y0, int y1) {
        int cw = s.cw, lh = s.lh, W = s.w;
        s.rect(0, y0, W, y1 - y0, E_GRUND);
        // ------------------------------------------------ Suchfeld
        boolean fokus = ed.modus.equals("fmsearch");
        int sy = y0 + lh / 2 + 6, sh = lh + 10;
        s.rect(6, sy, W - 12, sh, E_KONSOLE);
        s.frame(6, sy, W - 12, sh, fokus ? E_HELL : E_MATT);
        s.frame(9, sy + 3, W - 18, sh - 6, fokus ? E_TEXT : E_MATT);
        String kopf = " SUCHE: DATEINAME ODER INHALT ";
        if (fokus) {
            s.rect(3 * cw, sy - lh / 2, s.textWidth(kopf), lh, E_TEXT);
            s.text(3 * cw, sy - lh / 2, kopf, E_GRUND);
        } else {
            s.rect(3 * cw, sy - lh / 2, s.textWidth(kopf), lh, E_GRUND);
            s.text(3 * cw, sy - lh / 2, kopf, E_TEXT);
        }
        String hinweis;
        if (listenTitel != null) hinweis = listenTitel + ": " + erg[0].e.size();
        else if (ergebnis) hinweis = "Dateien " + erg[0].e.size() + "   Inhalt " + erg[1].e.size() + (sucht ? "+" : "") + "   ";
        else hinweis = fokus ? "tippen zum Suchen   " : "/ sucht   ";
        hinweis += ed.index.laeuft ? "Index wird gelesen..." : "Index " + ed.index.dateien.size() + " Dateien";
        int hx = W - 12 - 2 * cw - s.textWidth(hinweis);
        s.text(hx, sy + 5, hinweis, E_MATT);
        s.clip(9, sy, hx - 9 - cw, sh);
        int x = s.text(9 + 2 * cw, sy + 5, "> ", E_HELL);
        x = s.text(x, sy + 5, suche, E_HELL);
        if (fokus) s.rect(x + 1, sy + 6, cw - 2, lh - 2, E_HELL);
        s.noClip();
        // ------------------------------------------------ Bereiche
        int py = sy + sh + lh;
        int ty = y1 - lh - 16;
        int ph = ty - py - 6;
        if (listenTitel != null) {
            panelMalen(s, erg[0], 6, py, W - 12, ph, true);
        } else {
            int w = W / 2;
            panelMalen(s, panel(0), 6, py, w - 9, ph, aktiv == 0);
            panelMalen(s, panel(1), w + 3, py, w - 9, ph, aktiv == 1);
        }
        // ------------------------------------------------ Funktionstasten
        s.rect(0, ty - 2, W, lh + 16, E_GRUND);
        int bw = W / 10;
        for (int k = 0; k < 10; k++) {
            int bx = k * bw;
            int xx = s.text(bx + 4, ty + 6, String.valueOf(k + 1), E_HELL);
            s.rect(xx + 2, ty + 2, bw - (xx - bx) - 8, lh + 8, E_TEXT);
            s.clip(xx + 2, ty, bw - (xx - bx) - 8, lh + 12);
            s.text(xx + 6, ty + 6, TASTEN[k], E_GRUND);
            s.noClip();
        }
    }

    private void panelMalen(Screen s, Panel p, int x, int y, int w, int h, boolean aktivJa) {
        int cw = s.cw, lh = s.lh;
        p.x = x;
        p.y = y;
        p.w = w;
        p.h = h;
        int rahmen = aktivJa ? E_HELL : E_MATT;
        s.rect(x, y, w, h, E_KONSOLE);
        s.frame(x, y, w, h, rahmen);
        s.frame(x + 3, y + 3, w - 6, h - 6, rahmen);
        boolean treffer = ergebnis && (p == erg[1] || listenTitel != null);
        boolean dateien = ergebnis && !treffer;
        String t = ergebnis ? p.titel + " (" + p.e.size() + ")" : p.titel;
        String kopf = " " + t + " ";
        int maxK = w - 4 * cw;
        if (s.textWidth(kopf) > maxK) kopf = " ~" + kopf.substring(kopf.length() - maxK / cw + 2);
        int kw = s.textWidth(kopf);
        int kx = x + (w - kw) / 2;
        s.rect(kx, y - lh / 2, kw, lh, aktivJa ? E_TEXT : E_KONSOLE);
        s.text(kx, y - lh / 2, kopf, aktivJa ? E_GRUND : E_TEXT);
        int gx = x + w - 12 * cw - 2 * cw;
        s.text(x + 2 * cw, y + lh / 2 + 2, treffer ? "Datei:Zeile  Text" : dateien ? "Pfad" : "Name", E_WARN);
        if (!treffer) {
            s.text(gx, y + lh / 2 + 2, "     Groesse", E_WARN);
            s.rect(gx - cw, y + 6, 2, h - 2 * lh - 12, E_MATT);
        }
        int zeilen = Math.max(1, (h - 3 * lh) / lh);
        if (p.cursor >= p.e.size()) p.cursor = Math.max(0, p.e.size() - 1);
        if (p.cursor < p.oben) p.oben = p.cursor;
        if (p.cursor >= p.oben + zeilen) p.oben = p.cursor - zeilen + 1;
        p.zeilenY = y + lh * 3 / 2 + 4;
        int nameBreite = (treffer ? w - 4 * cw : gx - x - 4 * cw);
        for (int k = 0; k < zeilen; k++) {
            int i = p.oben + k;
            if (i >= p.e.size()) break;
            Eintrag e = p.e.get(i);
            int zy = p.zeilenY + k * lh;
            boolean gewaehlt = i == p.cursor;
            int f = e.ordner ? E_HELL : E_TEXT;
            if (gewaehlt && aktivJa) {
                s.rect(x + 6, zy, w - 12, lh, E_TEXT);
                f = E_GRUND;
            } else if (gewaehlt) {
                s.frame(x + 6, zy, w - 12, lh, E_MATT);
            }
            int hell = gewaehlt && aktivJa ? E_GRUND : E_WARN;
            s.clip(x + 2 * cw, zy, nameBreite, lh);
            if (treffer) {
                trefferMalen(s, e, x + 2 * cw, zy, nameBreite, f, hell, gewaehlt && aktivJa);
            } else if (dateien) {
                int xx = x + 2 * cw;
                for (int c = 0; c < e.name.length(); c++) {
                    boolean passt = false;
                    if (e.wo != null) for (int q : e.wo) passt |= q == c;
                    s.glyph(xx + c * cw, zy, e.name.charAt(c), passt ? hell : f);
                }
            } else {
                s.text(x + 2 * cw, zy, (e.ordner && !e.name.equals("..") ? "/" : "") + e.name, f);
            }
            s.noClip();
            if (!treffer) {
                String g = e.name.equals("..") ? "<HOCH>" : e.ordner ? "<ORDNER>" : groesse(e.groesse);
                s.text(x + w - 2 * cw - s.textWidth(g), zy, g, f);
            }
        }
        s.rect(x + 6, y + h - lh - 10, w - 12, 2, E_MATT);
        Eintrag e = auswahlVon(p);
        if (e != null) {
            s.clip(x, y, w - 2 * cw, h);
            String u = e.zeile >= 0 ? e.name + ":" + (e.zeile + 1) + ":" + (e.spalte + 1) : e.pfad.toString();
            s.text(x + 2 * cw, y + h - lh - 5, u, E_TEXT);
            s.noClip();
        }
    }

    private static Eintrag auswahlVon(Panel p) {
        return p.cursor >= 0 && p.cursor < p.e.size() ? p.e.get(p.cursor) : null;
    }

    private void trefferMalen(Screen s, Eintrag e, int x, int y, int breite, int f, int hell, boolean gewaehlt) {
        int cw = s.cw;
        String ort = e.name + ":" + (e.zeile + 1);
        int maxOrt = Math.max(10, breite / cw * 2 / 5);
        if (ort.length() > maxOrt) ort = "~" + ort.substring(ort.length() - maxOrt + 1);
        int xx = s.text(x, y, ort, gewaehlt ? E_GRUND : E_MATT) + 2 * cw;
        String z = e.text == null ? "" : e.text.replace('\t', ' ');
        int ab = 0;
        while (ab < z.length() && ab < e.spalte && z.charAt(ab) == ' ') ab++;
        int platz = Math.max(1, (x + breite - xx) / cw);
        // Treffer sichtbar halten
        if (e.spalte - ab + e.laenge > platz) ab = Math.max(ab, e.spalte + e.laenge - platz + 4);
        for (int c = ab; c < z.length() && (c - ab) < platz; c++) {
            boolean imTreffer = c >= e.spalte && c < e.spalte + e.laenge;
            s.glyph(xx + (c - ab) * cw, y, z.charAt(c), imTreffer ? hell : f);
        }
    }

    private static String groesse(long g) {
        if (g < 0) return "";
        if (g < 10_000) return String.valueOf(g);
        if (g < 10_000_000) return (g / 1024) + "K";
        return (g / (1024 * 1024)) + "M";
    }
}
