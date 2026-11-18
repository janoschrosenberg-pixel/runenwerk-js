package runen;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.SwingUtilities;

/**
 * Mattermost ueber die REST-API v4: beim Start anmelden (mattermost.server, mattermost.user, mattermost.password
 * oder mattermost.token in config.txt), regelmaessig die ungelesenen Nachrichten zaehlen (oben in der Tab-Leiste),
 * Kanaele als Chat-Tab oeffnen. Der Tab ist ein normaler Puffer (alle Vim-Befehle): oben der Verlauf, unter der
 * Trennlinie der Entwurf; Strg+Enter (oder Leertaste m s) sendet ihn.
 */
final class Mattermost {
    /** Trennlinie zwischen Verlauf und Entwurf im Chat-Tab */
    static final String TRENN = "------------------------ Nachricht (Strg+Enter sendet) ------------------------";

    /** Kanal mit Zaehlern (unveraenderlich, wird je Abfrage neu gebaut) */
    record Kanal(String id, String team, String art, String name, String anzeige, int ungelesen, int erwaehnt, long letzte) {}

    record Post(String id, String user, String text, long zeit, String typ, Map<String, Integer> reaktionen) {}

    /** Chat-Tab eines Kanals */
    static final class Chat {
        final String kanal;
        String titel;
        /** Kennung des zuletzt gezeigten Stands (letzte Post-ID und Aenderungszeit) */
        String stand = "";

        Chat(String kanal, String titel) {
            this.kanal = kanal;
            this.titel = titel;
        }
    }

    private final Editor ed;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private volatile String server, token, ich = "", ichId;
    /** "aus" (nicht eingerichtet), "verbinde", "ok", sonst Fehlertext */
    volatile String status = "aus";
    volatile List<Kanal> kanaele = List.of();
    volatile int ungelesen, erwaehnt;
    private final Map<String, String> namen = new ConcurrentHashMap<>();
    private Thread faden;
    private final Object wecker = new Object();
    private volatile boolean jetzt;
    /** eigene Emojis des Servers (Namen) */
    volatile List<String> eigeneEmojis = List.of();
    /** Websocket fuer Live-Updates (null, wenn nicht verbunden) */
    private volatile java.net.http.WebSocket ws;
    private volatile boolean wsVerbindet;
    /** Live-Updates ueber den Websocket laufen */
    volatile boolean live;
    volatile String wsFehler;

    Mattermost(Editor ed) {
        this.ed = ed;
    }

    boolean eingerichtet() {
        return !ed.kf.get("mattermost.server", "").isBlank();
    }

    // ================================================================ Verbindung
    /** beim Start und mit mmConnect: Einstellungen lesen, Abfrage-Faden starten */
    void starten() {
        server = ed.kf.get("mattermost.server", "").strip().replaceAll("/+$", "");
        if (server.isEmpty()) {
            status = "aus";
            return;
        }
        if (!server.startsWith("http")) server = "https://" + server;
        token = null;
        status = "verbinde";
        wsSchliessen();
        if (faden == null) {
            faden = new Thread(this::schleife, "mattermost");
            faden.setDaemon(true);
            faden.start();
        } else {
            wecken();
        }
    }

    void wecken() {
        synchronized (wecker) {
            jetzt = true;
            wecker.notifyAll();
        }
    }

    private void schleife() {
        while (true) {
            try {
                abfragen();
            } catch (RuntimeException e) {
                status = "Fehler: " + e;
                ed.protokoll("Mattermost", e);
            }
            wsVerbinden();
            int s = Math.max(5, ed.kf.zahl("mattermost.interval", 30));
            boolean chat = !chatTabs().isEmpty();
            long warten = (live ? Math.max(s, 300) : chat ? Math.min(s, 5) : s) * 1000L;
            synchronized (wecker) {
                if (!jetzt) {
                    try {
                        wecker.wait(warten);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                jetzt = false;
            }
        }
    }

    record Antwort(int code, String text, String token) {}

    Antwort anfrage(String methode, String pfad, Object body) {
        String url = server + "/api/v4" + pfad;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Accept", "application/json")
                    .header("X-Requested-With", "XMLHttpRequest");
            if (token != null) b.header("Authorization", "Bearer " + token);
            if (body != null) b.header("Content-Type", "application/json").method(methode, HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
            else b.method(methode, HttpRequest.BodyPublishers.noBody());
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Antwort(r.statusCode(), r.body(), r.headers().firstValue("Token").orElse(null));
        } catch (IOException | IllegalArgumentException e) {
            return new Antwort(-1, e.getMessage() == null ? e.toString() : e.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Antwort(-1, "unterbrochen", null);
        }
    }

    static String fehlerText(Antwort a) {
        if (a.code() == -1) return a.text();
        String m = null;
        try {
            m = Json.str(Json.get(Json.parse(a.text()), "message"));
        } catch (RuntimeException e) {
            // kein JSON
        }
        String t = m != null ? m : a.text().strip().replaceAll("\\s+", " ");
        if (t.length() > 160) t = t.substring(0, 160) + "...";
        return "HTTP " + a.code() + (t.isEmpty() ? "" : ": " + t);
    }

    private boolean anmelden() {
        String tk = ed.kf.get("mattermost.token", "").strip();
        if (!tk.isEmpty()) {
            token = tk;
        } else {
            String u = ed.kf.get("mattermost.user", "").strip(), pw = ed.kf.get("mattermost.password", "");
            if (u.isEmpty() || pw.isEmpty()) {
                status = "mattermost.user und mattermost.password (oder mattermost.token) fehlen";
                return false;
            }
            Antwort a = anfrage("POST", "/users/login", Json.obj("login_id", u, "password", pw));
            if (a.code() != 200 || a.token() == null) {
                status = "Anmeldung: " + fehlerText(a);
                return false;
            }
            token = a.token();
        }
        Antwort me = anfrage("GET", "/users/me", null);
        if (me.code() != 200) {
            status = "Anmeldung: " + fehlerText(me);
            token = null;
            return false;
        }
        Object j = Json.parse(me.text());
        ichId = Json.str(Json.get(j, "id"));
        ich = Json.str(Json.get(j, "username"));
        if (ichId != null && ich != null) namen.put(ichId, ich);
        // eigene Emojis des Servers (fuer die Emoji-Auswahl)
        List<String> eig = new ArrayList<>();
        for (int seite = 0; seite < 10; seite++) {
            Antwort e = anfrage("GET", "/emoji?per_page=200&sort=name&page=" + seite, null);
            if (e.code() != 200) break;
            List<Object> l = Json.list(Json.parse(e.text()));
            for (Object x : l) if (Json.str(Json.get(x, "name")) != null) eig.add(Json.str(Json.get(x, "name")));
            if (l.size() < 200) break;
        }
        eigeneEmojis = eig;
        return true;
    }

    // ================================================================ Websocket
    private static final java.util.Set<String> EREIGNISSE = java.util.Set.of("posted", "post_edited", "post_deleted", "channel_viewed", "reaction_added",
            "reaction_removed", "channel_created", "channel_updated", "channel_deleted", "direct_added", "group_added", "user_added", "user_removed",
            "multiple_channels_viewed", "thread_read_changed");

    /** Websocket aufbauen (mattermost.live = off: nur abfragen); bei Ereignissen sofort neu laden */
    private void wsVerbinden() {
        if (ws != null || wsVerbindet || token == null || !ed.kf.an("mattermost.live", true)) return;
        String u = ed.kf.get("mattermost.websocket", "").strip();
        if (u.isEmpty()) u = server.replaceFirst("^http", "ws") + "/api/v4/websocket";
        wsVerbindet = true;
        String tk = token;
        try {
            http.newWebSocketBuilder().header("Authorization", "Bearer " + tk).connectTimeout(Duration.ofSeconds(15)).buildAsync(URI.create(u), new Lauscher())
                    .whenComplete((w, e) -> {
                        wsVerbindet = false;
                        if (e != null) {
                            live = false;
                            wsFehler = e.getCause() != null ? e.getCause().toString() : e.toString();
                            return;
                        }
                        ws = w;
                        live = true;
                        wsFehler = null;
                        w.sendText(Json.write(Json.obj("seq", 1, "action", "authentication_challenge", "data", Json.obj("token", tk))), true);
                        SwingUtilities.invokeLater(ed.neuMalen);
                    });
        } catch (IllegalArgumentException e) {
            wsVerbindet = false;
            wsFehler = e.getMessage();
        }
    }

    private void wsSchliessen() {
        java.net.http.WebSocket w = ws;
        ws = null;
        live = false;
        if (w != null) w.abort();
    }

    /** Verbindung weg: in ein paar Sekunden neu verbinden (ueber die Abfrage-Schleife) */
    private void wsWeg(String grund) {
        ws = null;
        live = false;
        wsFehler = grund;
        hintergrund(() -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                return;
            }
            wecken();
        });
        SwingUtilities.invokeLater(ed.neuMalen);
    }

    private final class Lauscher implements java.net.http.WebSocket.Listener {
        private final StringBuilder teil = new StringBuilder();

        @Override
        public java.util.concurrent.CompletionStage<?> onText(java.net.http.WebSocket w, CharSequence data, boolean last) {
            teil.append(data);
            if (last) {
                String t = teil.toString();
                teil.setLength(0);
                ereignis(t);
            }
            w.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(java.net.http.WebSocket w, int code, String reason) {
            if (w == ws) wsWeg("geschlossen (" + code + (reason == null || reason.isEmpty() ? "" : ": " + reason) + ")");
            return null;
        }

        @Override
        public void onError(java.net.http.WebSocket w, Throwable e) {
            if (w == ws) wsWeg(e.toString());
        }
    }

    /** Ereignis vom Websocket: Nachrichten, Lesestand, Reaktionen -> sofort neu laden */
    void ereignis(String text) {
        try {
            String ev = Json.str(Json.get(Json.parse(text), "event"));
            if (ev != null && EREIGNISSE.contains(ev)) wecken();
        } catch (RuntimeException e) {
            // kein JSON
        }
    }

    /** ein Durchlauf: anmelden falls noetig, Kanaele mit Zaehlern lesen, offene Chats neu laden */
    void abfragen() {
        if (server == null || server.isEmpty()) return;
        if (token == null && !anmelden()) return;
        Antwort t = anfrage("GET", "/users/me/teams", null);
        if (t.code() == 401) {
            token = null;
            status = "abgemeldet - neuer Versuch";
            return;
        }
        if (t.code() != 200) {
            status = fehlerText(t);
            return;
        }
        Map<String, Kanal> neu = new LinkedHashMap<>();
        Map<String, String> teamNamen = new HashMap<>();
        for (Object team : Json.list(Json.parse(t.text()))) {
            String tid = Json.str(Json.get(team, "id"));
            teamNamen.put(tid, Json.str(Json.get(team, "display_name")));
            Antwort k = anfrage("GET", "/users/me/teams/" + tid + "/channels", null);
            Antwort m = anfrage("GET", "/users/me/teams/" + tid + "/channels/members", null);
            if (k.code() != 200 || m.code() != 200) {
                status = fehlerText(k.code() != 200 ? k : m);
                return;
            }
            Map<String, Object> mitglied = new HashMap<>();
            for (Object x : Json.list(Json.parse(m.text()))) mitglied.put(Json.str(Json.get(x, "channel_id")), x);
            for (Object c : Json.list(Json.parse(k.text()))) {
                String id = Json.str(Json.get(c, "id"));
                if (id == null || neu.containsKey(id) || lang(Json.get(c, "delete_at")) > 0) continue;
                Object mg = mitglied.get(id);
                long gesamt = lang(Json.get(c, "total_msg_count")), gelesen = mg == null ? gesamt : lang(Json.get(mg, "msg_count"));
                int erw = mg == null ? 0 : (int) lang(Json.get(mg, "mention_count"));
                int ung = (int) Math.max(0, gesamt - gelesen);
                // stumm geschaltete Kanaele zaehlen nur Erwaehnungen
                if ("mention".equals(Json.str(Json.get(mg, "notify_props", "mark_unread")))) ung = erw;
                String art = Json.str(Json.get(c, "type"));
                neu.put(id, new Kanal(id, tid, art, Json.str(Json.get(c, "name")), Json.str(Json.get(c, "display_name")), ung, erw,
                        lang(Json.get(c, "last_post_at"))));
            }
        }
        // Direktnachrichten: Name des anderen
        List<String> fehlen = new ArrayList<>();
        for (Kanal k : neu.values()) {
            if (!"D".equals(k.art()) || k.name() == null) continue;
            String anderer = andererVon(k.name());
            if (anderer != null && !namen.containsKey(anderer)) fehlen.add(anderer);
        }
        namenHolen(fehlen);
        List<Kanal> l = new ArrayList<>();
        int u = 0, e = 0;
        for (Kanal k : neu.values()) {
            String anz = anzeigename(k, teamNamen.size() > 1 ? teamNamen.get(k.team()) : null);
            k = new Kanal(k.id(), k.team(), k.art(), k.name(), anz, k.ungelesen(), k.erwaehnt(), k.letzte());
            l.add(k);
            u += k.ungelesen();
            e += k.erwaehnt();
        }
        int vorher = ungelesen;
        List<Kanal> alt = kanaele;
        kanaele = l;
        ungelesen = u;
        erwaehnt = e;
        status = "ok";
        if (u > vorher) {
            Kanal wo = null;
            for (Kanal k : l) {
                for (Kanal a : alt) if (a.id().equals(k.id()) && k.ungelesen() > a.ungelesen()) wo = k;
                if (wo == null && alt.isEmpty() && k.ungelesen() > 0) wo = k;
            }
            Kanal w = wo;
            if (w != null && !alt.isEmpty()) SwingUtilities.invokeLater(() -> {
                if (ed.meldung == null) ed.meldung = "Mattermost: neue Nachricht in " + w.anzeige() + " (Leertaste m u)";
            });
        }
        chatsLaden();
        SwingUtilities.invokeLater(() -> {
            if (NEU_LADEN.equals(ed.meldung)) ed.meldung = "Mattermost: " + ungelesen + " ungelesen" + (erwaehnt > 0 ? ", " + erwaehnt + " Erwaehnungen" : "");
            ed.neuMalen.run();
        });
    }

    private static final String NEU_LADEN = "Mattermost wird neu geladen ...";

    private String andererVon(String dmName) {
        String[] t = dmName.split("__");
        if (t.length != 2) return null;
        return t[0].equals(ichId) ? t[1] : t[0];
    }

    private String anzeigename(Kanal k, String team) {
        if ("D".equals(k.art())) {
            String a = k.name() == null ? null : andererVon(k.name());
            String n = a == null ? null : namen.get(a);
            return "@" + (n != null ? n : k.name());
        }
        String n = k.anzeige() != null && !k.anzeige().isBlank() ? k.anzeige() : k.name();
        return ("G".equals(k.art()) ? "" : "~") + n + (team != null && !"G".equals(k.art()) ? " (" + team + ")" : "");
    }

    private void namenHolen(Collection<String> ids) {
        List<String> l = new ArrayList<>();
        for (String i : ids) if (i != null && !namen.containsKey(i) && !l.contains(i)) l.add(i);
        if (l.isEmpty()) return;
        Antwort a = anfrage("POST", "/users/ids", l);
        if (a.code() != 200) return;
        for (Object u : Json.list(Json.parse(a.text()))) {
            String id = Json.str(Json.get(u, "id")), n = Json.str(Json.get(u, "username"));
            if (id != null && n != null) namen.put(id, n);
        }
    }

    static long lang(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    // ================================================================ Chat
    /** offene Chat-Tabs neu laden (im Abfrage-Faden) */
    private void chatsLaden() {
        for (Doc d : chatTabs()) laden(d);
    }

    /** offene Chat-Tabs (aus dem Swing-Faden gelesen) */
    private List<Doc> chatTabs() {
        List<Doc> l = new ArrayList<>();
        Runnable r = () -> {
            for (Doc d : ed.tabs) if (d.chat != null) l.add(d);
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(r);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.lang.reflect.InvocationTargetException e) {
                // keine Chats
            }
        }
        return l;
    }

    private void laden(Doc d) {
        Chat c = d.chat;
        Antwort a = anfrage("GET", "/channels/" + c.kanal + "/posts?per_page=" + Math.max(10, ed.kf.zahl("mattermost.history", 100)), null);
        if (a.code() != 200) {
            status = fehlerText(a);
            return;
        }
        Object j = Json.parse(a.text());
        List<Post> posts = new ArrayList<>();
        List<String> wer = new ArrayList<>();
        long aenderung = 0;
        for (Object id : Json.list(Json.get(j, "order"))) {
            Object p = Json.get(j, "posts", (String) id);
            if (p == null) continue;
            String u = Json.str(Json.get(p, "user_id"));
            wer.add(u);
            aenderung = Math.max(aenderung, lang(Json.get(p, "update_at")));
            Map<String, Integer> re = new LinkedHashMap<>();
            for (Object r : Json.list(Json.get(p, "metadata", "reactions"))) {
                String en = Json.str(Json.get(r, "emoji_name"));
                if (en != null) re.merge(en, 1, Integer::sum);
            }
            posts.add(new Post((String) id, u, Json.str(Json.get(p, "message")), lang(Json.get(p, "create_at")), Json.str(Json.get(p, "type")), re));
        }
        posts.sort((x, y) -> Long.compare(x.zeit(), y.zeit()));
        namenHolen(wer);
        String stand = (posts.isEmpty() ? "" : posts.get(posts.size() - 1).id()) + "/" + posts.size() + "/" + aenderung;
        String verlauf = verlauf(c, posts);
        SwingUtilities.invokeLater(() -> {
            if (!ed.tabs.contains(d)) return;
            if (!stand.equals(c.stand)) {
                c.stand = stand;
                zeigen(d, verlauf);
            }
            // sichtbarer Chat gilt als gelesen
            if (ed.d() == d) gelesen(c.kanal);
            ed.neuMalen.run();
        });
    }

    private void gelesen(String kanal) {
        boolean offen = false;
        for (Kanal k : kanaele) if (k.id().equals(kanal) && (k.ungelesen() > 0 || k.erwaehnt() > 0)) offen = true;
        if (!offen) return;
        hintergrund(() -> {
            anfrage("POST", "/channels/members/me/view", Json.obj("channel_id", kanal));
            List<Kanal> l = new ArrayList<>();
            int u = 0, e = 0;
            for (Kanal k : kanaele) {
                if (k.id().equals(kanal)) k = new Kanal(k.id(), k.team(), k.art(), k.name(), k.anzeige(), 0, 0, k.letzte());
                l.add(k);
                u += k.ungelesen();
                e += k.erwaehnt();
            }
            kanaele = l;
            ungelesen = u;
            erwaehnt = e;
            SwingUtilities.invokeLater(ed.neuMalen);
        });
    }

    private static final DateTimeFormatter UHR = DateTimeFormatter.ofPattern("HH:mm");

    /** Verlauf als Text: Kopfzeile, Tagestrenner, "HH:mm name: Text" (Folgezeilen eingerueckt) */
    String verlauf(Chat c, List<Post> posts) {
        StringBuilder b = new StringBuilder("# " + c.titel + "   (Strg+Enter sendet, Leertaste m r laedt neu, :q schliesst)\n");
        LocalDate tag = null;
        ZoneId zone = ZoneId.systemDefault();
        for (Post p : posts) {
            var zeit = Instant.ofEpochMilli(p.zeit()).atZone(zone);
            if (!zeit.toLocalDate().equals(tag)) {
                tag = zeit.toLocalDate();
                b.append("\n--- ").append(tag).append(" ---\n");
            }
            String wer = p.typ() != null && p.typ().startsWith("system_") ? "*" : namen.getOrDefault(p.user(), "?");
            String kopf = UHR.format(zeit) + " " + wer + ": ";
            String[] z = Emojis.ersetzen(p.text() == null ? "" : p.text()).split("\n", -1);
            b.append(kopf).append(z[0]).append('\n');
            String ein = " ".repeat(6);
            for (int i = 1; i < z.length; i++) b.append(ein).append(z[i]).append('\n');
            if (!p.reaktionen().isEmpty()) {
                List<String> r = new ArrayList<>();
                for (Map.Entry<String, Integer> e : p.reaktionen().entrySet()) {
                    String zc = Emojis.zeichen(e.getKey());
                    r.add((zc != null ? zc : ":" + e.getKey() + ":") + " " + e.getValue());
                }
                b.append(ein).append("[").append(String.join("  ", r)).append("]\n");
            }
        }
        if (posts.isEmpty()) b.append("\n(noch keine Nachrichten)\n");
        b.append('\n');
        return b.toString();
    }

    /** Verlauf ersetzen, Entwurf und Cursor (im Entwurf) behalten */
    static void zeigen(Doc d, String verlauf) {
        String t = d.t.toString();
        int s = t.indexOf(TRENN);
        String entwurf = s < 0 ? "" : t.substring(Math.min(t.length(), s + TRENN.length() + 1));
        int imEntwurf = s >= 0 && d.cursor > s + TRENN.length() ? d.cursor - (s + TRENN.length() + 1) : -1;
        boolean leer = t.isEmpty();
        int alt = d.cursor;
        d.setText(verlauf + TRENN + "\n" + entwurf);
        int start = verlauf.length() + TRENN.length() + 1;
        if (leer) d.cursor = start + entwurf.length();
        else if (imEntwurf >= 0) d.cursor = Math.min(d.t.length(), start + imEntwurf);
        else d.cursor = Math.min(alt, verlauf.length());
        d.geaendert = false;
    }

    /** Entwurf eines Chat-Tabs (Text unter der Trennlinie), null ohne Trennlinie */
    static String entwurf(Doc d) {
        String t = d.t.toString();
        int s = t.indexOf(TRENN);
        return s < 0 ? null : t.substring(Math.min(t.length(), s + TRENN.length() + 1));
    }

    void oeffnen(Kanal k) {
        for (int i = 0; i < ed.tabs.size(); i++) {
            Doc d = ed.tabs.get(i);
            if (d.chat != null && d.chat.kanal.equals(k.id())) {
                ed.aktiv = i;
                gelesen(k.id());
                wecken();
                return;
            }
        }
        Doc d = new Doc();
        d.chat = new Chat(k.id(), k.anzeige());
        d.titel = k.anzeige();
        zeigen(d, "# " + k.anzeige() + "\n\n(wird geladen ...)\n\n");
        d.chat.stand = "";
        Doc alt = ed.d();
        if (ed.tabs.size() == 1 && alt.pfad == null && alt.term == null && alt.chat == null && !alt.geaendert && alt.t.length() == 0) ed.tabs.set(0, d);
        else ed.tabs.add(d);
        ed.aktiv = ed.tabs.indexOf(d);
        ed.befehl("switchState", "insert");
        hintergrund(() -> laden(d));
        gelesen(k.id());
    }

    void senden(Doc d) {
        if (d.chat == null) {
            ed.fehler("kein Chat-Tab (Leertaste m m oeffnet einen Kanal)");
            return;
        }
        String e = entwurf(d);
        if (e == null) {
            ed.fehler("Trennlinie fehlt - Leertaste m r baut den Chat neu auf (der Entwurf geht dabei verloren)");
            d.chat.stand = "";
            return;
        }
        String text = e.strip();
        if (text.isEmpty()) {
            ed.meldung = "nichts zu senden (Text unter die Trennlinie schreiben)";
            return;
        }
        Chat c = d.chat;
        ed.meldung = "wird gesendet ...";
        hintergrund(() -> {
            Antwort a = anfrage("POST", "/posts", Json.obj("channel_id", c.kanal, "message", text));
            SwingUtilities.invokeLater(() -> {
                if (a.code() == 201 || a.code() == 200) {
                    // Entwurf leeren, Verlauf neu laden
                    String t = d.t.toString();
                    int s = t.indexOf(TRENN);
                    if (s >= 0) {
                        d.merken();
                        d.loeschen(s + TRENN.length() + 1, t.length() - (s + TRENN.length() + 1));
                        d.geaendert = false;
                    }
                    ed.meldung = "gesendet an " + c.titel;
                    c.stand = "";
                    hintergrund(() -> laden(d));
                } else {
                    ed.fehler("nicht gesendet: " + fehlerText(a));
                }
                ed.neuMalen.run();
            });
        });
    }

    private static void hintergrund(Runnable r) {
        Thread t = new Thread(r, "mattermost");
        t.setDaemon(true);
        t.start();
    }

    /** Text fuer die Tab-Leiste oben (null, wenn nicht eingerichtet) */
    String anzeige() {
        if (server == null || server.isEmpty()) return null;
        if (status.equals("verbinde")) return "MM ...";
        if (!status.equals("ok")) return "MM !";
        if (ungelesen == 0 && erwaehnt == 0) return live ? "MM" : "MM~";
        return "MM " + ungelesen + " neu" + (erwaehnt > 0 ? " @" + erwaehnt : "");
    }

    /** Kanaele fuer die Auswahl: ungelesene zuerst, dann nach letzter Nachricht */
    List<Kanal> sortiert() {
        List<Kanal> l = new ArrayList<>(kanaele);
        l.sort((a, b) -> {
            int u = Boolean.compare(b.ungelesen() + b.erwaehnt() > 0, a.ungelesen() + a.erwaehnt() > 0);
            return u != 0 ? u : Long.compare(b.letzte(), a.letzte());
        });
        return l;
    }

    static String eintrag(Kanal k) {
        String z = k.erwaehnt() > 0 ? "  (" + k.ungelesen() + " neu, @" + k.erwaehnt() + ")" : k.ungelesen() > 0 ? "  (" + k.ungelesen() + " neu)" : "";
        return k.anzeige() + z;
    }

    // ================================================================ Befehle
    void befehle(Map<String, Editor.Cmd> c) {
        /** @binding Mattermost: Kanal waehlen und als Chat-Tab oeffnen (ungelesene zuerst) */
        c.put("mmChannel", a -> {
            if (!bereit()) return;
            List<Kanal> l = sortiert();
            List<String> e = new ArrayList<>();
            Map<String, Kanal> zu = new HashMap<>();
            for (Kanal k : l) {
                String s = eintrag(k);
                e.add(s);
                zu.put(s, k);
            }
            ed.wz.auswahl("Mattermost: Kanal oeffnen (" + ungelesen + " ungelesen)", e, null, s -> oeffnen(zu.get(s)));
        });
        /** @binding Mattermost: naechsten Kanal mit ungelesenen Nachrichten oeffnen */
        c.put("mmUnread", a -> {
            if (!bereit()) return;
            for (Kanal k : sortiert()) {
                if (k.ungelesen() + k.erwaehnt() > 0) {
                    oeffnen(k);
                    return;
                }
            }
            ed.meldung = "Mattermost: nichts ungelesen";
        });
        /** @binding Mattermost: Entwurf des Chat-Tabs senden (auch Strg+Enter im Chat) */
        c.put("mmSend", a -> senden(ed.d()));
        /** @binding Mattermost: Kanaele und offene Chats sofort neu laden */
        c.put("mmRefresh", a -> {
            if (ed.d().chat != null) ed.d().chat.stand = "";
            if (!eingerichtet()) {
                ed.fehler("mattermost.server fehlt in config.txt");
                return;
            }
            ed.meldung = NEU_LADEN;
            wecken();
        });
        /** @binding Mattermost: neu anmelden (nach Aenderungen an mattermost.* in config.txt) */
        c.put("mmConnect", a -> {
            ed.kf.lesen();
            starten();
            ed.meldung = eingerichtet() ? "Mattermost: verbinde mit " + server : "mattermost.server fehlt in config.txt";
        });
        /** @binding Mattermost: Verbindungsstand (Anmeldung, Websocket) zeigen */
        c.put("mmStatus", a -> {
            ed.meldung = !eingerichtet() ? "Mattermost ist nicht eingerichtet" : "Mattermost " + server + ": " + status + (ich.isEmpty() ? "" : " als " + ich)
                    + ", Live-Updates " + (live ? "an (Websocket)" : "aus" + (wsFehler != null ? " - " + wsFehler : "") + " (alle " + ed.kf.zahl("mattermost.interval", 30) + " s abfragen)")
                    + ", " + eigeneEmojis.size() + " eigene Emojis";
        });
    }

    private boolean bereit() {
        if (!eingerichtet()) {
            ed.fehler("Mattermost ist nicht eingerichtet: mattermost.server, mattermost.user, mattermost.password in config.txt");
            return false;
        }
        if (!status.equals("ok")) {
            ed.fehler("Mattermost: " + (status.equals("verbinde") ? "verbinde noch ..." : status));
            wecken();
            return false;
        }
        return true;
    }

    // ================================================================ Farben
    /** Chat-Tab: Kopf, Tagestrenner, Uhrzeit leise, Namen farbig (eigener anders), Trennlinie leise */
    static byte[] faerben(CharSequence s, String ich) {
        byte[] f = new byte[s.length()];
        int i = 0, n = s.length();
        while (i < n) {
            int e = i;
            while (e < n && s.charAt(e) != '\n') e++;
            String z = s.subSequence(i, e).toString();
            if (z.startsWith("# ")) {
                java.util.Arrays.fill(f, i, e, Ausgabe.KOPF);
            } else if (z.startsWith("--- ") || z.equals(TRENN)) {
                java.util.Arrays.fill(f, i, e, Ausgabe.LEISE);
            } else if (z.startsWith("      [") && z.endsWith("]")) {
                java.util.Arrays.fill(f, i, e, Ausgabe.LEISE);
            } else if (z.matches("\\d\\d:\\d\\d \\S+: .*|\\d\\d:\\d\\d \\S+:")) {
                java.util.Arrays.fill(f, i, i + 5, Ausgabe.LEISE);
                int d = z.indexOf(": ", 6);
                if (d < 0) d = z.length() - 1;
                String wer = z.substring(6, d);
                java.util.Arrays.fill(f, i + 6, i + d + 1, wer.equals(ich) ? Ausgabe.EIGEN : wer.equals("*") ? Ausgabe.LEISE : Ausgabe.ORT);
            }
            // :eigenes_emoji: (nicht umgewandelt) farbig
            java.util.regex.Matcher em = EIGEN.matcher(z);
            while (em.find()) if (f[i + em.start()] != Ausgabe.KOPF) java.util.Arrays.fill(f, i + em.start(), i + em.end(), Ausgabe.INFO);
            i = e + 1;
        }
        return f;
    }

    private static final java.util.regex.Pattern EIGEN = java.util.regex.Pattern.compile(":[a-z0-9_+-]+:");

    String ich() {
        return ich;
    }
}
