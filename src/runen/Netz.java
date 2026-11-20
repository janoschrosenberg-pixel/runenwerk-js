package runen;

import java.io.IOException;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP-Verbindungen mit Proxy. Fuer Verbindungen ins Internet (Update, Downloads): proxy in config.txt, sonst der
 * Proxy des Systems (macOS-Netzwerkeinstellungen, Windows-Interneteinstellungen, HTTPS_PROXY/HTTP_PROXY). Fuer
 * interne Dienste (mattermost, jira, scm) nur DIENST.proxy, sonst wie bisher ohne Aenderung.
 * Werte: host:port, http://host:port, http://benutzer:passwort@host:port, system, off.
 */
final class Netz {
    private Netz() {}

    /** Proxy; benutzer null ohne Anmeldung */
    record Proxy(String host, int port, String benutzer, String passwort, String herkunft) {
        String text() {
            return host + ":" + port + " (" + herkunft + ")";
        }
    }

    /** Konfiguration fuer statische Nutzer (Laden); vom Editor gesetzt */
    static volatile Konfig konfig;

    static {
        // Basic-Anmeldung beim Proxy auch fuer HTTPS-Tunnel erlauben (sonst schaltet Java sie ab)
        if (System.getProperty("jdk.http.auth.tunneling.disabledSchemes") == null) System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
    }

    private static final Pattern WERT = Pattern.compile("^(?:https?://)?(?:([^:@/]+)(?::([^@/]*))?@)?([^:/@]+)(?::(\\d+))?/?$");

    /** "host:port" usw. lesen; null bei leer, system oder off */
    static Proxy lesen(String w, String herkunft) {
        if (w == null) return null;
        w = w.strip();
        if (w.isEmpty() || w.equalsIgnoreCase("off") || w.equalsIgnoreCase("system") || w.equalsIgnoreCase("direct")) return null;
        Matcher m = WERT.matcher(w);
        if (!m.find()) return null;
        int port = m.group(4) == null ? 8080 : Integer.parseInt(m.group(4));
        String u = m.group(1) == null ? null : java.net.URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
        String p = m.group(2) == null ? null : java.net.URLDecoder.decode(m.group(2), StandardCharsets.UTF_8);
        return new Proxy(m.group(3), port, u, p, herkunft);
    }

    private static volatile Proxy system;
    private static volatile boolean systemGelesen;
    /** Hinweis, wenn das System eine automatische Konfiguration (PAC) nutzt, die Java nicht auswertet */
    static volatile String pacHinweis;

    /** Proxy des Systems fuer HTTPS (einmal gelesen); null = direkt */
    static Proxy system() {
        if (systemGelesen) return system;
        Proxy p = null;
        for (String v : new String[] {"HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy"}) {
            if (p == null) p = lesen(System.getenv(v), "Umgebung " + v);
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (p == null && os.contains("mac")) p = mac(befehl("scutil", "--proxy"));
            if (p == null && os.contains("win")) p = windows(befehl("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"));
        } catch (IOException | RuntimeException e) {
            // ohne Systemproxy
        }
        system = p;
        systemGelesen = true;
        return p;
    }

    private static String befehl(String... b) throws IOException {
        Process pr = new ProcessBuilder(b).redirectErrorStream(true).start();
        try {
            String t = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            pr.waitFor();
            return t;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    /** Ausgabe von scutil --proxy */
    static Proxy mac(String t) {
        String pac = wert(t, "ProxyAutoConfigURLString");
        if ("1".equals(wert(t, "ProxyAutoConfigEnable")) && pac != null) pacHinweis = pac;
        for (String art : new String[] {"HTTPS", "HTTP"}) {
            if ("1".equals(wert(t, art + "Enable")) && wert(t, art + "Proxy") != null) {
                String port = wert(t, art + "Port");
                return new Proxy(wert(t, art + "Proxy"), port == null ? 8080 : Integer.parseInt(port), null, null, "macOS-Einstellungen");
            }
        }
        return null;
    }

    private static String wert(String t, String k) {
        Matcher m = Pattern.compile("(?m)^\\s*" + k + "\\s*:\\s*(\\S+)\\s*$").matcher(t);
        return m.find() ? m.group(1) : null;
    }

    /** Ausgabe von reg query ...\Internet Settings */
    static Proxy windows(String t) {
        Matcher pac = Pattern.compile("(?m)^\\s*AutoConfigURL\\s+REG_SZ\\s+(\\S+)").matcher(t);
        if (pac.find()) pacHinweis = pac.group(1);
        Matcher an = Pattern.compile("(?m)^\\s*ProxyEnable\\s+REG_DWORD\\s+0x(\\d+)").matcher(t);
        if (!an.find() || an.group(1).equals("0")) return null;
        Matcher s = Pattern.compile("(?m)^\\s*ProxyServer\\s+REG_SZ\\s+(\\S+)").matcher(t);
        if (!s.find()) return null;
        String w = s.group(1);
        // "https=host:port;http=host:port" oder "host:port"
        Matcher h = Pattern.compile("(?:^|;)https=([^;]+)").matcher(w);
        if (h.find()) w = h.group(1);
        else if (w.contains("=")) {
            Matcher x = Pattern.compile("(?:^|;)http=([^;]+)").matcher(w);
            if (!x.find()) return null;
            w = x.group(1);
        }
        return lesen(w, "Windows-Einstellungen");
    }

    /**
     * Proxy fuer einen Dienst: DIENST.proxy, bei Verbindungen ins Internet sonst proxy, sonst der des Systems.
     * leer zurueck = keine Aenderung (interne Dienste ohne Eintrag), Proxy NO = ausdruecklich direkt
     */
    static Proxy fuer(Konfig kf, String dienst, boolean internet) {
        if (kf == null) return internet ? system() : null;
        String w = kf.get(dienst + ".proxy", "").strip();
        if (w.isEmpty() && internet) w = kf.get("proxy", "").strip();
        if (w.equalsIgnoreCase("off") || w.equalsIgnoreCase("direct")) return null;
        if (w.isEmpty() || w.equalsIgnoreCase("system")) return internet || w.equalsIgnoreCase("system") ? system() : null;
        return lesen(w, kf.get(dienst + ".proxy", "").isBlank() ? "proxy" : dienst + ".proxy");
    }

    /** HttpClient fuer einen Dienst (mit Proxy und Proxy-Anmeldung, falls eingestellt) */
    static HttpClient client(Konfig kf, String dienst, boolean internet, Duration verbinden, HttpClient.Redirect weiter) {
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(verbinden).followRedirects(weiter);
        String w = kf == null ? "" : kf.get(dienst + ".proxy", "").strip();
        if (w.isEmpty() && internet && kf != null) w = kf.get("proxy", "").strip();
        Proxy p = fuer(kf, dienst, internet);
        if (p != null) {
            b.proxy(ProxySelector.of(new InetSocketAddress(p.host(), p.port())));
            if (p.benutzer() != null) {
                b.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        if (getRequestorType() != RequestorType.PROXY) return null;
                        return new PasswordAuthentication(p.benutzer(), p.passwort() == null ? new char[0] : p.passwort().toCharArray());
                    }
                });
            }
        } else if (w.equalsIgnoreCase("off") || w.equalsIgnoreCase("direct")) {
            b.proxy(HttpClient.Builder.NO_PROXY);
        }
        return b.build();
    }

    /** Kennung der Einstellung, damit ein zwischengespeicherter Client nach einer Aenderung neu gebaut wird */
    static String stand(Konfig kf, String dienst, boolean internet) {
        if (kf == null) return "";
        return kf.get(dienst + ".proxy", "") + "|" + (internet ? kf.get("proxy", "") : "");
    }

    /** "ueber Proxy host:port (Herkunft)" oder "direkt" */
    static String weg(Konfig kf, String dienst, boolean internet) {
        Proxy p = fuer(kf, dienst, internet);
        return p == null ? "direkt" : "ueber Proxy " + p.text();
    }

    /** verstaendlicher Fehlertext fuer eine fehlgeschlagene Verbindung */
    static String fehler(Throwable e, String url, Konfig kf, String dienst, boolean internet) {
        Throwable u = e;
        while (u.getCause() != null && u.getMessage() == null) u = u.getCause();
        String host = url;
        try {
            host = URI.create(url).getHost();
        } catch (RuntimeException ex) {
            // ganze Adresse
        }
        String weg = weg(kf, dienst, internet);
        String hinweis = internet ? "Proxy? proxy = host:port in config.txt" : dienst + ".proxy = host:port in config.txt, falls ein Proxy noetig ist";
        if (pacHinweis != null && fuer(kf, dienst, internet) == null) hinweis += " (das System nutzt eine automatische Proxy-Konfiguration " + pacHinweis
                + ", die Java nicht auswertet - den Proxy daraus als proxy eintragen)";
        // genauerer Grund weiter unten in der Kette (Name unbekannt, Proxy-Anmeldung)
        for (Throwable x = e; x != null; x = x.getCause()) {
            if (x instanceof UnknownHostException || x instanceof java.nio.channels.UnresolvedAddressException) {
                return host + " ist unbekannt (" + weg + ", Name nicht aufloesbar) - " + hinweis;
            }
            String xm = x.getMessage();
            if (xm != null && xm.contains("authentication attempts")) {
                return "der Proxy lehnt die Anmeldung ab (" + weg + ") - Benutzer und Passwort in proxy pruefen (http://benutzer:passwort@host:port)";
            }
        }
        if (u instanceof HttpConnectTimeoutException || u instanceof ConnectException || e instanceof ConnectException) {
            return "keine Verbindung zu " + host + " (" + weg + ") - " + hinweis;
        }
        if (u instanceof UnknownHostException) return host + " ist unbekannt (" + weg + ", DNS) - " + hinweis;
        if (u instanceof HttpTimeoutException) return "keine Antwort von " + host + " (" + weg + ")";
        if (u instanceof javax.net.ssl.SSLException) return "TLS-Fehler mit " + host + " (" + weg + "): " + u.getMessage()
                + " - steht ein Firmen-Proxy mit eigenem Zertifikat dazwischen, muss es in den Java-Truststore";
        String m = u.getMessage();
        return (m == null ? u.getClass().getSimpleName() : m) + " (" + host + ", " + weg + ")";
    }
}
