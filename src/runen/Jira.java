package runen;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.SwingUtilities;

/**
 * Jira ueber die REST-API (v2 und Agile 1.0): jira.server, jira.user, jira.password (oder jira.token), jira.project
 * in config.txt. Meine Tickets, das Board des aktiven Sprints und einzelne Tickets erscheinen als schreibgeschuetzte
 * Puffer; ENTER auf einem Ticket-Schluessel (PROJ-12) oeffnet das Ticket.
 */
final class Jira {
    private final Editor ed;
    private HttpClient httpClient;
    private String httpStand;

    /** Client fuer diesen Dienst: ohne jira.proxy wie bisher, mit Eintrag ueber den Proxy (neu gebaut, wenn er sich aendert) */
    private synchronized HttpClient http() {
        String st = Netz.stand(ed.kf, "jira", false);
        if (httpClient == null || !st.equals(httpStand)) {
            httpClient = Netz.client(ed.kf, "jira", false, Duration.ofSeconds(15), HttpClient.Redirect.NORMAL);
            httpStand = st;
        }
        return httpClient;
    }
    static final Pattern SCHLUESSEL = Pattern.compile("\\b[A-Z][A-Z0-9_]+-\\d+\\b");

    /** Board-Puffer: welche Spalte des Rasters welches Ticket zeigt */
    private static final class Raster {
        int oben, spaltenBreite, links;
        String[][] tickets = new String[0][];
    }

    private final Map<Doc, Raster> raster = new HashMap<>();

    Jira(Editor ed) {
        this.ed = ed;
    }

    boolean eingerichtet() {
        return !ed.kf.get("jira.server", "").isBlank();
    }

    private String server() {
        String s = ed.kf.get("jira.server", "").strip().replaceAll("/+$", "");
        return s.isEmpty() || s.startsWith("http") ? s : "https://" + s;
    }

    private String projekt() {
        return ed.kf.get("jira.project", "").strip();
    }

    record Antwort(int code, String text) {}

    Antwort anfrage(String methode, String pfad, Object body) {
        String url = server() + pfad;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(40)).header("Accept", "application/json");
            String tk = ed.kf.get("jira.token", "").strip();
            if (!tk.isEmpty()) {
                b.header("Authorization", "Bearer " + tk);
            } else {
                String u = ed.kf.get("jira.user", ""), pw = ed.kf.get("jira.password", "");
                b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((u + ":" + pw).getBytes(StandardCharsets.UTF_8)));
            }
            if (body != null) b.header("Content-Type", "application/json").method(methode, HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
            else b.method(methode, HttpRequest.BodyPublishers.noBody());
            HttpResponse<String> r = http().send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Antwort(r.statusCode(), r.body());
        } catch (IOException | IllegalArgumentException e) {
            return new Antwort(-1, e.getMessage() == null ? e.toString() : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Antwort(-1, "unterbrochen");
        }
    }

    static String fehlerText(Antwort a) {
        if (a.code() == -1) return a.text();
        String m = null;
        try {
            Object j = Json.parse(a.text());
            List<Object> l = Json.list(Json.get(j, "errorMessages"));
            if (!l.isEmpty()) m = String.valueOf(l.get(0));
            if (m == null) m = Json.str(Json.get(j, "message"));
        } catch (RuntimeException e) {
            // kein JSON
        }
        String t = m != null ? m : a.text().strip().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");
        if (t.length() > 160) t = t.substring(0, 160) + "...";
        String hinweis = a.code() == 401 ? " (Anmeldung: jira.user/jira.password oder jira.token pruefen)" : a.code() == 403 ? " (keine Berechtigung)" : "";
        return "HTTP " + a.code() + (t.isEmpty() ? "" : ": " + t) + hinweis;
    }

    private boolean bereit() {
        if (eingerichtet()) return true;
        ed.fehler("Jira ist nicht eingerichtet: jira.server, jira.user, jira.password (oder jira.token), jira.project in config.txt");
        return false;
    }

    private static void hintergrund(Runnable r) {
        Thread t = new Thread(r, "jira");
        t.setDaemon(true);
        t.start();
    }

    // ================================================================ Puffer
    /** schreibgeschuetzter Puffer mit Titel (vorhandener wird wiederverwendet) */
    private Doc puffer(String titel, String art) {
        for (int i = 0; i < ed.tabs.size(); i++) {
            Doc d = ed.tabs.get(i);
            if (titel.equals(d.titel) && d.jira != null) {
                ed.aktiv = i;
                return d;
            }
        }
        Doc d = new Doc();
        d.titel = titel;
        d.art = "jira";
        d.jira = art;
        d.nurLesen = true;
        Doc alt = ed.d();
        if (ed.tabs.size() == 1 && alt.pfad == null && alt.term == null && alt.chat == null && !alt.geaendert && alt.t.length() == 0) ed.tabs.set(0, d);
        else ed.tabs.add(d);
        ed.aktiv = ed.tabs.indexOf(d);
        ed.modus = ed.km.modeDa("nurlesen") ? "nurlesen" : "normal";
        return d;
    }

    private static void fuellen(Doc d, String text, boolean cursorBehalten) {
        int c = d.cursor;
        d.nurLesen = false;
        d.setText(text);
        d.nurLesen = true;
        d.geaendert = false;
        d.cursor = cursorBehalten ? Math.min(c, Math.max(0, d.t.length() - 1)) : 0;
        if (!cursorBehalten) d.oben = 0;
    }

    /** Ladeanzeige, Abfrage im Hintergrund, dann Text in den Puffer */
    private void laden(Doc d, String warten, java.util.function.Supplier<String> abfrage, boolean behalten) {
        if (d.t.length() == 0) fuellen(d, warten + "\n", false);
        ed.meldung = warten;
        hintergrund(() -> {
            String t;
            try {
                t = abfrage.get();
            } catch (RuntimeException e) {
                t = "Fehler: " + e + "\n";
                ed.protokoll("Jira", e);
            }
            String text = t;
            SwingUtilities.invokeLater(() -> {
                if (!ed.tabs.contains(d)) return;
                fuellen(d, text, behalten);
                if (ed.meldung != null && ed.meldung.equals(warten)) ed.meldung = text.startsWith("Fehler") ? text.strip() : d.titel + ": ENTER auf einem Schluessel oeffnet das Ticket, Leertaste j r laedt neu";
                ed.neuMalen.run();
            });
        });
    }

    // ================================================================ Meine Tickets
    void meine(boolean behalten) {
        if (!bereit()) return;
        Doc d = puffer("Jira: meine Tickets", "meine");
        raster.remove(d);
        String jql = ed.kf.get("jira.mine.jql", "assignee = currentUser() AND resolution = Unresolved ORDER BY priority DESC, updated DESC");
        laden(d, "Jira: meine Tickets werden geladen ...", () -> meineText(jql), behalten);
    }

    /** Suche ueber JQL; Jira Cloud ohne /search faellt auf /search/jql zurueck */
    private Antwort suche(String jql, String felder) {
        String q = "?jql=" + URLEncoder.encode(jql, StandardCharsets.UTF_8) + "&maxResults=200&fields=" + felder;
        Antwort a = anfrage("GET", "/rest/api/2/search" + q, null);
        if (a.code() == 410 || a.code() == 404) a = anfrage("GET", "/rest/api/2/search/jql" + q, null);
        return a;
    }

    String meineText(String jql) {
        Antwort a = suche(jql, "summary,status,priority,issuetype,updated");
        if (a.code() != 200) return "Fehler: " + fehlerText(a) + "\n";
        List<Object> issues = Json.list(Json.get(Json.parse(a.text()), "issues"));
        // nach Status gruppiert, Reihenfolge wie von Jira geliefert
        Map<String, List<Object>> nach = new LinkedHashMap<>();
        for (Object i : issues) nach.computeIfAbsent(feld(i, "status", "name"), k -> new ArrayList<>()).add(i);
        StringBuilder b = new StringBuilder("# Jira: mir zugewiesen (" + issues.size() + ")   ENTER oeffnet, Leertaste j r laedt neu, q schliesst\n");
        int breite = 0;
        for (Object i : issues) breite = Math.max(breite, Json.str(Json.get(i, "key")).length());
        for (Map.Entry<String, List<Object>> e : nach.entrySet()) {
            b.append("\n").append(e.getKey()).append(" (").append(e.getValue().size()).append(")\n").append("-".repeat(e.getKey().length() + 4)).append('\n');
            for (Object i : e.getValue()) {
                String k = Json.str(Json.get(i, "key"));
                b.append("  ").append(k).append(" ".repeat(breite - k.length() + 2)).append(String.format("%-8s ", kurz(feld(i, "issuetype", "name"), 8)))
                        .append(String.format("%-8s ", kurz(feld(i, "priority", "name"), 8))).append(feld(i, "summary")).append('\n');
            }
        }
        if (issues.isEmpty()) b.append("\n(nichts zugewiesen)\n");
        return b.toString();
    }

    private static String feld(Object issue, String... pfad) {
        String[] p = new String[pfad.length + 1];
        p[0] = "fields";
        System.arraycopy(pfad, 0, p, 1, pfad.length);
        Object o = Json.get(issue, p);
        return o == null ? "" : String.valueOf(o);
    }

    private static String kurz(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "~";
    }

    // ================================================================ Ticket
    void ticket(String key) {
        if (!bereit()) return;
        String k = key.strip().toUpperCase();
        if (!k.matches("[A-Z][A-Z0-9_]+-\\d+")) {
            if (k.matches("\\d+") && !projekt().isEmpty()) k = projekt().toUpperCase() + "-" + k;
            else {
                ed.fehler("kein Ticket-Schluessel: " + key + " (z. B. PROJ-12)");
                return;
            }
        }
        String schl = k;
        ed.springMerken();
        Doc d = puffer(schl, "ticket:" + schl);
        raster.remove(d);
        laden(d, "Jira: " + schl + " wird geladen ...", () -> ticketText(schl), false);
    }

    private static final DateTimeFormatter ZEIT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    static String zeit(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        try {
            return OffsetDateTime.parse(iso.replaceFirst("([+-]\\d\\d)(\\d\\d)$", "$1:$2")).format(ZEIT);
        } catch (RuntimeException e) {
            return iso.length() > 16 ? iso.substring(0, 16).replace('T', ' ') : iso;
        }
    }

    String ticketText(String key) {
        Antwort a = anfrage("GET", "/rest/api/2/issue/" + key
                // alle Felder mit Namen: Akzeptanzkriterien stehen meist in einem eigenen Feld (customfield_...)
                + "?fields=*all&expand=names",
                null);
        if (a.code() != 200) return "Fehler: " + key + ": " + fehlerText(a) + "\n";
        Object i = Json.parse(a.text());
        StringBuilder b = new StringBuilder();
        String titel = key + "  " + feld(i, "summary");
        b.append("# ").append(titel).append('\n').append("=".repeat(Math.min(100, titel.length() + 2))).append("\n\n");
        b.append("Typ: ").append(feld(i, "issuetype", "name")).append("   Status: ").append(feld(i, "status", "name")).append("   Prioritaet: ")
                .append(feld(i, "priority", "name")).append('\n');
        b.append("Bearbeiter: ").append(name(Json.get(i, "fields", "assignee"))).append("   Melder: ").append(name(Json.get(i, "fields", "reporter"))).append('\n');
        b.append("Erstellt: ").append(zeit(feld(i, "created"))).append("   Aktualisiert: ").append(zeit(feld(i, "updated")))
                .append(feld(i, "duedate").isEmpty() ? "" : "   Faellig: " + feld(i, "duedate")).append('\n');
        String labels = namen(Json.get(i, "fields", "labels"), null), komp = namen(Json.get(i, "fields", "components"), "name"),
                vers = namen(Json.get(i, "fields", "fixVersions"), "name");
        if (!labels.isEmpty()) b.append("Labels: ").append(labels).append('\n');
        if (!komp.isEmpty()) b.append("Komponenten: ").append(komp).append('\n');
        if (!vers.isEmpty()) b.append("Versionen: ").append(vers).append('\n');
        Object parent = Json.get(i, "fields", "parent");
        if (parent != null) b.append("Uebergeordnet: ").append(Json.str(Json.get(parent, "key"))).append("  ").append(feld(parent, "summary")).append('\n');
        b.append("Link: ").append(server()).append("/browse/").append(key).append('\n');
        abschnitt(b, "Beschreibung");
        String desc = feld(i, "description");
        b.append(desc.isBlank() ? "(keine Beschreibung)" : desc.replace("\r", "").strip()).append('\n');
        // Akzeptanzkriterien: Felder, deren Name danach klingt, oder jira.acceptance (IDs oder Namen, mit Komma)
        Map<String, Object> namen = new LinkedHashMap<>();
        if (Json.get(i, "names") instanceof Map<?, ?> nm) for (Map.Entry<?, ?> e : nm.entrySet()) namen.put(String.valueOf(e.getKey()), e.getValue());
        for (Map.Entry<String, Object> e : namen.entrySet()) {
            if (!akzeptanzFeld(e.getKey(), String.valueOf(e.getValue()))) continue;
            List<String> punkte = kriterien(Json.get(i, "fields", e.getKey()));
            if (punkte.isEmpty()) continue;
            abschnitt(b, String.valueOf(e.getValue()) + " (" + punkte.size() + ")");
            for (String z : punkte) b.append(z).append('\n');
        }
        List<Object> subs = Json.list(Json.get(i, "fields", "subtasks"));
        if (!subs.isEmpty()) {
            abschnitt(b, "Unteraufgaben (" + subs.size() + ")");
            for (Object s : subs) b.append("- ").append(Json.str(Json.get(s, "key"))).append("  [").append(feld(s, "status", "name")).append("]  ").append(feld(s, "summary")).append('\n');
        }
        List<Object> links = Json.list(Json.get(i, "fields", "issuelinks"));
        if (!links.isEmpty()) {
            abschnitt(b, "Verknuepfungen (" + links.size() + ")");
            for (Object l : links) {
                Object aus = Json.get(l, "outwardIssue"), ein = Json.get(l, "inwardIssue");
                Object z = aus != null ? aus : ein;
                String art = Json.str(Json.get(l, "type", aus != null ? "outward" : "inward"));
                if (z != null) b.append("- ").append(art).append(" ").append(Json.str(Json.get(z, "key"))).append("  [").append(feld(z, "status", "name")).append("]  ")
                        .append(feld(z, "summary")).append('\n');
            }
        }
        List<Object> kom = Json.list(Json.get(i, "fields", "comment", "comments"));
        abschnitt(b, "Kommentare (" + kom.size() + ")");
        for (Object c : kom) {
            b.append("--- ").append(name(Json.get(c, "author"))).append(", ").append(zeit(Json.str(Json.get(c, "created")))).append(" ---\n");
            b.append(String.valueOf(Json.get(c, "body")).replace("\r", "").strip()).append("\n\n");
        }
        if (kom.isEmpty()) b.append("(keine Kommentare)\n");
        return b.toString();
    }

    private static final Pattern AKZEPTANZ = Pattern.compile("(?i)akzeptanz|acceptance|abnahme|definition of done|checklist|checkliste");

    /** ist das Feld (ID, Name) eines mit Akzeptanzkriterien? */
    boolean akzeptanzFeld(String id, String name) {
        String fest = ed.kf.get("jira.acceptance", "").strip();
        if (!fest.isEmpty()) {
            for (String f : fest.split(",")) if (f.strip().equalsIgnoreCase(id) || f.strip().equalsIgnoreCase(name)) return true;
            return false;
        }
        return id.startsWith("customfield_") && AKZEPTANZ.matcher(name).find();
    }

    /**
     * Akzeptanzkriterien als Zeilen: Text (Wiki-Liste mit * oder #, auch nummeriert) wird zu "- Punkt", Checklisten
     * (Listen von Objekten mit name/text/value und checked/isChecked/status) zu "[x] Punkt" bzw. "[ ] Punkt"
     */
    static List<String> kriterien(Object wert) {
        List<String> r = new ArrayList<>();
        if (wert == null) return r;
        if (wert instanceof String t) {
            for (String z : t.replace("\r", "").split("\n")) {
                String s = z.stripTrailing();
                if (s.isBlank()) continue;
                Matcher m = Pattern.compile("^\\s*([*#-]+)\\s+(.*)$").matcher(s);
                if (m.find()) r.add("  ".repeat(Math.max(0, m.group(1).length() - 1)) + "- " + m.group(2).strip());
                else r.add(s.strip());
            }
            return r;
        }
        if (wert instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?>) {
                    String text = null;
                    for (String k : new String[] {"name", "text", "value", "summary", "title"}) if (text == null && Json.get(o, k) != null) text = String.valueOf(Json.get(o, k));
                    if (text == null) text = String.valueOf(o);
                    Object c = Json.get(o, "checked");
                    if (c == null) c = Json.get(o, "isChecked");
                    if (c == null) c = Json.get(o, "done");
                    String st = Json.str(Json.get(o, "status", "name"));
                    if (st == null) st = Json.str(Json.get(o, "status"));
                    boolean erledigt = Boolean.TRUE.equals(c) || st != null && st.matches("(?i)done|erledigt|checked|ok");
                    boolean offen = Boolean.FALSE.equals(c) || st != null && !erledigt;
                    r.add((erledigt ? "[x] " : offen ? "[ ] " : "- ") + text.strip());
                } else if (o != null) {
                    r.add("- " + o);
                }
            }
            return r;
        }
        if (wert instanceof Map<?, ?>) {
            Object v = Json.get(wert, "value");
            if (v != null) return kriterien(v);
        }
        r.add(String.valueOf(wert));
        return r;
    }

    private static void abschnitt(StringBuilder b, String t) {
        b.append('\n').append(t).append('\n').append("-".repeat(t.length())).append('\n');
    }

    private static String name(Object person) {
        if (person == null) return "nicht zugewiesen";
        String n = Json.str(Json.get(person, "displayName"));
        return n != null ? n : String.valueOf(Json.get(person, "name"));
    }

    private static String namen(Object liste, String feld) {
        List<String> r = new ArrayList<>();
        for (Object o : Json.list(liste)) r.add(feld == null ? String.valueOf(o) : String.valueOf(Json.get(o, feld)));
        return String.join(", ", r);
    }

    // ================================================================ Sprint-Board
    void sprint(boolean behalten) {
        if (!bereit()) return;
        if (projekt().isEmpty() && ed.kf.get("jira.board", "").isBlank()) {
            ed.fehler("jira.project (oder jira.board) fehlt in config.txt");
            return;
        }
        Doc d = puffer("Jira: Sprint", "sprint");
        int breite = Math.max(60, ed.scr.w / Math.max(1, ed.scr.cw) - ed.rand(d) - 2);
        Raster r = new Raster();
        laden(d, "Jira: Sprint wird geladen ...", () -> {
            String t = boardText(breite, r);
            SwingUtilities.invokeLater(() -> raster.put(d, r));
            return t;
        }, behalten);
    }

    /** Board des aktiven Sprints als Text mit Spalten (wie das Jira-Board); r merkt sich die Tickets im Raster */
    String boardText(int breite, Raster r) {
        String board = ed.kf.get("jira.board", "").strip();
        if (board.isEmpty()) {
            Antwort b = anfrage("GET", "/rest/agile/1.0/board?projectKeyOrId=" + URLEncoder.encode(projekt(), StandardCharsets.UTF_8), null);
            if (b.code() != 200) return "Fehler: Boards von " + projekt() + ": " + fehlerText(b) + "\n";
            List<Object> l = Json.list(Json.get(Json.parse(b.text()), "values"));
            Object wahl = null;
            for (Object x : l) if ("scrum".equals(Json.str(Json.get(x, "type")))) {
                wahl = x;
                break;
            }
            if (wahl == null && !l.isEmpty()) wahl = l.get(0);
            if (wahl == null) return "Fehler: kein Board fuer das Projekt " + projekt() + " (jira.board = ID setzt eines)\n";
            board = String.valueOf(Mattermost.lang(Json.get(wahl, "id")));
        }
        Antwort s = anfrage("GET", "/rest/agile/1.0/board/" + board + "/sprint?state=active", null);
        if (s.code() != 200) return "Fehler: Sprints von Board " + board + ": " + fehlerText(s) + "\n";
        List<Object> sprints = Json.list(Json.get(Json.parse(s.text()), "values"));
        if (sprints.isEmpty()) return "# Kein aktiver Sprint auf Board " + board + "\n";
        Object sp = sprints.get(0);
        long sid = Mattermost.lang(Json.get(sp, "id"));
        // Spalten des Boards mit ihren Status
        Antwort k = anfrage("GET", "/rest/agile/1.0/board/" + board + "/configuration", null);
        List<String> spalten = new ArrayList<>();
        Map<String, Integer> statusSpalte = new HashMap<>();
        if (k.code() == 200) {
            for (Object c : Json.list(Json.get(Json.parse(k.text()), "columnConfig", "columns"))) {
                List<Object> st = Json.list(Json.get(c, "statuses"));
                if (st.isEmpty()) continue;
                spalten.add(Json.str(Json.get(c, "name")));
                for (Object x : st) statusSpalte.put(String.valueOf(Json.get(x, "id")), spalten.size() - 1);
            }
        }
        // Tickets des Sprints (seitenweise)
        List<Object> issues = new ArrayList<>();
        for (int start = 0; start < 2000;) {
            Antwort a = anfrage("GET", "/rest/agile/1.0/board/" + board + "/sprint/" + sid + "/issue?startAt=" + start + "&maxResults=200&fields=summary,status,assignee,issuetype,priority",
                    null);
            if (a.code() != 200) return "Fehler: Tickets des Sprints: " + fehlerText(a) + "\n";
            Object j = Json.parse(a.text());
            List<Object> l = Json.list(Json.get(j, "issues"));
            issues.addAll(l);
            start += l.size();
            if (l.isEmpty() || start >= Mattermost.lang(Json.get(j, "total"))) break;
        }
        // ohne Board-Konfiguration: Spalten nach Status-Kategorie
        if (spalten.isEmpty()) {
            spalten.addAll(List.of("Offen", "In Arbeit", "Erledigt"));
            for (Object i : issues) {
                String kat = feld(i, "status", "statusCategory", "key");
                statusSpalte.put(feld(i, "status", "id"), kat.equals("done") ? 2 : kat.equals("indeterminate") ? 1 : 0);
            }
        }
        List<List<Object>> inSpalte = new ArrayList<>();
        for (int c = 0; c < spalten.size(); c++) inSpalte.add(new ArrayList<>());
        for (Object i : issues) {
            Integer c = statusSpalte.get(feld(i, "status", "id"));
            if (c != null) inSpalte.get(c).add(i);
        }
        int erledigt = inSpalte.get(spalten.size() - 1).size(), gesamt = 0;
        for (List<Object> l : inSpalte) gesamt += l.size();

        StringBuilder b = new StringBuilder();
        b.append("# Sprint: ").append(Json.str(Json.get(sp, "name"))).append("   (Board ").append(board).append(sprints.size() > 1 ? ", " + sprints.size() + " aktive Sprints" : "")
                .append(")   ENTER oeffnet ein Ticket, Leertaste j r laedt neu\n");
        String von = Json.str(Json.get(sp, "startDate")), bis = Json.str(Json.get(sp, "endDate"));
        if (von != null && bis != null) {
            String rest = "";
            try {
                LocalDate ende = OffsetDateTime.parse(bis.replaceFirst("([+-]\\d\\d)(\\d\\d)$", "$1:$2")).toLocalDate();
                long tage = ChronoUnit.DAYS.between(LocalDate.now(), ende);
                rest = tage >= 0 ? ", noch " + tage + (tage == 1 ? " Tag" : " Tage") : ", seit " + (-tage) + " Tagen vorbei";
            } catch (RuntimeException e) {
                // ohne Resttage
            }
            b.append(zeit(von).substring(0, 10)).append(" bis ").append(zeit(bis).substring(0, 10)).append(rest).append('\n');
        }
        String ziel = Json.str(Json.get(sp, "goal"));
        if (ziel != null && !ziel.isBlank()) b.append("Ziel: ").append(ziel.strip().replace('\n', ' ')).append('\n');
        int balken = 30, voll = gesamt == 0 ? 0 : erledigt * balken / gesamt;
        b.append("[").append("█".repeat(voll)).append("░".repeat(balken - voll)).append("] ").append(erledigt).append("/").append(gesamt).append(" erledigt")
                .append(gesamt == 0 ? "" : " (" + erledigt * 100 / gesamt + " %)").append("\n\n");

        int n = spalten.size();
        int w = Math.max(18, (breite - 1) / n - 1);
        r.spaltenBreite = w;
        r.links = 0;
        String[] oben = {"┌", "┬", "┐"}, mitte = {"├", "┼", "┤"}, unten = {"└", "┴", "┘"};
        b.append(linie(oben, n, w));
        StringBuilder kopf = new StringBuilder("│");
        for (int c = 0; c < n; c++) kopf.append(zelle(" " + spalten.get(c) + " (" + inSpalte.get(c).size() + ")", w)).append("│");
        b.append(kopf).append('\n').append(linie(mitte, n, w));
        // Karten: erste Zeile Schluessel und Bearbeiter, dann die Zusammenfassung umbrochen (hoechstens 3 Zeilen)
        List<List<String>> zeilen = new ArrayList<>(), schl = new ArrayList<>();
        int hoehe = 0;
        for (int c = 0; c < n; c++) {
            List<String> z = new ArrayList<>(), ks = new ArrayList<>();
            for (Object i : inSpalte.get(c)) {
                String key = Json.str(Json.get(i, "key"));
                Object as = Json.get(i, "fields", "assignee");
                String wer = as == null ? "-" : vorname(name(as));
                z.add(" " + key + " · " + wer);
                ks.add(key);
                for (String u : umbrechen(feld(i, "summary"), w - 2, 3)) {
                    z.add("  " + u);
                    ks.add(key);
                }
                z.add("");
                ks.add(null);
            }
            zeilen.add(z);
            schl.add(ks);
            hoehe = Math.max(hoehe, z.size());
        }
        r.oben = (int) b.chars().filter(ch -> ch == '\n').count();
        r.tickets = new String[hoehe][n];
        for (int y = 0; y < hoehe; y++) {
            StringBuilder z = new StringBuilder("│");
            for (int c = 0; c < n; c++) {
                List<String> l = zeilen.get(c);
                z.append(zelle(y < l.size() ? l.get(y) : "", w)).append("│");
                r.tickets[y][c] = y < l.size() ? schl.get(c).get(y) : null;
            }
            b.append(z).append('\n');
        }
        b.append(linie(unten, n, w));
        // Zusammenfassung je Bearbeiter
        Map<String, int[]> wer = new LinkedHashMap<>();
        for (int c = 0; c < n; c++) for (Object i : inSpalte.get(c)) {
            Object as = Json.get(i, "fields", "assignee");
            int[] x = wer.computeIfAbsent(as == null ? "nicht zugewiesen" : name(as), q -> new int[2]);
            x[0]++;
            if (c == n - 1) x[1]++;
        }
        if (!wer.isEmpty()) {
            b.append("\nNach Bearbeiter\n---------------\n");
            for (Map.Entry<String, int[]> e : wer.entrySet()) b.append("  ").append(e.getKey()).append(": ").append(e.getValue()[1]).append("/").append(e.getValue()[0]).append(" erledigt\n");
        }
        return b.toString();
    }

    private static String vorname(String n) {
        int i = n.indexOf(' ');
        return i > 0 ? n.substring(0, i) : n;
    }

    private static String linie(String[] t, int n, int w) {
        StringBuilder b = new StringBuilder(t[0]);
        for (int c = 0; c < n; c++) b.append("─".repeat(w)).append(c < n - 1 ? t[1] : t[2]);
        return b.append('\n').toString();
    }

    private static String zelle(String s, int w) {
        if (s.length() > w) return s.substring(0, w - 1) + "~";
        return s + " ".repeat(w - s.length());
    }

    static List<String> umbrechen(String s, int w, int max) {
        List<String> r = new ArrayList<>();
        StringBuilder z = new StringBuilder();
        for (String wort : s.strip().split("\\s+")) {
            if (z.length() > 0 && z.length() + 1 + wort.length() > w) {
                r.add(z.toString());
                z.setLength(0);
                if (r.size() == max) {
                    String l = r.get(max - 1);
                    r.set(max - 1, (l.length() > w - 1 ? l.substring(0, w - 1) : l) + "~");
                    return r;
                }
            }
            if (z.length() > 0) z.append(' ');
            z.append(wort.length() > w ? wort.substring(0, w - 1) + "~" : wort);
        }
        if (z.length() > 0) r.add(z.toString());
        return r;
    }

    // ================================================================ Springen
    /** Ticket unter dem Cursor: im Board die Karte, sonst ein Schluessel in der Zeile; null wenn keiner */
    String schluesselBei(Doc d) {
        int z = d.zeileVon(d.cursor), sp = d.cursor - d.anf(z);
        Raster r = raster.get(d);
        if (r != null && z >= r.oben && z < r.oben + r.tickets.length) {
            int c = (sp - 1) / (r.spaltenBreite + 1);
            if (c >= 0 && c < r.tickets[z - r.oben].length && r.tickets[z - r.oben][c] != null) return r.tickets[z - r.oben][c];
        }
        String zeile = d.zeile(z);
        Matcher m = SCHLUESSEL.matcher(zeile);
        String erst = null;
        while (m.find()) {
            if (sp >= m.start() && sp <= m.end()) return m.group();
            if (erst == null) erst = m.group();
        }
        // in Jira-Puffern reicht eine Zeile mit genau einem Schluessel
        return d.jira != null ? erst : null;
    }

    /** ENTER in Jira-Puffern: Ticket unter dem Cursor oeffnen; false, wenn keins da ist */
    boolean springen(Doc d) {
        if (d.jira == null) return false;
        String k = schluesselBei(d);
        if (k == null || ("ticket:" + k).equals(d.jira)) return false;
        ticket(k);
        return true;
    }

    // ================================================================ Farben
    static byte[] faerben(CharSequence s) {
        byte[] f = new byte[s.length()];
        int i = 0, n = s.length();
        String vorige = "";
        while (i < n) {
            int e = i;
            while (e < n && s.charAt(e) != '\n') e++;
            String z = s.subSequence(i, e).toString();
            if (z.startsWith("# ")) {
                java.util.Arrays.fill(f, i, e, Ausgabe.KOPF);
            } else if (z.matches("[=-]{3,}") || z.matches("--- .* ---")) {
                java.util.Arrays.fill(f, i, e, Ausgabe.LEISE);
                // Ueberschrift ueber der Linie
                if (z.matches("-{3,}") && !vorige.isBlank()) java.util.Arrays.fill(f, Math.max(0, i - vorige.length() - 1), i - 1, Ausgabe.KOPF);
            } else {
                for (int k = 0; k < z.length(); k++) {
                    char c = z.charAt(k);
                    if (c >= 0x2500 && c <= 0x257F || c == '░') f[i + k] = Ausgabe.LEISE;
                    else if (c == '█') f[i + k] = Ausgabe.ERFOLG;
                }
                Matcher fm = Pattern.compile("(?:^|   )([A-Z][\\w ]{1,20}):").matcher(z);
                while (fm.find()) java.util.Arrays.fill(f, i + fm.start(1), i + fm.end(1) + 1, Ausgabe.INFO);
            }
            // Checklisten: erledigt gruen, offen gelb
            if (z.startsWith("[x] ")) java.util.Arrays.fill(f, i, i + 3, Ausgabe.ERFOLG);
            else if (z.startsWith("[ ] ")) java.util.Arrays.fill(f, i, i + 3, Ausgabe.WARN);
            Matcher m = SCHLUESSEL.matcher(z);
            while (m.find()) java.util.Arrays.fill(f, i + m.start(), i + m.end(), Ausgabe.ORT);
            vorige = z;
            i = e + 1;
        }
        return f;
    }

    // ================================================================ Tagebuch
    /** Ordner fuer die Tagesdateien (jira.journal, Vorgabe jira-tage neben config.txt); null = aus */
    Path tagebuchOrdner() {
        String o = ed.kf.get("jira.journal", "").strip();
        if (o.equalsIgnoreCase("off")) return null;
        return o.isEmpty() ? ed.konfig.resolve("jira-tage") : ed.aufloesen(o);
    }

    Path tagesDatei(LocalDate tag) {
        Path o = tagebuchOrdner();
        return o == null ? null : o.resolve(tag + ".txt");
    }

    private Thread tagebuchFaden;

    /** beim Start: stuendlich (jira.journal.interval Minuten) erfassen, solange der Editor laeuft */
    void starten() {
        if (!eingerichtet() || tagebuchOrdner() == null || tagebuchFaden != null) return;
        tagebuchFaden = new Thread(() -> {
            while (true) {
                try {
                    erfassen();
                } catch (RuntimeException e) {
                    ed.protokoll("Jira-Tagebuch", e);
                }
                try {
                    Thread.sleep(Math.max(5, ed.kf.zahl("jira.journal.interval", 60)) * 60_000L);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "jira-tagebuch");
        tagebuchFaden.setDaemon(true);
        tagebuchFaden.start();
    }

    /** Zeile der Tagesdatei: Schluessel, Status, seit, bis (oder null), Titel */
    record Eintrag(String key, String status, String seit, String bis, String titel) {}

    private static final Pattern ZEILE = Pattern.compile("^(\\S+)  +(.+?)  +seit (\\d\\d:\\d\\d)(?: bis (\\d\\d:\\d\\d))?  +(.*)$");

    static List<Eintrag> lesen(String text) {
        List<Eintrag> r = new ArrayList<>();
        for (String z : text.split("\n")) {
            Matcher m = ZEILE.matcher(z);
            if (m.find() && SCHLUESSEL.matcher(m.group(1)).matches()) r.add(new Eintrag(m.group(1), m.group(2).strip(), m.group(3), m.group(4), m.group(5)));
        }
        return r;
    }

    /**
     * meine Tickets in Bearbeitung (Status-Kategorie "In Arbeit") in die Datei des Tages: neue mit "seit", bekannte
     * aktualisiert, nicht mehr bearbeitete mit "bis" (sie bleiben fuer den Tag stehen). Meldung oder Fehlertext.
     */
    synchronized String erfassen() {
        Path datei = tagesDatei(LocalDate.now());
        if (datei == null) return "Jira-Tagebuch ist aus (jira.journal = off)";
        String jql = ed.kf.get("jira.journal.jql", "assignee = currentUser() AND resolution = Unresolved ORDER BY updated DESC");
        Antwort a = suche(jql, "summary,status");
        if (a.code() != 200) return "Fehler: " + fehlerText(a);
        Map<String, String[]> jetzt = new LinkedHashMap<>();
        for (Object i : Json.list(Json.get(Json.parse(a.text()), "issues"))) {
            if (!"indeterminate".equals(feld(i, "status", "statusCategory", "key"))) continue;
            jetzt.put(Json.str(Json.get(i, "key")), new String[] {feld(i, "status", "name"), feld(i, "summary").replace('\n', ' ')});
        }
        String uhr = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        List<Eintrag> alt = new ArrayList<>();
        try {
            if (Files.exists(datei)) alt = lesen(Files.readString(datei, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return "Fehler: " + datei + ": " + e.getMessage();
        }
        Map<String, Eintrag> neu = new LinkedHashMap<>();
        for (Eintrag e : alt) {
            String[] j = jetzt.get(e.key());
            if (j != null) neu.put(e.key(), new Eintrag(e.key(), j[0], e.seit(), null, j[1]));
            else neu.put(e.key(), new Eintrag(e.key(), e.status(), e.seit(), e.bis() != null ? e.bis() : uhr, e.titel()));
        }
        for (Map.Entry<String, String[]> j : jetzt.entrySet()) if (!neu.containsKey(j.getKey())) neu.put(j.getKey(), new Eintrag(j.getKey(), j.getValue()[0], uhr, null, j.getValue()[1]));
        int bk = 6, bs = 6;
        for (Eintrag e : neu.values()) {
            bk = Math.max(bk, e.key().length());
            bs = Math.max(bs, e.status().length());
        }
        StringBuilder b = new StringBuilder("# " + LocalDate.now() + " - meine Tickets in Bearbeitung (zuletzt erfasst " + uhr + ")\n\n");
        for (Eintrag e : neu.values()) {
            String zeit = "seit " + e.seit() + (e.bis() != null ? " bis " + e.bis() : "");
            b.append(e.key()).append(" ".repeat(bk - e.key().length() + 2)).append(e.status()).append(" ".repeat(bs - e.status().length() + 2)).append(zeit)
                    .append(" ".repeat(Math.max(2, 21 - zeit.length()))).append(e.titel()).append('\n');
        }
        if (neu.isEmpty()) b.append("(nichts in Bearbeitung)\n");
        try {
            Files.createDirectories(datei.getParent());
            Files.writeString(datei, b.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Fehler: " + datei + ": " + e.getMessage();
        }
        long offen = neu.values().stream().filter(e -> e.bis() == null).count();
        return "Jira-Tagebuch " + datei.getFileName() + ": " + offen + " in Bearbeitung" + (neu.size() > offen ? ", " + (neu.size() - offen) + " heute erledigt oder abgegeben" : "");
    }

    /** Tagesdatei oeffnen (schreibgeschuetzt geht nicht: es ist eine normale Datei; neu laden, falls offen und unveraendert) */
    private void tagOeffnen(Path p) {
        for (Doc t : ed.tabs) {
            if (p.equals(t.pfad) && !t.geaendert) {
                try {
                    t.nurLesen = false;
                    int c = t.cursor;
                    t.setText(Doc.laden(p).t.toString());
                    t.cursor = Math.min(c, t.t.length());
                    t.geaendert = false;
                } catch (IOException e) {
                    // bleibt wie es ist
                }
            }
        }
        ed.springMerken();
        ed.oeffnen(p);
    }

    // ================================================================ Befehle
    void befehle(Map<String, Editor.Cmd> c) {
        /** @binding Jira-Tagebuch: jetzt erfassen, welche meiner Tickets in Bearbeitung sind, und die Datei des Tages oeffnen */
        c.put("jiraJournal", a -> {
            if (!bereit()) return;
            if (tagebuchOrdner() == null) {
                ed.fehler("Jira-Tagebuch ist aus (jira.journal = off in config.txt)");
                return;
            }
            ed.meldung = "Jira-Tagebuch wird erfasst ...";
            hintergrund(() -> {
                String m = erfassen();
                SwingUtilities.invokeLater(() -> {
                    if (m.startsWith("Fehler")) ed.fehler(m);
                    else {
                        tagOeffnen(tagesDatei(LocalDate.now()));
                        ed.meldung = m;
                    }
                    ed.neuMalen.run();
                });
            });
        });
        /** @binding Jira-Tagebuch: einen frueheren Tag waehlen (neueste zuerst) und seine Datei oeffnen */
        c.put("jiraJournalDays", a -> {
            Path o = tagebuchOrdner();
            if (o == null || !Files.isDirectory(o)) {
                ed.fehler("noch keine Tagesdateien" + (o == null ? " (jira.journal = off)" : " in " + o));
                return;
            }
            List<String> tage = new ArrayList<>();
            try (var l = Files.list(o)) {
                l.map(x -> x.getFileName().toString()).filter(n -> n.matches("\\d{4}-\\d\\d-\\d\\d\\.txt")).sorted(java.util.Comparator.reverseOrder())
                        .forEach(n -> tage.add(n.substring(0, 10)));
            } catch (IOException e) {
                ed.fehler("nicht gelesen: " + o);
                return;
            }
            if (tage.isEmpty()) {
                ed.fehler("noch keine Tagesdateien in " + o);
                return;
            }
            ed.wz.auswahl("Jira-Tagebuch: Tag waehlen (" + tage.size() + ")", tage, null, t -> tagOeffnen(o.resolve(t + ".txt")));
        });
        /** @binding Jira: mir zugewiesene, offene Tickets (nach Status gruppiert; ENTER oeffnet) */
        c.put("jiraMine", a -> meine(false));
        /** @binding Jira: Board des aktiven Sprints (Spalten wie in Jira, Fortschritt; ENTER oeffnet ein Ticket) */
        c.put("jiraSprint", a -> sprint(false));
        /** @binding Jira: Ticket oeffnen (Schluessel unter dem Cursor vorgeschlagen, eine Zahl allein ergaenzt jira.project) */
        c.put("jiraOpen", a -> {
            if (!a.isBlank()) {
                ticket(a);
                return;
            }
            if (!bereit()) return;
            String k = schluesselBei(ed.d());
            String vor = k != null ? k : projekt().isEmpty() ? "" : projekt().toUpperCase() + "-";
            ed.popupFrage("Jira-Ticket oeffnen (z. B. " + (projekt().isEmpty() ? "PROJ" : projekt().toUpperCase()) + "-12):", vor, this::ticket);
        });
        /** @binding Jira: aktuellen Jira-Puffer neu laden */
        c.put("jiraRefresh", a -> {
            Doc d = ed.d();
            if (d.jira == null) {
                ed.fehler("kein Jira-Puffer (Leertaste j m, j s, j o)");
                return;
            }
            if (d.jira.equals("meine")) meine(true);
            else if (d.jira.equals("sprint")) sprint(true);
            else if (d.jira.startsWith("ticket:")) {
                String k = d.jira.substring(7);
                laden(d, "Jira: " + k + " wird geladen ...", () -> ticketText(k), true);
            }
        });
        /** @binding Jira: Ticket unter dem Cursor (oder des Puffers) im Browser oeffnen */
        c.put("jiraBrowser", a -> {
            if (!bereit()) return;
            Doc d = ed.d();
            String k = schluesselBei(d);
            if (k == null && d.jira != null && d.jira.startsWith("ticket:")) k = d.jira.substring(7);
            String u = server() + (k != null ? "/browse/" + k : "");
            try {
                java.awt.Desktop.getDesktop().browse(URI.create(u));
                ed.meldung = "im Browser: " + u;
            } catch (IOException | RuntimeException e) {
                ed.fehler("Browser nicht geoeffnet: " + u);
            }
        });
    }
}
