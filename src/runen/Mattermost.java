package runen;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.SwingUtilities;

/**
 * Mattermost ueber die REST-API v4, sparsam mit dem Server:
 * <ul>
 * <li>Kanaele und Zaehler kommen aus einem Abgleich beim Start, nach einem Neuverbinden und sonst selten
 * (mit Websocket alle 10 Minuten, ohne alle mattermost.interval Sekunden, mindestens 60).</li>
 * <li>Der Websocket liefert neue Nachrichten, Aenderungen, Reaktionen und den Lesestand mit Inhalt; sie werden
 * direkt eingearbeitet, ohne neue Anfragen.</li>
 * <li>Nachrichten gibt es nur fuer den einen gewaehlten Kanal (Chat-Tab). Ohne gewaehlten Kanal werden keine
 * Nachrichten geladen; ein anderer Kanal ersetzt den aktuellen.</li>
 * <li>Die Personenliste wird einmal geladen und in mattermost-nutzer.tsv neben config.txt gespeichert;
 * neu geladen wird sie nur mit Leertaste m p.</li>
 * <li>Bei Fehlern wird laenger gewartet (5 s, 10 s, ... bis 10 Minuten); eine abgelehnte Anmeldung wird nicht
 * wiederholt, bis Leertaste m c neu verbindet.</li>
 * </ul>
 */
final class Mattermost {
    /** Trennlinie zwischen Verlauf und Entwurf im Chat-Tab */
    static final String TRENN = "------------------------ Nachricht (Strg+Enter sendet) ------------------------";

    /** Kanal mit Zaehlern (unveraenderlich; Aenderungen bauen einen neuen) */
    record Kanal(String id, String team, String art, String name, String anzeige, int ungelesen, int erwaehnt, long letzte, boolean stumm) {
        Kanal mit(int u, int e, long l) {
            return new Kanal(id, team, art, name, anzeige, u, e, l, stumm);
        }
    }

    /** Nachricht; reaktionen als "Benutzer-ID|Emoji" */
    record Post(String id, String user, String text, long zeit, long geaendert, String typ, Set<String> reaktionen) {}

    /** der gewaehlte Kanal im Chat-Tab */
    static final class Chat {
        final String kanal;
        String titel;
        final Doc doc;
        /** Nachrichten nach ID (nur im Swing-Faden aendern) */
        final Map<String, Post> posts = new LinkedHashMap<>();
        /** groesste Aenderungszeit (fuer ?since= ohne Websocket) */
        long seit;
        boolean geladen;
        volatile boolean zu;
        volatile long naechsteAbfrage;
        /** Zeile des Verlaufs -> Post-ID (null fuer Kopf, Tagestrenner, Leerzeilen) */
        List<String> zeilen = List.of();
        /** Post-ID -> eigene Reaktionen (Emoji-Namen) */
        Map<String, Set<String>> meine = Map.of();

        Chat(String kanal, String titel, Doc doc) {
            this.kanal = kanal;
            this.titel = titel;
            this.doc = doc;
        }
    }

    private final Editor ed;
    private HttpClient httpClient;
    private String httpStand;

    /** Client fuer diesen Dienst: ohne mattermost.proxy wie bisher, mit Eintrag ueber den Proxy (neu gebaut, wenn er sich aendert) */
    private synchronized HttpClient http() {
        String st = Netz.stand(ed.kf, "mattermost", false);
        if (httpClient == null || !st.equals(httpStand)) {
            httpClient = Netz.client(ed.kf, "mattermost", false, Duration.ofSeconds(15), HttpClient.Redirect.NORMAL);
            httpStand = st;
        }
        return httpClient;
    }
    private volatile String server, token, ich = "", ichId;
    /** "aus" (nicht eingerichtet), "verbinde", "ok", sonst Fehlertext */
    volatile String status = "aus";
    volatile List<Kanal> kanaele = List.of();
    volatile int ungelesen, erwaehnt;
    private final Map<String, String> namen = new ConcurrentHashMap<>();
    /** alle Personen aus mattermost-nutzer.tsv: {Benutzername, voller Name, ID} */
    private volatile List<String[]> nutzerListe = List.of();
    /** eigene Emojis des Servers (einmal je Sitzung geladen) */
    volatile List<String> eigeneEmojis = List.of();
    private volatile boolean emojisGeladen;
    /** Live-Updates ueber den Websocket laufen */
    volatile boolean live;
    volatile String wsFehler;
    private volatile java.net.http.WebSocket ws;
    private volatile boolean wsVerbindet, warLive;
    private volatile long wsNaechster;
    private int wsStufe, fehlerStufe;
    /** Anmeldung abgelehnt: keine Versuche mehr bis mmConnect */
    private volatile boolean gesperrt;
    private volatile boolean abgleichNoetig, chatNeu;
    private volatile long abgleichFrueh, letzterAbgleich;
    /** der gewaehlte Kanal (hoechstens einer) */
    private volatile Chat aktiv;
    /** Entwuerfe anderer Kanaele (beim Wechsel aufgehoben) */
    private final Map<String, String> entwuerfe = new HashMap<>();
    /** gezaehlte Anfragen an den Server (fuer mmStatus und Tests) */
    final java.util.concurrent.atomic.AtomicInteger anfragen = new java.util.concurrent.atomic.AtomicInteger();
    private Thread faden;
    private final Object wecker = new Object();
    private volatile boolean jetzt;
    private javax.swing.Timer gelesenUhr;
    private String gelesenKanal;

    Mattermost(Editor ed) {
        this.ed = ed;
    }

    boolean eingerichtet() {
        return !ed.kf.get("mattermost.server", "").isBlank();
    }

    // ================================================================ Verbindung
    /** beim Start und mit mmConnect: Einstellungen lesen, Personen aus der Datei, Abfrage-Faden starten */
    void starten() {
        server = ed.kf.get("mattermost.server", "").strip().replaceAll("/+$", "");
        if (server.isEmpty()) {
            status = "aus";
            return;
        }
        if (!server.startsWith("http")) server = "https://" + server;
        token = null;
        gesperrt = false;
        fehlerStufe = 0;
        wsStufe = 0;
        wsNaechster = 0;
        status = "verbinde";
        wsSchliessen();
        nutzerAusDatei();
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
            long warten;
            try {
                warten = durchlauf();
            } catch (RuntimeException e) {
                status = "Fehler: " + e;
                ed.protokoll("Mattermost", e);
                warten = zurueckfahren();
            }
            synchronized (wecker) {
                if (!jetzt && warten > 0) {
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

    /** Wartezeit nach einem Fehler: 5 s, 10 s, 20 s ... hoechstens 10 Minuten */
    private long zurueckfahren() {
        long s = Math.min(600, 5L << Math.min(fehlerStufe, 7));
        fehlerStufe++;
        SwingUtilities.invokeLater(ed.neuMalen);
        return s * 1000;
    }

    private int abstand() {
        return Math.max(30, ed.kf.zahl("mattermost.interval", 30));
    }

    /** ein Schritt der Schleife; gibt die Wartezeit bis zum naechsten in Millisekunden zurueck */
    long durchlauf() {
        if (server == null || server.isEmpty() || gesperrt) return 3_600_000;
        if (token == null) {
            int r = anmelden();
            if (r == 2) {
                gesperrt = true;
                SwingUtilities.invokeLater(ed.neuMalen);
                return 3_600_000;
            }
            if (r == 1) return zurueckfahren();
            abgleichNoetig = true;
            abgleichFrueh = 0;
            chatNeu = true;
            if (!emojisGeladen) emojisLaden();
            if (nutzerListe.isEmpty()) nutzerLaden();
        }
        long t = System.currentTimeMillis();
        if (abgleichNoetig && t >= abgleichFrueh) {
            abgleichNoetig = false;
            int r = abgleich();
            if (r == 401) {
                token = null;
                return 0;
            }
            if (r != 200) {
                abgleichNoetig = true;
                return zurueckfahren();
            }
            letzterAbgleich = System.currentTimeMillis();
        }
        Chat c = aktiv;
        if (c != null && c.zu) {
            aktiv = null;
            c = null;
        }
        if (c != null && (chatNeu || !live && t >= c.naechsteAbfrage)) {
            chatNeu = false;
            int r = chatLaden(c);
            if (r == 401) {
                token = null;
                return 0;
            }
            if (r != 200) {
                chatNeu = true;
                return zurueckfahren();
            }
            c.naechsteAbfrage = System.currentTimeMillis() + abstand() * 1000L;
        }
        fehlerStufe = 0;
        wsVerbinden();
        long jetztMs = System.currentTimeMillis();
        long periode = (live ? 600L : Math.max(60, ed.kf.zahl("mattermost.interval", 30))) * 1000L;
        long w = letzterAbgleich + periode - jetztMs;
        if (w <= 0) {
            abgleichNoetig = true;
            abgleichFrueh = 0;
            return 0;
        }
        if (abgleichNoetig) w = Math.min(w, Math.max(0, abgleichFrueh - jetztMs));
        if (c != null && !live) w = Math.min(w, Math.max(0, c.naechsteAbfrage - jetztMs));
        if (!live && ws == null && ed.kf.an("mattermost.live", true)) w = Math.min(w, Math.max(0, wsNaechster - jetztMs));
        return Math.max(200, w);
    }

    /** Abgleich der Kanaele bald (nach Ereignissen wie neuer Kanal): hoechstens einer je 30 Sekunden */
    void abgleichAnfordern(boolean sofort) {
        abgleichNoetig = true;
        abgleichFrueh = sofort ? 0 : Math.max(System.currentTimeMillis(), letzterAbgleich + 30_000);
        wecken();
    }

    record Antwort(int code, String text, String token) {}

    Antwort anfrage(String methode, String pfad, Object body) {
        String url = server + "/api/v4" + pfad;
        anfragen.incrementAndGet();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Accept", "application/json")
                    .header("X-Requested-With", "XMLHttpRequest");
            if (token != null) b.header("Authorization", "Bearer " + token);
            if (body != null) b.header("Content-Type", "application/json").method(methode, HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
            else b.method(methode, HttpRequest.BodyPublishers.noBody());
            HttpResponse<String> r = http().send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

    /** 0 angemeldet, 1 spaeter noch einmal (Netz, Server), 2 abgelehnt (keine weiteren Versuche) */
    private int anmelden() {
        String tk = ed.kf.get("mattermost.token", "").strip();
        if (!tk.isEmpty()) {
            token = tk;
        } else {
            String u = ed.kf.get("mattermost.user", "").strip(), pw = ed.kf.get("mattermost.password", "");
            if (u.isEmpty() || pw.isEmpty()) {
                status = "mattermost.user und mattermost.password (oder mattermost.token) fehlen";
                return 2;
            }
            Antwort a = anfrage("POST", "/users/login", Json.obj("login_id", u, "password", pw));
            if (a.code() != 200 || a.token() == null) {
                boolean abgelehnt = a.code() == 400 || a.code() == 401 || a.code() == 403;
                status = "Anmeldung: " + fehlerText(a) + (abgelehnt ? " - Leertaste m c versucht es erneut" : "");
                return abgelehnt ? 2 : 1;
            }
            token = a.token();
        }
        Antwort me = anfrage("GET", "/users/me", null);
        if (me.code() != 200) {
            boolean abgelehnt = me.code() == 401 || me.code() == 403;
            status = "Anmeldung: " + fehlerText(me) + (abgelehnt ? " - Leertaste m c versucht es erneut" : "");
            token = null;
            return abgelehnt ? 2 : 1;
        }
        Object j = Json.parse(me.text());
        ichId = Json.str(Json.get(j, "id"));
        ich = Json.str(Json.get(j, "username"));
        if (ichId != null && ich != null) namen.put(ichId, ich);
        return 0;
    }

    private void emojisLaden() {
        List<String> eig = new ArrayList<>();
        for (int seite = 0; seite < 10; seite++) {
            Antwort e = anfrage("GET", "/emoji?per_page=200&sort=name&page=" + seite, null);
            if (e.code() != 200) break;
            List<Object> l = Json.list(Json.parse(e.text()));
            for (Object x : l) if (Json.str(Json.get(x, "name")) != null) eig.add(Json.str(Json.get(x, "name")));
            if (l.size() < 200) break;
        }
        eigeneEmojis = eig;
        emojisGeladen = true;
    }

    // ================================================================ Websocket
    /** Ereignisse, nach denen die Kanalliste neu abgeglichen wird (selten) */
    private static final Set<String> KANAL_EREIGNISSE = Set.of("channel_created", "channel_updated", "channel_deleted", "channel_converted", "direct_added",
            "group_added", "user_added", "user_removed", "channel_member_updated", "added_to_team", "leave_team");

    /** Websocket aufbauen (mattermost.live = off: nur abfragen); nach Fehlschlaegen mit wachsendem Abstand */
    private void wsVerbinden() {
        if (ws != null || wsVerbindet || token == null || !ed.kf.an("mattermost.live", true) || System.currentTimeMillis() < wsNaechster) return;
        String u = ed.kf.get("mattermost.websocket", "").strip();
        if (u.isEmpty()) u = server.replaceFirst("^http", "ws") + "/api/v4/websocket";
        wsVerbindet = true;
        String tk = token;
        try {
            http().newWebSocketBuilder().header("Authorization", "Bearer " + tk).connectTimeout(Duration.ofSeconds(15)).buildAsync(URI.create(u), new Lauscher())
                    .whenComplete((w, e) -> {
                        wsVerbindet = false;
                        if (e != null) {
                            wsFehlschlag(e.getCause() != null ? e.getCause().toString() : e.toString());
                            return;
                        }
                        ws = w;
                        live = true;
                        wsFehler = null;
                        wsStufe = 0;
                        w.sendText(Json.write(Json.obj("seq", 1, "action", "authentication_challenge", "data", Json.obj("token", tk))), true);
                        // nach einer Unterbrechung: was dazwischen kam, einmal nachholen
                        if (warLive) {
                            abgleichAnfordern(true);
                            chatNeu = true;
                        }
                        warLive = true;
                        SwingUtilities.invokeLater(ed.neuMalen);
                    });
        } catch (IllegalArgumentException e) {
            wsVerbindet = false;
            wsFehlschlag(e.getMessage());
        }
    }

    private void wsFehlschlag(String grund) {
        ws = null;
        live = false;
        wsFehler = grund;
        wsNaechster = System.currentTimeMillis() + Math.min(600, 5L << Math.min(wsStufe, 7)) * 1000L;
        wsStufe++;
        wecken();
        SwingUtilities.invokeLater(ed.neuMalen);
    }

    private void wsSchliessen() {
        java.net.http.WebSocket w = ws;
        ws = null;
        live = false;
        warLive = false;
        if (w != null) w.abort();
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
            if (w == ws) wsFehlschlag("geschlossen (" + code + (reason == null || reason.isEmpty() ? "" : ": " + reason) + ")");
            return null;
        }

        @Override
        public void onError(java.net.http.WebSocket w, Throwable e) {
            if (w == ws) wsFehlschlag(e.toString());
        }
    }

    private static Object jsonText(Object o) {
        if (o instanceof String s && !s.isEmpty()) {
            try {
                return Json.parse(s);
            } catch (RuntimeException e) {
                return null;
            }
        }
        return o instanceof Map<?, ?> ? o : null;
    }

    /** Ereignis vom Websocket: mit seinem Inhalt einarbeiten, ohne neue Anfragen (ausser fuer unbekannte Namen) */
    void ereignis(String text) {
        Object j;
        try {
            j = Json.parse(text);
        } catch (RuntimeException e) {
            return;
        }
        String ev = Json.str(Json.get(j, "event"));
        if (ev == null) return;
        Object data = Json.get(j, "data");
        String kanal = Json.str(Json.get(j, "broadcast", "channel_id"));
        switch (ev) {
            case "posted", "post_edited", "post_deleted" -> {
                Object p = jsonText(Json.get(data, "post"));
                if (p == null) return;
                Post post = post(p);
                String ch = Json.str(Json.get(p, "channel_id"));
                String k = ch != null ? ch : kanal;
                boolean geloescht = ev.equals("post_deleted") || lang(Json.get(p, "delete_at")) > 0;
                boolean mich = false;
                Object erw = jsonText(Json.get(data, "mentions"));
                if (erw == null && Json.get(data, "mentions") instanceof String s && s.startsWith("[")) erw = Json.parse(s);
                for (Object x : Json.list(erw != null ? erw : Json.get(data, "mentions"))) mich |= String.valueOf(x).equals(ichId);
                if (post.user() != null && !namen.containsKey(post.user())) hintergrund(() -> {
                    namenHolen(List.of(post.user()));
                    SwingUtilities.invokeLater(() -> zeigenNeu(aktiv));
                });
                boolean m = mich;
                SwingUtilities.invokeLater(() -> beitrag(ev, k, post, geloescht, m));
            }
            case "reaction_added", "reaction_removed" -> {
                Object r = jsonText(Json.get(data, "reaction"));
                if (r == null) return;
                String post = Json.str(Json.get(r, "post_id")), user = Json.str(Json.get(r, "user_id")), emoji = Json.str(Json.get(r, "emoji_name"));
                SwingUtilities.invokeLater(() -> reaktion(ev.equals("reaction_added"), post, user, emoji));
            }
            case "channel_viewed" -> {
                String ch = Json.str(Json.get(data, "channel_id"));
                SwingUtilities.invokeLater(() -> zaehlerNull(ch));
            }
            case "multiple_channels_viewed" -> {
                Object zeiten = Json.get(data, "channel_times");
                if (zeiten instanceof Map<?, ?> zm) for (Object ch : zm.keySet()) SwingUtilities.invokeLater(() -> zaehlerNull(String.valueOf(ch)));
            }
            default -> {
                if (KANAL_EREIGNISSE.contains(ev)) abgleichAnfordern(false);
            }
        }
    }

    /** Post aus dem JSON von Mattermost */
    private Post post(Object p) {
        Set<String> re = new LinkedHashSet<>();
        for (Object r : Json.list(Json.get(p, "metadata", "reactions"))) {
            String en = Json.str(Json.get(r, "emoji_name"));
            if (en != null) re.add(Json.str(Json.get(r, "user_id")) + "|" + en);
        }
        return new Post(Json.str(Json.get(p, "id")), Json.str(Json.get(p, "user_id")), Json.str(Json.get(p, "message")), lang(Json.get(p, "create_at")),
                lang(Json.get(p, "update_at")), Json.str(Json.get(p, "type")), re);
    }

    /** neue, geaenderte oder geloeschte Nachricht (im Swing-Faden) */
    private void beitrag(String ev, String kanal, Post post, boolean geloescht, boolean mich) {
        Chat c = aktiv;
        boolean imChat = c != null && !c.zu && c.kanal.equals(kanal);
        if (imChat) {
            if (geloescht) c.posts.remove(post.id());
            else if (ev.equals("posted") || c.posts.containsKey(post.id())) {
                // bekannte Reaktionen behalten (geaenderte Nachrichten kommen ohne sie)
                Post alt = c.posts.get(post.id());
                c.posts.put(post.id(), alt != null && post.reaktionen().isEmpty() ? new Post(post.id(), post.user(), post.text(), post.zeit(), post.geaendert(), post.typ(),
                        alt.reaktionen()) : post);
            }
            c.seit = Math.max(c.seit, post.geaendert());
            zeigenNeu(c);
        }
        if (!ev.equals("posted") || geloescht) return;
        Kanal k = null;
        for (Kanal x : kanaele) if (x.id().equals(kanal)) k = x;
        if (k == null) {
            // unbekannter Kanal (neue Direktnachricht): Kanalliste bald neu
            abgleichAnfordern(false);
            return;
        }
        boolean meins = post.user() != null && post.user().equals(ichId);
        if (meins || imChat && sichtbar(c)) {
            if (imChat && !meins) gelesenBald(kanal);
            ersetzen(k.mit(k.ungelesen(), k.erwaehnt(), post.zeit()));
            return;
        }
        boolean erw = mich || "D".equals(k.art());
        ersetzen(k.mit(k.stumm() && !erw ? k.ungelesen() : k.ungelesen() + 1, k.erwaehnt() + (erw ? 1 : 0), post.zeit()));
        Kanal w = k;
        if (ed.meldung == null) ed.meldung = "Mattermost: neue Nachricht in " + w.anzeige() + " von @" + namen.getOrDefault(post.user(), "?") + " (Leertaste m u)";
        ed.neuMalen.run();
    }

    private void reaktion(boolean dazu, String post, String user, String emoji) {
        Chat c = aktiv;
        if (c == null || c.zu || post == null || emoji == null) return;
        Post p = c.posts.get(post);
        if (p == null) return;
        Set<String> re = new LinkedHashSet<>(p.reaktionen());
        if (dazu) re.add(user + "|" + emoji);
        else re.remove(user + "|" + emoji);
        c.posts.put(post, new Post(p.id(), p.user(), p.text(), p.zeit(), p.geaendert(), p.typ(), re));
        zeigenNeu(c);
    }

    /** Kanal in der Liste ersetzen und Summen neu zaehlen (im Swing-Faden) */
    private void ersetzen(Kanal k) {
        List<Kanal> l = new ArrayList<>();
        for (Kanal x : kanaele) l.add(x.id().equals(k.id()) ? k : x);
        kanaeleSetzen(l);
    }

    private void kanaeleSetzen(List<Kanal> l) {
        int u = 0, e = 0;
        for (Kanal k : l) {
            u += k.ungelesen();
            e += k.erwaehnt();
        }
        kanaele = l;
        ungelesen = u;
        erwaehnt = e;
        ed.neuMalen.run();
    }

    private void zaehlerNull(String kanal) {
        for (Kanal k : kanaele) if (k.id().equals(kanal) && (k.ungelesen() > 0 || k.erwaehnt() > 0)) ersetzen(k.mit(0, 0, k.letzte()));
    }

    // ================================================================ Abgleich der Kanaele
    /** Kanaele und Zaehler aller Teams (3 Anfragen je Team); HTTP-Code der ersten fehlgeschlagenen, sonst 200 */
    private int abgleich() {
        Antwort t = anfrage("GET", "/users/me/teams", null);
        if (t.code() != 200) {
            status = fehlerText(t);
            return t.code();
        }
        Map<String, Kanal> neu = new LinkedHashMap<>();
        Map<String, String> teamNamen = new HashMap<>();
        for (Object team : Json.list(Json.parse(t.text()))) {
            String tid = Json.str(Json.get(team, "id"));
            teamNamen.put(tid, Json.str(Json.get(team, "display_name")));
            Antwort k = anfrage("GET", "/users/me/teams/" + tid + "/channels", null);
            if (k.code() != 200) {
                status = fehlerText(k);
                return k.code();
            }
            Antwort m = anfrage("GET", "/users/me/teams/" + tid + "/channels/members", null);
            if (m.code() != 200) {
                status = fehlerText(m);
                return m.code();
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
                boolean stumm = "mention".equals(Json.str(Json.get(mg, "notify_props", "mark_unread")));
                if (stumm) ung = erw;
                neu.put(id, new Kanal(id, tid, Json.str(Json.get(c, "type")), Json.str(Json.get(c, "name")), Json.str(Json.get(c, "display_name")), ung, erw,
                        lang(Json.get(c, "last_post_at")), stumm));
            }
        }
        // Direktnachrichten: Name des anderen (aus der Personenliste, sonst einzeln nachfragen)
        List<String> fehlen = new ArrayList<>();
        for (Kanal k : neu.values()) {
            if (!"D".equals(k.art()) || k.name() == null) continue;
            String anderer = andererVon(k.name());
            if (anderer != null && !namen.containsKey(anderer)) fehlen.add(anderer);
        }
        namenHolen(fehlen);
        List<Kanal> l = new ArrayList<>();
        for (Kanal k : neu.values()) {
            String anz = anzeigename(k, teamNamen.size() > 1 ? teamNamen.get(k.team()) : null);
            l.add(new Kanal(k.id(), k.team(), k.art(), k.name(), anz, k.ungelesen(), k.erwaehnt(), k.letzte(), k.stumm()));
        }
        status = "ok";
        SwingUtilities.invokeLater(() -> {
            // der sichtbare Kanal bleibt gelesen
            Chat c = aktiv;
            List<Kanal> z = new ArrayList<>();
            for (Kanal k : l) z.add(c != null && !c.zu && sichtbar(c) && k.id().equals(c.kanal) && k.ungelesen() + k.erwaehnt() > 0 ? k.mit(0, 0, k.letzte()) : k);
            if (c != null && !c.zu && sichtbar(c)) for (Kanal k : l) if (k.id().equals(c.kanal) && k.ungelesen() + k.erwaehnt() > 0) gelesenBald(c.kanal);
            kanaeleSetzen(z);
            if (NEU_LADEN.equals(ed.meldung)) ed.meldung = "Mattermost: " + ungelesen + " ungelesen" + (erwaehnt > 0 ? ", " + erwaehnt + " Erwaehnungen" : "");
        });
        return 200;
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

    /** Namen einzelner unbekannter Personen (z. B. neu angelegt oder geloescht); die ganze Liste bleibt unberuehrt */
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

    // ================================================================ Chat (der eine gewaehlte Kanal)
    /** Nachrichten des Kanals: beim ersten Mal die letzten mattermost.history, danach nur Aenderungen (?since=) */
    private int chatLaden(Chat c) {
        boolean voll = !c.geladen;
        String q = voll ? "?per_page=" + Math.max(10, ed.kf.zahl("mattermost.history", 100)) : "?since=" + c.seit;
        Antwort a = anfrage("GET", "/channels/" + c.kanal + "/posts" + q, null);
        if (a.code() != 200) {
            status = fehlerText(a);
            return a.code();
        }
        Object j = Json.parse(a.text());
        List<Post> posts = new ArrayList<>();
        List<String> geloescht = new ArrayList<>();
        List<String> wer = new ArrayList<>();
        for (Object id : Json.list(Json.get(j, "order"))) {
            Object p = Json.get(j, "posts", String.valueOf(id));
            if (p == null) continue;
            if (lang(Json.get(p, "delete_at")) > 0) {
                geloescht.add(String.valueOf(id));
                continue;
            }
            Post post = post(p);
            posts.add(post);
            wer.add(post.user());
        }
        // im ?since-Modus stehen geloeschte nur in posts, nicht in order
        if (Json.get(j, "posts") instanceof Map<?, ?> alle) for (Object p : alle.values()) if (lang(Json.get(p, "delete_at")) > 0) geloescht.add(Json.str(Json.get(p, "id")));
        namenHolen(wer);
        SwingUtilities.invokeLater(() -> {
            if (c.zu || aktiv != c) return;
            if (voll) c.posts.clear();
            for (Post p : posts) {
                c.posts.put(p.id(), p);
                c.seit = Math.max(c.seit, p.geaendert());
            }
            for (String g : geloescht) c.posts.remove(g);
            c.geladen = true;
            zeigenNeu(c);
            if (sichtbar(c) && (!posts.isEmpty() || voll)) gelesenBald(c.kanal);
        });
        return 200;
    }

    /** Chat-Tab neu aufbauen (Verlauf aus den Nachrichten, Entwurf und Cursor bleiben) */
    private void zeigenNeu(Chat c) {
        if (c == null || c.zu || !ed.tabs.contains(c.doc)) return;
        List<Post> l = new ArrayList<>(c.posts.values());
        l.sort((x, y) -> Long.compare(x.zeit(), y.zeit()));
        // nicht endlos wachsen
        int max = Math.max(10, ed.kf.zahl("mattermost.history", 100)) * 3;
        if (l.size() > max) {
            for (Post p : l.subList(0, l.size() - max)) c.posts.remove(p.id());
            l = l.subList(l.size() - max, l.size());
        }
        List<String> zeilen = new ArrayList<>();
        String v = c.geladen ? verlauf(c, l, zeilen) : "# " + c.titel + "\n\n(wird geladen ...)\n\n";
        Map<String, Set<String>> meine = new HashMap<>();
        for (Post p : l) {
            Set<String> m = new LinkedHashSet<>();
            for (String r : p.reaktionen()) if (r.startsWith(ichId + "|")) m.add(r.substring(r.indexOf('|') + 1));
            meine.put(p.id(), m);
        }
        c.zeilen = zeilen;
        c.meine = meine;
        zeigen(c.doc, v);
        ed.neuMalen.run();
    }

    /** ist der Chat-Tab gerade sichtbar? */
    private boolean sichtbar(Chat c) {
        return !ed.tabs.isEmpty() && ed.d() == c.doc;
    }

    /** Lesestand an den Server melden, gebuendelt: hoechstens einmal je 10 Sekunden */
    private void gelesenBald(String kanal) {
        gelesenBald(kanal, false);
    }

    /** sofort: beim Waehlen eines Kanals (kurz verzoegert), sonst gebuendelt nach 10 Sekunden */
    private void gelesenBald(String kanal, boolean sofort) {
        if (!sofort && gelesenUhr != null && gelesenUhr.isRunning() && kanal.equals(gelesenKanal)) return;
        if (gelesenUhr != null) gelesenUhr.stop();
        gelesenKanal = kanal;
        zaehlerNull(kanal);
        gelesenUhr = new javax.swing.Timer(sofort ? 300 : 10_000, e -> hintergrund(() -> anfrage("POST", "/channels/members/me/view", Json.obj("channel_id", kanal))));
        gelesenUhr.setRepeats(false);
        gelesenUhr.start();
    }

    /** Chat-Tab wurde geschlossen: kein Kanal mehr gewaehlt, keine Nachrichten mehr */
    void geschlossen(Doc d) {
        Chat c = d.chat;
        if (c == null) return;
        c.zu = true;
        if (aktiv == c) aktiv = null;
    }

    private static final DateTimeFormatter UHR = DateTimeFormatter.ofPattern("HH:mm");

    /** Verlauf als Text: Kopfzeile, Tagestrenner, "HH:mm name: Text" (Folgezeilen eingerueckt), Reaktionen */
    String verlauf(Chat c, List<Post> posts, List<String> zeilen) {
        StringBuilder b = new StringBuilder("# " + c.titel + "   (Strg+Enter sendet, Leertaste m + reagiert, Leertaste m m wechselt den Kanal, :q schliesst)\n");
        zeilen.add(null);
        LocalDate tag = null;
        ZoneId zone = ZoneId.systemDefault();
        for (Post p : posts) {
            var zeit = Instant.ofEpochMilli(p.zeit()).atZone(zone);
            if (!zeit.toLocalDate().equals(tag)) {
                tag = zeit.toLocalDate();
                b.append("\n--- ").append(tag).append(" ---\n");
                zeilen.add(null);
                zeilen.add(null);
            }
            String wer = p.typ() != null && p.typ().startsWith("system_") ? "*" : namen.getOrDefault(p.user(), "?");
            String kopf = UHR.format(zeit) + " " + wer + ": ";
            String[] z = Emojis.ersetzen(p.text() == null ? "" : p.text()).split("\n", -1);
            b.append(kopf).append(z[0]).append('\n');
            zeilen.add(p.id());
            String ein = " ".repeat(6);
            for (int i = 1; i < z.length; i++) {
                b.append(ein).append(z[i]).append('\n');
                zeilen.add(p.id());
            }
            if (!p.reaktionen().isEmpty()) {
                Map<String, Integer> anzahl = new LinkedHashMap<>();
                Set<String> mein = new LinkedHashSet<>();
                for (String r : p.reaktionen()) {
                    String em = r.substring(r.indexOf('|') + 1);
                    anzahl.merge(em, 1, Integer::sum);
                    if (r.startsWith(ichId + "|")) mein.add(em);
                }
                List<String> r = new ArrayList<>();
                for (Map.Entry<String, Integer> e : anzahl.entrySet()) {
                    String zc = Emojis.zeichen(e.getKey());
                    // eigene Reaktion mit * (Leertaste m + mit demselben Emoji nimmt sie zurueck)
                    r.add((zc != null ? zc : ":" + e.getKey() + ":") + " " + e.getValue() + (mein.contains(e.getKey()) ? "*" : ""));
                }
                b.append(ein).append("[").append(String.join("  ", r)).append("]\n");
                zeilen.add(p.id());
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
        boolean war = d.geaendert;
        d.setText(verlauf + TRENN + "\n" + entwurf);
        int start = verlauf.length() + TRENN.length() + 1;
        if (leer) d.cursor = start + entwurf.length();
        else if (imEntwurf >= 0) d.cursor = Math.min(d.t.length(), start + imEntwurf);
        else d.cursor = Math.min(alt, verlauf.length());
        d.geaendert = war && !entwurf.isBlank();
    }

    /** Entwurf eines Chat-Tabs (Text unter der Trennlinie), null ohne Trennlinie */
    static String entwurf(Doc d) {
        String t = d.t.toString();
        int s = t.indexOf(TRENN);
        return s < 0 ? null : t.substring(Math.min(t.length(), s + TRENN.length() + 1));
    }

    /** Kanal waehlen: es gibt hoechstens einen Chat-Tab; ein anderer Kanal ersetzt den bisherigen (sein Entwurf bleibt aufgehoben) */
    void oeffnen(Kanal k) {
        Doc d = null;
        for (Doc t : ed.tabs) if (t.chat != null) d = t;
        if (d != null && d.chat.kanal.equals(k.id()) && !d.chat.zu) {
            ed.aktiv = ed.tabs.indexOf(d);
            if (k.ungelesen() + k.erwaehnt() > 0) gelesenBald(k.id(), true);
            return;
        }
        String entwurf = entwuerfe.getOrDefault(k.id(), "");
        if (d != null) {
            String e = entwurf(d);
            if (e != null && !e.isBlank()) entwuerfe.put(d.chat.kanal, e);
            else entwuerfe.remove(d.chat.kanal);
            d.chat.zu = true;
        } else {
            d = new Doc();
            Doc alt = ed.d();
            if (ed.tabs.size() == 1 && alt.pfad == null && alt.term == null && alt.chat == null && !alt.geaendert && alt.t.length() == 0) ed.tabs.set(0, d);
            else ed.tabs.add(d);
        }
        entwuerfe.remove(k.id());
        Chat c = new Chat(k.id(), k.anzeige(), d);
        d.chat = c;
        d.titel = k.anzeige();
        d.undo.clear();
        d.redo.clear();
        d.setText("# " + k.anzeige() + "\n\n(wird geladen ...)\n\n" + TRENN + "\n" + entwurf);
        d.cursor = d.t.length();
        d.oben = 0;
        d.geaendert = false;
        ed.aktiv = ed.tabs.indexOf(d);
        ed.befehl("switchState", "insert");
        aktiv = c;
        chatNeu = true;
        wecken();
        if (k.ungelesen() + k.erwaehnt() > 0) gelesenBald(k.id(), true);
    }

    void senden(Doc d) {
        if (d.chat == null) {
            ed.fehler("kein Chat-Tab (Leertaste m m waehlt einen Kanal)");
            return;
        }
        String e = entwurf(d);
        if (e == null) {
            ed.fehler("Trennlinie fehlt - Leertaste m r baut den Chat neu auf (der Entwurf geht dabei verloren)");
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
                    // Entwurf leeren; die Antwort ist die Nachricht selbst (der Websocket meldet sie ebenfalls)
                    String t = d.t.toString();
                    int s = t.indexOf(TRENN);
                    if (s >= 0) {
                        d.merken();
                        d.loeschen(s + TRENN.length() + 1, t.length() - (s + TRENN.length() + 1));
                        d.geaendert = false;
                    }
                    Post p = null;
                    try {
                        p = post(Json.parse(a.text()));
                    } catch (RuntimeException ex) {
                        // keine Nachricht in der Antwort
                    }
                    if (p != null && p.id() != null) {
                        if (aktiv == c && !c.zu) {
                            c.posts.put(p.id(), p);
                            c.seit = Math.max(c.seit, p.geaendert());
                            zeigenNeu(c);
                        }
                    } else if (!live) {
                        // ohne Websocket und ohne Nachricht in der Antwort: einmal die Aenderungen holen
                        chatNeu = true;
                        wecken();
                    }
                    ed.meldung = "gesendet an " + c.titel;
                } else {
                    ed.fehler("nicht gesendet: " + fehlerText(a));
                }
                ed.neuMalen.run();
            });
        });
    }

    // ================================================================ Personen (Datei)
    private Path nutzerDatei() {
        return ed.konfig.resolve("mattermost-nutzer.tsv");
    }

    /** Personenliste aus mattermost-nutzer.tsv (wenn sie zum Server passt) */
    private void nutzerAusDatei() {
        nutzerListe = List.of();
        try {
            if (!Files.exists(nutzerDatei())) return;
            List<String> z = Files.readAllLines(nutzerDatei(), StandardCharsets.UTF_8);
            if (z.isEmpty() || !z.get(0).equals("# " + server)) return;
            List<String[]> l = new ArrayList<>();
            for (String s : z.subList(1, z.size())) {
                String[] t = s.split("\t", -1);
                if (t.length < 3) continue;
                l.add(new String[] {t[1], t[2], t[0]});
                namen.put(t[0], t[1]);
            }
            nutzerListe = l;
        } catch (IOException e) {
            // ohne Datei: wird nach der Anmeldung einmal geladen
        }
    }

    /** alle aktiven Personen vom Server holen und in die Datei schreiben (einmal, sonst nur mit Leertaste m p) */
    private int nutzerLaden() {
        List<String[]> r = new ArrayList<>();
        for (int seite = 0; seite < 50; seite++) {
            Antwort a = anfrage("GET", "/users?active=true&per_page=200&page=" + seite, null);
            if (a.code() != 200) {
                if (seite == 0) return a.code();
                break;
            }
            List<Object> l = Json.list(Json.parse(a.text()));
            for (Object u : l) {
                String id = Json.str(Json.get(u, "id")), n = Json.str(Json.get(u, "username"));
                if (id == null || n == null || lang(Json.get(u, "delete_at")) > 0) continue;
                namen.put(id, n);
                String vor = Json.str(Json.get(u, "first_name")), nach = Json.str(Json.get(u, "last_name"));
                String voll = ((vor == null ? "" : vor) + " " + (nach == null ? "" : nach)).strip();
                if (voll.isEmpty() && Json.str(Json.get(u, "nickname")) != null) voll = Json.str(Json.get(u, "nickname"));
                r.add(new String[] {n, voll.replace('\t', ' '), id});
            }
            if (l.size() < 200) break;
        }
        r.sort((x, y) -> x[0].compareToIgnoreCase(y[0]));
        nutzerListe = r;
        StringBuilder b = new StringBuilder("# " + server + "\n");
        for (String[] u : r) b.append(u[2]).append('\t').append(u[0]).append('\t').append(u[1]).append('\n');
        try {
            Files.writeString(nutzerDatei(), b.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ed.protokoll("Mattermost: Personenliste nicht gespeichert", e);
        }
        return 200;
    }

    /** Personen ohne mich */
    private List<String[]> andere() {
        List<String[]> l = new ArrayList<>();
        for (String[] u : nutzerListe) if (ichId == null || !u[2].equals(ichId)) l.add(u);
        return l;
    }

    /** passender Mattermost-Benutzer zu einem SCM-Benutzer: gleicher Name, gleicher Mail-Anfang oder gleicher voller Name */
    String passend(String scm, String anzeige, String mail) {
        String lokal = mail == null || !mail.contains("@") ? null : mail.substring(0, mail.indexOf('@'));
        String umgedreht = anzeige != null && anzeige.contains(",") ? (anzeige.substring(anzeige.indexOf(',') + 1).strip() + " " + anzeige.substring(0, anzeige.indexOf(',')).strip()) : anzeige;
        for (String[] u : nutzerListe) if (u[0].equalsIgnoreCase(scm)) return u[0];
        if (lokal != null) for (String[] u : nutzerListe) if (u[0].equalsIgnoreCase(lokal)) return u[0];
        for (String[] u : nutzerListe) if (!u[1].isEmpty() && (u[1].equalsIgnoreCase(anzeige) || u[1].equalsIgnoreCase(umgedreht))) return u[0];
        return "";
    }

    /** Mattermost-Benutzer, deren Name oder voller Name den Text enthaelt (fuer das Personen-Formular) */
    List<String> vorschlaege(String q) {
        String s = q.strip().replaceFirst("^@", "").toLowerCase();
        List<String> r = new ArrayList<>();
        for (String[] u : nutzerListe) if (s.isEmpty() || u[0].toLowerCase().contains(s) || u[1].toLowerCase().contains(s)) r.add(u[0]);
        return r.size() > 50 ? r.subList(0, 50) : r;
    }

    /** private Nachricht an einen Benutzer senden (ohne Chat-Tab); fertig(null) = gesendet, sonst der Fehler */
    void direktSenden(String benutzer, String text, java.util.function.Consumer<String> fertig) {
        String name = benutzer.strip().replaceFirst("^@", "");
        if (!eingerichtet() || gesperrt || !"ok".equals(status)) {
            fertig.accept("Mattermost ist nicht verbunden (" + status + ")");
            return;
        }
        hintergrund(() -> {
            String id = null;
            for (String[] u : nutzerListe) if (u[0].equalsIgnoreCase(name)) id = u[2];
            if (id == null) {
                Antwort u = anfrage("GET", "/users/username/" + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8), null);
                if (u.code() == 200) id = Json.str(Json.get(Json.parse(u.text()), "id"));
            }
            String fehler = null;
            if (id == null) {
                fehler = "Mattermost-Benutzer " + name + " nicht gefunden";
            } else {
                Antwort k = anfrage("POST", "/channels/direct", List.of(ichId, id));
                if (k.code() != 200 && k.code() != 201) fehler = "Direktkanal: " + fehlerText(k);
                else {
                    Antwort p = anfrage("POST", "/posts", Json.obj("channel_id", Json.str(Json.get(Json.parse(k.text()), "id")), "message", text));
                    if (p.code() != 200 && p.code() != 201) fehler = "Nachricht: " + fehlerText(p);
                }
            }
            String f = fehler;
            SwingUtilities.invokeLater(() -> fertig.accept(f));
        });
    }

    static boolean nameZeichen(char c) {
        return Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-';
    }

    /** nach @ im Entwurf: Personen aus der Personenliste vorschlagen; false, wenn kein @-Wort */
    boolean erwaehnen(Doc d) {
        if (d.chat == null) return false;
        int anf = d.cursor;
        while (anf > 0 && nameZeichen(d.at(anf - 1))) anf--;
        if (anf == 0 || d.at(anf - 1) != '@') return false;
        List<Map<String, Object>> l = new ArrayList<>();
        for (String[] m : andere()) l.add(Json.obj("item", Json.obj("label", m[0], "filterText", m[0] + " " + m[1].toLowerCase(), "detail", m[1], "insertText", m[0] + " ")));
        l.add(Json.obj("item", Json.obj("label", "here", "filterText", "here", "detail", "alle, die gerade online sind", "insertText", "here ")));
        l.add(Json.obj("item", Json.obj("label", "channel", "filterText", "channel", "detail", "alle im Kanal", "insertText", "channel ")));
        l.add(Json.obj("item", Json.obj("label", "all", "filterText", "all", "detail", "alle im Kanal", "insertText", "all ")));
        if (nutzerListe.isEmpty()) ed.meldung = "Personenliste fehlt noch - Leertaste m p laedt sie";
        ed.vorschlaegeZeigen(l, anf);
        return true;
    }

    /** private Nachricht: Person aus der Personenliste waehlen, Direktkanal anlegen bzw. finden und waehlen */
    void direktStarten() {
        if (!bereit()) return;
        List<String[]> l = andere();
        if (l.isEmpty()) {
            ed.fehler("Personenliste ist leer - Leertaste m p laedt sie vom Server");
            return;
        }
        List<String> e = new ArrayList<>();
        Map<String, String[]> zu = new HashMap<>();
        for (String[] u : l) {
            String t = "@" + u[0] + (u[1].isEmpty() ? "" : "  (" + u[1] + ")");
            e.add(t);
            zu.put(t, u);
        }
        ed.wz.auswahl("Mattermost: private Nachricht an (" + e.size() + " Personen)", e, null, t -> direkt(zu.get(t)));
    }

    void direkt(String[] u) {
        if (u == null || u[2] == null) return;
        // gibt es den Direktkanal schon, ohne Anfrage
        for (Kanal k : kanaele) {
            if ("D".equals(k.art()) && k.name() != null && u[2].equals(andererVon(k.name()))) {
                oeffnen(k);
                return;
            }
        }
        ed.meldung = "Direktnachricht an @" + u[0] + " ...";
        hintergrund(() -> {
            Antwort a = anfrage("POST", "/channels/direct", List.of(ichId, u[2]));
            SwingUtilities.invokeLater(() -> {
                if (a.code() != 200 && a.code() != 201) {
                    ed.fehler("Direktnachricht nicht moeglich: " + fehlerText(a));
                    return;
                }
                Object c = Json.parse(a.text());
                namen.put(u[2], u[0]);
                Kanal k = new Kanal(Json.str(Json.get(c, "id")), Json.str(Json.get(c, "team_id")), "D", Json.str(Json.get(c, "name")), "@" + u[0], 0, 0,
                        lang(Json.get(c, "last_post_at")), false);
                List<Kanal> l = new ArrayList<>(kanaele);
                l.removeIf(x -> x.id().equals(k.id()));
                l.add(k);
                kanaeleSetzen(l);
                oeffnen(k);
                ed.meldung = "private Nachricht an @" + u[0] + ": unter die Trennlinie schreiben, Strg+Enter sendet";
                ed.neuMalen.run();
            });
        });
    }

    // ================================================================ Reaktionen
    /** Post der Cursorzeile im Chat (auch Folge- und Reaktionszeilen), sonst null */
    static String postBei(Doc d) {
        if (d.chat == null) return null;
        int z = d.zeileVon(d.cursor);
        List<String> l = d.chat.zeilen;
        return z < l.size() ? l.get(z) : null;
    }

    void reagierenStarten(Doc d) {
        if (d.chat == null) {
            ed.fehler("nur im Chat-Tab (Leertaste m m waehlt einen Kanal)");
            return;
        }
        String post = postBei(d);
        if (post == null) {
            ed.fehler("Cursor auf eine Nachricht stellen, dann Leertaste m +");
            return;
        }
        Chat c = d.chat;
        ed.emoji.oeffnen("Reaktion auf die Nachricht", e -> reagieren(d, c, post, e.name()));
    }

    /** Reaktion setzen oder, wenn sie schon von mir ist, zuruecknehmen */
    void reagieren(Doc d, Chat c, String post, String emoji) {
        boolean weg = c.meine.getOrDefault(post, Set.of()).contains(emoji);
        String zc = Emojis.zeichen(emoji);
        String anz = zc != null ? zc : ":" + emoji + ":";
        ed.meldung = (weg ? "Reaktion " + anz + " wird entfernt ..." : "Reaktion " + anz + " ...");
        hintergrund(() -> {
            Antwort a = weg ? anfrage("DELETE", "/users/" + ichId + "/posts/" + post + "/reactions/" + java.net.URLEncoder.encode(emoji, StandardCharsets.UTF_8), null)
                    : anfrage("POST", "/reactions", Json.obj("user_id", ichId, "post_id", post, "emoji_name", emoji));
            SwingUtilities.invokeLater(() -> {
                if (a.code() == 200 || a.code() == 201) {
                    ed.meldung = weg ? "Reaktion " + anz + " entfernt" : "reagiert mit " + anz;
                    reaktion(!weg, post, ichId, emoji);
                } else {
                    ed.fehler("Reaktion nicht gesetzt: " + fehlerText(a));
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
        if (gesperrt || !status.equals("ok")) return "MM !";
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
        /** @binding Mattermost: Kanal waehlen (ungelesene zuerst); nur dieser eine Kanal bekommt Nachrichten, ein anderer ersetzt ihn */
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
            Chat ak = aktiv;
            ed.wz.auswahl("Mattermost: Kanal waehlen (" + ungelesen + " ungelesen" + (ak != null && !ak.zu ? ", jetzt " + ak.titel : "") + ")", e, null, s -> oeffnen(zu.get(s)));
        });
        /** @binding Mattermost: private Nachricht an eine Person (aus der Personenliste; Direktkanal wird der gewaehlte Kanal) */
        c.put("mmDirect", a -> direktStarten());
        /** @binding Mattermost: Kanal mit ungelesenen Nachrichten waehlen */
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
        /** @binding Mattermost: auf die Nachricht unter dem Cursor mit einem Emoji reagieren (Auswahl; dasselbe Emoji noch einmal nimmt die Reaktion zurueck) */
        c.put("mmReact", a -> reagierenStarten(ed.d()));
        /** @binding Mattermost: Entwurf des Chat-Tabs senden (auch Strg+Enter im Chat) */
        c.put("mmSend", a -> senden(ed.d()));
        /** @binding Mattermost: Kanalliste und den gewaehlten Kanal jetzt neu laden */
        c.put("mmRefresh", a -> {
            if (!eingerichtet()) {
                ed.fehler("mattermost.server fehlt in config.txt");
                return;
            }
            Chat ak = aktiv;
            if (ak != null && !ak.zu) {
                ak.geladen = false;
                chatNeu = true;
            }
            ed.meldung = NEU_LADEN;
            abgleichAnfordern(true);
        });
        /** @binding Mattermost: Personenliste vom Server neu laden und in mattermost-nutzer.tsv speichern */
        c.put("mmUsersRefresh", a -> {
            if (!bereit()) return;
            ed.meldung = "Mattermost: Personenliste wird geladen ...";
            hintergrund(() -> {
                int r = nutzerLaden();
                SwingUtilities.invokeLater(() -> {
                    if (r == 200) ed.meldung = "Mattermost: " + nutzerListe.size() + " Personen in " + nutzerDatei().getFileName() + " gespeichert";
                    else ed.fehler("Personenliste nicht geladen: HTTP " + r);
                    ed.neuMalen.run();
                });
            });
        });
        /** @binding Mattermost: neu anmelden (nach Aenderungen an mattermost.* in config.txt oder nach einer abgelehnten Anmeldung) */
        c.put("mmConnect", a -> {
            ed.kf.lesen();
            starten();
            ed.meldung = eingerichtet() ? "Mattermost: verbinde mit " + server : "mattermost.server fehlt in config.txt";
        });
        /** @binding Mattermost: Verbindungsstand (Anmeldung, Websocket, Anfragen) zeigen */
        c.put("mmStatus", a -> {
            Chat ak = aktiv;
            ed.meldung = !eingerichtet() ? "Mattermost ist nicht eingerichtet"
                    : "Mattermost " + server + ": " + status + (ich.isEmpty() ? "" : " als " + ich) + ", Live-Updates "
                            + (live ? "an (Websocket)" : "aus" + (wsFehler != null ? " - " + wsFehler : "") + " (alle " + abstand() + " s abfragen)") + ", Kanal: "
                            + (ak != null && !ak.zu ? ak.titel : "keiner") + ", " + nutzerListe.size() + " Personen, " + anfragen.get() + " Anfragen seit dem Start";
        });
    }

    private boolean bereit() {
        if (!eingerichtet()) {
            ed.fehler("Mattermost ist nicht eingerichtet: mattermost.server, mattermost.user, mattermost.password in config.txt");
            return false;
        }
        if (gesperrt || !status.equals("ok")) {
            ed.fehler("Mattermost: " + (status.equals("verbinde") ? "verbinde noch ..." : status));
            if (!gesperrt) wecken();
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
