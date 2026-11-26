package runen;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Kleiner Mailclient fuer Microsoft Exchange ueber EWS (Exchange Web Services, SOAP ueber HTTPS): beim Start
 * anmelden (mail.server, mail.user, mail.password in config.txt; Anmeldung NTLM oder Basic, mail.token als Bearer),
 * ungelesene Mails im Posteingang oben in der Tab-Leiste, Posteingang und Mails als Puffer, Antworten, Weiterleiten,
 * Loeschen (in Geloeschte Elemente) und neue Mails mit Strg+Enter senden. Eine abgelehnte Anmeldung wird nicht
 * wiederholt (sonst sperrt das Active Directory das Konto), bis Leertaste e c neu verbindet.
 */
final class Mail {
    /** Trennlinie zwischen Kopf (An, Cc, Betreff) und Text einer neuen Mail */
    static final String TRENN = "------------------------ Text (Strg+Enter sendet) ------------------------";

    /** Mail in der Liste */
    record Eintrag(String id, String ck, String von, String vonAdresse, String betreff, String zeit, boolean gelesen, boolean anhang) {}

    /** ganze Mail */
    record Nachricht(Eintrag e, List<String> an, List<String> cc, String text) {}

    /** Mail-Puffer: "eingang" (Liste), "nachricht" (eine Mail) oder "entwurf" (neue Mail) */
    static final class Puffer {
        final String art;
        /** Zeile der Liste -> Mail (nur eingang) */
        List<Eintrag> zeilen = List.of();
        /** die angezeigte Mail (nur nachricht) */
        Nachricht nachricht;

        Puffer(String art) {
            this.art = art;
        }
    }

    private final Editor ed;
    /** "aus", "verbinde", "ok" oder Fehlertext */
    volatile String status = "aus";
    volatile int ungelesen, gesamt;
    private volatile boolean gesperrt;
    private Thread faden;
    private final Object wecker = new Object();
    private volatile boolean jetzt;
    private int fehlerStufe;
    final java.util.concurrent.atomic.AtomicInteger anfragen = new java.util.concurrent.atomic.AtomicInteger();

    Mail(Editor ed) {
        this.ed = ed;
    }

    boolean eingerichtet() {
        return !ed.kf.get("mail.server", "").isBlank();
    }

    /** EWS-Adresse: mail.server als ganze URL oder Rechnername (dann https://NAME/EWS/Exchange.asmx) */
    String adresse() {
        String s = ed.kf.get("mail.server", "").strip();
        if (s.isEmpty()) return null;
        if (!s.startsWith("http")) s = "https://" + s;
        if (!s.toLowerCase().contains("/ews/")) s = s.replaceAll("/+$", "") + "/EWS/Exchange.asmx";
        return s;
    }

    // ================================================================ SOAP
    record Antwort(int code, Document xml, String fehler) {}

    private static final String KOPF = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:t=\"http://schemas.microsoft.com/exchange/services/2006/types\""
            + " xmlns:m=\"http://schemas.microsoft.com/exchange/services/2006/messages\"><soap:Header><t:RequestServerVersion Version=\"Exchange2010_SP2\"/></soap:Header><soap:Body>";
    private static final String FUSS = "</soap:Body></soap:Envelope>";

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** EWS-Anfrage (Inhalt von soap:Body); NTLM/Basic ueber den Authenticator, Bearer mit mail.token */
    Antwort ews(String inhalt) {
        String url = adresse();
        anfragen.incrementAndGet();
        try {
            Netz.Proxy p = Netz.fuer(ed.kf, "mail", false);
            HttpURLConnection c = (HttpURLConnection) (p == null ? URI.create(url).toURL().openConnection()
                    : URI.create(url).toURL().openConnection(new java.net.Proxy(java.net.Proxy.Type.HTTP, new InetSocketAddress(p.host(), p.port()))));
            c.setConnectTimeout(15_000);
            c.setReadTimeout(60_000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
            c.setRequestProperty("Accept", "text/xml");
            String tk = ed.kf.get("mail.token", "").strip();
            if (!tk.isEmpty()) {
                c.setRequestProperty("Authorization", "Bearer " + tk);
            } else {
                String u = ed.kf.get("mail.user", "").strip(), pw = ed.kf.get("mail.password", "");
                c.setAuthenticator(new Authenticator() {
                    private int versuche;

                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        // nur einmal je Anfrage: falsche Daten nicht wiederholt schicken (Kontosperre)
                        if (getRequestorType() != RequestorType.SERVER || versuche++ > 0) return null;
                        return new PasswordAuthentication(u, pw.toCharArray());
                    }
                });
            }
            byte[] b = (KOPF + inhalt + FUSS).getBytes(StandardCharsets.UTF_8);
            try (OutputStream o = c.getOutputStream()) {
                o.write(b);
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] r = in == null ? new byte[0] : in.readAllBytes();
            if (code == 401) return new Antwort(401, null, "Anmeldung abgelehnt (HTTP 401) - mail.user (DOMAENE\\name oder name@firma.de) und mail.password pruefen");
            Document d = null;
            if (r.length > 0) {
                try {
                    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                    f.setNamespaceAware(true);
                    f.setExpandEntityReferences(false);
                    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                    d = f.newDocumentBuilder().parse(new ByteArrayInputStream(r));
                } catch (Exception e) {
                    // kein XML (z. B. HTML-Fehlerseite)
                }
            }
            if (code != 200) {
                String f = d != null ? text(d.getDocumentElement(), "faultstring") : null;
                return new Antwort(code, d, "HTTP " + code + (f != null ? ": " + f : ""));
            }
            if (d == null) return new Antwort(code, null, "keine EWS-Antwort (ist " + url + " die EWS-Adresse?)");
            // ResponseClass="Error" in einer ResponseMessage
            for (Element m : alle(d.getDocumentElement(), "*")) {
                if ("Error".equals(m.getAttribute("ResponseClass"))) return new Antwort(500, d, "EWS: " + nz(text(m, "MessageText"), m.getLocalName()));
            }
            return new Antwort(200, d, null);
        } catch (IOException | IllegalArgumentException e) {
            return new Antwort(-1, null, Netz.fehler(e, url == null ? "" : url, ed.kf, "mail", false));
        }
    }

    private static String nz(String s, String sonst) {
        return s == null || s.isBlank() ? sonst : s;
    }

    /** alle Nachkommen mit lokalem Namen (beliebiger Namensraum) */
    static List<Element> alle(Node n, String name) {
        List<Element> r = new ArrayList<>();
        NodeList l = n instanceof Document d ? d.getElementsByTagNameNS("*", name) : ((Element) n).getElementsByTagNameNS("*", name);
        for (int i = 0; i < l.getLength(); i++) r.add((Element) l.item(i));
        return r;
    }

    static Element erstes(Node n, String name) {
        List<Element> l = alle(n, name);
        return l.isEmpty() ? null : l.get(0);
    }

    static String text(Node n, String name) {
        Element e = n == null ? null : erstes(n, name);
        return e == null ? null : e.getTextContent();
    }

    // ================================================================ Abfrage
    /** beim Start und mit mailConnect */
    void starten() {
        if (!eingerichtet()) {
            status = "aus";
            return;
        }
        gesperrt = false;
        fehlerStufe = 0;
        status = "verbinde";
        if (faden == null) {
            faden = new Thread(this::schleife, "mail");
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
                ed.protokoll("Mail", e);
                warten = zurueck();
            }
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

    private long zurueck() {
        long s = Math.min(600, 5L << Math.min(fehlerStufe, 7));
        fehlerStufe++;
        SwingUtilities.invokeLater(ed.neuMalen);
        return s * 1000;
    }

    /** Posteingang zaehlen (eine kleine Anfrage); bei neuen Mails die neueste melden */
    long durchlauf() {
        if (!eingerichtet() || gesperrt) return 3_600_000;
        Antwort a = ews("<m:GetFolder><m:FolderShape><t:BaseShape>Default</t:BaseShape></m:FolderShape><m:FolderIds><t:DistinguishedFolderId Id=\"inbox\"/></m:FolderIds></m:GetFolder>");
        if (a.code() == 401) {
            gesperrt = true;
            status = a.fehler() + " - Leertaste e c versucht es erneut";
            SwingUtilities.invokeLater(ed.neuMalen);
            return 3_600_000;
        }
        if (a.code() != 200) {
            status = a.fehler();
            return zurueck();
        }
        int u = zahl(text(a.xml(), "UnreadCount")), g = zahl(text(a.xml(), "TotalCount"));
        int vorher = ungelesen;
        boolean erstes = !"ok".equals(status);
        ungelesen = u;
        gesamt = g;
        status = "ok";
        fehlerStufe = 0;
        if (!erstes && u > vorher) {
            List<Eintrag> neu = liste(1, true);
            if (!neu.isEmpty()) {
                Eintrag e = neu.get(0);
                SwingUtilities.invokeLater(() -> {
                    if (ed.meldung == null) ed.meldung = "Neue Mail von " + e.von() + ": " + e.betreff() + " (Leertaste e e)";
                });
            }
        }
        SwingUtilities.invokeLater(ed.neuMalen);
        return Math.max(30, ed.kf.zahl("mail.interval", 60)) * 1000L;
    }

    private static int zahl(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Text fuer die Tab-Leiste oben (null, wenn nicht eingerichtet) */
    String anzeige() {
        if (!eingerichtet()) return null;
        if (status.equals("verbinde")) return "Mail ...";
        if (gesperrt || !status.equals("ok")) return "Mail !";
        return ungelesen == 0 ? "Mail" : "Mail " + ungelesen + " neu";
    }

    // ================================================================ Posteingang
    private static final String FELDER = "<t:AdditionalProperties><t:FieldURI FieldURI=\"item:Subject\"/><t:FieldURI FieldURI=\"item:DateTimeReceived\"/>"
            + "<t:FieldURI FieldURI=\"message:From\"/><t:FieldURI FieldURI=\"message:IsRead\"/><t:FieldURI FieldURI=\"item:HasAttachments\"/></t:AdditionalProperties>";

    /** die neuesten Mails im Posteingang (nurUngelesen: nur ungelesene) */
    List<Eintrag> liste(int anzahl, boolean nurUngelesen) {
        String filter = nurUngelesen ? "<m:Restriction><t:IsEqualTo><t:FieldURI FieldURI=\"message:IsRead\"/><t:FieldURIOrConstant><t:Constant Value=\"false\"/></t:FieldURIOrConstant></t:IsEqualTo></m:Restriction>" : "";
        Antwort a = ews("<m:FindItem Traversal=\"Shallow\"><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape>" + FELDER + "</m:ItemShape>"
                + "<m:IndexedPageItemView MaxEntriesReturned=\"" + anzahl + "\" Offset=\"0\" BasePoint=\"Beginning\"/>" + filter
                + "<m:SortOrder><t:FieldOrder Order=\"Descending\"><t:FieldURI FieldURI=\"item:DateTimeReceived\"/></t:FieldOrder></m:SortOrder>"
                + "<m:ParentFolderIds><t:DistinguishedFolderId Id=\"inbox\"/></m:ParentFolderIds></m:FindItem>");
        if (a.code() != 200) throw new IllegalStateException(a.fehler());
        List<Eintrag> r = new ArrayList<>();
        for (Element m : alle(a.xml(), "Message")) r.add(eintrag(m));
        return r;
    }

    private static Eintrag eintrag(Element m) {
        Element id = erstes(m, "ItemId");
        Element von = erstes(m, "From");
        return new Eintrag(id == null ? null : id.getAttribute("Id"), id == null ? null : id.getAttribute("ChangeKey"), nz(text(von, "Name"), nz(text(von, "EmailAddress"), "?")),
                text(von, "EmailAddress"), nz(text(m, "Subject"), "(kein Betreff)"), text(m, "DateTimeReceived"), "true".equals(text(m, "IsRead")),
                "true".equals(text(m, "HasAttachments")));
    }

    private static final DateTimeFormatter HEUTE = DateTimeFormatter.ofPattern("HH:mm"), TAG = DateTimeFormatter.ofPattern("dd.MM. HH:mm"),
            JAHR = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    static String zeit(String iso) {
        if (iso == null) return "";
        try {
            ZonedDateTime z = Instant.parse(iso).atZone(ZoneId.systemDefault());
            if (z.toLocalDate().equals(LocalDate.now())) return "heute " + HEUTE.format(z);
            if (z.getYear() == LocalDate.now().getYear()) return TAG.format(z);
            return JAHR.format(z);
        } catch (RuntimeException e) {
            return iso;
        }
    }

    private Doc puffer(String titel, Puffer p, boolean lesen) {
        Doc d = null;
        for (int i = 0; i < ed.tabs.size(); i++) {
            Doc t = ed.tabs.get(i);
            if (titel.equals(t.titel) && t.mail != null && t.mail.art.equals(p.art) && !p.art.equals("entwurf")) {
                ed.aktiv = i;
                d = t;
            }
        }
        if (d == null) {
            d = new Doc();
            d.titel = titel;
            Doc alt = ed.d();
            if (ed.tabs.size() == 1 && alt.pfad == null && alt.term == null && alt.chat == null && !alt.geaendert && alt.t.length() == 0) ed.tabs.set(0, d);
            else ed.tabs.add(d);
            ed.aktiv = ed.tabs.indexOf(d);
        }
        d.mail = p;
        d.art = "mail";
        d.nurLesen = lesen;
        ed.modus = lesen && ed.km.modeDa("nurlesen") ? "nurlesen" : "normal";
        return d;
    }

    private static void fuellen(Doc d, String text, int cursor) {
        boolean l = d.nurLesen;
        d.nurLesen = false;
        d.setText(text);
        d.nurLesen = l;
        d.geaendert = false;
        d.cursor = Math.max(0, Math.min(cursor, d.t.length()));
        d.oben = 0;
    }

    void eingang() {
        if (!bereit()) return;
        Doc d = puffer("Mail: Posteingang", new Puffer("eingang"), true);
        if (d.t.length() == 0) fuellen(d, "# Posteingang\n\n(wird geladen ...)\n", 0);
        ed.meldung = "Posteingang wird geladen ...";
        Thread t = new Thread(() -> {
            List<Eintrag> l;
            String fehler = null;
            try {
                l = liste(Math.max(10, ed.kf.zahl("mail.count", 50)), false);
            } catch (RuntimeException e) {
                l = List.of();
                fehler = e.getMessage();
            }
            List<Eintrag> liste = l;
            String f = fehler;
            SwingUtilities.invokeLater(() -> {
                if (!ed.tabs.contains(d)) return;
                if (f != null) {
                    fuellen(d, "# Posteingang\n\nFehler: " + f + "\n", 0);
                    ed.fehler(f);
                    return;
                }
                StringBuilder b = new StringBuilder("# Posteingang (" + ungelesen + " ungelesen von " + gesamt + ")   ENTER oeffnet, Leertaste e: n neu, a antworten, d loeschen, r neu laden\n\n");
                List<Eintrag> zeilen = new ArrayList<>(java.util.Arrays.asList((Eintrag) null, null));
                int bw = 8;
                for (Eintrag e : liste) bw = Math.max(bw, Math.min(28, e.von().length()));
                for (Eintrag e : liste) {
                    String z = zeit(e.zeit());
                    String von = e.von().length() > bw ? e.von().substring(0, bw - 1) + "~" : e.von();
                    b.append(e.gelesen() ? "  " : "* ").append(z).append(" ".repeat(Math.max(1, 13 - z.length()))).append(von).append(" ".repeat(bw - von.length() + 2))
                            .append(e.betreff()).append(e.anhang() ? "  [Anhang]" : "").append('\n');
                    zeilen.add(e);
                }
                if (liste.isEmpty()) b.append("(keine Mails)\n");
                d.mail.zeilen = zeilen;
                int c = d.cursor;
                fuellen(d, b.toString(), c);
                // Cursor auf die erste Mail (oder wo er war, wenn er schon in der Liste stand)
                if (!liste.isEmpty() && d.zeileVon(d.cursor) < 2) d.cursor = d.anf(2);
                ed.meldung = liste.size() + " Mails, " + ungelesen + " ungelesen";
                ed.neuMalen.run();
            });
        }, "mail");
        t.setDaemon(true);
        t.start();
    }

    /** Mail der Cursorzeile im Posteingang bzw. die angezeigte */
    Eintrag eintragBei(Doc d) {
        if (d.mail == null) return null;
        if (d.mail.nachricht != null) return d.mail.nachricht.e();
        int z = d.zeileVon(d.cursor);
        return z < d.mail.zeilen.size() ? d.mail.zeilen.get(z) : null;
    }

    /** ENTER im Posteingang: Mail oeffnen; false, wenn kein Mail-Puffer */
    boolean springen(Doc d) {
        if (d.mail == null || !d.mail.art.equals("eingang")) return false;
        Eintrag e = eintragBei(d);
        if (e != null) oeffnen(e);
        return true;
    }

    // ================================================================ Lesen
    void oeffnen(Eintrag e) {
        ed.meldung = "Mail wird geladen ...";
        Thread t = new Thread(() -> {
            Antwort a = ews("<m:GetItem><m:ItemShape><t:BaseShape>AllProperties</t:BaseShape><t:BodyType>Text</t:BodyType></m:ItemShape><m:ItemIds><t:ItemId Id=\""
                    + esc(e.id()) + "\" ChangeKey=\"" + esc(e.ck()) + "\"/></m:ItemIds></m:GetItem>");
            if (a.code() != 200) {
                SwingUtilities.invokeLater(() -> ed.fehler("Mail nicht geladen: " + a.fehler()));
                return;
            }
            Element m = erstes(a.xml(), "Message");
            if (m == null) {
                SwingUtilities.invokeLater(() -> ed.fehler("Mail nicht geladen: keine Nachricht in der Antwort"));
                return;
            }
            Eintrag voll = eintrag(m);
            Nachricht n = new Nachricht(voll, empfaenger(m, "ToRecipients"), empfaenger(m, "CcRecipients"), nz(text(m, "Body"), "").replace("\r", ""));
            // gelesen markieren
            if (!e.gelesen()) {
                Element id = erstes(m, "ItemId");
                ews("<m:UpdateItem MessageDisposition=\"SaveOnly\" ConflictResolution=\"AutoResolve\"><m:ItemChanges><t:ItemChange><t:ItemId Id=\"" + esc(id.getAttribute("Id"))
                        + "\" ChangeKey=\"" + esc(id.getAttribute("ChangeKey")) + "\"/><t:Updates><t:SetItemField><t:FieldURI FieldURI=\"message:IsRead\"/><t:Message><t:IsRead>true</t:IsRead>"
                        + "</t:Message></t:SetItemField></t:Updates></t:ItemChange></m:ItemChanges></m:UpdateItem>");
                ungelesen = Math.max(0, ungelesen - 1);
            }
            SwingUtilities.invokeLater(() -> zeigen(n));
        }, "mail");
        t.setDaemon(true);
        t.start();
    }

    private static List<String> empfaenger(Element m, String feld) {
        List<String> r = new ArrayList<>();
        Element f = erstes(m, feld);
        if (f == null) return r;
        for (Element mb : alle(f, "Mailbox")) {
            String name = text(mb, "Name"), adr = text(mb, "EmailAddress");
            r.add(name != null && adr != null && !name.equals(adr) ? name + " <" + adr + ">" : nz(adr, name));
        }
        return r;
    }

    private void zeigen(Nachricht n) {
        Puffer p = new Puffer("nachricht");
        p.nachricht = n;
        String betreff = n.e().betreff();
        Doc d = puffer("Mail: " + (betreff.length() > 30 ? betreff.substring(0, 29) + "~" : betreff), p, true);
        StringBuilder b = new StringBuilder("# " + betreff + "\n\n");
        b.append("Von: ").append(n.e().von()).append(n.e().vonAdresse() != null && !n.e().vonAdresse().equals(n.e().von()) ? " <" + n.e().vonAdresse() + ">" : "").append('\n');
        if (!n.an().isEmpty()) b.append("An: ").append(String.join(", ", n.an())).append('\n');
        if (!n.cc().isEmpty()) b.append("Cc: ").append(String.join(", ", n.cc())).append('\n');
        b.append("Datum: ").append(zeit(n.e().zeit())).append(n.e().anhang() ? "   (mit Anhang - im Outlook oeffnen)" : "").append("\n\n");
        b.append(n.text().strip()).append('\n');
        fuellen(d, b.toString(), 0);
        ed.meldung = "Leertaste e: a antworten, A allen antworten, w weiterleiten, d loeschen";
        // Liste: gelesen-Markierung auffrischen
        for (Doc t : ed.tabs) {
            if (t.mail != null && t.mail.art.equals("eingang")) {
                for (int z = 0; z < t.mail.zeilen.size(); z++) {
                    Eintrag e = t.mail.zeilen.get(z);
                    if (e != null && e.id() != null && e.id().equals(n.e().id()) && !e.gelesen() && t.zeile(z).startsWith("* ")) {
                        boolean l = t.nurLesen;
                        t.nurLesen = false;
                        t.ersetzen(t.anf(z), t.anf(z) + 2, "  ");
                        t.nurLesen = l;
                        t.geaendert = false;
                    }
                }
            }
        }
        ed.neuMalen.run();
    }

    // ================================================================ Schreiben
    /** neue Mail: An, Cc, Betreff, Trennlinie, Text; Strg+Enter sendet */
    void entwurf(String an, String cc, String betreff, String text) {
        Doc d = puffer("Mail: neu", new Puffer("entwurf"), false);
        String kopf = "An: " + an + "\nCc: " + cc + "\nBetreff: " + betreff + "\n" + TRENN + "\n";
        fuellen(d, kopf + text, 0);
        // Cursor: leeres An, sonst an den Anfang des Textes
        d.cursor = an.isBlank() ? 4 : kopf.length();
        ed.befehl("switchState", "insert");
        ed.meldung = "Mail schreiben: Strg+Enter (oder Leertaste e s) sendet; Adressen mit Komma trennen";
    }

    private static String zitat(Nachricht n) {
        StringBuilder b = new StringBuilder("\n\n----- Urspruengliche Nachricht -----\nVon: " + n.e().von() + "\nGesendet: " + zeit(n.e().zeit()) + "\nBetreff: " + n.e().betreff() + "\n\n");
        for (String z : n.text().strip().split("\n", -1)) b.append("> ").append(z).append('\n');
        return b.toString();
    }

    private static String vorsilbe(String v, String betreff) {
        return betreff.matches("(?i)^(" + v + "|AW|RE|WG|FW|FWD):.*") && betreff.toUpperCase().startsWith(v.toUpperCase() + ":") ? betreff : v + ": " + betreff;
    }

    private Nachricht offene() {
        Doc d = ed.d();
        if (d.mail != null && d.mail.nachricht != null) return d.mail.nachricht;
        ed.fehler("erst eine Mail oeffnen (Leertaste e e, ENTER)");
        return null;
    }

    void antworten(boolean alle) {
        Nachricht n = offene();
        if (n == null) return;
        String an = n.e().vonAdresse() != null ? n.e().vonAdresse() : n.e().von();
        String cc = "";
        if (alle) {
            List<String> l = new ArrayList<>();
            String ich = ed.kf.get("mail.address", "").strip().toLowerCase();
            for (String x : n.an()) if (!adresseVon(x).equalsIgnoreCase(an) && (ich.isEmpty() || !adresseVon(x).toLowerCase().equals(ich))) l.add(adresseVon(x));
            if (!l.isEmpty()) an = an + ", " + String.join(", ", l);
            List<String> c = new ArrayList<>();
            for (String x : n.cc()) if (ich.isEmpty() || !adresseVon(x).toLowerCase().equals(ich)) c.add(adresseVon(x));
            cc = String.join(", ", c);
        }
        entwurf(an, cc, vorsilbe("AW", n.e().betreff()), zitat(n));
        ed.d().cursor = ed.d().t.indexOf(TRENN) + TRENN.length() + 1;
    }

    void weiterleiten() {
        Nachricht n = offene();
        if (n == null) return;
        entwurf("", "", vorsilbe("WG", n.e().betreff()), zitat(n));
    }

    /** "Name <adresse>" -> adresse */
    static String adresseVon(String s) {
        int a = s.lastIndexOf('<'), e = s.lastIndexOf('>');
        return a >= 0 && e > a ? s.substring(a + 1, e).strip() : s.strip();
    }

    /** Kopf und Text eines Entwurfs: {an, cc, betreff, text}; null ohne Trennlinie */
    static String[] entwurfLesen(String t) {
        int s = t.indexOf(TRENN);
        if (s < 0) return null;
        String an = "", cc = "", betreff = "";
        for (String z : t.substring(0, s).split("\n")) {
            String k = z.strip();
            if (k.regionMatches(true, 0, "An:", 0, 3)) an = k.substring(3).strip();
            else if (k.regionMatches(true, 0, "Cc:", 0, 3)) cc = k.substring(3).strip();
            else if (k.regionMatches(true, 0, "Betreff:", 0, 8)) betreff = k.substring(8).strip();
        }
        return new String[] {an, cc, betreff, t.substring(Math.min(t.length(), s + TRENN.length() + 1))};
    }

    private static String mailboxen(String feld, String liste) {
        StringBuilder b = new StringBuilder();
        for (String x : liste.split("[,;]")) {
            String a = adresseVon(x);
            if (!a.isEmpty()) b.append("<t:Mailbox><t:EmailAddress>").append(esc(a)).append("</t:EmailAddress></t:Mailbox>");
        }
        return b.length() == 0 ? "" : "<t:" + feld + ">" + b + "</t:" + feld + ">";
    }

    /** Strg+Enter im Entwurf */
    void senden(Doc d) {
        if (d.mail == null || !d.mail.art.equals("entwurf")) {
            ed.fehler("kein Mail-Entwurf (Leertaste e n schreibt eine neue Mail)");
            return;
        }
        String[] f = entwurfLesen(d.t.toString());
        if (f == null) {
            ed.fehler("Trennlinie fehlt - An:, Cc:, Betreff: oben, dann die Linie, dann der Text");
            return;
        }
        if (f[0].isBlank()) {
            ed.fehler("kein Empfaenger (An:)");
            return;
        }
        for (String x : (f[0] + "," + f[1]).split("[,;]")) {
            String a = adresseVon(x);
            if (!a.isEmpty() && !a.matches("[^@\\s]+@[^@\\s]+")) {
                ed.fehler("keine gueltige Adresse: " + a);
                return;
            }
        }
        String xml = "<m:CreateItem MessageDisposition=\"SendAndSaveCopy\"><m:SavedItemFolderId><t:DistinguishedFolderId Id=\"sentitems\"/></m:SavedItemFolderId><m:Items><t:Message>"
                + "<t:Subject>" + esc(f[2]) + "</t:Subject><t:Body BodyType=\"Text\">" + esc(f[3]) + "</t:Body>" + mailboxen("ToRecipients", f[0]) + mailboxen("CcRecipients", f[1])
                + "</t:Message></m:Items></m:CreateItem>";
        ed.meldung = "Mail wird gesendet ...";
        Thread t = new Thread(() -> {
            Antwort a = ews(xml);
            SwingUtilities.invokeLater(() -> {
                if (a.code() != 200) {
                    ed.fehler("nicht gesendet: " + a.fehler());
                    return;
                }
                d.geaendert = false;
                int i = ed.tabs.indexOf(d);
                if (i >= 0) {
                    ed.aktiv = i;
                    ed.befehl("forceClose", "");
                }
                ed.modus = ed.d().nurLesen && ed.km.modeDa("nurlesen") ? "nurlesen" : "normal";
                ed.meldung = "gesendet an " + f[0] + (f[2].isEmpty() ? "" : ": " + f[2]);
                ed.neuMalen.run();
            });
        }, "mail");
        t.setDaemon(true);
        t.start();
    }

    /** Mail in Geloeschte Elemente (mit Rueckfrage ueber die Auswahl) */
    void loeschenStarten() {
        Doc d = ed.d();
        Eintrag e = eintragBei(d);
        if (e == null || e.id() == null) {
            ed.fehler("keine Mail unter dem Cursor");
            return;
        }
        ed.wz.auswahlTasten("Mail \"" + e.betreff() + "\" von " + e.von() + " in Geloeschte Elemente verschieben?", List.of("[j] Ja, loeschen", "[n] Nein"), w -> {
            if (!w.startsWith("[j]")) return;
            Thread t = new Thread(() -> {
                Antwort a = ews("<m:DeleteItem DeleteType=\"MoveToDeletedItems\"><m:ItemIds><t:ItemId Id=\"" + esc(e.id()) + "\" ChangeKey=\"" + esc(e.ck()) + "\"/></m:ItemIds></m:DeleteItem>");
                SwingUtilities.invokeLater(() -> {
                    if (a.code() != 200) {
                        ed.fehler("nicht geloescht: " + a.fehler());
                        return;
                    }
                    ed.meldung = "in Geloeschte Elemente: " + e.betreff();
                    if (d.mail != null && d.mail.art.equals("nachricht")) {
                        int i = ed.tabs.indexOf(d);
                        if (i >= 0) {
                            ed.aktiv = i;
                            ed.befehl("close", "");
                        }
                    }
                    for (Doc x : List.copyOf(ed.tabs)) if (x.mail != null && x.mail.art.equals("eingang")) {
                        ed.aktiv = ed.tabs.indexOf(x);
                        eingang();
                    }
                    wecken();
                });
            }, "mail");
            t.setDaemon(true);
            t.start();
        });
    }

    private boolean bereit() {
        if (!eingerichtet()) {
            ed.fehler("Mail ist nicht eingerichtet: mail.server, mail.user, mail.password in config.txt");
            return false;
        }
        if (gesperrt || !status.equals("ok")) {
            ed.fehler("Mail: " + (status.equals("verbinde") ? "verbinde noch ..." : status));
            if (!gesperrt) wecken();
            return false;
        }
        return true;
    }

    // ================================================================ Farben
    static byte[] faerben(CharSequence s) {
        byte[] f = new byte[s.length()];
        int i = 0, n = s.length(), nr = 0;
        boolean kopf = true;
        while (i < n) {
            int e = i;
            while (e < n && s.charAt(e) != '\n') e++;
            String z = s.subSequence(i, e).toString();
            if (z.startsWith("# ")) java.util.Arrays.fill(f, i, e, Ausgabe.KOPF);
            else if (z.equals(TRENN) || z.startsWith("----- ")) {
                java.util.Arrays.fill(f, i, e, Ausgabe.LEISE);
                kopf = false;
            } else if (z.startsWith("> ")) java.util.Arrays.fill(f, i, e, Ausgabe.LEISE);
            else if (z.startsWith("* ")) java.util.Arrays.fill(f, i, e, Ausgabe.EIGEN);
            java.util.regex.Matcher m = FELD.matcher(z);
            if (kopf && m.find()) java.util.Arrays.fill(f, i, i + m.end(), Ausgabe.INFO);
            // Kopf (Von, An, Datum) endet mit der ersten Leerzeile nach dem Titel
            if (z.isEmpty() && nr > 1) kopf = false;
            nr++;
            i = e + 1;
        }
        return f;
    }

    private static final java.util.regex.Pattern FELD = java.util.regex.Pattern.compile("^(Von|An|Cc|Datum|Betreff):");

    // ================================================================ Befehle
    void befehle(Map<String, Editor.Cmd> c) {
        /** @binding Mail: Posteingang oeffnen (neueste zuerst, * = ungelesen; ENTER oeffnet eine Mail) */
        c.put("mailInbox", a -> eingang());
        /** @binding Mail: neue Mail schreiben (An, Cc, Betreff, Text; Strg+Enter sendet) */
        c.put("mailNew", a -> {
            if (bereit()) entwurf("", "", "", "");
        });
        /** @binding Mail: auf die geoeffnete Mail antworten */
        c.put("mailReply", a -> antworten(false));
        /** @binding Mail: allen antworten */
        c.put("mailReplyAll", a -> antworten(true));
        /** @binding Mail: weiterleiten */
        c.put("mailForward", a -> weiterleiten());
        /** @binding Mail: Mail unter dem Cursor bzw. die geoeffnete in Geloeschte Elemente (mit Rueckfrage) */
        c.put("mailDelete", a -> loeschenStarten());
        /** @binding Mail: Entwurf senden (auch Strg+Enter im Entwurf) */
        c.put("mailSend", a -> senden(ed.d()));
        /** @binding Mail: Posteingang neu laden */
        c.put("mailRefresh", a -> {
            wecken();
            for (Doc d : List.copyOf(ed.tabs)) if (d.mail != null && d.mail.art.equals("eingang")) {
                ed.aktiv = ed.tabs.indexOf(d);
                eingang();
                return;
            }
            ed.meldung = "Mail wird abgefragt ...";
        });
        /** @binding Mail: neu anmelden (nach Aenderungen an mail.* in config.txt oder nach einer abgelehnten Anmeldung) */
        c.put("mailConnect", a -> {
            ed.kf.lesen();
            starten();
            ed.meldung = eingerichtet() ? "Mail: verbinde mit " + adresse() : "mail.server fehlt in config.txt";
        });
        /** @binding Mail: Verbindungsstand zeigen */
        c.put("mailStatus", a -> ed.meldung = !eingerichtet() ? "Mail ist nicht eingerichtet (mail.server in config.txt)"
                : "Mail " + adresse() + ": " + status + ", " + ungelesen + " ungelesen von " + gesamt + ", " + Netz.weg(ed.kf, "mail", false) + ", " + anfragen.get() + " Anfragen");
    }
}
