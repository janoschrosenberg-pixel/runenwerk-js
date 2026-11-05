package runen;

import static runen.Editor.E_FEHLER;
import static runen.Editor.E_GRUND;
import static runen.Editor.E_HELL;
import static runen.Editor.E_INFO;
import static runen.Editor.E_KONSOLE;
import static runen.Editor.E_MATT;
import static runen.Editor.E_TEXT;
import static runen.Editor.E_WARN;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.SwingUtilities;

/**
 * Pull Requests im SCM-Manager (REST-API v2, scm-review-plugin): erstellen (Quell- und Ziel-Branch,
 * Reviewer mit Vervollstaendigung), alle ansehen (Panel unten wie git status), freigeben, mergen
 * (Squash als Vorgabe, Quell-Branch danach loeschen) und als Diff mit allen Kommentaren zeigen;
 * Kommentare zu einer Codezeile (z. B. Befunde von Teamscale) springen mit ENTER an die Stelle.
 * config.txt: scm.url, scm.apikey (dazu bei Bedarf scm.user, scm.repo, scm.auth).
 */
final class Scm {
    record Antwort(int code, String text, java.net.http.HttpHeaders kopf) {}

    /** das Repo im SCM-Manager zu einem lokalen git-Repo */
    record Ziel(Path repo, String remote, String ns, String name) {}

    private final Editor ed;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();

    boolean offen;
    private Ziel ziel;
    private List<Object> prs = List.of();
    private int wahl, oben;
    private String status = "IN_PROGRESS";
    private String zustand = "";
    private String ich;
    /** Benutzer fuer die Reviewer-Auswahl: id -> Anzeigename */
    private final Map<String, String> benutzer = new LinkedHashMap<>();

    static final List<String> FILTER = List.of("IN_PROGRESS", "MINE", "REVIEWER", "ALL");
    static final List<String> STRATEGIEN = List.of("SQUASH", "MERGE_COMMIT", "FAST_FORWARD_IF_POSSIBLE", "REBASE");

    Scm(Editor ed) {
        this.ed = ed;
    }

    // ================================================================ REST
    private String basis() {
        String u = ed.kf.get("scm.url", "").strip();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/api/v2")) u = u.substring(0, u.length() - 7);
        return u;
    }

    private boolean eingerichtet() {
        if (basis().isEmpty() || ed.kf.get("scm.apikey", "").isBlank()) {
            ed.fehler("SCM-Manager: scm.url (z. B. https://scm.firma.de/scm) und scm.apikey in config.txt setzen");
            return false;
        }
        return true;
    }

    /**
     * Anmeldung mit dem API-Schluessel: scm.auth = basic (Benutzer:Schluessel), bearer, oder auto (Vorgabe):
     * Basic mit scm.user bzw. dem Benutzer, der im Schluessel steht (SCM-Manager-Schluessel sind
     * Base64-JSON mit "user"), sonst Bearer.
     */
    String anmeldung() {
        String key = ed.kf.get("scm.apikey", "").strip();
        String art = ed.kf.get("scm.auth", "auto").strip();
        String user = ed.kf.get("scm.user", "").strip();
        if (user.isEmpty()) user = benutzerAusSchluessel(key);
        if (art.equals("bearer") || art.equals("auto") && user.isEmpty()) return "Bearer " + key;
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + key).getBytes(StandardCharsets.UTF_8));
    }

    static String benutzerAusSchluessel(String key) {
        try {
            String j = new String(Base64.getDecoder().decode(key.replace('-', '+').replace('_', '/')), StandardCharsets.UTF_8);
            String u = Json.str(Json.get(Json.parse(j), "user"));
            return u == null ? "" : u;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Anfrage an pfad (relativ zu /api/v2) oder eine ganze URL aus _links; im Hintergrund aufrufen */
    Antwort anfrage(String methode, String pfad, String typ, String body) {
        String url = pfad.startsWith("http") ? pfad : basis() + "/api/v2" + pfad;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("Authorization", anmeldung())
                    .header("Accept", "application/json, text/plain, */*");
            if (body != null) b.header("Content-Type", typ).method(methode, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            else b.method(methode, HttpRequest.BodyPublishers.noBody());
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Antwort(r.statusCode(), r.body(), r.headers());
        } catch (IOException | IllegalArgumentException e) {
            return new Antwort(-1, url + ": " + e.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Antwort(-1, "unterbrochen", null);
        }
    }

    /** Fehlertext einer Antwort: message aus dem JSON, sonst der Anfang des Textes */
    static String fehlerText(Antwort a) {
        if (a.code() == -1) return a.text();
        String m = null;
        try {
            Object j = Json.parse(a.text());
            m = Json.str(Json.get(j, "message"));
            List<Object> v = Json.list(Json.get(j, "violations"));
            if (!v.isEmpty()) m = (m == null ? "" : m + ": ") + Json.str(Json.get(v.get(0), "message"));
        } catch (RuntimeException e) {
            // kein JSON
        }
        String t = m != null ? m : a.text().strip().replaceAll("\\s+", " ");
        if (t.length() > 200) t = t.substring(0, 200) + "...";
        String hinweis = a.code() == 401 ? " (Anmeldung: scm.apikey pruefen, ggf. scm.user oder scm.auth = bearer setzen)" : a.code() == 403 ? " (keine Berechtigung)" : "";
        return "HTTP " + a.code() + (t.isEmpty() ? "" : ": " + t) + hinweis;
    }

    private static String seg(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String repoPfad(Ziel z) {
        return seg(z.ns()) + "/" + seg(z.name());
    }

    /** href eines Links; bei einer Liste (z. B. merge je Strategie) der mit diesem name */
    static String link(Object o, String name, String unter) {
        Object l = Json.get(o, "_links", name);
        if (l instanceof List<?> li) {
            for (Object x : li) if (unter == null || unter.equals(Json.str(Json.get(x, "name")))) return Json.str(Json.get(x, "href"));
            return null;
        }
        return Json.str(Json.get(l, "href"));
    }

    /** erste Liste in _embedded (pullRequests, branches, pullRequestComments ...) */
    static List<Object> eingebettet(Object o) {
        Object e = Json.get(o, "_embedded");
        if (e instanceof Map<?, ?> m) for (Object v : m.values()) if (v instanceof List<?>) return Json.list(v);
        return o instanceof List<?> ? Json.list(o) : List.of();
    }

    private static void hintergrund(Runnable r) {
        Thread t = new Thread(r, "scm");
        t.setDaemon(true);
        t.start();
    }

    private void ui(Runnable r) {
        SwingUtilities.invokeLater(() -> {
            r.run();
            ed.neuMalen.run();
        });
    }

    // ================================================================ Repo
    /** SCM-Manager-Repo zum git-Repo der aktuellen Datei: scm.repo, sonst aus der Remote-URL (.../repo/NS/NAME) */
    /**
     * Repo der aktuellen Datei (ohne Datei: das zuletzt benutzte, sonst das des Arbeitsbereichs); der Name im
     * SCM-Manager kommt aus seiner Remote-URL, sonst aus scm.repo.ORDNER bzw. scm.repo
     */
    private Ziel zielFinden() {
        Doc d = ed.d();
        Path eigen = d.pfad != null ? Git.wurzel(d.pfad) : d.term != null ? Git.wurzel(d.term.ordner()) : null;
        Path repo = eigen != null ? eigen : ziel != null ? ziel.repo() : ed.wz.repoFuer("");
        if (repo == null) return null;
        Git.Lauf rl = Git.git(repo, "remote");
        List<String> remotes = new ArrayList<>();
        if (rl.code() == 0) for (String x : rl.ausgabe().split("\\R")) if (!x.isBlank()) remotes.add(x.strip());
        String remote = remotes.contains("origin") ? "origin" : remotes.isEmpty() ? "origin" : remotes.get(0);
        String ordner = repo.getFileName() == null ? "" : repo.getFileName().toString();
        String fest = ed.kf.get("scm.repo." + ordner, "").strip();
        if (fest.contains("/")) return new Ziel(repo, remote, fest.substring(0, fest.indexOf('/')), fest.substring(fest.indexOf('/') + 1));
        Git.Lauf u = Git.git(repo, "remote", "get-url", remote);
        String[] ns = u.code() == 0 ? repoAusUrl(u.ausgabe().strip()) : null;
        if (ns != null) return new Ziel(repo, remote, ns[0], ns[1]);
        fest = ed.kf.get("scm.repo", "").strip();
        if (fest.contains("/")) return new Ziel(repo, remote, fest.substring(0, fest.indexOf('/')), fest.substring(fest.indexOf('/') + 1));
        ed.fehler("SCM-Manager: Repo nicht aus der Remote-URL von " + repo + " erkannt (" + u.ausgabe().strip() + ") - scm.repo." + ordner
                + " = NAMESPACE/NAME in config.txt");
        return null;
    }

    /** NAMESPACE und NAME aus einer Remote-URL: .../repo/NS/NAME, sonst die letzten zwei Teile (https, ssh, host:ns/name) */
    static String[] repoAusUrl(String url) {
        String u = url.strip().replace('\\', '/');
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith(".git")) u = u.substring(0, u.length() - 4);
        Matcher m = Pattern.compile("(?:^|/)repo/([^/]+)/([^/]+)$").matcher(u);
        if (m.find()) return new String[] {m.group(1), m.group(2)};
        // scp-Form user@host:ns/name
        if (!u.contains("://")) {
            int c = u.indexOf(':');
            if (c >= 0) u = u.substring(c + 1);
        } else {
            u = u.substring(u.indexOf("://") + 3);
            int sl = u.indexOf('/');
            u = sl < 0 ? "" : u.substring(sl + 1);
        }
        String[] t = u.split("/");
        if (t.length < 2 || t[t.length - 2].isEmpty() || t[t.length - 1].isEmpty()) return null;
        return new String[] {t[t.length - 2], t[t.length - 1]};
    }

    private String ichHolen() {
        if (ich != null) return ich;
        Antwort a = anfrage("GET", "/me", null, null);
        if (a.code() == 200) {
            try {
                ich = Json.str(Json.get(Json.parse(a.text()), "name"));
            } catch (RuntimeException e) {
                // unbekannt
            }
        }
        return ich;
    }

    private void benutzerMerken(Object person) {
        String id = Json.str(Json.get(person, "id"));
        if (id == null) id = Json.str(Json.get(person, "name"));
        String n = Json.str(Json.get(person, "displayName"));
        if (id != null) benutzer.putIfAbsent(id, n == null ? id : n);
    }

    // ================================================================ Liste (Panel)
    void oeffnen() {
        if (!eingerichtet()) return;
        Ziel z = zielFinden();
        if (z == null) return;
        // anderes Repo: alte Liste nicht stehen lassen
        if (ziel == null || !ziel.repo().equals(z.repo()) || !repoPfad(ziel).equals(repoPfad(z))) {
            prs = List.of();
            wahl = 0;
        }
        ziel = z;
        offen = true;
        ed.konsoleOffen = false;
        ed.wz.statusOffen = false;
        ed.modus = "pullrequests";
        laden();
    }

    private void laden() {
        Ziel z = ziel;
        String st = status;
        zustand = "lade Pull Requests von " + z.ns() + "/" + z.name() + " (" + z.repo().getFileName() + ") ...";
        hintergrund(() -> {
            ichHolen();
            Antwort a = anfrage("GET", "/pull-requests/" + repoPfad(z) + "?status=" + st + "&pageSize=200", null, null);
            boolean selbstFiltern = false;
            if (a.code() == 400) {
                // aeltere Plugins kennen den Filter nicht: alle holen und selbst filtern
                a = anfrage("GET", "/pull-requests/" + repoPfad(z), null, null);
                selbstFiltern = true;
            }
            Antwort ant = a;
            List<Object> l = new ArrayList<>();
            String fehler = null;
            if (ant.code() == 200) {
                try {
                    for (Object p : eingebettet(Json.parse(ant.text()))) {
                        String s = Json.str(Json.get(p, "status"));
                        if (selbstFiltern && st.equals("IN_PROGRESS") && !"OPEN".equals(s) && !"DRAFT".equals(s)) continue;
                        l.add(p);
                    }
                } catch (RuntimeException e) {
                    fehler = "Antwort nicht lesbar: " + e.getMessage();
                }
            } else {
                fehler = fehlerText(ant);
            }
            String f = fehler;
            ui(() -> {
                if (f != null) {
                    zustand = "Fehler: " + f;
                    ed.fehler("SCM-Manager: " + f);
                    return;
                }
                String gewaehlt = wahl < prs.size() ? id(prs.get(wahl)) : null;
                prs = l;
                for (Object p : l) {
                    benutzerMerken(Json.get(p, "author"));
                    for (Object r : Json.list(Json.get(p, "reviewer"))) benutzerMerken(r);
                }
                wahl = 0;
                for (int i = 0; i < l.size(); i++) if (id(l.get(i)).equals(gewaehlt)) wahl = i;
                zustand = l.isEmpty() ? "keine Pull Requests (" + filterName() + ")" : "";
            });
        });
    }

    static String id(Object pr) {
        Object i = Json.get(pr, "id");
        return i instanceof Number n ? String.valueOf(n.longValue()) : String.valueOf(i);
    }

    private String filterName() {
        return switch (status) {
            case "IN_PROGRESS" -> "offen";
            case "MINE" -> "meine";
            case "REVIEWER" -> "zum Review";
            default -> "alle";
        };
    }

    private Object gewaehlt() {
        return wahl >= 0 && wahl < prs.size() ? prs.get(wahl) : null;
    }

    /** habe ich (bzw. hat id) freigegeben? */
    private static boolean freigegeben(Object pr, String id) {
        for (Object r : Json.list(Json.get(pr, "reviewer"))) {
            if (id != null && id.equals(Json.str(Json.get(r, "id"))) && Boolean.TRUE.equals(Json.get(r, "approved"))) return true;
        }
        return false;
    }

    static String person(Object p) {
        String n = Json.str(Json.get(p, "displayName"));
        String i = Json.str(Json.get(p, "id"));
        return n != null ? n : i != null ? i : "?";
    }

    String zeile(Object pr) {
        int frei = 0, rev = 0;
        for (Object r : Json.list(Json.get(pr, "reviewer"))) {
            rev++;
            if (Boolean.TRUE.equals(Json.get(r, "approved"))) frei++;
        }
        String st = nz(Json.str(Json.get(pr, "status")));
        Object tasks = Json.get(pr, "tasks");
        int todo = Json.num(Json.get(tasks, "todo"), 0);
        return String.format("#%-4s %-8s %s -> %s   %s   (%s)   Freigaben %d/%d%s%s", id(pr), st, nz(Json.str(Json.get(pr, "source"))),
                nz(Json.str(Json.get(pr, "target"))), nz(Json.str(Json.get(pr, "title"))), person(Json.get(pr, "author")), frei, rev,
                freigegeben(pr, ich) ? "  [von mir freigegeben]" : "", todo > 0 ? "  Aufgaben offen: " + todo : "");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    void malen(int y0) {
        Screen s = ed.scr;
        int W = s.w, lh = s.lh, cw = s.cw;
        int h = s.h - ed.unten() - 6 - y0;
        boolean fokus = ed.modus.equals("pullrequests");
        s.rect(0, y0, W, h, E_KONSOLE);
        s.frame(4, y0, W - 8, h, fokus ? E_HELL : E_TEXT);
        s.frame(5, y0 + 1, W - 10, h - 2, E_MATT);
        String titel = " PULL REQUESTS " + (ziel == null ? "" : ziel.ns() + "/" + ziel.name()) + " (" + filterName() + ")"
                + (fokus ? "  (Tasten unten in der Statuszeile) " : "  (Leertaste g r r) ");
        s.rect(3 * cw, y0 - lh / 2, s.textWidth(titel), lh, E_KONSOLE);
        s.text(3 * cw, y0 - lh / 2, titel, E_HELL);
        int n = Math.max(1, (h - lh - 8) / lh);
        if (wahl < oben) oben = wahl;
        if (wahl >= oben + n) oben = wahl - n + 1;
        s.clip(8, y0 + 2, W - 16, h - 4);
        int zy0 = y0 + lh / 2 + 4;
        if (!zustand.isEmpty()) {
            s.text(2 * cw, zy0, zustand, zustand.startsWith("Fehler") ? E_FEHLER : E_MATT);
            zy0 += lh;
            n--;
        }
        for (int k = 0; k < n && oben + k < prs.size(); k++) {
            int i = oben + k;
            Object pr = prs.get(i);
            int zy = zy0 + k * lh;
            boolean g = fokus && i == wahl;
            if (g) s.rect(8, zy, W - 16, lh, E_TEXT);
            String st = nz(Json.str(Json.get(pr, "status")));
            int f = st.equals("DRAFT") ? E_MATT : st.equals("MERGED") ? E_INFO : st.equals("REJECTED") ? E_FEHLER : freigegeben(pr, ich) ? E_HELL : E_TEXT;
            s.text(2 * cw, zy, zeile(pr), g ? E_GRUND : f);
        }
        s.noClip();
    }

    // ================================================================ Erstellen
    /** Leertaste g r n: Quell-Branch (aktueller vorgewaehlt, wenn gepusht), Ziel, Titel, Beschreibung, Reviewer */
    void erstellen() {
        if (!eingerichtet()) return;
        Ziel z = zielFinden();
        if (z == null) return;
        ziel = z;
        String aktuell = Git.branch(z.repo());
        ed.meldung = "lese die Branches im SCM-Manager ...";
        hintergrund(() -> {
            Antwort a = anfrage("GET", "/repositories/" + repoPfad(z) + "/branches/", null, null);
            List<String> branches = new ArrayList<>();
            String standard = null;
            String fehler = null;
            if (a.code() == 200) {
                for (Object b : eingebettet(Json.parse(a.text()))) {
                    String n = Json.str(Json.get(b, "name"));
                    if (n == null) continue;
                    branches.add(n);
                    if (Boolean.TRUE.equals(Json.get(b, "defaultBranch"))) standard = n;
                }
            } else {
                fehler = fehlerText(a);
            }
            ichHolen();
            String f = fehler, std = standard;
            ui(() -> {
                if (f != null) {
                    ed.fehler("SCM-Manager Branches: " + f);
                    return;
                }
                boolean gepusht = branches.contains(aktuell);
                List<String> quellen = new ArrayList<>(branches);
                if (gepusht) {
                    quellen.remove(aktuell);
                    quellen.add(0, aktuell);
                }
                ed.wz.auswahl("Pull Request von (Quell-Branch)" + (gepusht || aktuell == null ? "" : " - " + aktuell + " ist nicht gepusht (Leertaste g P)"), quellen,
                        gepusht ? aktuell : null, quelle -> {
                            List<String> ziele = new ArrayList<>(branches);
                            ziele.remove(quelle);
                            if (std != null && ziele.remove(std)) ziele.add(0, std);
                            ed.wz.auswahl("Pull Request " + quelle + " nach (Ziel-Branch)", ziele, std, zielBranch -> titelFragen(z, quelle, zielBranch));
                        });
            });
        });
    }

    private Consumer<String> eingabe;

    private void frage(String titel, String vorgabe, Consumer<String> weiter) {
        eingabe = weiter;
        ed.popupOpen(titel, "scmInput", vorgabe);
    }

    private void titelFragen(Ziel z, String quelle, String zielBranch) {
        hintergrund(() -> {
            Git.Lauf l = Git.git(z.repo(), "log", "-1", "--format=%s", z.remote() + "/" + quelle);
            String vorschlag = l.code() == 0 && !l.ausgabe().isBlank() ? l.ausgabe().strip() : quelle;
            ui(() -> frage("Titel (" + quelle + " -> " + zielBranch + "):", vorschlag, titel -> {
                if (titel.isBlank()) {
                    ed.meldung = "kein Titel - abgebrochen";
                    return;
                }
                frage("Beschreibung (leer lassen geht):", "", beschr -> reviewerWaehlen(z, quelle, zielBranch, titel.strip(), beschr.strip(), new ArrayList<>()));
            }));
        });
    }

    /** 1 = alle Benutzer vom Server (Benutzerliste), 2 = gesammelt (Vorschlaege, Pull Requests), 0 = noch nicht */
    private volatile int alleStand;
    private volatile boolean alleLaeuft;

    private String alleHinweis() {
        return alleStand == 1 ? "alle Benutzer, tippen filtert" : alleStand == 2 ? "tippen filtert und sucht weitere im SCM-Manager" : "lade die Benutzer ..., tippen sucht im SCM-Manager";
    }

    /** Benutzer nach Anzeigename */
    private List<String> sortiert() {
        List<String> l = new ArrayList<>(benutzer.keySet());
        l.sort((a, b) -> benutzer.get(a).compareToIgnoreCase(benutzer.get(b)));
        return l;
    }

    /**
     * Alle Benutzer fuer die Reviewer-Auswahl, einmal je Sitzung: die Benutzerliste des SCM-Managers (braucht das
     * Recht, Benutzer zu lesen); sonst gesammelt aus den Vorschlaegen (viele Suchen mit zwei Buchstaben) und
     * aus Autoren und Reviewern aller Pull Requests im Repo. Danach kommen sie in die offene Auswahl.
     */
    private void alleLaden(Ziel z, java.util.function.Supplier<List<String>> eintraege) {
        if (alleStand != 0 || alleLaeuft) return;
        alleLaeuft = true;
        hintergrund(() -> {
            int stand = 2;
            Antwort a = anfrage("GET", "/users?pageSize=10000&sortBy=displayName", null, null);
            List<Object> gefunden = new ArrayList<>();
            // seitenweise (_links.next), falls der Server die Seitengroesse begrenzt
            for (int seite = 0; a.code() == 200 && seite < 100; seite++) {
                try {
                    Object o = Json.parse(a.text());
                    for (Object u : eingebettet(o)) if (!Boolean.FALSE.equals(Json.get(u, "active"))) gefunden.add(u);
                    stand = 1;
                    String weiter = link(o, "next", null);
                    if (weiter == null) break;
                    a = anfrage("GET", weiter, null, null);
                } catch (RuntimeException e) {
                    break;
                }
            }
            if (stand == 2) {
                String[] suchen = {"a", "e", "i", "o", "u", "y"};
                List<Thread> th = new ArrayList<>();
                List<Object> sync = java.util.Collections.synchronizedList(gefunden);
                for (String v : suchen) {
                    for (String k : "bcdfghjklmnprstvwz".split("")) {
                        for (String q : new String[] {v + k, k + v}) {
                            Thread t = new Thread(() -> {
                                Antwort r = anfrage("GET", "/autocomplete/users?q=" + q + "&limit=100", null, null);
                                if (r.code() == 400) r = anfrage("GET", "/autocomplete/users?q=" + q, null, null);
                                if (r.code() != 200) return;
                                try {
                                    sync.addAll(Json.list(Json.parse(r.text())));
                                } catch (RuntimeException e) {
                                    // naechste Suche
                                }
                            }, "scm-benutzer");
                            t.setDaemon(true);
                            th.add(t);
                        }
                    }
                }
                // in Gruppen, damit der Server nicht 200 Anfragen auf einmal bekommt
                for (int i = 0; i < th.size(); i += 12) {
                    for (int j = i; j < Math.min(th.size(), i + 12); j++) th.get(j).start();
                    for (int j = i; j < Math.min(th.size(), i + 12); j++) {
                        try {
                            th.get(j).join(30_000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
                Antwort p = anfrage("GET", "/pull-requests/" + repoPfad(z) + "?status=ALL&pageSize=500", null, null);
                if (p.code() == 400) p = anfrage("GET", "/pull-requests/" + repoPfad(z), null, null);
                if (p.code() == 200) {
                    try {
                        for (Object pr : eingebettet(Json.parse(p.text()))) {
                            gefunden.add(Json.get(pr, "author"));
                            gefunden.addAll(Json.list(Json.get(pr, "reviewer")));
                        }
                    } catch (RuntimeException e) {
                        // ohne
                    }
                }
            }
            int st = stand;
            ui(() -> {
                for (Object u : gefunden) if (u != null) benutzerMerken(u);
                alleStand = st;
                alleLaeuft = false;
                ed.wz.auswahlSetzen(eintraege.get());
                ed.meldung = benutzer.size() + " Benutzer" + (st == 1 ? "" : " (aus Vorschlaegen und Pull Requests; tippen sucht weitere)");
            });
        });
    }

    private static final String FERTIG = "-> fertig, Pull Request erstellen";

    private String benutzerEintrag(String id) {
        String n = benutzer.get(id);
        return n == null || n.equals(id) ? id : id + "  (" + n + ")";
    }

    private List<String> reviewerEintraege(List<String> gewaehlt) {
        List<String> l = new ArrayList<>();
        l.add(FERTIG + (gewaehlt.isEmpty() ? " (ohne Reviewer)" : " (Reviewer: " + String.join(", ", gewaehlt) + ")"));
        for (String id : sortiert()) if (!gewaehlt.contains(id) && !id.equals(ich)) l.add(benutzerEintrag(id));
        return l;
    }

    /** Reviewer nacheinander waehlen; beim Tippen schlaegt der SCM-Manager weitere Benutzer vor */
    private void reviewerWaehlen(Ziel z, String quelle, String zielBranch, String titel, String beschr, List<String> gewaehlt) {
        List<String> l = reviewerEintraege(gewaehlt);
        ed.wz.auswahl("Reviewer hinzufuegen (" + alleHinweis() + ")", l, null, w -> {
            if (w.startsWith(FERTIG)) {
                anlegen(z, quelle, zielBranch, titel, beschr, gewaehlt);
                return;
            }
            String id = w.contains("  (") ? w.substring(0, w.indexOf("  (")) : w.strip();
            gewaehlt.add(id);
            reviewerWaehlen(z, quelle, zielBranch, titel, beschr, gewaehlt);
        });
        alleLaden(z, () -> reviewerEintraege(gewaehlt));
        ed.wz.auswahlNachladen = q -> {
            if (q.strip().length() < 2) return;
            hintergrund(() -> {
                Antwort a = anfrage("GET", "/autocomplete/users?q=" + seg(q.strip()), null, null);
                if (a.code() != 200) return;
                List<String> neu = new ArrayList<>();
                try {
                    for (Object u : Json.list(Json.parse(a.text()))) {
                        benutzerMerken(u);
                        String id = Json.str(Json.get(u, "id"));
                        if (id != null && !gewaehlt.contains(id)) neu.add(benutzerEintrag(id));
                    }
                } catch (RuntimeException e) {
                    return;
                }
                SwingUtilities.invokeLater(() -> ed.wz.auswahlDazu(neu));
            });
        };
    }

    private void anlegen(Ziel z, String quelle, String zielBranch, String titel, String beschr, List<String> reviewer) {
        List<Object> rv = new ArrayList<>();
        for (String r : reviewer) rv.add(Json.obj("id", r));
        String body = Json.write(Json.obj("source", quelle, "target", zielBranch, "title", titel, "description", beschr, "reviewer", rv, "status", "OPEN"));
        ed.meldung = "erstelle den Pull Request ...";
        hintergrund(() -> {
            Antwort a = anfrage("POST", "/pull-requests/" + repoPfad(z), "application/vnd.scmm-pullRequest+json;v=2", body);
            ui(() -> {
                if (a.code() != 201 && a.code() != 200) {
                    ed.fehler("Pull Request nicht erstellt: " + fehlerText(a));
                    return;
                }
                String ort = a.kopf() == null ? "" : a.kopf().firstValue("Location").orElse("");
                String nr = ort.isEmpty() ? "" : " #" + ort.substring(ort.lastIndexOf('/') + 1);
                ed.meldung = "Pull Request" + nr + " erstellt: " + quelle + " -> " + zielBranch + (reviewer.isEmpty() ? "" : ", Reviewer " + String.join(", ", reviewer));
                ed.print("SCM-Manager: " + ed.meldung + (ort.isEmpty() ? "" : " (" + ort + ")"));
                ziel = z;
                offen = true;
                ed.modus = "pullrequests";
                laden();
            });
        });
    }

    // ================================================================ Freigeben, Ablehnen, Mergen
    /** freigeben bzw. zuruecknehmen; danach (Liste neu laden oder die Ansicht) */
    void freigeben(Object pr, boolean ja, Runnable danach) {
        if (pr == null) return;
        String l = link(pr, ja ? "approve" : "disapprove", null);
        if (l == null) {
            if (ja && link(pr, "disapprove", null) != null) {
                ed.meldung = "#" + id(pr) + " ist schon von dir freigegeben (x nimmt es zurueck)";
                return;
            }
            if (!ja && link(pr, "approve", null) != null) {
                ed.meldung = "#" + id(pr) + " ist nicht von dir freigegeben";
                return;
            }
            String self = link(pr, "self", null);
            l = self == null ? null : self.replaceAll("/+$", "") + (ja ? "/approve" : "/disapprove");
        }
        if (l == null) return;
        String url = l;
        ed.meldung = (ja ? "gebe #" : "nehme die Freigabe fuer #") + id(pr) + (ja ? " frei ..." : " zurueck ...");
        hintergrund(() -> {
            Antwort a = anfrage("POST", url, "application/json", "");
            ui(() -> {
                if (a.code() / 100 != 2) {
                    ed.fehler((ja ? "Freigabe" : "Zuruecknehmen") + " fehlgeschlagen: " + fehlerText(a));
                    return;
                }
                ed.meldung = "#" + id(pr) + (ja ? " freigegeben" : ": Freigabe zurueckgenommen");
                danach.run();
            });
        });
    }

    /** ablehnen mit Begruendung (rejectWithMessage, sonst reject) */
    void ablehnen(Object pr, Runnable danach) {
        if (pr == null) return;
        if (!"OPEN".equals(Json.str(Json.get(pr, "status"))) && !"DRAFT".equals(Json.str(Json.get(pr, "status")))) {
            ed.meldung = "#" + id(pr) + " ist nicht offen";
            return;
        }
        frage("#" + id(pr) + " ABLEHNEN - Begruendung (leer geht), ENTER lehnt ab, ESC bricht ab:", "", grund -> {
            String mit = link(pr, "rejectWithMessage", null), ohne = link(pr, "reject", null);
            String self = link(pr, "self", null);
            String url = mit != null ? mit : ohne != null ? ohne : self == null ? null : self.replaceAll("/+$", "") + "/reject";
            if (url == null) return;
            String body = Json.write(Json.obj("message", grund.strip()));
            ed.meldung = "lehne #" + id(pr) + " ab ...";
            hintergrund(() -> {
                Antwort a = anfrage("POST", url, "application/json", body);
                ui(() -> {
                    if (a.code() / 100 != 2) {
                        ed.fehler("Ablehnen fehlgeschlagen: " + fehlerText(a));
                        return;
                    }
                    ed.meldung = "#" + id(pr) + " abgelehnt";
                    danach.run();
                });
            });
        });
    }

    /** m: Strategie (Vorgabe Squash bzw. scm.merge.strategy), Commit-Nachricht, Quell-Branch loeschen (Vorgabe ja) */
    void mergen(Object pr, Runnable danach) {
        if (pr == null) return;
        String st = nz(Json.str(Json.get(pr, "status")));
        if (!st.equals("OPEN")) {
            ed.meldung = "#" + id(pr) + " ist " + st + " - mergen geht nur offene Pull Requests";
            return;
        }
        String vorgabe = ed.kf.get("scm.merge.strategy", "SQUASH").strip().toUpperCase();
        List<String> l = new ArrayList<>();
        for (String s : STRATEGIEN) l.add(s.equals(vorgabe) ? s + "  (Vorgabe)" : s);
        l.sort((a, b) -> Boolean.compare(!a.endsWith("(Vorgabe)"), !b.endsWith("(Vorgabe)")));
        ed.wz.auswahl("#" + id(pr) + " mergen (" + Json.str(Json.get(pr, "source")) + " -> " + Json.str(Json.get(pr, "target")) + "): Strategie", l, l.get(0), w -> {
            String strategie = w.split("\\s")[0];
            ed.meldung = "hole die Commit-Nachricht ...";
            hintergrund(() -> {
                String nachricht = Json.str(Json.get(pr, "title")) + " (#" + id(pr) + ")";
                String dl = link(pr, "defaultCommitMessage", null);
                if (dl != null) {
                    Antwort a = anfrage("GET", dl + (dl.contains("?") ? "&" : "?") + "strategy=" + strategie, null, null);
                    if (a.code() == 200 && !a.text().isBlank()) nachricht = a.text().strip();
                }
                String n = nachricht;
                int nl = n.indexOf('\n');
                String betreff = nl < 0 ? n : n.substring(0, nl), rest = nl < 0 ? "" : n.substring(nl);
                ui(() -> frage("Commit-Nachricht (" + strategie + ", erste Zeile):", betreff, b -> {
                    if (b.isBlank()) {
                        ed.meldung = "keine Nachricht - abgebrochen";
                        return;
                    }
                    boolean loeschen = !ed.kf.get("scm.merge.deleteBranch", "true").strip().equals("false");
                    List<String> wahlen = loeschen ? List.of("Quell-Branch " + Json.str(Json.get(pr, "source")) + " loeschen  (Vorgabe)", "Quell-Branch behalten")
                            : List.of("Quell-Branch behalten  (Vorgabe)", "Quell-Branch " + Json.str(Json.get(pr, "source")) + " loeschen");
                    ed.wz.auswahl("Nach dem Merge", wahlen, wahlen.get(0),
                            x -> mergeSenden(pr, strategie, b.strip() + rest, x.endsWith("loeschen") || x.contains("loeschen  ("), danach));
                }));
            });
        });
    }

    private void mergeSenden(Object pr, String strategie, String nachricht, boolean loeschen, Runnable danach) {
        String url = link(pr, "merge", strategie);
        if (url == null) url = link(pr, "merge", null);
        if (url == null) url = basis() + "/api/v2/merge/" + repoPfad(ziel) + "/" + id(pr);
        if (!url.contains("strategy=")) url += (url.contains("?") ? "&" : "?") + "strategy=" + strategie;
        String body = Json.write(Json.obj("commitMessage", nachricht, "shouldDeleteSourceBranch", loeschen));
        String u = url;
        Ziel z = ziel;
        ed.meldung = "merge #" + id(pr) + " (" + strategie + ") ...";
        hintergrund(() -> {
            Antwort a = anfrage("POST", u, "application/vnd.scmm-mergeCommand+json", body);
            if (a.code() / 100 == 2) Git.git(z.repo(), "fetch", "--prune", z.remote());
            ui(() -> {
                if (a.code() / 100 != 2) {
                    ed.fehler("Merge fehlgeschlagen: " + fehlerText(a) + (a.code() == 409 ? " (Konflikte - erst im Branch loesen)" : ""));
                    return;
                }
                ed.meldung = "#" + id(pr) + " gemergt (" + strategie + ")" + (loeschen ? ", " + Json.str(Json.get(pr, "source")) + " geloescht" : "");
                ed.print("SCM-Manager: " + ed.meldung);
                danach.run();
            });
        });
    }

    // ================================================================ Kommentieren, Reviewer
    /**
     * Kommentar schreiben: an eine Codezeile (datei mit neu- bzw. alt-Nummer), allgemein (datei null)
     * oder als Antwort (auf != null). \n im Text wird ein Zeilenumbruch.
     */
    void kommentieren(Object pr, String titel, String datei, int neu, int alt, Kommentar auf, Runnable danach) {
        if (pr == null) return;
        frage(titel + " (\\n = neue Zeile)", "", text -> {
            if (text.isBlank()) {
                ed.meldung = "leerer Kommentar - nichts geschickt";
                return;
            }
            String t = text.strip().replace("\\n", "\n");
            String url;
            Map<String, Object> body = Json.obj("comment", t);
            if (auf != null) {
                url = link(auf.roh(), "reply", null);
                if (url == null) {
                    String self = link(auf.roh(), "self", null);
                    url = self == null ? null : self.replaceAll("/+$", "") + "/replies/";
                }
            } else {
                url = link(pr, "comments", null);
                if (url == null && link(pr, "self", null) != null) url = link(pr, "self", null).replaceAll("/+$", "") + "/comments/";
                if (datei != null) {
                    Map<String, Object> loc = Json.obj("file", datei);
                    if (neu > 0) loc.put("newLineNumber", neu);
                    if (alt > 0) loc.put("oldLineNumber", alt);
                    body.put("location", loc);
                }
            }
            if (url == null) {
                ed.fehler("kein Link zum Kommentieren");
                return;
            }
            String u = url, b = Json.write(body);
            ed.meldung = "schicke den Kommentar ...";
            hintergrund(() -> {
                Antwort a = anfrage("POST", u, "application/json", b);
                ui(() -> {
                    if (a.code() / 100 != 2) {
                        ed.fehler("Kommentar nicht gespeichert: " + fehlerText(a));
                        return;
                    }
                    ed.meldung = auf != null ? "Antwort gespeichert" : "Kommentar gespeichert";
                    danach.run();
                });
            });
        });
    }

    /**
     * Eine Datei des Pull Requests zuruecknehmen: nur wenn der lokale Branch der Quell-Branch ist und die Datei
     * dort (Commit und Arbeitsverzeichnis) denselben Stand hat wie im Pull Request. Dann kommt die Datei aus dem
     * Ziel-Branch (gibt es sie dort nicht, wird sie entfernt; bei Umbenennungen beide Pfade) und wird committet.
     */
    void dateiZuruecksetzen(Object pr, Ziel z, String alt, String neu, Runnable danach) {
        if (pr == null || z == null) return;
        String quelle = Json.str(Json.get(pr, "source")), zielBranch = Json.str(Json.get(pr, "target"));
        List<String> pfade = new ArrayList<>();
        for (String x : new String[] {alt, neu}) if (x != null && !x.equals("/dev/null") && !pfade.contains(x)) pfade.add(x);
        if (pfade.isEmpty() || quelle == null || zielBranch == null) return;
        String name = String.join(" / ", pfade);
        for (Doc t : ed.tabs) {
            for (String x : pfade) {
                if (t.pfad != null && t.geaendert && t.pfad.equals(z.repo().resolve(x).normalize())) {
                    ed.fehler(x + " ist im Editor geaendert und nicht gespeichert - erst speichern oder verwerfen");
                    return;
                }
            }
        }
        ed.meldung = "pruefe " + name + " ...";
        hintergrund(() -> {
            Path repo = z.repo();
            Git.git(repo, "fetch", "--quiet", z.remote());
            String rq = z.remote() + "/" + quelle, rz = z.remote() + "/" + zielBranch;
            String lokal = Git.branch(repo);
            String fehler = null;
            if (!quelle.equals(lokal)) {
                fehler = "lokal ist " + lokal + " ausgecheckt, der Pull Request kommt aus " + quelle + " - erst auf " + quelle + " wechseln (Leertaste b)";
            } else if (Git.git(repo, "rev-parse", "--verify", "-q", rq).code() != 0) {
                fehler = rq + " gibt es nicht (git fetch fehlgeschlagen?)";
            } else {
                List<String> sa = new ArrayList<>(List.of("status", "--porcelain", "--"));
                sa.addAll(pfade);
                Git.Lauf st = Git.git(repo, sa.toArray(new String[0]));
                if (!st.ausgabe().isBlank()) fehler = name + " hat nicht committete Aenderungen:\n" + st.ausgabe().strip();
                for (String x : pfade) {
                    if (fehler != null) break;
                    String h = blob(repo, "HEAD", x), q = blob(repo, rq, x);
                    if (!h.equals(q)) fehler = x + " ist lokal anders als im Pull Request (" + (h.isEmpty() ? "lokal nicht da" : q.isEmpty() ? "im Pull Request nicht da" : "anderer Inhalt")
                            + ") - erst pull/push, damit beide gleich sind";
                }
            }
            String f = fehler;
            List<String> plan = new ArrayList<>();
            if (f == null) {
                for (String x : pfade) {
                    String zb = blob(repo, rz, x), hb = blob(repo, "HEAD", x);
                    if (zb.equals(hb)) continue;
                    boolean imZiel = !zb.isEmpty(), lokalDa = !hb.isEmpty();
                    if (imZiel) plan.add("ersetzen:" + x);
                    else if (lokalDa) plan.add("loeschen:" + x);
                }
            }
            ui(() -> {
                if (f != null) {
                    ed.fehler("Datei zuruecksetzen: " + f);
                    return;
                }
                if (plan.isEmpty()) {
                    ed.meldung = name + ": nichts zu tun, schon wie in " + zielBranch;
                    return;
                }
                ed.meldung = null;
                List<String> was = new ArrayList<>();
                for (String x : plan) was.add((x.startsWith("ersetzen:") ? "wie in " + zielBranch + ": " : "loeschen: ") + x.substring(x.indexOf(':') + 1));
                frage(String.join(", ", was) + " - dann commit auf " + quelle + ". Commit-Nachricht (leer = abbrechen):",
                        "Aenderungen an " + Editor.name(Path.of(pfade.get(pfade.size() - 1))) + " zurueckgenommen (Stand von " + zielBranch + ")", nachricht -> {
                            if (nachricht.isBlank()) {
                                ed.meldung = "zuruecksetzen abgebrochen";
                                return;
                            }
                            zuruecksetzenAusfuehren(z, rz, plan, pfade, nachricht.strip(), danach);
                        });
            });
        });
    }

    private void zuruecksetzenAusfuehren(Ziel z, String rz, List<String> plan, List<String> pfade, String nachricht, Runnable danach) {
        Path repo = z.repo();
        ed.meldung = "setze zurueck ...";
        hintergrund(() -> {
            String fehler = null;
            for (String x : plan) {
                String p = x.substring(x.indexOf(':') + 1);
                Git.Lauf l = x.startsWith("ersetzen:") ? Git.git(repo, "checkout", rz, "--", p) : Git.git(repo, "rm", "-q", "--", p);
                if (l.code() != 0) {
                    fehler = Werkzeug.letzteZeile(l.ausgabe());
                    break;
                }
            }
            String[] commit = new String[pfade.size() + 5];
            commit[0] = "commit";
            commit[1] = "-q";
            commit[2] = "-m";
            commit[3] = nachricht;
            commit[4] = "--";
            for (int i = 0; i < pfade.size(); i++) commit[5 + i] = pfade.get(i);
            if (fehler == null) {
                Git.Lauf c = Git.git(repo, commit);
                if (c.code() != 0) fehler = Werkzeug.letzteZeile(c.ausgabe());
            }
            if (fehler != null) {
                // nichts halb stehen lassen: Index und Dateien zurueck auf HEAD
                List<String> args = new ArrayList<>(List.of("restore", "--source=HEAD", "--staged", "--worktree", "--"));
                for (String p : pfade) if (!blob(repo, "HEAD", p).isEmpty()) args.add(p);
                if (args.size() > 5) Git.git(repo, args.toArray(new String[0]));
            }
            String hash = Git.git(repo, "rev-parse", "--short", "HEAD").ausgabe().strip();
            String f = fehler;
            ui(() -> {
                ed.wz.neuLaden(repo);
                ed.index.neuEinlesen();
                if (f != null) {
                    ed.fehler("Datei zuruecksetzen fehlgeschlagen (nichts geaendert): " + f);
                    return;
                }
                ed.print("SCM-Manager: " + hash + " " + nachricht);
                ed.meldung = hash + " committet: " + String.join(", ", pfade) + " - noch nicht gepusht (Leertaste g P), erst danach im Pull Request";
                if (danach != null) danach.run();
            });
        });
    }

    /** Blob-Hash von ref:pfad, leer wenn es die Datei dort nicht gibt */
    private static String blob(Path repo, String ref, String pfad) {
        Git.Lauf l = Git.git(repo, "rev-parse", "--verify", "-q", ref + ":" + pfad);
        return l.code() == 0 ? l.ausgabe().strip() : "";
    }

    private static final String UEBERNEHMEN = "-> uebernehmen";

    /** Reviewer an- und abwaehlen (tippen sucht weitere), dann den Pull Request aktualisieren */
    void reviewerAendern(Object pr, Runnable danach) {
        if (pr == null) return;
        List<String> jetzt = new ArrayList<>();
        for (Object r : Json.list(Json.get(pr, "reviewer"))) {
            benutzerMerken(r);
            String id = Json.str(Json.get(r, "id"));
            if (id != null) jetzt.add(id);
        }
        reviewerListe(pr, jetzt, danach);
    }

    private List<String> aendernEintraege(Object pr, List<String> gewaehlt) {
        List<String> l = new ArrayList<>();
        l.add(UEBERNEHMEN + " (" + (gewaehlt.isEmpty() ? "keine Reviewer" : String.join(", ", gewaehlt)) + ")");
        for (String id : gewaehlt) l.add("[x] " + benutzerEintrag(id));
        for (String id : sortiert()) if (!gewaehlt.contains(id) && !id.equals(ich)) l.add("[ ] " + benutzerEintrag(id));
        return l;
    }

    private void reviewerListe(Object pr, List<String> gewaehlt, Runnable danach) {
        List<String> l = aendernEintraege(pr, gewaehlt);
        ed.wz.auswahl("Reviewer von #" + id(pr) + ": ENTER waehlt an/ab (" + alleHinweis() + ")", l, null, w -> {
            if (w.startsWith(UEBERNEHMEN)) {
                reviewerSenden(pr, gewaehlt, danach);
                return;
            }
            String e = w.substring(4);
            String id = e.contains("  (") ? e.substring(0, e.indexOf("  (")) : e.strip();
            if (!gewaehlt.remove(id)) gewaehlt.add(id);
            reviewerListe(pr, gewaehlt, danach);
        });
        if (ziel != null) alleLaden(ziel, () -> aendernEintraege(pr, gewaehlt));
        ed.wz.auswahlNachladen = q -> {
            if (q.strip().length() < 2) return;
            hintergrund(() -> {
                Antwort a = anfrage("GET", "/autocomplete/users?q=" + seg(q.strip()), null, null);
                if (a.code() != 200) return;
                List<String> neu = new ArrayList<>();
                try {
                    for (Object u : Json.list(Json.parse(a.text()))) {
                        benutzerMerken(u);
                        String id = Json.str(Json.get(u, "id"));
                        if (id != null && !gewaehlt.contains(id)) neu.add("[ ] " + benutzerEintrag(id));
                    }
                } catch (RuntimeException e) {
                    return;
                }
                SwingUtilities.invokeLater(() -> ed.wz.auswahlDazu(neu));
            });
        };
    }

    private void reviewerSenden(Object pr, List<String> ids, Runnable danach) {
        String url = link(pr, "update", null);
        if (url == null) url = link(pr, "self", null);
        if (url == null) return;
        List<Object> rv = new ArrayList<>();
        for (String r : ids) rv.add(Json.obj("id", r));
        Map<String, Object> body = Json.obj("title", Json.get(pr, "title"), "description", nz(Json.str(Json.get(pr, "description"))), "source", Json.get(pr, "source"),
                "target", Json.get(pr, "target"), "reviewer", rv);
        if (Json.get(pr, "status") != null) body.put("status", Json.get(pr, "status"));
        String u = url, b = Json.write(body);
        ed.meldung = "aktualisiere die Reviewer ...";
        hintergrund(() -> {
            Antwort a = anfrage("PUT", u, "application/vnd.scmm-pullRequest+json;v=2", b);
            ui(() -> {
                if (a.code() / 100 != 2) {
                    ed.fehler("Reviewer nicht geaendert: " + fehlerText(a));
                    return;
                }
                ed.meldung = "Reviewer von #" + id(pr) + ": " + (ids.isEmpty() ? "keine" : String.join(", ", ids));
                danach.run();
            });
        });
    }

    void imBrowser(Object pr) {
        if (pr == null || ziel == null) return;
        String u = basis() + "/repo/" + repoPfad(ziel) + "/pull-request/" + id(pr) + "/";
        try {
            java.awt.Desktop.getDesktop().browse(URI.create(u));
            ed.meldung = u;
        } catch (Exception e) {
            ed.meldung = "Browser nicht geoeffnet: " + u;
        }
    }

    // ================================================================ Ansicht mit Diff und Kommentaren
    /** Kommentar eines Pull Requests: Codestelle (Datei/Zeile, null ohne), Text, Antworten; roh fuer die Links */
    record Kommentar(String datei, int neu, int alt, String autor, String datum, String typ, String text, List<String[]> antworten, boolean veraltet, Object roh) {}

    static List<Kommentar> kommentareLesen(String json) {
        List<Kommentar> r = new ArrayList<>();
        for (Object c : eingebettet(Json.parse(json))) {
            if (Boolean.TRUE.equals(Json.get(c, "systemComment"))) continue;
            Object loc = Json.get(c, "location");
            String datei = Json.str(Json.get(loc, "file"));
            int neu = Json.num(Json.get(loc, "newLineNumber"), 0), alt = Json.num(Json.get(loc, "oldLineNumber"), 0);
            List<String[]> antw = new ArrayList<>();
            List<Object> rep = Json.list(Json.get(c, "_embedded", "replies"));
            if (rep.isEmpty()) rep = Json.list(Json.get(c, "replies"));
            for (Object x : rep) antw.add(new String[] {person(Json.get(x, "author")), datum(Json.str(Json.get(x, "date"))), nz(Json.str(Json.get(x, "comment")))});
            r.add(new Kommentar(datei, neu, alt, person(Json.get(c, "author")), datum(Json.str(Json.get(c, "date"))), nz(Json.str(Json.get(c, "type"))),
                    nz(Json.str(Json.get(c, "comment"))), antw, Boolean.TRUE.equals(Json.get(c, "outdated")), c));
        }
        return r;
    }

    static String datum(String iso) {
        if (iso == null) return "";
        return iso.length() >= 16 ? iso.substring(0, 10) + " " + iso.substring(11, 16) : iso;
    }

    /** Codestellen im Text (Teamscale & Co.): pfad:zeile, pfad (Zeile/line N), pfad#LN, ...?line=N */
    private static final Pattern STELLE = Pattern.compile(
            "([\\w./\\\\-]+\\.[A-Za-z][\\w]{0,9})(?::(\\d+)|#L(\\d+)|[?&](?:line|zeile)=(\\d+)|[^\\n\\w]{0,4}\\(?(?:Zeilen?|lines?|Line|Zeile|L\\.?)\\s*(\\d+))?");

    static String[] stelleImText(String t) {
        Matcher m = STELLE.matcher(t);
        while (m.find()) {
            String p = m.group(1);
            String zeile = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4) != null ? m.group(4) : m.group(5);
            // nur Pfade mit Ordner oder einer Zeilenangabe, keine Rechnernamen aus URLs
            if (zeile == null && !p.contains("/")) continue;
            if (p.startsWith("//") || t.regionMatches(Math.max(0, m.start() - 3), "://", 0, 3)) continue;
            return new String[] {p, zeile == null ? "1" : zeile};
        }
        return null;
    }

    /** Pull Request frisch holen, dazu Kommentare und Diff, dann nebeneinander zeigen */
    void zeigen(Object alt) {
        if (alt == null) return;
        Ziel z = ziel;
        ed.meldung = "hole Diff und Kommentare von #" + id(alt) + " ...";
        hintergrund(() -> {
            Object pr = alt;
            String self = link(alt, "self", null);
            if (self != null) {
                Antwort a = anfrage("GET", self, null, null);
                if (a.code() == 200) {
                    try {
                        pr = Json.parse(a.text());
                    } catch (RuntimeException e) {
                        // der alte Stand tut es auch
                    }
                }
            }
            String quelle = Json.str(Json.get(pr, "source")), zielBranch = Json.str(Json.get(pr, "target"));
            String kl = link(pr, "comments", null);
            if (kl == null && self != null) kl = self.replaceAll("/+$", "") + "/comments/";
            List<Kommentar> kom = new ArrayList<>();
            String komFehler = null;
            if (kl != null) {
                Antwort a = anfrage("GET", kl, null, null);
                if (a.code() == 200) {
                    try {
                        kom = kommentareLesen(a.text());
                    } catch (RuntimeException e) {
                        komFehler = e.getMessage();
                    }
                } else {
                    komFehler = fehlerText(a);
                }
            }
            // Diff: lokal ueber git (dann springt ENTER in die eigenen Dateien), sonst vom Server
            Git.git(z.repo(), "fetch", "--quiet", z.remote());
            String rq = z.remote() + "/" + quelle, rz = z.remote() + "/" + zielBranch;
            Git.Lauf d = Git.inhalt(z.repo(), "diff", "--no-color", "--no-ext-diff", "-M", rz + "..." + rq);
            String diff = d.code() == 0 ? d.ausgabe() : null;
            String woher = "git diff " + rz + "..." + rq;
            if (diff == null) {
                Antwort a = anfrage("GET", "/repositories/" + repoPfad(z) + "/incoming/" + seg(quelle) + "/" + seg(zielBranch) + "/diff", null, null);
                diff = a.code() == 200 ? a.text() : "";
                woher = a.code() == 200 ? "Diff vom SCM-Manager" : "kein Diff: " + Werkzeug.letzteZeile(d.ausgabe()) + "; Server: " + fehlerText(a);
            }
            Object frisch = pr;
            List<Kommentar> k2 = kom;
            String kf = komFehler, df = diff, wo = woher;
            ui(() -> {
                // frischen Stand auch in der Liste
                for (int i = 0; i < prs.size(); i++) if (id(prs.get(i)).equals(id(frisch))) prs.set(i, frisch);
                ed.prAnsicht.zeigen(frisch, z, k2, kf, df, wo, ich);
            });
        });
    }

    // ================================================================ Befehle
    void befehle(Map<String, Editor.Cmd> c) {
        /** @binding SCM-Manager: alle Pull Requests des Repos (Panel unten) */
        c.put("scmPullRequests", a -> {
            ed.prAnsicht.aktiv = false;
            if (offen && ziel != null && !ed.modus.equals("pullrequests")) {
                ed.modus = "pullrequests";
                laden();
                return;
            }
            oeffnen();
        });
        /** @binding SCM-Manager: Pull Request erstellen (Quell-Branch, Ziel-Branch, Titel, Reviewer) */
        c.put("scmCreate", a -> erstellen());
        /** @binding Pull Requests: schliessen */
        c.put("scmClose", a -> {
            offen = false;
            if (ed.modus.equals("pullrequests")) ed.modus = "normal";
        });
        /** @binding Pull Requests: zurueck in den Text, die Liste bleibt sichtbar */
        c.put("scmBack", a -> ed.modus = "normal");
        /** @binding Pull Requests: naechster */
        c.put("scmDown", a -> wahl = Math.min(prs.size() - 1, wahl + 1));
        /** @binding Pull Requests: voriger */
        c.put("scmUp", a -> wahl = Math.max(0, wahl - 1));
        /** @binding Pull Requests: neu laden */
        c.put("scmRefresh", a -> laden());
        /** @binding Pull Requests: Filter wechseln (offen, meine, zum Review, alle) */
        c.put("scmFilter", a -> {
            status = FILTER.get((FILTER.indexOf(status) + 1) % FILTER.size());
            laden();
        });
        /** @binding Pull Requests: nebeneinander mit allen Kommentaren zeigen */
        c.put("scmShow", a -> zeigen(gewaehlt()));
        /** @binding Pull Requests: freigeben */
        c.put("scmApprove", a -> freigeben(gewaehlt(), true, this::laden));
        /** @binding Pull Requests: Freigabe zuruecknehmen */
        c.put("scmDisapprove", a -> freigeben(gewaehlt(), false, this::laden));
        /** @binding Pull Requests: mergen (Strategie, Vorgabe Squash; Quell-Branch danach loeschen) */
        c.put("scmMerge", a -> mergen(gewaehlt(), this::laden));
        /** @binding Pull Requests: ablehnen (mit Begruendung) */
        c.put("scmReject", a -> ablehnen(gewaehlt(), this::laden));
        /** @binding Pull Requests: Reviewer aendern */
        c.put("scmReviewers", a -> reviewerAendern(gewaehlt(), this::laden));
        /** @binding Pull Requests: im Browser oeffnen */
        c.put("scmBrowser", a -> imBrowser(gewaehlt()));
        /** @binding SCM-Manager: Antwort aus dem Eingabefenster (intern) */
        c.put("scmInput", a -> {
            Consumer<String> w = eingabe;
            eingabe = null;
            if (w != null) w.accept(a);
        });
    }

    /** Statuszeile in der Liste: Tasten mit Funktion */
    static final String[][] TASTEN = {{"ENTER", "Ansehen", "Ansehen"}, {"a", "Freigeben", "Freigabe"}, {"x", "Freigabe zurueck", "Zurueck"}, {"m", "Mergen", "Merge"},
            {"R", "Ablehnen", "Ablehnen"}, {"e", "Reviewer", "Rev."}, {"n", "Neuer PR", "Neu"}, {"s", "Filter", "Filter"}, {"g", "neu laden", "laden"}, {"w", "Browser", "Web"},
            {"TAB", "zum Text", "Text"}, {"ESC", "schliessen", "zu"}};
}
