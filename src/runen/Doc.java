package runen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Ein offener Text (Tab): Inhalt mit \n, Zeilenanfaenge, Cursor, Undo-Staende. */
final class Doc {
    record Stand(String text, int cursor) {}

    record Diag(int zeile, int spalte, int endZeile, int endSpalte, int schwere, String text) {}

    Path pfad;
    final StringBuilder t = new StringBuilder();
    private int[] anf = {0};
    private int zeilen = 1;
    private boolean frisch = true;
    boolean crlf;
    boolean geaendert;
    int cursor;
    /** gewuenschte Spalte fuer hoch/runter (-1: aktuelle) */
    int spalteMerk = -1;
    int oben, links;
    /** Zaehler fuer Aenderungen (Hervorhebung, Language Server) */
    int version;
    int lspVersion = -1;
    boolean lspOffen;
    final Deque<Stand> undo = new ArrayDeque<>();
    final Deque<Stand> redo = new ArrayDeque<>();
    byte[] farben;
    int farbenVersion = -1;
    List<Diag> diags = new ArrayList<>();

    static Doc laden(Path p) throws IOException {
        Doc d = new Doc();
        d.pfad = p;
        if (Files.exists(p)) {
            String s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            d.crlf = s.contains("\r\n");
            d.t.append(s.replace("\r\n", "\n").replace('\r', '\n'));
        } else {
            d.crlf = System.getProperty("os.name").toLowerCase().contains("win");
        }
        return d;
    }

    void speichern() throws IOException {
        String s = t.toString();
        if (crlf) s = s.replace("\n", "\r\n");
        if (pfad.getParent() != null) Files.createDirectories(pfad.getParent());
        Files.write(pfad, s.getBytes(StandardCharsets.UTF_8));
        geaendert = false;
    }

    String name() {
        if (term != null) return "terminal " + term.nr;
        return pfad == null ? "[neu]" : pfad.getFileName().toString();
    }

    /** Sprache fuer Hervorhebung und Language Server (oder null) */
    String sprache() {
        if (pfad == null) return null;
        String n = pfad.getFileName().toString().toLowerCase();
        if (n.endsWith(".tsx")) return "typescriptreact";
        if (n.endsWith(".ts") || n.endsWith(".mts") || n.endsWith(".cts")) return "typescript";
        if (n.endsWith(".jsx")) return "javascriptreact";
        if (n.endsWith(".js") || n.endsWith(".mjs") || n.endsWith(".cjs")) return "javascript";
        if (n.endsWith(".java")) return "java";
        if (n.endsWith(".sql") || n.endsWith(".pks") || n.endsWith(".pkb") || n.endsWith(".pls") || n.endsWith(".prc") || n.endsWith(".fnc") || n.endsWith(".trg")) return "sql";
        return null;
    }

    /** Sprache mit Language Server: typescript (auch JS) oder java */
    String lspArt() {
        String s = sprache();
        if (s == null || s.equals("sql")) return null;
        return s.equals("java") ? "java" : "ts";
    }

    /** aktives Datenbankkonto (SQL-Dateien) */
    String dbKonto;
    /** git blame je Zeile (sonst null) */
    Werkzeug.Blame blame;
        /** Terminal-Tab (sonst null) */
    Terminal term;
        /** Ergebnistabelle der letzten Abfrage in dieser Datei */
    Werkzeug.Tabelle tabelle;

    boolean jsx() {
        String s = sprache();
        return s != null && (s.endsWith("react") || s.equals("javascript"));
    }

    // ---------------------------------------------------------------- Zeilen
    private void index() {
        if (!frisch) return;
        int n = 1;
        for (int k = 0; k < t.length(); k++) if (t.charAt(k) == '\n') n++;
        if (anf.length < n) anf = new int[n + 64];
        int z = 1;
        anf[0] = 0;
        for (int k = 0; k < t.length(); k++) if (t.charAt(k) == '\n') anf[z++] = k + 1;
        zeilen = n;
        frisch = false;
    }

    int zeilen() {
        index();
        return zeilen;
    }

    int zeileVon(int pos) {
        index();
        int lo = 0, hi = zeilen - 1;
        while (lo < hi) {
            int m = (lo + hi + 1) >>> 1;
            if (anf[m] <= pos) lo = m;
            else hi = m - 1;
        }
        return lo;
    }

    int anf(int z) {
        index();
        return anf[Math.max(0, Math.min(z, zeilen - 1))];
    }

    /** Ende der Zeile (vor dem \n) */
    int ende(int z) {
        index();
        z = Math.max(0, Math.min(z, zeilen - 1));
        return z + 1 < zeilen ? anf[z + 1] - 1 : t.length();
    }

    String zeile(int z) {
        return t.substring(anf(z), ende(z));
    }

    int spalte(int pos) {
        return pos - anf(zeileVon(pos));
    }

    /** Position aus Zeile und Spalte (begrenzt auf die Zeile) */
    int stelle(int z, int sp) {
        z = Math.max(0, Math.min(z, zeilen() - 1));
        return Math.min(anf(z) + Math.max(0, sp), ende(z));
    }

    char at(int pos) {
        return pos >= 0 && pos < t.length() ? t.charAt(pos) : '\n';
    }

    // ---------------------------------------------------------------- Aendern
    void einfuegen(int pos, String s) {
        if (s.isEmpty()) return;
        t.insert(pos, s);
        if (cursor >= pos) cursor += s.length();
        geaendert();
    }

    void loeschen(int pos, int n) {
        if (pos < 0) {
            n += pos;
            pos = 0;
        }
        n = Math.min(n, t.length() - pos);
        if (n <= 0) return;
        t.delete(pos, pos + n);
        if (cursor > pos + n) cursor -= n;
        else if (cursor > pos) cursor = pos;
        geaendert();
    }

    void ersetzen(int a, int e, String s) {
        int c = cursor;
        t.replace(a, e, s);
        if (c >= e) cursor = c + s.length() - (e - a);
        else if (c > a) cursor = a;
        geaendert();
    }

    void setText(String s) {
        t.setLength(0);
        t.append(s);
        cursor = Math.min(cursor, t.length());
        geaendert();
    }

    private void geaendert() {
        frisch = true;
        geaendert = true;
        version++;
    }

    // ---------------------------------------------------------------- Undo
    void merken() {
        undo.push(new Stand(t.toString(), cursor));
        while (undo.size() > 500) undo.removeLast();
        redo.clear();
    }

    boolean undo() {
        if (undo.isEmpty()) return false;
        redo.push(new Stand(t.toString(), cursor));
        Stand s = undo.pop();
        setText(s.text());
        cursor = Math.min(s.cursor(), t.length());
        return true;
    }

    boolean redo() {
        if (redo.isEmpty()) return false;
        undo.push(new Stand(t.toString(), cursor));
        Stand s = redo.pop();
        setText(s.text());
        cursor = Math.min(s.cursor(), t.length());
        return true;
    }
}
