package runen;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Datenbank ueber JDBC (Oracle oder jeder andere Treiber): Treiber-Jar aus config.txt, mehrere
 * Konten, eine offene Verbindung je Konto. Ausgefuehrt wird in einem eigenen Faden.
 */
final class Db {
    record Konto(String name, String url, String benutzer, String passwort) {}

    /** Ergebnis eines Statements: Tabelle (spalten != null) oder Meldung */
    record Ergebnis(String konto, String sql, List<String> spalten, List<String[]> zeilen, boolean mehr, String meldung, boolean fehler,
            List<String> ausgabe, long ms) {}

    record Bereich(int anf, int ende) {}

    final Map<String, Konto> konten = new LinkedHashMap<>();
    private String treiberPfad, treiberKlasse;
    int maxZeilen = 500;
    boolean autocommit;
    private Driver treiber;
    private final Map<String, Connection> verbindungen = new HashMap<>();
    private final ExecutorService faden = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "db");
        t.setDaemon(true);
        return t;
    });
    private volatile Statement laufend;
    volatile boolean beschaeftigt;
    final List<String> fehler = new ArrayList<>();

    void konfigurieren(Konfig kf, Path basis) {
        fehler.clear();
        String t = kf.get("db.driver", null);
        String neuPfad = t == null ? null : basis.resolve(t).toString();
        if (neuPfad != null && !neuPfad.equals(treiberPfad)) treiber = null;
        treiberPfad = neuPfad;
        treiberKlasse = kf.get("db.driverClass", "oracle.jdbc.OracleDriver");
        maxZeilen = Math.max(1, kf.zahl("db.maxRows", 500));
        autocommit = kf.an("db.autocommit", false);
        konten.clear();
        for (var e : kf.mitAnfang("db.account.").entrySet()) {
            String[] teile = e.getValue().split("\\|", -1);
            if (teile.length < 3) {
                fehler.add("db.account." + e.getKey() + ": erwartet URL | BENUTZER | PASSWORT");
                continue;
            }
            konten.put(e.getKey(), new Konto(e.getKey(), teile[0].strip(), teile[1].strip(), teile[2].strip()));
        }
    }

    private Driver treiber() throws Exception {
        if (treiber != null) return treiber;
        ClassLoader cl = Db.class.getClassLoader();
        if (treiberPfad != null) {
            Path p = Path.of(treiberPfad);
            if (!Files.isRegularFile(p)) throw new SQLException("Treiber nicht gefunden: " + p + " (db.driver in config.txt)");
            cl = new URLClassLoader(new URL[] {p.toUri().toURL()}, cl);
        }
        try {
            treiber = (Driver) Class.forName(treiberKlasse, true, cl).getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            throw new SQLException("Treiberklasse " + treiberKlasse + " nicht gefunden" + (treiberPfad == null ? " (db.driver in config.txt fehlt)" : " in " + treiberPfad));
        }
        return treiber;
    }

    private Connection verbindung(Konto k) throws Exception {
        Connection c = verbindungen.get(k.name());
        if (c != null && !c.isClosed()) return c;
        java.util.Properties p = new java.util.Properties();
        p.setProperty("user", k.benutzer());
        p.setProperty("password", k.passwort());
        p.setProperty("oracle.net.CONNECT_TIMEOUT", "10000");
        c = treiber().connect(k.url(), p);
        if (c == null) throw new SQLException("Treiber " + treiberKlasse + " passt nicht zur URL " + k.url());
        c.setAutoCommit(autocommit);
        if (oracle(c)) {
            try (Statement s = c.createStatement()) {
                s.execute("BEGIN DBMS_OUTPUT.ENABLE(NULL); END;");
            } catch (SQLException e) {
                // ohne DBMS_OUTPUT
            }
        }
        verbindungen.put(k.name(), c);
        return c;
    }

    private static boolean oracle(Connection c) {
        try {
            return c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("oracle");
        } catch (SQLException e) {
            return false;
        }
    }

    /** Statements nacheinander; jedes Ergebnis an ergebnis (im db-Faden) */
    void ausfuehren(String konto, List<String> sqls, Consumer<Ergebnis> ergebnis) {
        Konto k = konten.get(konto);
        if (k == null) {
            ergebnis.accept(new Ergebnis(konto, "", null, null, false, "Konto " + konto + " gibt es nicht (db.account." + konto + " in config.txt)", true, List.of(), 0));
            return;
        }
        beschaeftigt = true;
        faden.submit(() -> {
            try {
                for (String sql : sqls) {
                    Ergebnis e = eins(k, sql);
                    ergebnis.accept(e);
                    if (e.fehler()) break;
                }
            } finally {
                beschaeftigt = false;
                laufend = null;
            }
        });
    }

    private Ergebnis eins(Konto k, String sql) {
        long t0 = System.currentTimeMillis();
        try {
            Connection c = verbindung(k);
            try (Statement s = c.createStatement()) {
                laufend = s;
                s.setMaxRows(maxZeilen + 1);
                boolean tabelle = s.execute(sql);
                List<String> ausgabe = ausgabe(c);
                long ms = System.currentTimeMillis() - t0;
                if (tabelle) {
                    try (ResultSet rs = s.getResultSet()) {
                        ResultSetMetaData md = rs.getMetaData();
                        int n = md.getColumnCount();
                        List<String> spalten = new ArrayList<>();
                        for (int i = 1; i <= n; i++) spalten.add(md.getColumnLabel(i));
                        List<String[]> zeilen = new ArrayList<>();
                        boolean mehr = false;
                        while (rs.next()) {
                            if (zeilen.size() >= maxZeilen) {
                                mehr = true;
                                break;
                            }
                            String[] z = new String[n];
                            for (int i = 1; i <= n; i++) z[i - 1] = wert(rs, i, md.getColumnType(i));
                            zeilen.add(z);
                        }
                        ms = System.currentTimeMillis() - t0;
                        String m = zeilen.size() + (mehr ? "+" : "") + " Zeilen in " + ms + " ms";
                        return new Ergebnis(k.name(), sql, spalten, zeilen, mehr, m, false, ausgabe, ms);
                    }
                }
                int anz = s.getUpdateCount();
                String m = anz >= 0 ? anz + " Zeilen betroffen" : "ausgefuehrt";
                if (!autocommit && anz > 0) m += " (noch nicht committet)";
                return new Ergebnis(k.name(), sql, null, null, false, m + " in " + ms + " ms", false, ausgabe, ms);
            }
        } catch (Exception e) {
            String m = e instanceof SQLException ? e.getMessage().strip() : e.toString();
            return new Ergebnis(k.name(), sql, null, null, false, m, true, List.of(), System.currentTimeMillis() - t0);
        }
    }

    private static String wert(ResultSet rs, int i, int typ) throws SQLException {
        if (typ == Types.BLOB || typ == Types.BINARY || typ == Types.VARBINARY || typ == Types.LONGVARBINARY) {
            return rs.getObject(i) == null ? null : "<BLOB>";
        }
        return rs.getString(i);
    }

    /** DBMS_OUTPUT-Zeilen (nur Oracle) */
    private static List<String> ausgabe(Connection c) {
        if (!oracle(c)) return List.of();
        List<String> l = new ArrayList<>();
        try (CallableStatement cs = c.prepareCall("{call DBMS_OUTPUT.GET_LINE(?, ?)}")) {
            cs.registerOutParameter(1, Types.VARCHAR);
            cs.registerOutParameter(2, Types.INTEGER);
            for (int k = 0; k < 10000; k++) {
                cs.execute();
                if (cs.getInt(2) != 0) break;
                String z = cs.getString(1);
                l.add(z == null ? "" : z);
            }
        } catch (SQLException e) {
            // ohne DBMS_OUTPUT
        }
        return l;
    }

    void abbrechen() {
        Statement s = laufend;
        if (s == null) return;
        try {
            s.cancel();
        } catch (SQLException e) {
            // schon fertig
        }
    }

    void commit(String konto, boolean commit, Consumer<String> antwort) {
        faden.submit(() -> {
            Connection c = verbindungen.get(konto);
            try {
                if (c == null || c.isClosed()) {
                    antwort.accept("keine Verbindung fuer " + konto);
                    return;
                }
                if (commit) c.commit();
                else c.rollback();
                antwort.accept((commit ? "commit" : "rollback") + " auf " + konto);
            } catch (SQLException e) {
                antwort.accept(e.getMessage());
            }
        });
    }

    void trennen() {
        faden.submit(() -> {
            for (Connection c : verbindungen.values()) {
                try {
                    c.close();
                } catch (SQLException e) {
                    // egal
                }
            }
            verbindungen.clear();
        });
    }

    // ---------------------------------------------------------------- Statements zerlegen
    private static final Pattern PLSQL = Pattern.compile(
            "(?is)^(declare|begin|create\\s+(or\\s+replace\\s+)?((editionable|noneditionable)\\s+)?(function|procedure|package|trigger|type|library|java))\\b.*");

    /**
     * Statements im Text: Ende bei ; oder einer Zeile mit nur /, bei einer Leerzeile oder am Textende.
     * PL/SQL-Bloecke (DECLARE, BEGIN, CREATE PROCEDURE ...) enden nur bei / oder am Textende.
     * Die Bereiche enthalten den Abschluss (; oder /) nicht; END; behaelt sein Semikolon.
     */
    static List<Bereich> statements(CharSequence t) {
        List<Bereich> r = new ArrayList<>();
        int n = t.length(), i = 0;
        while (i < n) {
            i = ueberspringen(t, i);
            if (i >= n) break;
            int a = i;
            int zeilenEnde = a;
            while (zeilenEnde < n && t.charAt(zeilenEnde) != '\n') zeilenEnde++;
            String anfang = t.subSequence(a, Math.min(n, a + 200)).toString();
            boolean plsql = PLSQL.matcher(anfang).matches();
            int e = -1, weiter = n;
            while (i < n) {
                char c = t.charAt(i);
                char d = i + 1 < n ? t.charAt(i + 1) : 0;
                if (c == '\'') {
                    i++;
                    while (i < n && !(t.charAt(i) == '\'' && (i + 1 >= n || t.charAt(i + 1) != '\''))) i += t.charAt(i) == '\'' ? 2 : 1;
                    i++;
                    continue;
                }
                if (c == '-' && d == '-') {
                    while (i < n && t.charAt(i) != '\n') i++;
                    continue;
                }
                if (c == '/' && d == '*') {
                    int z = indexOf(t, "*/", i + 2);
                    i = z < 0 ? n : z + 2;
                    continue;
                }
                if (c == '\n') {
                    // Zeile mit nur /
                    int j = i + 1;
                    while (j < n && (t.charAt(j) == ' ' || t.charAt(j) == '\t')) j++;
                    if (j < n && t.charAt(j) == '/') {
                        int k = j + 1;
                        while (k < n && (t.charAt(k) == ' ' || t.charAt(k) == '\t' || t.charAt(k) == '\r')) k++;
                        if (k >= n || t.charAt(k) == '\n') {
                            e = i;
                            weiter = k;
                            break;
                        }
                    }
                    if (!plsql) {
                        // Leerzeile beendet ein SQL-Statement
                        int k = i + 1;
                        while (k < n && (t.charAt(k) == ' ' || t.charAt(k) == '\t' || t.charAt(k) == '\r')) k++;
                        if (k >= n || t.charAt(k) == '\n') {
                            e = i;
                            weiter = k;
                            break;
                        }
                    }
                    i++;
                    continue;
                }
                if (c == ';' && !plsql) {
                    e = i;
                    weiter = i + 1;
                    break;
                }
                i++;
            }
            if (e < 0) {
                e = n;
                weiter = n;
            }
            int ee = e;
            while (ee > a && Character.isWhitespace(t.charAt(ee - 1))) ee--;
            if (ee > a) r.add(new Bereich(a, ee));
            i = Math.max(weiter, a + 1);
        }
        return r;
    }

    /** Leerraum und Kommentare vor einem Statement */
    private static int ueberspringen(CharSequence t, int i) {
        int n = t.length();
        while (i < n) {
            char c = t.charAt(i);
            char d = i + 1 < n ? t.charAt(i + 1) : 0;
            if (Character.isWhitespace(c) || c == ';') {
                i++;
            } else if (c == '-' && d == '-') {
                while (i < n && t.charAt(i) != '\n') i++;
            } else if (c == '/' && d == '*') {
                int z = indexOf(t, "*/", i + 2);
                i = z < 0 ? n : z + 2;
            } else if (c == '/' && (d == '\n' || d == 0 || d == '\r' || d == ' ')) {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    private static int indexOf(CharSequence s, String was, int ab) {
        for (int k = ab; k + 1 < s.length(); k++) if (s.charAt(k) == was.charAt(0) && s.charAt(k + 1) == was.charAt(1)) return k;
        return -1;
    }

    /** Statement am Cursor (oder das letzte davor) */
    static Bereich amCursor(CharSequence t, int cursor) {
        Bereich vor = null;
        for (Bereich b : statements(t)) {
            if (cursor >= b.anf() && cursor <= b.ende() + 1) return b;
            if (b.anf() <= cursor) vor = b;
        }
        return vor;
    }
}
