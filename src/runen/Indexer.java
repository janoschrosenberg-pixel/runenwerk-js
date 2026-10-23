package runen;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Dateien im Arbeitsordner: Liste aller Projektdateien (fuer die Namenssuche) und ein
 * Inhalt-Zwischenspeicher fuer die Volltextsuche. Laeuft in eigenen Faeden.
 */
final class Indexer {
    record Datei(Path pfad, String rel, String relKlein, long groesse) {}

    record Treffer(Path pfad, String rel, int zeile, int spalte, int laenge, String text) {}

    /** zwischengespeicherter Inhalt; klein = Zeichen fuer Zeichen kleingeschrieben (gleiche Laenge) */
    private record Inhalt(long zeit, long groesse, String text, String klein, long geprueft) {}

    /** Obergrenze fuer den Zwischenspeicher (Zeichen, Text und Kleinschrift zusammen) */
    private static final long CACHE_MAX = 128L << 20;
    private final java.util.concurrent.atomic.AtomicLong cacheZeichen = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Ein Arbeitsbereich: ausnahmen ohne / gelten fuer jeden Ordner dieses Namens, mit / nur
     * relativ zur Wurzel (/build).
     */
    record Wurzel(Path pfad, String name, List<String> ausnahmen) {}

    volatile List<Wurzel> wurzeln = List.of();
    volatile List<Datei> dateien = List.of();
    volatile boolean laeuft;
    long maxGroesse = 1 << 20;
    private static final Set<String> BINAER = Set.of("png", "jpg", "jpeg", "gif", "webp", "ico", "bmp", "pdf", "zip", "gz", "tgz", "woff", "woff2",
            "ttf", "otf", "eot", "mp3", "mp4", "webm", "wav", "ogg", "mov", "exe", "dll", "so", "dylib", "jar", "class", "lock", "map", "wasm", "avif");

    private final Map<Path, Inhalt> inhalte = new ConcurrentHashMap<>();
    private final ExecutorService scanner = Executors.newSingleThreadExecutor(r -> daemon(r, "index"));
    private final ExecutorService sucher = Executors.newSingleThreadExecutor(r -> daemon(r, "suche"));
    private final AtomicInteger suchNr = new AtomicInteger();
    private final Runnable fertig;

    Indexer(Runnable fertig) {
        this.fertig = fertig;
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    void setWurzeln(List<Wurzel> w) {
        wurzeln = List.copyOf(w);
        inhalte.clear();
        cacheZeichen.set(0);
        dateien = List.of();
        neuEinlesen();
    }

    void neuEinlesen() {
        List<Wurzel> ws = wurzeln;
        if (ws.isEmpty()) return;
        laeuft = true;
        scanner.submit(() -> {
            List<Datei> neu = new ArrayList<>();
            for (Wurzel w : ws) einlesen(w, ws.size() > 1, neu);
            neu.sort((a, b) -> a.rel().compareToIgnoreCase(b.rel()));
            if (ws == wurzeln) dateien = Collections.unmodifiableList(neu);
            laeuft = false;
            fertig.run();
        });
    }

    private static void einlesen(Wurzel w, boolean mitName, List<Datei> neu) {
        Path wp = w.pfad();
        Set<String> namen = new HashSet<>(Set.of(".git"));
        Set<String> pfade = new HashSet<>();
        for (String a : w.ausnahmen()) {
            String x = a.replace('\\', '/');
            while (x.endsWith("/")) x = x.substring(0, x.length() - 1);
            if (x.isEmpty()) continue;
            if (x.startsWith("/")) pfade.add(x.substring(1).toLowerCase());
            else namen.add(x);
        }
        try {
            Files.walkFileTree(wp, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                    if (d.equals(wp)) return FileVisitResult.CONTINUE;
                    if (namen.contains(d.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                    if (pfade.contains(wp.relativize(d).toString().replace('\\', '/').toLowerCase())) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    if (a.isRegularFile()) {
                        String rel = wp.relativize(f).toString().replace('\\', '/');
                        if (mitName) rel = w.name() + "/" + rel;
                        neu.add(new Datei(f, rel, rel.toLowerCase(), a.size()));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // halb gelesen ist besser als nichts
        }
    }

    // ---------------------------------------------------------------- Namenssuche
    /** Bewertung fuer Teilfolgen-Suche: hoeher ist besser, -1 passt nicht */
    static int bewerten(String text, String q, int[] wo) {
        int ti = 0, wert = 0, folge = 0;
        int name = text.lastIndexOf('/') + 1;
        for (int k = 0; k < q.length(); k++) {
            char c = q.charAt(k);
            int f = text.indexOf(c, ti);
            if (f < 0) return -1;
            if (wo != null) wo[k] = f;
            if (f == ti && k > 0) folge++;
            else folge = 0;
            wert += 10 + folge * 15;
            if (f >= name) wert += 8;
            if (f == name || (f > 0 && "/-_.".indexOf(text.charAt(f - 1)) >= 0)) wert += 12;
            ti = f + 1;
        }
        if (text.substring(name).startsWith(q)) wert += 60;
        return wert - text.length() / 4;
    }

    List<Datei> namenSuchen(String q, int max) {
        String ql = q.toLowerCase().replace('\\', '/').replace(" ", "");
        List<Datei> alle = dateien;
        List<int[]> wert = new ArrayList<>();
        for (int k = 0; k < alle.size(); k++) {
            int b = bewerten(alle.get(k).relKlein(), ql, null);
            if (b >= 0) wert.add(new int[] {b, k});
        }
        wert.sort((a, b) -> b[0] != a[0] ? b[0] - a[0] : a[1] - b[1]);
        List<Datei> r = new ArrayList<>();
        for (int k = 0; k < Math.min(max, wert.size()); k++) r.add(alle.get(wert.get(k)[1]));
        return r;
    }

    // ---------------------------------------------------------------- Inhaltssuche
    static String klein(String t) {
        // String.toLowerCase kann die Laenge aendern (z. B. bei I mit Punkt): dann passen Trefferpositionen nicht mehr
        char[] c = t.toCharArray();
        for (int i = 0; i < c.length; i++) c[i] = Character.toLowerCase(c[i]);
        return new String(c);
    }

    /** {text, kleingeschrieben} oder null */
    private Inhalt inhalt(Datei d) {
        String n = d.rel();
        int p = n.lastIndexOf('.');
        if (p >= 0 && BINAER.contains(n.substring(p + 1).toLowerCase())) return null;
        if (d.groesse() > maxGroesse) return null;
        try {
            long jetzt = System.currentTimeMillis();
            Inhalt alt = inhalte.get(d.pfad());
            // Aenderungsdatum hoechstens alle 10 s nachsehen (bei vielen Dateien teuer, besonders unter Windows)
            if (alt != null && jetzt - alt.geprueft() < 10_000) return alt.text() == null ? null : alt;
            long zeit = Files.getLastModifiedTime(d.pfad()).toMillis();
            if (alt != null && alt.zeit() == zeit && alt.groesse() == d.groesse()) {
                inhalte.put(d.pfad(), new Inhalt(alt.zeit(), alt.groesse(), alt.text(), alt.klein(), jetzt));
                return alt.text() == null ? null : alt;
            }
            byte[] b = Files.readAllBytes(d.pfad());
            String text = null;
            boolean binaer = false;
            for (int k = 0; k < Math.min(b.length, 8000); k++) {
                if (b[k] == 0) {
                    binaer = true;
                    break;
                }
            }
            if (!binaer) {
                CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE);
                text = dec.decode(ByteBuffer.wrap(b)).toString();
            }
            Inhalt neu = new Inhalt(zeit, d.groesse(), text, text == null ? null : klein(text), jetzt);
            long n2 = text == null ? 0 : 2L * text.length();
            if (alt != null && alt.text() != null) cacheZeichen.addAndGet(-2L * alt.text().length());
            if (cacheZeichen.get() + n2 < CACHE_MAX) {
                inhalte.put(d.pfad(), neu);
                cacheZeichen.addAndGet(n2);
            } else if (alt != null) {
                inhalte.remove(d.pfad());
            }
            return text == null ? null : neu;
        } catch (IOException e) {
            return null;
        }
    }

    /** Inhalt durchsuchen (im Hintergrund); gross geschrieben = genau, sonst ohne Gross/Klein */
    void inhaltSuchen(String q, int max, BiConsumer<List<Treffer>, Boolean> ergebnis) {
        int nr = suchNr.incrementAndGet();
        if (q.isEmpty()) {
            ergebnis.accept(List.of(), true);
            return;
        }
        sucher.submit(() -> {
            boolean genau = !q.equals(klein(q));
            String ql = genau ? q : klein(q);
            List<Treffer> r = new ArrayList<>();
            int seit = 0;
            long gezeigt = System.currentTimeMillis();
            for (Datei d : dateien) {
                if (suchNr.get() != nr) return;
                Inhalt in = inhalt(d);
                if (in == null) continue;
                String t = in.text();
                String such = genau ? t : in.klein();
                int von = such.indexOf(ql);
                int zeile = 0, zeilAnf = 0, gezaehlt = 0;
                while (von >= 0 && r.size() < max) {
                    for (int k = gezaehlt; k < von; k++) {
                        if (t.charAt(k) == '\n') {
                            zeile++;
                            zeilAnf = k + 1;
                        }
                    }
                    gezaehlt = von;
                    int e = t.indexOf('\n', von);
                    if (e < 0) e = t.length();
                    String z = t.substring(zeilAnf, Math.min(e, zeilAnf + 400));
                    r.add(new Treffer(d.pfad(), d.rel(), zeile, von - zeilAnf, q.length(), z));
                    von = such.indexOf(ql, e);
                }
                if (r.size() >= max) break;
                // zwischendurch zeigen, was schon da ist
                if (r.size() > seit && System.currentTimeMillis() - gezeigt > 200) {
                    seit = r.size();
                    gezeigt = System.currentTimeMillis();
                    List<Treffer> zwischen = List.copyOf(r);
                    if (suchNr.get() == nr) ergebnis.accept(zwischen, false);
                }
            }
            if (suchNr.get() == nr) ergebnis.accept(r, true);
        });
    }
}
