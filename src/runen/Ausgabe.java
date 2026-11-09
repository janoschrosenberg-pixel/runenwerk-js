package runen;

import static runen.Highlight.TEXT;

import java.util.Arrays;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hervorhebung fuer Ausgaben (Terminal-Tabs, .log): Maven ([INFO]/[WARNING]/[ERROR], Abschnitte,
 * BUILD SUCCESS/FAILURE, Testergebnisse), Logzeilen (Spring Boot, logback, log4j, JUL: Zeit und Logger
 * leise, Level farbig), Stacktraces (Exception rot, eigene Codezeilen hell mit Datei:Zeile, Bibliotheken
 * gedimmt), npm, tsc/javac-Fehler. Eine Klasse je Zeichen wie {@link Highlight}.
 */
final class Ausgabe {
    private Ausgabe() {}

    /** Klassen nach denen von Highlight (Farben in Editor.FARBEN) */
    static final byte FEHLER = 15, WARN = 16, INFO = 17, LEISE = 18, ERFOLG = 19, EIGEN = 20, ORT = 21, KOPF = 22;

    private static final Pattern JAVA_FRAME = Pattern.compile("at\\s+(?:[\\w.-]+(?:@[\\w.-]+)?/)?([\\w$]+(?:\\.[\\w$]+)*)\\.[\\w$<>-]+\\(([^)]*)\\)");
    private static final Pattern AUSNAHME = Pattern.compile(
            "^(?:Caused by: |Suppressed: |Exception in thread \"[^\"]*\" |\\[[A-Z]+\\] )?(?:[a-z][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error|Throwable|Failure)\\b.*"
                    + "|^(?:Uncaught )?(?:[A-Z]\\w*)?Error(?:\\s*\\[[\\w-]+\\])?: .*");
    /** Level einer Logzeile in den ersten Zeichen (nach Zeitstempel, Klammern ...) */
    private static final Pattern LEVEL = Pattern.compile("\\b(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL|SEVERE|FINE|FINER|FINEST|CONFIG)\\b");
    private static final Pattern MAVEN = Pattern.compile("^\\[(INFO|WARNING|WARN|ERROR|DEBUG)\\]");
    private static final Pattern TESTS = Pattern.compile("Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+)");
    private static final Pattern ERGEBNIS = Pattern.compile("\\b(SUCCESS|FAILURE|SKIPPED|PASSED|FAILED|PASS|FAIL|BUILD SUCCESSFUL|BUILD FAILED)\\b");
    /** pfad:zeile[:spalte], pfad:[z,s], pfad(z,s) */
    private static final Pattern STELLE = Pattern.compile(
            "(?:file://)?(?:[A-Za-z]:[\\\\/]|/|\\./|\\.\\./)?[\\w@.~+-]+(?:[\\\\/][\\w@.~+-]+)*\\.[A-Za-z]\\w{0,9}(?::\\[\\d+(?:,\\d+)?\\]|:\\d+(?::\\d+)?|\\(\\d+,\\d+\\))");

    /**
     * @param eigen Java-Datei als paket/Datei.java: gehoert sie zum Projekt? (null = alles ausser JDK/Bibliotheken nach Namen)
     */
    static byte[] faerben(CharSequence s, Predicate<String> eigen) {
        int n = s.length();
        byte[] f = new byte[n];
        int i = 0;
        while (i < n) {
            int e = i;
            while (e < n && s.charAt(e) != '\n') e++;
            if (e - i <= 2000) zeile(s.subSequence(i, e).toString(), f, i, eigen);
            i = e + 1;
        }
        return f;
    }

    private static void setzen(byte[] f, int o, int a, int e, byte k) {
        Arrays.fill(f, o + a, o + e, k);
    }

    private static void zeile(String z, byte[] f, int o, Predicate<String> eigen) {
        int ein = 0;
        while (ein < z.length() && (z.charAt(ein) == ' ' || z.charAt(ein) == '\t')) ein++;
        String t = z.substring(ein);
        if (t.isEmpty()) return;
        // Stacktrace: at ... (Java, Node)
        if (t.startsWith("at ")) {
            frame(z, ein, f, o, eigen);
            return;
        }
        if (t.startsWith("... ") && (t.endsWith(" more") || t.contains("common frames omitted"))) {
            setzen(f, o, 0, z.length(), LEISE);
            return;
        }
        // Maven: [INFO] [WARNING] [ERROR] [DEBUG]
        Matcher m = MAVEN.matcher(z);
        if (m.find()) {
            maven(z, m.group(1), m.end(), f, o);
            return;
        }
        if (AUSNAHME.matcher(t).matches()) {
            setzen(f, o, 0, z.length(), FEHLER);
            return;
        }
        // npm
        if (t.startsWith("npm ERR!") || t.startsWith("npm error")) {
            setzen(f, o, 0, z.length(), FEHLER);
            return;
        }
        if (t.startsWith("npm WARN") || t.startsWith("npm warn")) {
            setzen(f, o, 0, z.length(), WARN);
            return;
        }
        // Logzeile mit Level (Spring Boot, logback, log4j, JUL): Level in den ersten 60 Zeichen, davor Zeit o. ae.
        Matcher l = LEVEL.matcher(z);
        if (l.find() && l.start() < 60 && (Character.isDigit(z.charAt(0)) || z.charAt(0) == '[' || l.start() == 0 || z.startsWith(l.group(1) + ":"))) {
            logZeile(z, l, f, o);
            return;
        }
        // Compiler (tsc, javac, eslint): Stelle, dann error/warning
        Matcher st = STELLE.matcher(z);
        if (st.find() && st.start() <= ein + 2) {
            String rest = z.substring(st.end()).toLowerCase();
            byte k = rest.matches("^[\\s:-]*(?:error|fehler)\\b.*") ? FEHLER : rest.matches("^[\\s:-]*(?:warning|warn)\\b.*") ? WARN : TEXT;
            if (k != TEXT) {
                setzen(f, o, 0, z.length(), k);
                setzen(f, o, st.start(), st.end(), ORT);
                return;
            }
        }
        String tl = t.toLowerCase();
        if (tl.startsWith("error") || tl.startsWith("fehler") || tl.startsWith("fatal") || t.startsWith("FAIL ") || t.startsWith("BUILD FAILED")) {
            setzen(f, o, 0, z.length(), FEHLER);
            return;
        }
        if (tl.startsWith("warning") || tl.startsWith("warn") || tl.startsWith("deprecated")) {
            setzen(f, o, 0, z.length(), WARN);
            return;
        }
        if (t.startsWith("PASS ") || t.startsWith("BUILD SUCCESSFUL") || t.startsWith("✓") || t.startsWith("✔")) {
            setzen(f, o, 0, z.length(), ERFOLG);
            return;
        }
        if (t.startsWith("✗") || t.startsWith("✘") || t.startsWith("✕")) {
            setzen(f, o, 0, z.length(), FEHLER);
            return;
        }
        tests(z, f, o);
    }

    /** Stacktrace-Zeile: Projektcode hell mit Datei:Zeile, Bibliotheken und Laufzeit gedimmt */
    private static void frame(String z, int ein, byte[] f, int o, Predicate<String> eigen) {
        boolean meins;
        int oa = -1, oe = -1;
        Matcher m = JAVA_FRAME.matcher(z);
        if (m.find(ein)) {
            String klasse = m.group(1), datei = m.group(2);
            int d = klasse.lastIndexOf('.');
            String paket = d < 0 ? "" : klasse.substring(0, d);
            while (!paket.isEmpty()) {
                int p = paket.lastIndexOf('.');
                String letzt = paket.substring(p + 1);
                if (letzt.isEmpty() || !Character.isUpperCase(letzt.charAt(0))) break;
                paket = p < 0 ? "" : paket.substring(0, p);
            }
            int dp = datei.indexOf(':');
            String name = dp < 0 ? datei : datei.substring(0, dp);
            meins = eigen != null ? eigen.test((paket.isEmpty() ? "" : paket.replace('.', '/') + "/") + name) : !fremdesPaket(klasse);
            oa = m.start(2);
            oe = m.end(2);
        } else {
            String r = z.substring(ein);
            meins = !(r.contains("node_modules") || r.contains("node:") || r.contains("(internal/") || r.contains("<anonymous>") || r.contains("native"));
            Matcher st = STELLE.matcher(z);
            if (st.find(ein)) {
                oa = st.start();
                oe = st.end();
            }
        }
        setzen(f, o, 0, z.length(), meins ? EIGEN : LEISE);
        if (meins && oa >= 0) setzen(f, o, oa, oe, ORT);
    }

    private static boolean fremdesPaket(String k) {
        for (String p : new String[] {"java.", "javax.", "jdk.", "sun.", "com.sun.", "org.springframework.", "org.apache.", "org.junit.", "org.hibernate.",
                "io.netty.", "reactor.", "kotlin.", "org.eclipse.", "com.fasterxml.", "org.mockito.", "net.bytebuddy.", "lombok.", "org.slf4j.", "ch.qos."}) {
            if (k.startsWith(p)) return true;
        }
        return false;
    }

    private static byte level(String l) {
        return switch (l) {
            case "ERROR", "FATAL", "SEVERE" -> FEHLER;
            case "WARN", "WARNING" -> WARN;
            case "DEBUG", "TRACE", "FINE", "FINER", "FINEST", "CONFIG" -> LEISE;
            default -> INFO;
        };
    }

    /** Logzeile: alles bis zum Level leise, Level farbig, Logger/Faden bis " : " leise, Meldung je nach Level */
    private static void logZeile(String z, Matcher l, byte[] f, int o) {
        byte k = level(l.group(1));
        setzen(f, o, 0, l.start(), LEISE);
        setzen(f, o, l.start(), l.end(), k);
        int msg = l.end();
        int dp = z.indexOf(" : ", l.end());
        if (dp < 0) dp = z.indexOf(" - ", l.end());
        if (dp >= 0 && dp < 220) {
            setzen(f, o, l.end(), dp + 3, LEISE);
            msg = dp + 3;
        }
        byte m = k == INFO ? TEXT : k;
        setzen(f, o, msg, z.length(), m);
        if (k == INFO) tests(z, f, o);
    }

    private static void maven(String z, String lv, int tagEnde, byte[] f, int o) {
        byte k = level(lv);
        setzen(f, o, 0, tagEnde, k);
        String r = z.substring(tagEnde).trim();
        if (k == FEHLER || k == WARN) {
            setzen(f, o, tagEnde, z.length(), k);
            Matcher st = STELLE.matcher(z);
            if (st.find(tagEnde)) setzen(f, o, st.start(), st.end(), ORT);
            return;
        }
        if (k == LEISE) {
            setzen(f, o, tagEnde, z.length(), LEISE);
            return;
        }
        if (r.equals("BUILD SUCCESS")) setzen(f, o, tagEnde, z.length(), ERFOLG);
        else if (r.equals("BUILD FAILURE")) setzen(f, o, tagEnde, z.length(), FEHLER);
        else if (r.matches("-{10,}") || r.matches("-+\\[ \\w+ \\]-+") || r.startsWith("Downloading") || r.startsWith("Downloaded") || r.startsWith("Progress") || r.startsWith("Total time")
                || r.startsWith("Finished at")) setzen(f, o, tagEnde, z.length(), LEISE);
        else if (r.startsWith("--- ") || r.startsWith(">>> ") || r.startsWith("<<< ") || r.startsWith("Building ") || r.startsWith("Reactor Summary")
                || r.matches("-+< .* >-+") || r.startsWith("T E S T S") || r.startsWith("Results:")) setzen(f, o, tagEnde, z.length(), KOPF);
        else {
            Matcher e = ERGEBNIS.matcher(z);
            // Reactor-Zusammenfassung: modul ...... SUCCESS [ 1.2 s]
            if (e.find(tagEnde) && z.contains("[") && z.lastIndexOf('[') > e.start()) setzen(f, o, e.start(), e.end(), ergebnis(e.group(1)));
            tests(z, f, o);
        }
    }

    private static byte ergebnis(String e) {
        return switch (e) {
            case "SUCCESS", "PASSED", "PASS", "BUILD SUCCESSFUL" -> ERFOLG;
            case "SKIPPED" -> LEISE;
            default -> FEHLER;
        };
    }

    /** Tests run: N, Failures: F, Errors: E - gruen ohne Fehler, sonst rot */
    private static void tests(String z, byte[] f, int o) {
        Matcher t = TESTS.matcher(z);
        if (!t.find()) return;
        boolean gut = t.group(2).equals("0") && t.group(3).equals("0");
        setzen(f, o, t.start(), z.length(), gut ? ERFOLG : FEHLER);
    }
}
