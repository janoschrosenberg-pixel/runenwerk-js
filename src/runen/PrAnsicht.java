package runen;

import static runen.Editor.E_FEHLER;
import static runen.Editor.E_GRUND;
import static runen.Editor.E_HELL;
import static runen.Editor.E_INFO;
import static runen.Editor.E_KONSOLE;
import static runen.Editor.E_LEISTE;
import static runen.Editor.E_MATT;
import static runen.Editor.E_TEXT;
import static runen.Editor.E_WARN;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pull Request nebeneinander: links die Dateien (mit Uebersicht), daneben alt und neu Seite an Seite,
 * Zeile fuer Zeile ausgerichtet. Geaenderte Zeilen sind getoent, innerhalb einer Zeile sind die
 * geaenderten Woerter kraeftiger markiert; lange Zeilen brechen um, Syntax ist gefaerbt. Kommentare
 * (auch Befunde von Teamscale) stehen als Kasten unter ihrer Zeile; ENTER springt an die Codestelle.
 * Die Statuszeile zeigt alle Tasten. Auch fuer den Vergleich einer Datei mit einem Commit (lokal:
 * ohne Pull Request, ohne die SCM-Tasten).
 */
final class PrAnsicht {
    static final int HUNK = 1, GLEICH = 2, WEG = 3, NEU = 4, PAAR = 5, KOMMENTAR = 6, TEXT = 7, LEER = 8;

    static final class Zeile {
        final int art;
        /** Zeilennummern (1-basiert, 0 = keine) */
        int alt, neu;
        String links = "", rechts = "";
        Scm.Kommentar kom;
        int farbe = E_TEXT, einzug;
        /** Codestelle "pfad TAB zeile" (Uebersicht) */
        String ziel;
        /** Woerter, die sich in einem Paar unterscheiden; Versatz in den Farben der Seite */
        boolean[] markL, markR;
        int offL = -1, offR = -1;

        Zeile(int art) {
            this.art = art;
        }

        boolean aenderung() {
            return art == WEG || art == NEU || art == PAAR;
        }
    }

    static final class Datei {
        String alt, neu, status = "M";
        int plus, minus, kommentare;
        final List<Zeile> zeilen = new ArrayList<>();
        byte[] farbenL, farbenR;
        boolean gefaerbt;

        String pfad() {
            return neu != null && !neu.equals("/dev/null") ? neu : alt;
        }
    }

    private final Editor ed;
    boolean aktiv;
    Object pr;
    Scm.Ziel ziel;
    private final List<Datei> dateien = new ArrayList<>();
    private int wahl, zeile, oben;
    private boolean umbruch = true;
    private String woher = "";
    /** lokaler Vergleich (Leertaste g d): Titel, Namen der Seiten, Repo, unified diff, neu rechnen */
    boolean lokal;
    private String lokTitel = "", lokAlt = "", lokNeu = "", lokDiff = "";
    private Path lokWurzel;
    private Runnable lokNeuLaden;

    PrAnsicht(Editor ed) {
        this.ed = ed;
    }

    boolean sichtbar() {
        return aktiv && (ed.modus.equals("prdiff") || ed.modus.equals("popup") || ed.modus.equals("picker") || ed.modus.equals("console"));
    }

    private Datei datei() {
        return dateien.get(Math.max(0, Math.min(wahl, dateien.size() - 1)));
    }

    private Zeile aktuelle() {
        List<Zeile> l = datei().zeilen;
        return l.isEmpty() ? null : l.get(Math.max(0, Math.min(zeile, l.size() - 1)));
    }

    // ================================================================ Aufbau
    /** neu fuellen; bei gleichem Pull Request bleiben Datei und Zeile */
    void zeigen(Object pr, Scm.Ziel z, List<Scm.Kommentar> kom, String komFehler, String diff, String woher, String ich) {
        boolean gleich = !lokal && this.pr != null && Scm.id(this.pr).equals(Scm.id(pr));
        lokal = false;
        String altePfad = gleich && !dateien.isEmpty() ? datei().pfad() : null;
        int alteZeile = zeile;
        this.pr = pr;
        this.ziel = z;
        this.woher = woher;
        dateien.clear();
        List<Datei> code = parsen(diff);
        Datei ueb = new Datei();
        ueb.alt = ueb.neu = null;
        ueb.status = "";
        dateien.add(ueb);
        dateien.addAll(code);
        List<Scm.Kommentar> allgemein = new ArrayList<>(), draussen = new ArrayList<>();
        for (Scm.Kommentar k : kom) {
            if (k.datei() == null || k.neu() <= 0 && k.alt() <= 0) {
                allgemein.add(k);
                continue;
            }
            if (!einhaengen(code, k)) draussen.add(k);
        }
        uebersicht(ueb, kom.size(), komFehler, allgemein, draussen, ich);
        aktiv = true;
        ed.modus = "prdiff";
        wahl = 0;
        zeile = 0;
        oben = 0;
        if (altePfad != null) {
            for (int i = 0; i < dateien.size(); i++) {
                if (altePfad.equals(dateien.get(i).pfad())) {
                    wahl = i;
                    zeile = Math.min(alteZeile, Math.max(0, dateien.get(i).zeilen.size() - 1));
                }
            }
        }
        ed.meldung = null;
    }

    /**
     * Datei gegen einen Commit nebeneinander: links alt (Commit), rechts neu (Editor); die Uebersicht zeigt
     * info. Gleicher Titel (neu laden): Zeile bleibt.
     */
    void zeigenLokal(String titel, String links, String rechts, List<String> info, String diff, Path wurzel, Runnable neu) {
        boolean gleich = lokal && aktiv && titel.equals(lokTitel);
        int alteWahl = wahl, alteZeile = zeile, altesOben = oben;
        lokal = true;
        pr = null;
        ziel = null;
        lokTitel = titel;
        lokAlt = links;
        lokNeu = rechts;
        lokDiff = diff;
        lokWurzel = wurzel;
        lokNeuLaden = neu;
        dateien.clear();
        Datei ueb = new Datei();
        ueb.status = "";
        dateien.add(ueb);
        dateien.addAll(parsen(diff));
        for (String t : info) text(ueb, t, t.equals(info.get(0)) ? E_HELL : t.endsWith(":") && t.equals(t.toUpperCase()) ? E_INFO : E_TEXT, 0, null);
        aktiv = true;
        ed.modus = "prdiff";
        if (gleich && alteWahl < dateien.size()) {
            wahl = alteWahl;
            zeile = Math.min(alteZeile, Math.max(0, datei().zeilen.size() - 1));
            oben = Math.min(altesOben, zeile);
        } else {
            // gleich auf die erste Aenderung der (ersten) Datei
            wahl = dateien.size() > 1 ? 1 : 0;
            zeile = 0;
            oben = 0;
            if (wahl > 0) naechste(z -> z.aenderung(), 1, false);
        }
        ed.meldung = null;
    }

    private static final Pattern HUNK_KOPF = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@ ?(.*)");

    /** unified diff (git) in Dateien und nebeneinander stehende Zeilen */
    static List<Datei> parsen(String diff) {
        List<Datei> r = new ArrayList<>();
        Datei d = null;
        int alt = 0, neu = 0;
        boolean imHunk = false;
        List<String> weg = new ArrayList<>(), dazu = new ArrayList<>();
        int[] wegAb = {0}, dazuAb = {0};
        for (String s : diff.split("\n", -1)) {
            if (s.endsWith("\r")) s = s.substring(0, s.length() - 1);
            boolean plus = imHunk && s.startsWith("+"), minus = imHunk && s.startsWith("-");
            if (d != null && !plus && !minus && (!weg.isEmpty() || !dazu.isEmpty())) {
                paareSetzen(d, weg, dazu, wegAb[0], dazuAb[0]);
                weg.clear();
                dazu.clear();
            }
            if (s.startsWith("diff --git ")) {
                d = new Datei();
                Matcher m = Pattern.compile("^diff --git a/(.*) b/(.*)$").matcher(s);
                if (m.find()) {
                    d.alt = m.group(1);
                    d.neu = m.group(2);
                }
                r.add(d);
                imHunk = false;
                continue;
            }
            if (d == null) continue;
            if (!imHunk) {
                if (s.startsWith("new file")) d.status = "A";
                else if (s.startsWith("deleted file")) d.status = "D";
                else if (s.startsWith("rename from ")) d.status = "R";
                else if (s.startsWith("--- ")) d.alt = pfad(s.substring(4), d.alt);
                else if (s.startsWith("+++ ")) d.neu = pfad(s.substring(4), d.neu);
                else if (s.startsWith("Binary files")) {
                    Zeile z = new Zeile(TEXT);
                    z.links = "Binaerdatei - kein Text-Diff";
                    z.farbe = E_MATT;
                    d.zeilen.add(z);
                }
            }
            Matcher h = HUNK_KOPF.matcher(s);
            if (h.find()) {
                alt = Integer.parseInt(h.group(1));
                neu = Integer.parseInt(h.group(3));
                int na = h.group(2) == null ? 1 : Integer.parseInt(h.group(2)), nn = h.group(4) == null ? 1 : Integer.parseInt(h.group(4));
                Zeile z = new Zeile(HUNK);
                z.links = "Zeilen " + bereich(alt, na) + "  ->  " + bereich(neu, nn) + (h.group(5).isBlank() ? "" : "     " + h.group(5).strip());
                d.zeilen.add(z);
                imHunk = true;
                continue;
            }
            if (!imHunk) continue;
            if (minus) {
                if (weg.isEmpty()) wegAb[0] = alt;
                weg.add(s.substring(1));
                alt++;
                d.minus++;
            } else if (plus) {
                if (dazu.isEmpty()) dazuAb[0] = neu;
                dazu.add(s.substring(1));
                neu++;
                d.plus++;
            } else if (s.startsWith(" ")) {
                Zeile z = new Zeile(GLEICH);
                z.alt = alt++;
                z.neu = neu++;
                z.links = z.rechts = s.isEmpty() ? "" : s.substring(1);
                d.zeilen.add(z);
            }
        }
        if (d != null && (!weg.isEmpty() || !dazu.isEmpty())) paareSetzen(d, weg, dazu, wegAb[0], dazuAb[0]);
        return r;
    }

    private static String bereich(int ab, int n) {
        return n <= 1 ? String.valueOf(ab) : ab + "-" + (ab + n - 1);
    }

    private static String pfad(String s, String sonst) {
        s = s.strip();
        if (s.equals("/dev/null")) return sonst;
        return s.startsWith("a/") || s.startsWith("b/") ? s.substring(2) : s;
    }

    /**
     * entfernte und neue Zeilen nebeneinander: gleich viele der Reihe nach als Paare; sonst werden aehnliche
     * Zeilen Paare (in Reihenfolge, beste Gesamtaehnlichkeit), der Rest steht allein
     */
    private static void paareSetzen(Datei d, List<String> weg, List<String> dazu, int altAb, int neuAb) {
        int n = weg.size(), m = dazu.size();
        boolean[][] paar = null;
        if (n > 0 && m > 0 && n != m && (long) n * m <= 40_000) {
            double[][] best = new double[n + 1][m + 1];
            double[][] sim = new double[n][m];
            for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) sim[i][j] = aehnlich(weg.get(i), dazu.get(j));
            for (int i = n - 1; i >= 0; i--) {
                for (int j = m - 1; j >= 0; j--) {
                    double v = Math.max(best[i + 1][j], best[i][j + 1]);
                    if (sim[i][j] >= 0.3) v = Math.max(v, best[i + 1][j + 1] + sim[i][j]);
                    best[i][j] = v;
                }
            }
            paar = new boolean[n][m];
            int i = 0, j = 0;
            while (i < n && j < m) {
                if (sim[i][j] >= 0.3 && best[i][j] == best[i + 1][j + 1] + sim[i][j]) paar[i++][j++] = true;
                else if (best[i][j] == best[i + 1][j]) i++;
                else j++;
            }
        }
        int i = 0, j = 0;
        while (i < n || j < m) {
            Zeile z;
            boolean p = paar == null ? i < n && j < m : i < n && j < m && paar[i][j];
            if (p) {
                z = new Zeile(PAAR);
                z.alt = altAb + i;
                z.neu = neuAb + j;
                z.links = weg.get(i++);
                z.rechts = dazu.get(j++);
                woerter(z);
            } else if (i < n && (paar == null || j >= m || !naechstesPaarIn(paar, i, j))) {
                z = new Zeile(WEG);
                z.alt = altAb + i;
                z.links = weg.get(i++);
            } else {
                z = new Zeile(NEU);
                z.neu = neuAb + j;
                z.rechts = dazu.get(j++);
            }
            d.zeilen.add(z);
        }
    }

    /** liegt das naechste Paar ab (i, j) in Zeile i (entfernte Seite)? dann erst die neuen Zeilen davor */
    private static boolean naechstesPaarIn(boolean[][] paar, int i, int j) {
        for (int k = j; k < paar[i].length; k++) if (paar[i][k]) return true;
        return false;
    }

    /** Aehnlichkeit zweier Zeilen 0..1 (gemeinsame Zeichen nach Woertern, ohne Einrueckung) */
    static double aehnlich(String a, String b) {
        a = a.strip();
        b = b.strip();
        if (a.equals(b)) return 1;
        if (a.isEmpty() || b.isEmpty()) return 0;
        Map<String, Integer> h = new HashMap<>();
        Matcher m = WORT.matcher(a);
        while (m.find()) if (!m.group().isBlank()) h.merge(m.group(), 1, Integer::sum);
        int gemein = 0;
        m = WORT.matcher(b);
        while (m.find()) {
            String t = m.group();
            Integer c = h.get(t);
            if (c != null && c > 0) {
                h.put(t, c - 1);
                gemein += t.length();
            }
        }
        return 2.0 * gemein / (a.replaceAll("\\s", "").length() + b.replaceAll("\\s", "").length());
    }

    private static final Pattern WORT = Pattern.compile("\\w+|\\s+|[^\\w\\s]");

    /** geaenderte Woerter eines Paars markieren (LCS ueber Woerter, bei langen Zeilen Anfang/Ende) */
    static void woerter(Zeile z) {
        String a = z.links, b = z.rechts;
        z.markL = new boolean[a.length()];
        z.markR = new boolean[b.length()];
        List<int[]> ta = token(a), tb = token(b);
        if (ta.size() > 300 || tb.size() > 300) {
            int p = 0;
            while (p < a.length() && p < b.length() && a.charAt(p) == b.charAt(p)) p++;
            int s = 0;
            while (s < a.length() - p && s < b.length() - p && a.charAt(a.length() - 1 - s) == b.charAt(b.length() - 1 - s)) s++;
            for (int i = p; i < a.length() - s; i++) z.markL[i] = true;
            for (int i = p; i < b.length() - s; i++) z.markR[i] = true;
            return;
        }
        int n = ta.size(), m = tb.size();
        int[][] l = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                l[i][j] = gleich(a, ta.get(i), b, tb.get(j)) ? l[i + 1][j + 1] + 1 : Math.max(l[i + 1][j], l[i][j + 1]);
            }
        }
        boolean[] ga = new boolean[n], gb = new boolean[m];
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (gleich(a, ta.get(i), b, tb.get(j))) {
                ga[i++] = true;
                gb[j++] = true;
            } else if (l[i + 1][j] >= l[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        for (int k = 0; k < n; k++) if (!ga[k]) for (int c = ta.get(k)[0]; c < ta.get(k)[1]; c++) z.markL[c] = true;
        for (int k = 0; k < m; k++) if (!gb[k]) for (int c = tb.get(k)[0]; c < tb.get(k)[1]; c++) z.markR[c] = true;
        // nur Leerraum geaendert, sonst nichts: nicht markieren (sieht sonst wie ein Fehler aus)
    }

    private static List<int[]> token(String s) {
        List<int[]> r = new ArrayList<>();
        Matcher m = WORT.matcher(s);
        while (m.find()) r.add(new int[] {m.start(), m.end()});
        return r;
    }

    private static boolean gleich(String a, int[] x, String b, int[] y) {
        return x[1] - x[0] == y[1] - y[0] && a.regionMatches(x[0], b, y[0], x[1] - x[0]);
    }

    /** Zeilenkommentar unter seine Zeile; false = Zeile nicht im Diff */
    private static boolean einhaengen(List<Datei> code, Scm.Kommentar k) {
        for (Datei d : code) {
            if (!k.datei().equals(d.neu) && !k.datei().equals(d.alt)) continue;
            for (int i = 0; i < d.zeilen.size(); i++) {
                Zeile z = d.zeilen.get(i);
                boolean passt = k.neu() > 0 ? z.neu == k.neu() && z.art != WEG : z.alt == k.alt() && z.art != NEU;
                if (!passt) continue;
                // hinter die Zeile und die Kommentare, die schon an ihr haengen
                int j = i + 1;
                while (j < d.zeilen.size() && d.zeilen.get(j).art == KOMMENTAR) j++;
                Zeile c = new Zeile(KOMMENTAR);
                c.kom = k;
                c.alt = z.alt;
                c.neu = z.neu;
                d.zeilen.add(j, c);
                d.kommentare++;
                return true;
            }
        }
        return false;
    }

    private void text(Datei u, String t, int farbe, int einzug, String ziel) {
        Zeile z = new Zeile(TEXT);
        z.links = t;
        z.farbe = farbe;
        z.einzug = einzug;
        z.ziel = ziel;
        u.zeilen.add(z);
    }

    private void kommentar(Datei u, Scm.Kommentar k) {
        Zeile z = new Zeile(KOMMENTAR);
        z.kom = k;
        u.zeilen.add(z);
        u.kommentare++;
    }

    private void uebersicht(Datei u, int anzahl, String komFehler, List<Scm.Kommentar> allgemein, List<Scm.Kommentar> draussen, String ich) {
        text(u, nz(Json.str(Json.get(pr, "title"))), E_HELL, 0, null);
        text(u, "", E_TEXT, 0, null);
        text(u, "Status       " + nz(Json.str(Json.get(pr, "status"))), E_TEXT, 0, null);
        text(u, "Branches     " + Json.str(Json.get(pr, "source")) + "  ->  " + Json.str(Json.get(pr, "target")), E_TEXT, 0, null);
        text(u, "Autor        " + Scm.person(Json.get(pr, "author")) + "   erstellt " + Scm.datum(Json.str(Json.get(pr, "creationDate"))), E_TEXT, 0, null);
        List<Object> rv = Json.list(Json.get(pr, "reviewer"));
        if (rv.isEmpty()) text(u, "Reviewer     keine (e fuegt hinzu)", E_MATT, 0, null);
        for (int i = 0; i < rv.size(); i++) {
            Object r = rv.get(i);
            boolean ok = Boolean.TRUE.equals(Json.get(r, "approved"));
            String id = Json.str(Json.get(r, "id"));
            text(u, (i == 0 ? "Reviewer     " : "             ") + (ok ? "[freigegeben] " : "[offen]       ") + Scm.person(r) + (id != null && id.equals(ich) ? "  (du)" : ""),
                    ok ? E_HELL : E_TEXT, 0, null);
        }
        Object tasks = Json.get(pr, "tasks");
        int todo = Json.num(Json.get(tasks, "todo"), 0), done = Json.num(Json.get(tasks, "done"), 0);
        if (todo + done > 0) text(u, "Aufgaben     " + todo + " offen, " + done + " erledigt", todo > 0 ? E_WARN : E_TEXT, 0, null);
        int plus = 0, minus = 0;
        for (Datei d : dateien) {
            plus += d.plus;
            minus += d.minus;
        }
        text(u, "Aenderungen  " + (dateien.size() - 1) + " Dateien, +" + plus + " -" + minus + "   (" + woher + ")", E_TEXT, 0, null);
        String beschr = nz(Json.str(Json.get(pr, "description"))).strip();
        text(u, "", E_TEXT, 0, null);
        text(u, "BESCHREIBUNG", E_INFO, 0, null);
        if (beschr.isEmpty()) text(u, "(keine)", E_MATT, 2, null);
        for (String b : beschr.split("\\R")) if (!beschr.isEmpty()) text(u, b, E_TEXT, 2, null);
        text(u, "", E_TEXT, 0, null);
        text(u, "KOMMENTARE (" + anzahl + ")" + (komFehler != null ? "   nicht geladen: " + komFehler : ""), komFehler != null ? E_FEHLER : E_INFO, 0, null);
        if (allgemein.isEmpty()) text(u, "keine allgemeinen Kommentare" + (anzahl > 0 ? " - die uebrigen stehen im Code unter ihrer Zeile" : ""), E_MATT, 2, null);
        for (Scm.Kommentar k : allgemein) kommentar(u, k);
        if (!draussen.isEmpty()) {
            text(u, "", E_TEXT, 0, null);
            text(u, "KOMMENTARE ZU ZEILEN AUSSERHALB DES DIFFS (" + draussen.size() + ")", E_INFO, 0, null);
            for (Scm.Kommentar k : draussen) kommentar(u, k);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ================================================================ Tasten
    /** Tasten fuer die Statuszeile: Taste, Funktion, kurz; wichtigste zuerst (fehlt Platz, fallen die letzten weg) */
    static final String[][] TASTEN = {{"a", "Freigeben", "Freigabe"}, {"x", "Freigabe zurueck", "Freig. weg"}, {"m", "Mergen", "Merge"}, {"R", "Ablehnen", "Ablehnen"},
            {"c", "Kommentar", "Komm."}, {"C", "Antworten", "Antw."}, {"e", "Reviewer", "Rev."}, {"u", "Datei zuruecksetzen", "Revert"}, {"ENTER", "zur Codestelle", "Code"}, {"TAB", "naechste Datei", "Datei"},
            {"n/p", "Aenderung", "Aend."}, {"]k/[k", "Kommentar", "Komm."}, {"q", "Liste", "Liste"}, {"w", "Umbruch", "Umbr."}, {"r", "neu laden", "neu"},
            {"o", "Browser", "Web"}};

    /** Tasten beim lokalen Vergleich */
    static final String[][] TASTEN_LOKAL = {{"ENTER", "zur Codestelle", "Code"}, {"n/p", "naechste/vorige Aenderung", "Aend."}, {"j/k", "Zeile", "Zeile"},
            {"Strg+d/u", "Seite", "Seite"}, {"w", "Umbruch", "Umbr."}, {"r", "neu vergleichen", "neu"}, {"t", "als Text (unified)", "Text"}, {"TAB", "Uebersicht/Datei", "Datei"},
            {"q", "schliessen", "zu"}};

    void befehle(Map<String, Editor.Cmd> c) {
        /** @binding Pull Request: Zeile runter/hoch (Zahl), auch seitenweise (page, -page) */
        c.put("prMove", a -> bewegen(a.strip()));
        /** @binding Pull Request: naechste/vorige Datei (1, -1); 0 = Uebersicht */
        c.put("prFile", a -> {
            int n = a.strip().equals("0") ? -wahl : a.strip().startsWith("-") ? -1 : 1;
            wahl = Math.floorMod(wahl + n, dateien.size());
            zeile = 0;
            oben = 0;
            if (wahl > 0) naechste(z -> z.aenderung() || z.art == KOMMENTAR, 1, false);
        });
        /** @binding Pull Request: naechste/vorige Aenderung (1, -1), auch ueber Dateien */
        c.put("prChange", a -> springenNach(z -> z.aenderung(), a.strip().startsWith("-") ? -1 : 1, "Aenderung"));
        /** @binding Pull Request: naechster/voriger Kommentar (1, -1), auch ueber Dateien */
        c.put("prComment", a -> springenNach(z -> z.art == KOMMENTAR, a.strip().startsWith("-") ? -1 : 1, "Kommentar"));
        /** @binding Pull Request: an die Codestelle (Zeile, Kommentar, Datei in der Uebersicht) */
        c.put("prOpen", a -> oeffnen());
        /** @binding Pull Request: lange Zeilen umbrechen an/aus */
        c.put("prWrap", a -> {
            umbruch = !umbruch;
            ed.meldung = umbruch ? "Umbruch an" : "Umbruch aus (lange Zeilen abgeschnitten)";
        });
        /** @binding Pull Request: zurueck zur Liste */
        c.put("prClose", a -> {
            aktiv = false;
            ed.modus = ed.scm.offen && !lokal ? "pullrequests" : "normal";
        });
        /** @binding Pull Request: freigeben */
        c.put("prApprove", a -> nurPr(() -> ed.scm.freigeben(pr, true, this::neuLaden)));
        /** @binding Pull Request: Freigabe zuruecknehmen */
        c.put("prDisapprove", a -> nurPr(() -> ed.scm.freigeben(pr, false, this::neuLaden)));
        /** @binding Pull Request: mergen (Strategie, Vorgabe Squash; Quell-Branch danach loeschen) */
        c.put("prMerge", a -> nurPr(() -> ed.scm.mergen(pr, this::neuLaden)));
        /** @binding Pull Request: ablehnen (mit Begruendung) */
        c.put("prReject", a -> nurPr(() -> ed.scm.ablehnen(pr, this::neuLaden)));
        /** @binding Pull Request: Kommentar schreiben (auf einer Codezeile zu dieser Zeile, sonst allgemein) */
        c.put("prCommentNew", a -> nurPr(() -> kommentieren(false)));
        /** @binding Pull Request: auf den Kommentar unter dem Cursor antworten */
        c.put("prReply", a -> nurPr(() -> kommentieren(true)));
        /** @binding Pull Request: Reviewer aendern (an/abwaehlen, tippen sucht) */
        c.put("prReviewers", a -> nurPr(() -> ed.scm.reviewerAendern(pr, this::neuLaden)));
        /** @binding Vergleich mit einem Commit: als Text (unified diff, schreibgeschuetzter Puffer) */
        c.put("prText", a -> {
            if (!lokal) {
                ed.meldung = "t: nur beim Vergleich mit einem Commit (Leertaste g d)";
                return;
            }
            aktiv = false;
            ed.unterschied.zeigen(lokTitel, "# " + lokAlt + "  ->  " + lokNeu + "\n# ENTER springt in die Datei, ]c / [c naechster/voriger Abschnitt\n\n" + lokDiff, lokWurzel);
        });
        /** @binding Pull Request: die gewaehlte Datei auf den Stand des Ziel-Branchs zuruecksetzen und committen (lokaler Quell-Branch) */
        c.put("prRevertFile", a -> nurPr(() -> {
            if (wahl == 0) {
                ed.meldung = "u: erst eine Datei waehlen (TAB)";
                return;
            }
            Datei d = datei();
            ed.scm.dateiZuruecksetzen(pr, ziel, d.alt, d.neu, null);
        }));
        /** @binding Pull Request: neu laden */
        c.put("prRefresh", a -> neuLaden());
        /** @binding Pull Request: im Browser oeffnen */
        c.put("prBrowser", a -> nurPr(() -> ed.scm.imBrowser(pr)));
    }

    void neuLaden() {
        if (lokal) {
            if (lokNeuLaden != null) lokNeuLaden.run();
        } else if (pr != null) {
            ed.scm.zeigen(pr);
        }
    }

    private void nurPr(Runnable r) {
        if (lokal) ed.meldung = "nur im Pull Request (Leertaste g r r)";
        else r.run();
    }

    /** Tasten fuer die Statuszeile */
    String[][] tastenJetzt() {
        return lokal ? TASTEN_LOKAL : TASTEN;
    }

    private void bewegen(String a) {
        List<Zeile> l = datei().zeilen;
        int seite = Math.max(1, sichtbareZeilen() - 2);
        int n = switch (a) {
            case "page" -> seite;
            case "-page" -> -seite;
            default -> {
                try {
                    yield Integer.parseInt(a);
                } catch (NumberFormatException e) {
                    yield 1;
                }
            }
        };
        zeile = Math.max(0, Math.min(l.size() - 1, zeile + n));
    }

    private void naechste(java.util.function.Predicate<Zeile> p, int r, boolean meldung) {
        List<Zeile> l = datei().zeilen;
        for (int i = zeile; i >= 0 && i < l.size(); i += r) {
            if (p.test(l.get(i)) && (i == 0 || !p.test(l.get(i - 1)) || l.get(i).art == KOMMENTAR)) {
                zeile = i;
                return;
            }
        }
        if (meldung) ed.meldung = "nichts gefunden";
    }

    /** naechster Block, der p erfuellt (ab der Zeile nach dem Cursor), sonst in den naechsten Dateien */
    private void springenNach(java.util.function.Predicate<Zeile> p, int r, String was) {
        int d = wahl, i = zeile;
        // aus dem aktuellen Block heraus
        List<Zeile> l = datei().zeilen;
        while (i >= 0 && i < l.size() && p.test(l.get(i)) && l.get(i).art != KOMMENTAR) i += r;
        if (i >= 0 && i < l.size() && l.get(i).art == KOMMENTAR && p.test(l.get(i))) i += r;
        for (int runde = 0; runde <= dateien.size(); runde++) {
            l = dateien.get(d).zeilen;
            for (; i >= 0 && i < l.size(); i += r) {
                if (!p.test(l.get(i))) continue;
                // bei Aenderungen an den Anfang des Blocks
                if (r < 0 && l.get(i).art != KOMMENTAR) while (i > 0 && p.test(l.get(i - 1)) && l.get(i - 1).art != KOMMENTAR) i--;
                wahl = d;
                zeile = i;
                return;
            }
            d = Math.floorMod(d + r, dateien.size());
            i = r > 0 ? 0 : dateien.get(d).zeilen.size() - 1;
        }
        ed.meldung = "kein " + was;
    }

    /** an eine Codestelle: die Ansicht schliesst (Leertaste g r r, ENTER kommt an dieselbe Stelle zurueck) */
    private void stelle(String pfad, int nr) {
        aktiv = false;
        ed.modus = "normal";
        ed.unterschied.stelleOeffnen(lokal ? lokWurzel : ziel.repo(), pfad, nr);
        ed.meldung = (ed.meldung == null ? "" : ed.meldung + "   ") + (lokal ? "(Leertaste g d: wieder vergleichen)" : "(Leertaste g r r, ENTER: zurueck zum Pull Request)");
    }

    private void oeffnen() {
        Zeile z = aktuelle();
        if (z == null) return;
        Datei d = datei();
        if (z.art == TEXT && z.ziel != null) {
            String[] t = z.ziel.split("\t");
            stelle(t[0], Integer.parseInt(t[1]));
            return;
        }
        if (z.art == KOMMENTAR) {
            Scm.Kommentar k = z.kom;
            String[] s = k.datei() != null ? new String[] {k.datei(), String.valueOf(Math.max(1, k.neu() > 0 ? k.neu() : k.alt()))} : Scm.stelleImText(k.text());
            if (s == null) {
                ed.meldung = "dieser Kommentar nennt keine Codestelle";
                return;
            }
            stelle(s[0], Integer.parseInt(s[1]));
            return;
        }
        if (wahl == 0 || d.pfad() == null) return;
        int n = z.neu > 0 ? z.neu : Math.max(1, naechsteNeue(d, zeile));
        if (d.status.equals("D")) {
            ed.meldung = d.pfad() + " wird geloescht";
            return;
        }
        stelle(d.pfad(), n);
    }

    /** fuer eine entfernte Zeile: die naechste Zeile der neuen Seite */
    private static int naechsteNeue(Datei d, int i) {
        for (int k = i; k < d.zeilen.size(); k++) if (d.zeilen.get(k).neu > 0) return d.zeilen.get(k).neu;
        for (int k = i; k >= 0; k--) if (d.zeilen.get(k).neu > 0) return d.zeilen.get(k).neu;
        return 1;
    }

    private void kommentieren(boolean antwort) {
        Zeile z = aktuelle();
        Datei d = datei();
        if (antwort) {
            if (z == null || z.art != KOMMENTAR) {
                ed.meldung = "C: Cursor auf einen Kommentar setzen";
                return;
            }
            ed.scm.kommentieren(pr, "Antwort an " + z.kom.autor() + ":", null, 0, 0, z.kom, this::neuLaden);
            return;
        }
        if (wahl > 0 && z != null && (z.art == GLEICH || z.aenderung())) {
            boolean neuSeite = z.art != WEG;
            int nr = neuSeite ? z.neu : z.alt;
            ed.scm.kommentieren(pr, "Kommentar zu " + d.pfad() + ":" + nr + (neuSeite ? "" : " (alt)") + ":", neuSeite ? d.neu : d.alt, neuSeite ? nr : 0, neuSeite ? 0 : nr, null,
                    this::neuLaden);
            return;
        }
        ed.scm.kommentieren(pr, "Kommentar zum Pull Request:", null, 0, 0, null, this::neuLaden);
    }

    // ================================================================ Malen
    private int sichtbareZeilen() {
        return Math.max(1, (ed.scr.h - ed.unten() - ed.oben() - 4 * ed.scr.lh) / ed.scr.lh);
    }

    private static final int GRUND_WEG = 0x2A0A12, GRUND_NEU = 0x0A2A14, MARK_WEG = 0x6A1828, MARK_NEU = 0x186A30, LUECKE = 0x050905, GRUND_KOM = 0x1A1606,
            KOM_RAND = 0xFFB000, GRUND_HUNK = 0x0A1A2A, ZEIGER = 0x14301C;

    private int sw, mx, mw, pw, nb, tl, tc;

    /** Hoehe einer Zeile in Bildschirmzeilen */
    private int hoehe(Zeile z) {
        if (z.art == KOMMENTAR) return kommentarZeilen(z.kom, tc - 6).size() + 1;
        if (z.art == TEXT) return umbruch ? Math.max(1, umbrechen(z.links, Math.max(10, tc - z.einzug)).size()) : 1;
        if (z.art == HUNK || z.art == LEER || !umbruch) return 1;
        return Math.max(Math.max(1, (z.links.length() + tl - 1) / Math.max(1, tl)), Math.max(1, (z.rechts.length() + tl - 1) / Math.max(1, tl)));
    }

    /** Kommentar als Bildschirmzeilen: Text umbrochen, Antworten eingerueckt */
    private static List<String[]> kommentarZeilen(Scm.Kommentar k, int breite) {
        List<String[]> r = new ArrayList<>();
        for (String z : k.text().split("\\R")) for (String u : umbrechen(z, breite)) r.add(new String[] {"t", u});
        for (String[] a : k.antworten()) {
            r.add(new String[] {"a", "-> " + a[0] + (a[1].isEmpty() ? "" : "  " + a[1])});
            for (String z : a[2].split("\\R")) for (String u : umbrechen(z, breite - 4)) r.add(new String[] {"at", u});
        }
        return r;
    }

    /** an Leerzeichen umbrechen (lange Woerter hart) */
    static List<String> umbrechen(String s, int breite) {
        List<String> r = new ArrayList<>();
        s = s.replace("\t", "    ");
        if (breite < 4) breite = 4;
        while (s.length() > breite) {
            int b = s.lastIndexOf(' ', breite);
            if (b < breite / 2) b = breite;
            r.add(s.substring(0, b));
            s = s.substring(b).stripLeading();
        }
        r.add(s);
        return r;
    }

    private void faerben(Datei d) {
        if (d.gefaerbt) return;
        d.gefaerbt = true;
        String sp = d.pfad() == null ? null : Doc.spracheVon(d.pfad());
        StringBuilder l = new StringBuilder(), r = new StringBuilder();
        for (Zeile z : d.zeilen) {
            if (z.art == GLEICH || z.art == WEG || z.art == PAAR) {
                z.offL = l.length();
                l.append(z.links).append('\n');
            }
            if (z.art == GLEICH || z.art == NEU || z.art == PAAR) {
                z.offR = r.length();
                r.append(z.rechts).append('\n');
            }
        }
        if (sp == null || l.length() + r.length() > 3_000_000) return;
        d.farbenL = Highlight.faerben(l, sp);
        d.farbenR = Highlight.faerben(r, sp);
    }

    void malen(int y0, int y1) {
        Screen s = ed.scr;
        s.rect(0, y0, s.w, y1 - y0, E_GRUND);
        if (dateien.isEmpty()) return;
        // ---------------------------------------- Kopf: Titel, Branches, Freigaben
        int top = lokal ? lokalKopf(s, y0) : prKopf(s, y0);
        // ---------------------------------------- Dateiliste links
        dateiListe(s, top, y1);
        // ---------------------------------------- Hauptbereich
        hauptbereich(s, top, y1);
    }

    private int lokalKopf(Screen s, int y0) {
        int W = s.w, cw = s.cw, lh = s.lh;
        s.rect(0, y0, W, 2 * lh + 6, E_LEISTE);
        int x = s.text(cw, y0 + 2, "DIFF  ", E_HELL);
        s.text(x, y0 + 2, dateien.size() > 1 ? dateien.get(1).pfad() : lokTitel, E_HELL);
        int plus = 0, minus = 0;
        for (Datei d : dateien) {
            plus += d.plus;
            minus += d.minus;
        }
        x = s.text(cw, y0 + lh + 4, "vorher ", E_MATT);
        x = s.text(x, y0 + lh + 4, lokAlt, E_TEXT);
        x = s.text(x, y0 + lh + 4, "   ->   nachher ", E_MATT);
        x = s.text(x, y0 + lh + 4, lokNeu, E_TEXT);
        x = s.text(x + 3 * cw, y0 + lh + 4, "+" + plus, 0x8CFFB0);
        s.text(x + cw, y0 + lh + 4, "-" + minus, E_FEHLER);
        return y0 + 2 * lh + 8;
    }

    private int prKopf(Screen s, int y0) {
        int W = s.w, cw = s.cw, lh = s.lh;
        int frei = 0, rev = 0;
        for (Object r : Json.list(Json.get(pr, "reviewer"))) {
            rev++;
            if (Boolean.TRUE.equals(Json.get(r, "approved"))) frei++;
        }
        String st = nz(Json.str(Json.get(pr, "status")));
        s.rect(0, y0, W, 2 * lh + 6, E_LEISTE);
        int x = s.text(cw, y0 + 2, "PR #" + Scm.id(pr) + "  ", E_HELL);
        s.clip(0, y0, W - 20 * cw, lh + 4);
        s.text(x, y0 + 2, nz(Json.str(Json.get(pr, "title"))), E_HELL);
        s.noClip();
        int sf = st.equals("OPEN") ? E_TEXT : st.equals("MERGED") ? E_INFO : st.equals("REJECTED") ? E_FEHLER : E_MATT;
        String badge = " " + st + " ";
        s.rect(W - s.textWidth(badge) - cw, y0 + 2, s.textWidth(badge), lh, sf);
        s.text(W - s.textWidth(badge) - cw, y0 + 2, badge, E_GRUND);
        String zeile2 = Json.str(Json.get(pr, "source")) + "  ->  " + Json.str(Json.get(pr, "target")) + "      von " + Scm.person(Json.get(pr, "author")) + "      Freigaben "
                + frei + "/" + rev + "      " + (dateien.size() - 1) + " Dateien";
        s.text(cw, y0 + lh + 4, zeile2, E_TEXT);
        return y0 + 2 * lh + 8;
    }

    private void dateiListe(Screen s, int top, int y1) {
        int cw = s.cw, lh = s.lh;
        sw = Math.max(28 * cw, Math.min(s.w / 5, 46 * cw));
        s.rect(0, top, sw, y1 - top, E_KONSOLE);
        s.text(cw, top + 2, "DATEIEN", E_MATT);
        int fy = top + lh + 4;
        int sichtbar = Math.max(1, (y1 - fy) / lh);
        int fOben = Math.max(0, Math.min(wahl - sichtbar / 2, dateien.size() - sichtbar));
        s.clip(0, fy, sw - 2, y1 - fy);
        for (int i = fOben; i < dateien.size() && i < fOben + sichtbar; i++) {
            Datei d = dateien.get(i);
            int yy = fy + (i - fOben) * lh;
            boolean g = i == wahl;
            if (g) s.rect(0, yy, sw - 2, lh, E_TEXT);
            if (i == 0) {
                s.text(cw, yy, "  Uebersicht" + (d.kommentare > 0 ? "  " + d.kommentare + " Kom." : ""), g ? E_GRUND : E_HELL);
                continue;
            }
            int stf = switch (d.status) {
                case "A" -> 0x8CFFB0;
                case "D" -> E_FEHLER;
                case "R" -> E_INFO;
                default -> E_WARN;
            };
            s.text(cw, yy, d.status, g ? E_GRUND : stf);
            String p = d.pfad();
            String name = p.substring(p.lastIndexOf('/') + 1), ordner = p.contains("/") ? p.substring(0, p.lastIndexOf('/')) : "";
            String zahl = "+" + d.plus + " -" + d.minus + (d.kommentare > 0 ? "  " + d.kommentare + "K" : "");
            int zx = sw - s.textWidth(zahl) - cw;
            s.clip(0, yy, zx - cw, lh);
            int nx = s.text(3 * cw, yy, name, g ? E_GRUND : E_HELL);
            if (!ordner.isEmpty()) s.text(nx + cw, yy, ordner, g ? E_LEISTE : E_MATT);
            s.clip(0, fy, sw - 2, y1 - fy);
            s.text(zx, yy, zahl, g ? E_GRUND : d.kommentare > 0 ? E_WARN : E_MATT);
        }
        s.noClip();
        s.rect(sw - 2, top, 2, y1 - top, E_MATT);
    }

    private void hauptbereich(Screen s, int top, int y1) {
        int W = s.w, cw = s.cw, lh = s.lh;
        mx = sw + cw;
        mw = W - mx - cw;
        Datei d = datei();
        faerben(d);
        int nmax = 1;
        for (Zeile z : d.zeilen) nmax = Math.max(nmax, Math.max(z.alt, z.neu));
        nb = String.valueOf(nmax).length() + 1;
        pw = (mw - cw) / 2;
        tl = Math.max(8, pw / cw - nb - 2);
        tc = Math.max(20, mw / cw - 2);
        int ky = top;
        if (wahl > 0) {
            // Dateikopf und Spaltenkoepfe
            s.rect(mx - cw, ky, mw + 2 * cw, lh + 4, E_LEISTE);
            String kopf = d.status.equals("R") ? d.alt + "  ->  " + d.neu : d.pfad();
            s.text(mx, ky + 2, kopf, E_HELL);
            String z = "+" + d.plus + "  -" + d.minus;
            s.text(mx + mw - s.textWidth(z), ky + 2, z, E_MATT);
            ky += lh + 4;
            String la = lokal ? lokAlt : Json.str(Json.get(pr, "target")), ln = lokal ? lokNeu : Json.str(Json.get(pr, "source"));
            s.text(mx, ky, d.status.equals("A") ? "(neu)" : "vorher (" + la + ")", E_MATT);
            s.text(mx + pw + cw, ky, d.status.equals("D") ? "(geloescht)" : "nachher (" + ln + ")", E_MATT);
            ky += lh;
        }
        int hy = ky + 2;
        int platz = Math.max(1, (y1 - hy) / lh);
        // Cursor sichtbar halten
        List<Zeile> l = d.zeilen;
        zeile = Math.max(0, Math.min(zeile, l.size() - 1));
        if (zeile < oben) oben = zeile;
        while (oben < zeile) {
            int summe = 0;
            for (int i = oben; i <= zeile; i++) summe += hoehe(l.get(i));
            if (summe <= platz) break;
            oben++;
        }
        s.clip(mx - cw, hy, mw + 2 * cw, y1 - hy);
        int yy = hy;
        if (l.isEmpty()) s.text(mx, yy, "(keine Aenderungen)", E_MATT);
        for (int i = oben; i < l.size() && yy < y1; i++) {
            Zeile z = l.get(i);
            int h = hoehe(z);
            boolean g = i == zeile;
            zeileMalen(s, d, z, yy, h, g);
            yy += h * lh;
        }
        s.noClip();
        if (wahl > 0) s.rect(mx + pw, hy, 1, Math.min(yy, y1) - hy, E_MATT);
    }

    private void zeileMalen(Screen s, Datei d, Zeile z, int y, int h, boolean g) {
        int cw = s.cw, lh = s.lh;
        int lx = mx, rx = mx + pw + cw;
        switch (z.art) {
            case HUNK -> {
                s.rect(mx - cw / 2, y, mw + cw, lh, g ? ZEIGER : GRUND_HUNK);
                s.text(mx + cw, y, "...  " + z.links, E_INFO);
            }
            case KOMMENTAR -> {
                Scm.Kommentar k = z.kom;
                int bx = mx + 2 * cw, bw = mw - 3 * cw;
                s.rect(bx, y + 2, bw, h * lh - 4, GRUND_KOM);
                s.rect(bx, y + 2, 4, h * lh - 4, k.typ().equals("TASK_TODO") ? E_FEHLER : KOM_RAND);
                if (g) s.frame(bx, y + 2, bw, h * lh - 4, E_HELL);
                boolean ts = k.autor().toLowerCase().contains("teamscale");
                String kopf = k.autor() + (k.datum().isEmpty() ? "" : "   " + k.datum()) + (k.typ().equals("TASK_TODO") ? "   [Aufgabe offen]" : k.typ().equals("TASK_DONE") ? "   [Aufgabe erledigt]" : "")
                        + (k.veraltet() ? "   [veraltet]" : "") + (k.datei() != null && wahl == 0 ? "   " + k.datei() + ":" + (k.neu() > 0 ? k.neu() : k.alt()) : "");
                s.text(bx + 2 * cw, y + 2, kopf, ts ? E_INFO : E_WARN);
                int ky = y + 2 + lh;
                for (String[] t : kommentarZeilen(k, tc - 6)) {
                    int f = t[0].equals("a") ? E_WARN : E_TEXT;
                    s.text(bx + (t[0].startsWith("a") ? 5 : 3) * cw, ky, t[1], f);
                    ky += lh;
                }
            }
            case TEXT -> {
                if (g) s.rect(mx - cw / 2, y, mw + cw, h * lh, ZEIGER);
                List<String> u = umbruch ? umbrechen(z.links, Math.max(10, tc - z.einzug)) : List.of(z.links);
                for (int k = 0; k < u.size(); k++) s.text(mx + z.einzug * cw, y + k * lh, u.get(k), z.farbe);
            }
            default -> {
                // Code: links alt, rechts neu
                int gl = z.art == WEG || z.art == PAAR ? GRUND_WEG : z.art == NEU ? LUECKE : 0;
                int gr = z.art == NEU || z.art == PAAR ? GRUND_NEU : z.art == WEG ? LUECKE : 0;
                if (gl != 0) s.rect(lx, y, pw, h * lh, gl);
                if (gr != 0) s.rect(rx, y, pw, h * lh, gr);
                if (g) {
                    s.rect(mx - cw, y, cw / 3, h * lh, E_HELL);
                    s.frame(lx, y, mw, h * lh, 0x2F6A3F);
                }
                seite(s, z.alt, z.art == NEU ? null : z.links, z.markL, d.farbenL, z.offL, lx, y, z.art == WEG || z.art == PAAR ? '-' : ' ', g);
                seite(s, z.neu, z.art == WEG ? null : z.rechts, z.markR, d.farbenR, z.offR, rx, y, z.art == NEU || z.art == PAAR ? '+' : ' ', g);
            }
        }
    }

    private void seite(Screen s, int nr, String text, boolean[] mark, byte[] farben, int off, int x, int y, char zeichen, boolean g) {
        if (text == null) return;
        int cw = s.cw, lh = s.lh;
        String n = nr > 0 ? String.valueOf(nr) : "";
        s.text(x + (nb - n.length()) * cw - cw / 2, y, n, g ? E_HELL : E_MATT);
        if (zeichen != ' ') s.text(x + nb * cw, y, String.valueOf(zeichen), zeichen == '-' ? E_FEHLER : 0x8CFFB0);
        int tx = x + (nb + 1) * cw + cw / 2;
        int spalte = 0, reihe = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            int breite = ch == '\t' ? 4 - spalte % 4 : 1;
            if (spalte + breite > tl) {
                if (!umbruch) {
                    s.text(tx + (tl - 1) * cw, y, ">", E_MATT);
                    return;
                }
                spalte = 0;
                reihe++;
                // Fortsetzung einer umbrochenen Zeile: Zeichen in der Nummernspalte
                s.text(x + (nb - 1) * cw - cw / 2, y + reihe * lh, "..", E_MATT);
            }
            int px = tx + spalte * cw, py = y + reihe * lh;
            if (mark != null && i < mark.length && mark[i]) s.rect(px, py, breite * cw, lh, zeichen == '-' ? MARK_WEG : MARK_NEU);
            int f = farben != null && off >= 0 && off + i < farben.length ? Editor.FARBEN[farben[off + i]] : E_TEXT;
            if (ch != '\t' && ch != ' ') s.glyph(px, py, ch, f);
            spalte += breite;
        }
    }

    /** Statuszeile: Tasten mit Funktion */
    static String[][] tasten() {
        return TASTEN;
    }

    /** Schluessel fuer Tests: Dateien und Zeilen als Text */
    List<String> alsText() {
        List<String> r = new ArrayList<>();
        Map<Integer, String> arten = new HashMap<>(Map.of(HUNK, "@@", GLEICH, "  ", WEG, "- ", NEU, "+ ", PAAR, "~ ", KOMMENTAR, "K ", TEXT, "T ", LEER, "  "));
        for (int i = 0; i < dateien.size(); i++) {
            Datei d = dateien.get(i);
            r.add("== " + (i == 0 ? "Uebersicht" : d.status + " " + d.pfad() + " +" + d.plus + " -" + d.minus));
            for (Zeile z : d.zeilen) {
                r.add(arten.get(z.art) + String.format("%3s %3s ", z.alt == 0 ? "" : z.alt, z.neu == 0 ? "" : z.neu) + (z.art == KOMMENTAR ? z.kom.autor() + ": " + z.kom.text().replace("\n", " / ")
                        : z.links + (z.art == PAAR ? "  |  " + z.rechts : z.art == NEU ? z.rechts : "")));
            }
        }
        return r;
    }

    /** Pfad der gewaehlten Datei (null in der Uebersicht) */
    String dateiPfad() {
        return wahl == 0 || dateien.isEmpty() ? null : datei().pfad();
    }

    String gewaehltText() {
        Zeile z = aktuelle();
        return z == null ? "" : wahl + ":" + zeile + " " + (z.art == KOMMENTAR ? "K " + z.kom.autor() : z.links + " | " + z.rechts);
    }
}
