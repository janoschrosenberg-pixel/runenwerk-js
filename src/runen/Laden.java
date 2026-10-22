package runen;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/** Herunterladen (jdtls, js-debug) und .tar.gz entpacken, ohne fremde Bibliotheken. */
final class Laden {
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(20)).build();

    static String text(String url) throws IOException, InterruptedException {
        HttpRequest r = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "runenjs").header("Accept", "application/vnd.github+json").build();
        HttpResponse<String> a = HTTP.send(r, HttpResponse.BodyHandlers.ofString());
        if (a.statusCode() != 200) throw new IOException(url + ": HTTP " + a.statusCode());
        return a.body();
    }

    /** Datei laden und nach ziel entpacken; fortschritt bekommt Text wie "12 MB" */
    static void tarGz(String url, Path ziel, Consumer<String> fortschritt) throws IOException, InterruptedException {
        HttpRequest r = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "runenjs").build();
        HttpResponse<InputStream> a = HTTP.send(r, HttpResponse.BodyHandlers.ofInputStream());
        if (a.statusCode() != 200) throw new IOException(url + ": HTTP " + a.statusCode());
        Files.createDirectories(ziel);
        try (InputStream roh = a.body(); InputStream in = new GZIPInputStream(new BufferedInputStream(new Zaehler(roh, fortschritt)), 1 << 16)) {
            entpacken(in, ziel);
        }
    }

    /** tar lesen: ustar, GNU-Langnamen (L) und pax-Kopf (x) mit path= */
    static void entpacken(InputStream in, Path ziel) throws IOException {
        byte[] kopf = new byte[512];
        String langName = null;
        Path basis = ziel.toAbsolutePath().normalize();
        while (true) {
            if (in.readNBytes(kopf, 0, 512) < 512) return;
            boolean leer = true;
            for (byte b : kopf) if (b != 0) {
                leer = false;
                break;
            }
            if (leer) return;
            String name = feld(kopf, 0, 100);
            long groesse = oktal(kopf, 124, 12);
            char art = (char) kopf[156];
            String prefix = feld(kopf, 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;
            if (art == 'L' || art == 'x' || art == 'g') {
                byte[] daten = in.readNBytes((int) groesse);
                ueberspringen(in, (512 - groesse % 512) % 512);
                if (art == 'L') langName = new String(daten, StandardCharsets.UTF_8).replace("\0", "");
                if (art == 'x') {
                    for (String z : new String(daten, StandardCharsets.UTF_8).split("\n")) {
                        int sp = z.indexOf(' ');
                        if (sp > 0 && z.startsWith("path=", sp + 1)) langName = z.substring(sp + 6);
                    }
                }
                continue;
            }
            if (langName != null) {
                name = langName;
                langName = null;
            }
            Path p = basis.resolve(name).normalize();
            if (!p.startsWith(basis)) throw new IOException("unsicherer Pfad im Archiv: " + name);
            if (art == '5') {
                Files.createDirectories(p);
            } else if (art == '0' || art == 0) {
                Files.createDirectories(p.getParent());
                try (OutputStream o = Files.newOutputStream(p)) {
                    long rest = groesse;
                    byte[] puffer = new byte[1 << 16];
                    while (rest > 0) {
                        int n = in.read(puffer, 0, (int) Math.min(puffer.length, rest));
                        if (n < 0) throw new IOException("Archiv zu kurz");
                        o.write(puffer, 0, n);
                        rest -= n;
                    }
                }
                ueberspringen(in, (512 - groesse % 512) % 512);
                if (name.contains("/bin/")) p.toFile().setExecutable(true);
                continue;
            }
            ueberspringen(in, groesse + (512 - groesse % 512) % 512);
        }
    }

    private static String feld(byte[] b, int a, int n) {
        int e = a;
        while (e < a + n && b[e] != 0) e++;
        return new String(b, a, e - a, StandardCharsets.UTF_8).trim();
    }

    private static long oktal(byte[] b, int a, int n) {
        if ((b[a] & 0x80) != 0) {
            // grosse Dateien: binaer
            long v = 0;
            for (int i = a + 1; i < a + n; i++) v = (v << 8) | (b[i] & 0xff);
            return v;
        }
        String s = feld(b, a, n).trim();
        return s.isEmpty() ? 0 : Long.parseLong(s, 8);
    }

    private static void ueberspringen(InputStream in, long n) throws IOException {
        while (n > 0) {
            long k = in.skip(n);
            if (k <= 0) {
                if (in.read() < 0) return;
                k = 1;
            }
            n -= k;
        }
    }

    /** zaehlt gelesene Bytes fuer die Fortschrittsanzeige */
    private static final class Zaehler extends InputStream {
        private final InputStream in;
        private final Consumer<String> f;
        private long n, gemeldet;

        Zaehler(InputStream in, Consumer<String> f) {
            this.in = in;
            this.f = f;
        }

        @Override
        public int read() throws IOException {
            int c = in.read();
            if (c >= 0) mehr(1);
            return c;
        }

        @Override
        public int read(byte[] b, int o, int l) throws IOException {
            int k = in.read(b, o, l);
            if (k > 0) mehr(k);
            return k;
        }

        private void mehr(int k) {
            n += k;
            if (n - gemeldet > 2_000_000) {
                gemeldet = n;
                f.accept(n / 1_000_000 + " MB");
            }
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    // ---------------------------------------------------------------- Java finden
    /** Java mit mindestens dieser Version: java.lsp.java, das laufende, JAVA_HOME, uebliche Ordner */
    static Path javaAb(int min) {
        List<Path> kandidaten = new ArrayList<>();
        if (Runtime.version().feature() >= min) return javaIn(Path.of(System.getProperty("java.home")));
        String jh = System.getenv("JAVA_HOME");
        if (jh != null) kandidaten.add(Path.of(jh));
        List<Path> ordner = new ArrayList<>();
        if (Lsp.WINDOWS) {
            for (String pf : new String[] {System.getenv("ProgramFiles"), System.getenv("ProgramW6432")}) {
                if (pf == null) continue;
                for (String h : new String[] {"Eclipse Adoptium", "Java", "Microsoft", "Zulu", "Amazon Corretto", "BellSoft"}) ordner.add(Path.of(pf, h));
            }
        } else {
            ordner.add(Path.of("/Library/Java/JavaVirtualMachines"));
            ordner.add(Path.of("/usr/lib/jvm"));
            ordner.add(Path.of("/opt/homebrew/opt"));
        }
        ordner.add(Path.of(System.getProperty("user.home"), ".jdks"));
        for (Path o : ordner) {
            try (var s = Files.list(o)) {
                s.forEach(p -> {
                    kandidaten.add(p);
                    kandidaten.add(p.resolve("Contents").resolve("Home"));
                    kandidaten.add(p.resolve("libexec").resolve("openjdk.jdk").resolve("Contents").resolve("Home"));
                });
            } catch (IOException e) {
                // gibt es nicht
            }
        }
        Path best = null;
        int bestV = 0;
        for (Path k : kandidaten) {
            int v = version(k);
            Path j = javaIn(k);
            if (v >= min && v > bestV && j != null) {
                best = j;
                bestV = v;
            }
        }
        return best;
    }

    private static Path javaIn(Path home) {
        Path j = home.resolve("bin").resolve(Lsp.WINDOWS ? "java.exe" : "java");
        return Files.isRegularFile(j) ? j : null;
    }

    /** Hauptversion aus der Datei release eines JDK */
    private static int version(Path home) {
        try {
            for (String z : Files.readAllLines(home.resolve("release"))) {
                if (!z.startsWith("JAVA_VERSION=")) continue;
                String v = z.substring(13).replace("\"", "");
                if (v.startsWith("1.")) v = v.substring(2);
                int e = 0;
                while (e < v.length() && Character.isDigit(v.charAt(e))) e++;
                return e == 0 ? 0 : Integer.parseInt(v.substring(0, e));
            }
        } catch (IOException | RuntimeException e) {
            // kein JDK
        }
        return 0;
    }
}
