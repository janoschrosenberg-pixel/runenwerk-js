package runen;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;

/**
 * Terminal als Tab: eine dauerhaft laufende Shell (cmd.exe bzw. die Login-Shell), deren Ausgabe
 * im Text des Tabs landet. Eingegeben wird in der Zeile darunter. Nach jedem Befehl meldet die Shell
 * eine Endmarke mit Exit-Code und Ordner. Ohne Pseudo-Terminal: Zeilenprogramme laufen, Vollbild-
 * Programme (vim, top) nicht.
 */
final class Terminal {
    final int nr;
    /** Name des Tabs (sonst "terminal N") */
    String titel;
    private final Editor ed;
    private final Doc doc;
    private Process p;
    private OutputStream ein;
    private Path ordner;
    private final String marke = "__RUNEN_ENDE_" + Long.toHexString(System.nanoTime()) + "__";
    final StringBuilder eingabe = new StringBuilder();
    int eingabeCursor;
    private final List<String> verlauf = new ArrayList<>();
    private int verlaufPos;
    volatile boolean laeuft;
    private int letzterCode;

    Terminal(Editor ed, Doc doc, Path ordner, int nr) {
        this.ed = ed;
        this.doc = doc;
        this.ordner = ordner;
        this.nr = nr;
    }

    Path ordner() {
        return ordner;
    }

    void starten() {
        List<String> b;
        Charset cs = StandardCharsets.UTF_8;
        if (Lsp.WINDOWS) {
            // /Q: Befehle nicht wiederholen; chcp 65001 fuer UTF-8
            b = List.of("cmd.exe", "/Q", "/K", "chcp 65001>nul");
        } else {
            String sh = System.getenv("SHELL");
            b = List.of(sh != null && Files.isExecutable(Path.of(sh)) ? sh : "/bin/sh", "-l");
        }
        ProcessBuilder pb = new ProcessBuilder(b).directory(ordner.toFile()).redirectErrorStream(true);
        Lsp.umgebung(pb);
        pb.environment().put("TERM", "dumb");
        pb.environment().put("NO_COLOR", "1");
        pb.environment().put("PAGER", "cat");
        pb.environment().put("GIT_PAGER", "cat");
        try {
            p = pb.start();
        } catch (IOException e) {
            schreiben("Shell nicht gestartet: " + e.getMessage() + "\n");
            return;
        }
        ein = p.getOutputStream();
        Thread t = new Thread(() -> lesen(p.getInputStream(), cs), "terminal-" + nr);
        t.setDaemon(true);
        t.start();
        schreiben("Terminal " + nr + " in " + ordner + "  (i: Eingabe, ESC: Text markieren/kopieren, Strg+C: Befehl abbrechen)\n");
    }

    private void lesen(InputStream in, Charset cs) {
        StringBuilder zeile = new StringBuilder();
        byte[] puffer = new byte[8192];
        java.io.ByteArrayOutputStream roh = new java.io.ByteArrayOutputStream();
        try {
            int n;
            while ((n = in.read(puffer)) > 0) {
                roh.write(puffer, 0, n);
                // nur ganze UTF-8-Zeichen weitergeben
                String t = new String(roh.toByteArray(), cs);
                if (t.endsWith("�") && n == puffer.length) continue;
                roh.reset();
                StringBuilder fertig = new StringBuilder();
                for (char c : t.toCharArray()) {
                    if (c == '\r') continue;
                    zeile.append(c);
                    if (c == '\n') {
                        verarbeiten(zeile.toString(), fertig);
                        zeile.setLength(0);
                    }
                }
                // angefangene Zeile (z. B. eine Rueckfrage ohne Zeilenende) gleich zeigen
                if (zeile.length() > 0 && in.available() == 0 && !zeile.toString().contains(marke.substring(0, 8))) {
                    fertig.append(zeile);
                    zeile.setLength(0);
                }
                if (fertig.length() > 0) schreiben(fertig.toString());
            }
        } catch (IOException e) {
            // Shell beendet
        }
        schreiben("[Shell beendet]\n");
        laeuft = false;
    }

    private void verarbeiten(String z, StringBuilder fertig) {
        int m = z.indexOf(marke);
        // cmd.exe setzt trotz /Q manchmal seine Eingabeaufforderung (C:\pfad>) davor
        if (Lsp.WINDOWS) z = z.replaceFirst("^[A-Za-z]:\\\\[^<>|\r\n]*>", "");
        if (m < 0) {
            fertig.append(z.replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", "").replaceAll("\u001B\\][^\u0007]*\u0007", ""));
            return;
        }
        if (m > 0) fertig.append(z, 0, m).append('\n');
        // Marke: CODE:ORDNER
        String rest = z.substring(m + marke.length()).strip();
        int d = rest.indexOf(':');
        try {
            letzterCode = Integer.parseInt(rest.substring(0, d).strip());
        } catch (RuntimeException e) {
            letzterCode = 0;
        }
        String o = d < 0 ? "" : rest.substring(d + 1).strip();
        if (!o.isEmpty()) {
            try {
                ordner = Path.of(o);
            } catch (RuntimeException e) {
                // bleibt
            }
        }
        laeuft = false;
        if (letzterCode != 0) fertig.append("[Exit-Code ").append(letzterCode).append("]\n");
    }

    /** Text ans Ende des Tabs (im Swing-Faden); folgt der Cursor dem Ende, bleibt er dort */
    private void schreiben(String t) {
        SwingUtilities.invokeLater(() -> {
            boolean amEnde = doc.cursor >= doc.t.length() - 1;
            doc.einfuegen(doc.t.length(), t);
            if (amEnde) doc.cursor = doc.t.length();
            doc.geaendert = false;
            // nicht unbegrenzt wachsen
            if (doc.t.length() > 2_000_000) doc.loeschen(0, doc.t.length() - 1_500_000);
            ed.neuMalen.run();
        });
    }

    /** Eingabezeile ausfuehren */
    void ausfuehren() {
        String b = eingabe.toString();
        eingabe.setLength(0);
        eingabeCursor = 0;
        if (!b.isBlank() && (verlauf.isEmpty() || !verlauf.get(verlauf.size() - 1).equals(b))) verlauf.add(b);
        verlaufPos = verlauf.size();
        schreiben(prompt() + b + "\n");
        if (b.strip().equals("clear") || b.strip().equals("cls")) {
            SwingUtilities.invokeLater(() -> {
                doc.setText("");
                doc.cursor = 0;
                doc.geaendert = false;
            });
            return;
        }
        if (ein == null || !p.isAlive()) {
            schreiben("Shell laeuft nicht (Tab schliessen und neu oeffnen)\n");
            return;
        }
        laeuft = true;
        String endeBefehl = Lsp.WINDOWS ? "echo " + marke + "%errorlevel%:%cd%" : "printf '%s%s:%s\\n' '" + marke + "' \"$?\" \"$PWD\"";
        try {
            ein.write((b + "\n" + endeBefehl + "\n").getBytes(StandardCharsets.UTF_8));
            ein.flush();
        } catch (IOException e) {
            schreiben("nicht gesendet: " + e.getMessage() + "\n");
        }
    }

    /** laufenden Befehl abbrechen (Kindprozesse der Shell beenden) */
    void abbrechen() {
        if (p == null) return;
        p.descendants().forEach(ProcessHandle::destroy);
        schreiben("^C\n");
    }

    void beenden() {
        if (p == null) return;
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
    }

    String prompt() {
        String o = ordner.toString();
        String home = System.getProperty("user.home");
        if (o.startsWith(home)) o = "~" + o.substring(home.length());
        return o + (laeuft ? " (laeuft)" : "") + " $ ";
    }

    // ---------------------------------------------------------------- Eingabezeile
    void zeichen(String c) {
        eingabe.insert(eingabeCursor, c);
        eingabeCursor += c.length();
    }

    void zurueck() {
        if (eingabeCursor == 0) return;
        eingabe.deleteCharAt(--eingabeCursor);
    }

    void links() {
        eingabeCursor = Math.max(0, eingabeCursor - 1);
    }

    void rechts() {
        eingabeCursor = Math.min(eingabe.length(), eingabeCursor + 1);
    }

    void verlauf(int d) {
        if (verlauf.isEmpty()) return;
        verlaufPos = Math.max(0, Math.min(verlauf.size(), verlaufPos + d));
        eingabe.setLength(0);
        if (verlaufPos < verlauf.size()) eingabe.append(verlauf.get(verlaufPos));
        eingabeCursor = eingabe.length();
    }
}
