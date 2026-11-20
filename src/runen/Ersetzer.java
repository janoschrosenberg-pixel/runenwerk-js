package runen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * Helfer fuer das Update unter Windows: dort ist die laufende Jar gesperrt. Er laeuft als eigener Prozess (aus der
 * Kopie runenjs.jar.alt), wartet, bis der Editor beendet ist, ersetzt die Jar durch die geladene runenjs.jar.neu und
 * startet den Editor neu.
 *
 * <pre>
 *   java -cp runenjs.jar.alt runen.Ersetzer PID NEU ZIEL LOG [BEFEHL ZUM NEUSTART ...]
 * </pre>
 */
public final class Ersetzer {
    private Ersetzer() {}

    public static void main(String[] a) {
        if (a.length < 4) {
            System.err.println("Ersetzer PID NEU ZIEL LOG [BEFEHL ...]");
            System.exit(2);
        }
        long pid = Long.parseLong(a[0]);
        Path neu = Path.of(a[1]), ziel = Path.of(a[2]), log = Path.of(a[3]);
        List<String> neustart = Arrays.asList(a).subList(4, a.length);
        System.exit(ausfuehren(pid, neu, ziel, log, neustart) ? 0 : 1);
    }

    /** warten, ersetzen, neu starten; true wenn ersetzt */
    static boolean ausfuehren(long pid, Path neu, Path ziel, Path log, List<String> neustart) {
        schreiben(log, "warte auf Prozess " + pid);
        ProcessHandle.of(pid).ifPresent(h -> h.onExit().join());
        boolean ersetzt = false;
        IOException letzter = null;
        // Windows gibt die Datei manchmal erst kurz nach dem Prozessende frei
        for (int i = 0; i < 120 && !ersetzt; i++) {
            try {
                Files.move(neu, ziel, StandardCopyOption.REPLACE_EXISTING);
                ersetzt = true;
            } catch (IOException e) {
                letzter = e;
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        schreiben(log, ersetzt ? "ersetzt: " + ziel : "nicht ersetzt: " + (letzter == null ? "?" : letzter.getMessage()) + " - die neue Jar liegt als " + neu);
        if (!neustart.isEmpty()) {
            try {
                new ProcessBuilder(neustart).start();
                schreiben(log, "neu gestartet: " + String.join(" ", neustart));
            } catch (IOException e) {
                schreiben(log, "Neustart nicht moeglich: " + e.getMessage());
            }
        }
        return ersetzt;
    }

    private static void schreiben(Path log, String s) {
        try {
            Files.writeString(log, LocalDateTime.now().withNano(0) + "  " + s + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // ohne Protokoll
        }
    }
}
