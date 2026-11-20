package runen;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import javax.swing.SwingUtilities;

/**
 * Selbst-Update: beim Start (update.check = on) das neueste Release auf GitHub fragen; ist es neuer als die eigene
 * Version (vorgaben/version.txt in der Jar), fragt der Editor nach. Bei "ja" laedt er runenjs.jar, prueft sie,
 * ersetzt die laufende Jar (die alte bleibt als runenjs.jar.alt) und startet neu.
 */
final class Aktualisierung {
    static final String QUELLE = "https://api.github.com/repos/janoschrosenberg-pixel/runenwerk-js/releases/latest";

    private final Editor ed;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private volatile boolean laeuft;

    record Release(String tag, String version, String url, long groesse, String notizen) {}

    Aktualisierung(Editor ed) {
        this.ed = ed;
    }

    /** eigene Version aus der Jar ("1.36.0"), null beim Start aus den Quellen */
    static String eigeneVersion() {
        try (InputStream in = Aktualisierung.class.getResourceAsStream("/vorgaben/version.txt")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return null;
        }
    }

    /** die laufende Jar (oder runenwerk.jar als Systemeigenschaft, fuer Tests), sonst null */
    static Path eigeneJar() {
        String p = System.getProperty("runenwerk.jar");
        if (p != null) return Path.of(p);
        try {
            Path j = Path.of(Aktualisierung.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return j.toString().endsWith(".jar") && Files.isRegularFile(j) ? j : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 1.10.0 > 1.9.3; Teile, die keine Zahl sind, zaehlen als 0 */
    static int vergleichen(String a, String b) {
        String[] x = a.replaceFirst("^v", "").split("[.-]"), y = b.replaceFirst("^v", "").split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? zahl(x[i]) : 0, q = i < y.length ? zahl(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int zahl(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String quelle() {
        String u = ed.kf.get("update.url", "").strip();
        return u.isEmpty() ? QUELLE : u;
    }

    /** neuestes Release (oder null mit Grund in fehler[0]) */
    Release neuestes(String[] fehler) {
        try {
            HttpRequest r = HttpRequest.newBuilder(URI.create(quelle())).timeout(Duration.ofSeconds(15)).header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "RUNENWERK-JS").GET().build();
            HttpResponse<String> a = http.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (a.statusCode() != 200) {
                fehler[0] = "HTTP " + a.statusCode();
                return null;
            }
            Object j = Json.parse(a.body());
            String tag = Json.str(Json.get(j, "tag_name"));
            if (tag == null || Boolean.TRUE.equals(Json.get(j, "draft")) || Boolean.TRUE.equals(Json.get(j, "prerelease"))) {
                fehler[0] = "kein fertiges Release";
                return null;
            }
            for (Object as : Json.list(Json.get(j, "assets"))) {
                if ("runenjs.jar".equals(Json.str(Json.get(as, "name")))) {
                    String body = Json.str(Json.get(j, "body"));
                    return new Release(tag, tag.replaceFirst("^v", ""), Json.str(Json.get(as, "browser_download_url")), Mattermost.lang(Json.get(as, "size")),
                            body == null ? "" : body);
                }
            }
            fehler[0] = tag + " hat keine runenjs.jar";
            return null;
        } catch (IOException | RuntimeException e) {
            fehler[0] = e.getMessage() == null ? e.toString() : e.getMessage();
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fehler[0] = "unterbrochen";
            return null;
        }
    }

    /** beim Start: still pruefen, nur bei einer neueren Version nachfragen */
    void beimStart() {
        if (!ed.kf.an("update.check", true) || eigeneVersion() == null || eigeneJar() == null) return;
        pruefen(false);
    }

    /** pruefen; laut = auch melden, wenn alles aktuell ist oder etwas schiefgeht */
    void pruefen(boolean laut) {
        String ich = eigeneVersion();
        if (ich == null) {
            if (laut) ed.fehler("Update: keine Versionsangabe (laeuft nicht aus runenjs.jar)");
            return;
        }
        if (laeuft) return;
        laeuft = true;
        if (laut) ed.meldung = "Update: suche nach einer neuen Version ...";
        Thread t = new Thread(() -> {
            String[] f = new String[1];
            Release r = neuestes(f);
            SwingUtilities.invokeLater(() -> {
                laeuft = false;
                if (r == null) {
                    if (laut) ed.fehler("Update: nicht geprueft (" + f[0] + ")");
                } else if (vergleichen(r.version(), ich) <= 0) {
                    if (laut) ed.meldung = "Update: " + ich + " ist aktuell";
                } else if (!laut && r.tag().equals(ed.kf.get("update.skip", ""))) {
                    // diese Version wurde uebersprungen
                } else {
                    fragen(r, ich);
                }
                ed.neuMalen.run();
            });
        }, "update");
        t.setDaemon(true);
        t.start();
    }

    private void fragen(Release r, String ich) {
        // ein anderes Fenster ist offen (z. B. Arbeitsordner waehlen): spaeter fragen
        if (ed.popupOffen || ed.wz.auswahlOffen || ed.emoji.offen) {
            javax.swing.Timer t = new javax.swing.Timer(2000, e -> fragen(r, ich));
            t.setRepeats(false);
            t.start();
            return;
        }
        String neu = erstePunkte(r.notizen());
        ed.wz.auswahlTasten("Neue Version " + r.tag() + " (du hast " + ich + ")" + (neu.isEmpty() ? "" : ": " + neu) + " - jetzt aktualisieren?",
                List.of("[j] Ja, jetzt aktualisieren und neu starten", "[n] Nein, spaeter fragen", "[u] Diese Version ueberspringen"), w -> {
                    if (w.startsWith("[j]")) aktualisieren(r);
                    else if (w.startsWith("[u]")) {
                        ed.kf.setzen("update.skip", r.tag());
                        ed.meldung = "Update: " + r.tag() + " wird uebersprungen (:updateCheck fragt trotzdem)";
                    } else {
                        ed.meldung = "Update: beim naechsten Start wieder fragen (:updateCheck jederzeit)";
                    }
                });
    }

    /** Ueberschriften der Release-Notizen als Kurzfassung */
    static String erstePunkte(String notizen) {
        List<String> r = new ArrayList<>();
        for (String z : notizen.split("\\R")) if (z.startsWith("### ")) r.add(z.substring(4).strip());
        String s = String.join(", ", r);
        return s.length() > 80 ? s.substring(0, 79) + "~" : s;
    }

    /** nur Downloads von GitHub (oder von update.url, wenn gesetzt) */
    private boolean erlaubt(String url) {
        if (url == null) return false;
        if (!ed.kf.get("update.url", "").isBlank()) return true;
        URI u = URI.create(url);
        return "https".equals(u.getScheme()) && u.getHost() != null && (u.getHost().equals("github.com") || u.getHost().endsWith(".githubusercontent.com"));
    }

    void aktualisieren(Release r) {
        Path jar = eigeneJar();
        if (jar == null) {
            ed.fehler("Update: laeuft nicht aus runenjs.jar - bitte von " + r.url() + " laden");
            return;
        }
        if (!erlaubt(r.url())) {
            ed.fehler("Update: unerwartete Download-Adresse " + r.url());
            return;
        }
        ed.meldung = "Update: lade " + r.tag() + " (" + r.groesse() / (1024 * 1024) + " MB) ...";
        Thread t = new Thread(() -> {
            String f = laden(r, jar);
            SwingUtilities.invokeLater(() -> {
                if (f != null) {
                    ed.fehler("Update fehlgeschlagen: " + f);
                    return;
                }
                neuStarten(r, jar);
            });
        }, "update");
        t.setDaemon(true);
        t.start();
    }

    /** herunterladen, pruefen und die Jar ersetzen; null wenn gelungen, sonst der Grund */
    String laden(Release r, Path jar) {
        Path neu = jar.resolveSibling(jar.getFileName() + ".neu"), alt = jar.resolveSibling(jar.getFileName() + ".alt");
        try {
            HttpRequest q = HttpRequest.newBuilder(URI.create(r.url())).timeout(Duration.ofMinutes(10)).header("User-Agent", "RUNENWERK-JS")
                    .header("Accept", "application/octet-stream").GET().build();
            HttpResponse<Path> a = http.send(q, HttpResponse.BodyHandlers.ofFile(neu));
            if (a.statusCode() != 200) {
                Files.deleteIfExists(neu);
                return "HTTP " + a.statusCode();
            }
            long g = Files.size(neu);
            if (r.groesse() > 0 && g != r.groesse()) {
                Files.deleteIfExists(neu);
                return "unvollstaendig (" + g + " von " + r.groesse() + " Bytes)";
            }
            // ist es eine Jar mit dem Editor?
            try (JarFile jf = new JarFile(neu.toFile())) {
                if (jf.getEntry("runen/Main.class") == null) {
                    Files.deleteIfExists(neu);
                    return "die geladene Datei ist keine RUNENWERK-Jar";
                }
            }
            Files.copy(jar, alt, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(neu, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(neu, jar, StandardCopyOption.REPLACE_EXISTING);
            }
            return null;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(neu);
            } catch (IOException ex) {
                // bleibt liegen
            }
            return e.getMessage() == null ? e.toString() : e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unterbrochen";
        }
    }

    /** mit derselben Befehlszeile neu starten; mit ungespeicherten Aenderungen erst beim naechsten Start */
    private void neuStarten(Release r, Path jar) {
        for (Doc d : ed.tabs) {
            if (d.geaendert && d.chat == null && d.pfad != null) {
                ed.meldung = "Update auf " + r.tag() + " installiert - ungespeicherte Aenderungen (" + d.name() + "): nach dem Speichern neu starten";
                return;
            }
        }
        if (!ed.kf.an("update.restart", true) || Boolean.getBoolean("runenwerk.ohneNeustart")) {
            ed.meldung = "Update auf " + r.tag() + " installiert - wirkt beim naechsten Start";
            return;
        }
        List<String> befehl = new ArrayList<>();
        ProcessHandle.Info info = ProcessHandle.current().info();
        String java = info.command().orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        befehl.add(java);
        String[] args = info.arguments().orElse(null);
        if (args != null && args.length > 0) {
            befehl.addAll(List.of(args));
        } else {
            befehl.add("-jar");
            befehl.add(jar.toString());
            if (ed.arbeitsordner != null) befehl.add(ed.arbeitsordner.toString());
        }
        try {
            new ProcessBuilder(befehl).inheritIO().start();
        } catch (IOException e) {
            ed.meldung = "Update auf " + r.tag() + " installiert - Neustart nicht moeglich (" + e.getMessage() + "), bitte selbst neu starten";
            return;
        }
        ed.quit(true);
    }

    void befehle(Map<String, Editor.Cmd> c) {
        /** @binding Nach einer neuen Version suchen und bei Bedarf aktualisieren (auch beim Start, update.check = off schaltet das ab) */
        c.put("updateCheck", a -> pruefen(true));
    }
}
