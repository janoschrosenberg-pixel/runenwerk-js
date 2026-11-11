package runen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Code Actions des Language Servers (Java: jdtls, TypeScript: typescript-language-server): Methode
 * extrahieren aus der Auswahl, fehlende Importe (bei mehreren Kandidaten waehlt man) und alle Aktionen an der
 * Stelle (Alt+ENTER).
 */
final class CodeAktion {
    private final Editor ed;
    private Consumer<String> eingabe;

    CodeAktion(Editor ed) {
        this.ed = ed;
    }

    // ================================================================ Anfrage und Ausfuehren
    private Map<String, Object> bereich(Doc d, int a, int e) {
        int za = d.zeileVon(a), ze = d.zeileVon(e);
        return Json.obj("start", Json.obj("line", za, "character", a - d.anf(za)), "end", Json.obj("line", ze, "character", e - d.anf(ze)));
    }

    /** Diagnosen des Servers, die den Bereich beruehren */
    private List<Object> diagnosenIn(Doc d, int a, int e) {
        List<Object> r = new ArrayList<>();
        for (Object o : ed.rohDiagnosen(d)) {
            int da = ed.offset(d, Json.get(o, "range", "start")), de = ed.offset(d, Json.get(o, "range", "end"));
            if (da <= e && de >= a) r.add(o);
        }
        return r;
    }

    /** textDocument/codeAction fuer [a, e); only = Arten (null: alle) */
    private void anfragen(Doc d, int a, int e, List<Object> diags, List<String> only, Consumer<List<Object>> weiter) {
        Lsp lsp = ed.lspFuer(d);
        ed.lspSync();
        Map<String, Object> ctx = Json.obj("diagnostics", diags, "triggerKind", 1);
        if (only != null) ctx.put("only", only);
        lsp.anfrage("textDocument/codeAction", Json.obj("textDocument", Json.obj("uri", Lsp.uri(d.pfad)), "range", bereich(d, a, e), "context", ctx), r -> {
            weiter.accept(r == null ? List.of() : Json.list(r));
            ed.neuMalen.run();
        });
    }

    /**
     * Aktion ausfuehren: mit edit direkt, mit command ueber workspace/executeCommand (der Server schickt die
     * Aenderung dann mit workspace/applyEdit), ohne beides erst codeAction/resolve
     */
    private void ausfuehren(Doc d, Object aktion, Runnable danach) {
        Lsp lsp = ed.lspFuer(d);
        Object edit = Json.get(aktion, "edit");
        Object cmd = Json.get(aktion, "command");
        // eine reine Command-Antwort: {title, command: "name", arguments}
        if (cmd instanceof String) cmd = aktion;
        if (edit == null && cmd == null && Json.get(aktion, "data") != null) {
            lsp.anfrage("codeAction/resolve", aktion, r -> {
                if (r == null) {
                    ed.meldung = "Aktion nicht aufgeloest";
                    return;
                }
                ausfuehren(d, r, danach);
            });
            return;
        }
        if (edit != null) ed.editAnwenden(edit);
        if (cmd != null) {
            Map<String, Object> p = Json.obj("command", Json.get(cmd, "command"));
            if (Json.get(cmd, "arguments") != null) p.put("arguments", Json.get(cmd, "arguments"));
            lsp.anfrage("workspace/executeCommand", p, r -> {
                ed.lspSync();
                if (danach != null) danach.run();
                ed.neuMalen.run();
            });
            return;
        }
        if (danach != null) danach.run();
    }

    private static String titel(Object a) {
        String t = Json.str(Json.get(a, "title"));
        return t == null ? "?" : t;
    }

    private static String art(Object a) {
        String k = Json.str(Json.get(a, "kind"));
        return k == null ? "" : k;
    }

    private void frage(String titel, String vorgabe, Consumer<String> weiter) {
        eingabe = weiter;
        ed.popupOpen(titel, "codeActionInput", vorgabe);
    }

    private boolean bereit(Doc d) {
        if (!ed.lspBereit()) return false;
        if (d.pfad == null) {
            ed.meldung = "erst speichern";
            return false;
        }
        return true;
    }

    // ================================================================ Methode extrahieren
    private static final Pattern AUFRUF = Pattern.compile("\\b([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(");
    private static final Pattern ERZEUGT = Pattern.compile("\\b(extracted\\d*|newFunction\\d*|newMethod\\d*|extractedMethod\\d*)\\s*\\(");

    /** Auswahl [a, e) in eine neue Methode/Funktion: Variante waehlen (TypeScript: Gueltigkeitsbereich), Name, umbenennen */
    void methodeExtrahieren(int a, int e) {
        Doc d = ed.d();
        if (!bereit(d)) return;
        if (e <= a) {
            ed.meldung = "erst einen Codeblock markieren (v oder V), dann Leertaste c m";
            return;
        }
        // ganze Zeilen ohne den letzten Zeilenumbruch, sonst lehnt jdtls die Auswahl gern ab
        while (e > a && Character.isWhitespace(d.at(e - 1))) e--;
        while (a < e && Character.isWhitespace(d.at(a))) a++;
        int aa = a, ee = e;
        ed.meldung = "frage den Language Server ...";
        anfragen(d, a, e, diagnosenIn(d, a, e), List.of("refactor.extract"), l -> {
            List<Object> m = new ArrayList<>();
            for (Object x : l) {
                String k = art(x), t = titel(x).toLowerCase();
                if (k.startsWith("refactor.extract.function") || k.startsWith("refactor.extract.method") || t.contains("extract to method")
                        || t.contains("extract method") || t.contains("extract to function") || t.contains("extract to inner function")
                        || t.contains("extract to method in")) m.add(x);
            }
            if (m.isEmpty()) {
                ed.meldung = "keine Methode aus dieser Auswahl (ganze Anweisungen markieren)" + (l.isEmpty() ? "" : "; moeglich: " + titelListe(l));
                return;
            }
            if (m.size() == 1) {
                nameFragen(d, m.get(0), aa, ee);
                return;
            }
            Map<String, Object> nach = new LinkedHashMap<>();
            for (Object x : m) nach.put(titel(x), x);
            ed.wz.auswahl("Methode extrahieren: wohin?", new ArrayList<>(nach.keySet()), null, w -> nameFragen(d, nach.get(w), aa, ee));
        });
    }

    private static String titelListe(List<Object> l) {
        List<String> t = new ArrayList<>();
        for (Object x : l) t.add(titel(x));
        return String.join(", ", t);
    }

    private void nameFragen(Doc d, Object aktion, int a, int e) {
        frage("Name der neuen Methode (" + titel(aktion) + "; leer = Vorschlag des Servers):", "", name -> {
            String n = name.strip();
            if (!n.isEmpty() && !n.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                ed.fehler("kein gueltiger Name: " + n);
                return;
            }
            String vorher = d.t.toString();
            d.merken();
            ausfuehren(d, aktion, () -> umbenennen(d, vorher, n));
        });
    }

    /** den erzeugten Namen (extracted, newFunction ...) finden und mit dem Language Server umbenennen */
    private void umbenennen(Doc d, String vorher, String name) {
        String jetzt = d.t.toString();
        if (jetzt.equals(vorher)) {
            ed.meldung = "nichts extrahiert";
            return;
        }
        // neuer Name: vorher nicht da, jetzt mindestens zweimal (Deklaration und Aufruf), sonst die bekannten Vorschlaege
        String erzeugt = null;
        Matcher m = AUFRUF.matcher(jetzt);
        while (m.find() && erzeugt == null) {
            String k = m.group(1);
            if (anzahl(vorher, k) == 0 && anzahl(jetzt, k) >= 2) erzeugt = k;
        }
        m = ERZEUGT.matcher(jetzt);
        while (erzeugt == null && m.find()) {
            String k = m.group(1);
            if (anzahl(jetzt, k) > anzahl(vorher, k)) erzeugt = k;
        }
        if (erzeugt == null || name.isEmpty() || name.equals(erzeugt)) {
            ed.meldung = "Methode " + (erzeugt == null ? "" : erzeugt + " ") + "extrahiert (noch nicht gespeichert)";
            return;
        }
        // Cursor auf den Aufruf/die Deklaration, dann umbenennen wie F2
        int i = jetzt.indexOf(erzeugt + "(");
        if (i < 0) i = jetzt.indexOf(erzeugt);
        if (ed.d() != d) return;
        d.cursor = i;
        String alt = erzeugt;
        ed.lspSync();
        ed.renameTo(name, "Methode " + name + " extrahiert (noch nicht gespeichert)");
        ed.meldung = "extrahiert als " + alt + ", benenne um in " + name + " ...";
    }

    private static int anzahl(String s, String w) {
        int n = 0;
        Matcher m = Pattern.compile("\\b" + Pattern.quote(w) + "\\b").matcher(s);
        while (m.find()) n++;
        return n;
    }

    // ================================================================ Importe
    private static boolean istImport(Object a) {
        String t = titel(a).toLowerCase();
        if (t.contains("remove") || t.contains("unused") || t.contains("all missing") || t.contains("organize") || t.contains("entfern")) return false;
        return t.startsWith("import ") || t.startsWith("add import") || t.startsWith("update import") || t.matches("^(add|change) .*import.*")
                || t.startsWith("import '") || t.contains("import from");
    }

    /**
     * Fehlende Importe der Datei: fuer jede Diagnose "nicht gefunden" die Import-Vorschlaege des Servers; ist es
     * genau einer, wird er genommen, sonst waehlt man aus der Liste (einer nach dem anderen)
     */
    void importe() {
        Doc d = ed.d();
        if (!bereit(d)) return;
        List<Object> diags = new ArrayList<>();
        for (Object g : ed.rohDiagnosen(d)) if (Json.num(Json.get(g, "severity"), 1) == 1) diags.add(g);
        if (diags.isEmpty()) {
            ed.meldung = "keine Fehler in der Datei - nichts zu importieren";
            return;
        }
        ed.meldung = "suche fehlende Importe ...";
        // je Diagnose die Vorschlaege sammeln, dann der Reihe nach abarbeiten
        List<List<Object>> je = new ArrayList<>();
        List<String> namen = new ArrayList<>();
        int[] offen = {diags.size()};
        for (Object g : diags) {
            int a = ed.offset(d, Json.get(g, "range", "start")), e = ed.offset(d, Json.get(g, "range", "end"));
            String wort = d.t.substring(Math.min(a, d.t.length()), Math.min(Math.max(a, e), d.t.length()));
            anfragen(d, a, e, List.of(g), List.of("quickfix"), l -> {
                List<Object> imp = new ArrayList<>();
                for (Object x : l) if (istImport(x)) imp.add(x);
                if (!imp.isEmpty() && !namen.contains(wort)) {
                    namen.add(wort);
                    je.add(imp);
                }
                if (--offen[0] == 0) abarbeiten(d, namen, je, 0, new ArrayList<>());
            });
        }
    }

    /** Reihenfolge der Kandidaten: java.util, java.*, javax/jakarta, eigene; java.awt und interne JDK-Pakete zuletzt */
    private static int rang(Object a) {
        String t = titel(a);
        if (t.contains("(java.util)")) return 0;
        if (t.contains("(java.awt") || t.contains("(org.w3c") || t.contains("(org.omg")) return 8;
        if (t.contains("(java.")) return 1;
        if (t.contains("(javax.") || t.contains("(jakarta.")) return 2;
        if (t.contains("(com.sun.") || t.contains("(sun.") || t.contains("(jdk.") || t.contains("internal")) return 9;
        return 5;
    }

    /** erst fuer jeden Namen waehlen (einer ist sofort genommen), dann alle zusammen auf den unveraenderten Text */
    private void abarbeiten(Doc d, List<String> namen, List<List<Object>> je, int i, List<Object[]> gewaehlt) {
        if (i >= je.size()) {
            zusammenAnwenden(d, gewaehlt);
            return;
        }
        List<Object> l = je.get(i);
        if (l.size() == 1) {
            gewaehlt.add(new Object[] {namen.get(i), l.get(0)});
            abarbeiten(d, namen, je, i + 1, gewaehlt);
            return;
        }
        Map<String, Object> nach = new LinkedHashMap<>();
        List<Object> sortiert = new ArrayList<>(l);
        sortiert.sort(java.util.Comparator.comparingInt(CodeAktion::rang));
        for (Object x : sortiert) nach.put(titel(x), x);
        nach.put("-> " + namen.get(i) + " nicht importieren", null);
        ed.meldung = null;
        ed.wz.auswahl("Import fuer " + namen.get(i) + " (" + l.size() + " Moeglichkeiten, tippen filtert)", new ArrayList<>(nach.keySet()), null, w -> {
            Object x = nach.get(w);
            if (x != null) gewaehlt.add(new Object[] {namen.get(i), x});
            abarbeiten(d, namen, je, i + 1, gewaehlt);
        });
    }

    /** Aenderungen aller gewaehlten Importe in einem Zug (gleiche doppelt nur einmal, ueberlappende nicht); Commands danach */
    private void zusammenAnwenden(Doc d, List<Object[]> gewaehlt) {
        if (gewaehlt.isEmpty()) {
            ed.meldung = "keine Import-Vorschlaege fuer die Fehler der Datei (laedt der Language Server noch? gleich noch einmal versuchen)";
            ed.neuMalen.run();
            return;
        }
        Map<String, List<Object>> je = new LinkedHashMap<>();
        List<Object> befehle = new ArrayList<>();
        List<Object[]> spaeter = new ArrayList<>();
        for (Object[] wx : gewaehlt) {
            Object x = wx[1];
            Object edit = Json.get(x, "edit");
            if (edit == null) {
                befehle.add(x);
                continue;
            }
            // ueberschneidet sich eine Aenderung mit einer schon gewaehlten, kommt dieser Import spaeter dran
            Map<String, List<Object>> e = editsJeDatei(edit);
            boolean passt = true;
            for (var en : e.entrySet()) for (Object t : en.getValue()) if (!je.getOrDefault(en.getKey(), List.of()).contains(t) && ueberlappt(je.getOrDefault(en.getKey(), List.of()), t)) passt = false;
            if (!passt) {
                spaeter.add(wx);
                continue;
            }
            for (var en : e.entrySet()) {
                List<Object> ziel = je.computeIfAbsent(en.getKey(), k -> new ArrayList<>());
                for (Object t : en.getValue()) if (!ziel.contains(t)) ziel.add(t);
            }
        }
        d.merken();
        Map<String, Object> changes = new LinkedHashMap<>(je);
        if (!changes.isEmpty()) ed.editAnwenden(Json.obj("changes", changes));
        int[] n = {gewaehlt.size() - spaeter.size()};
        Runnable fertig = () -> {
            ed.meldung = n[0] + " Import(e) hinzugefuegt (noch nicht gespeichert)";
            ed.neuMalen.run();
        };
        befehleNacheinander(d, befehle, 0, () -> nachholen(d, spaeter, 0, n, fertig));
    }

    /**
     * Importe, deren Aenderung sich mit einer anderen ueberschnitt: einzeln nacheinander, jeweils mit frischen
     * Diagnosen und Vorschlaegen (gleicher Titel wie gewaehlt)
     */
    private void nachholen(Doc d, List<Object[]> l, int i, int[] n, Runnable fertig) {
        if (i >= l.size()) {
            fertig.run();
            return;
        }
        String wort = (String) l.get(i)[0], titel = titel(l.get(i)[1]);
        int stand = ed.diagnosenStand;
        ed.meldung = "Import " + (i + 1) + " von " + l.size() + " nachholen: " + wort + " ...";
        int[] versuche = {0};
        javax.swing.Timer uhr = new javax.swing.Timer(200, null);
        uhr.addActionListener(ev -> {
            versuche[0]++;
            Object diag = null;
            for (Object g : ed.rohDiagnosen(d)) {
                int a = ed.offset(d, Json.get(g, "range", "start")), e = ed.offset(d, Json.get(g, "range", "end"));
                if (e > a && e <= d.t.length() && d.t.substring(a, e).equals(wort)) diag = g;
            }
            // auf neue Diagnosen warten (hoechstens 10 s)
            if ((ed.diagnosenStand == stand || diag == null) && versuche[0] < 50) return;
            uhr.stop();
            if (diag == null) {
                nachholen(d, l, i + 1, n, fertig);
                return;
            }
            int a = ed.offset(d, Json.get(diag, "range", "start")), e = ed.offset(d, Json.get(diag, "range", "end"));
            anfragen(d, a, e, List.of(diag), List.of("quickfix"), r -> {
                Object treffer = null;
                for (Object x : r) if (titel(x).equals(titel)) treffer = x;
                if (treffer == null) {
                    nachholen(d, l, i + 1, n, fertig);
                    return;
                }
                n[0]++;
                ausfuehren(d, treffer, () -> nachholen(d, l, i + 1, n, fertig));
            });
        });
        uhr.start();
    }

    private void befehleNacheinander(Doc d, List<Object> l, int i, Runnable fertig) {
        if (i >= l.size()) {
            fertig.run();
            return;
        }
        ausfuehren(d, l.get(i), () -> befehleNacheinander(d, l, i + 1, fertig));
    }

    private static Map<String, List<Object>> editsJeDatei(Object edit) {
        Map<String, List<Object>> je = new LinkedHashMap<>();
        Object ch = Json.get(edit, "changes");
        if (ch instanceof Map<?, ?> m) for (var e : m.entrySet()) je.computeIfAbsent((String) e.getKey(), k -> new ArrayList<>()).addAll(Json.list(e.getValue()));
        for (Object dc : Json.list(Json.get(edit, "documentChanges"))) {
            String u = Json.str(Json.get(dc, "textDocument", "uri"));
            if (u != null) je.computeIfAbsent(u, k -> new ArrayList<>()).addAll(Json.list(Json.get(dc, "edits")));
        }
        return je;
    }

    private static long pos(Object p) {
        return (long) Json.num(Json.get(p, "line"), 0) * 1_000_000 + Json.num(Json.get(p, "character"), 0);
    }

    /** ersetzt t einen Bereich, den schon eine andere Aenderung ersetzt? (reines Einfuegen an derselben Stelle geht) */
    private static boolean ueberlappt(List<Object> l, Object t) {
        long a = pos(Json.get(t, "range", "start")), e = pos(Json.get(t, "range", "end"));
        for (Object x : l) {
            long xa = pos(Json.get(x, "range", "start")), xe = pos(Json.get(x, "range", "end"));
            if (a == e && xa == xe) continue;
            if (a < xe && xa < e) return true;
            if (a == e && xa < a && a < xe || xa == xe && a < xa && xa < e) return true;
        }
        return false;
    }

    // ================================================================ alle Aktionen
    /** Alt+ENTER: alle Aktionen an der Stelle bzw. fuer die Auswahl (Quickfixes, Importe, Refactorings) */
    void alle(int a, int e) {
        Doc d = ed.d();
        if (!bereit(d)) return;
        ed.meldung = "frage den Language Server ...";
        // ohne Auswahl: die ganze Zeile fuer die Diagnosen, der Cursor fuer die Refactorings
        int z = d.zeileVon(a);
        List<Object> diags = diagnosenIn(d, e > a ? a : d.anf(z), e > a ? e : d.ende(z));
        anfragen(d, a, Math.max(a, e), diags, null, l -> {
            if (l.isEmpty()) {
                ed.meldung = "keine Aktionen hier";
                return;
            }
            ed.meldung = null;
            Map<String, Object> nach = new LinkedHashMap<>();
            for (Object x : l) {
                String t = titel(x);
                while (nach.containsKey(t)) t += " ";
                nach.put(t, x);
            }
            ed.wz.auswahl("Aktionen (" + l.size() + ")", new ArrayList<>(nach.keySet()), null, w -> {
                Object x = nach.get(w);
                String k = art(x);
                if (k.startsWith("refactor.extract.function") || titel(x).toLowerCase().matches(".*extract (to )?(method|function).*")) {
                    nameFragen(d, x, a, e);
                    return;
                }
                d.merken();
                ausfuehren(d, x, () -> {
                    if (ed.meldung == null) ed.meldung = w.strip() + " (noch nicht gespeichert)";
                });
            });
        });
    }

    void befehle(Map<String, Editor.Cmd> c) {
        /** @binding Visueller Modus: markierten Codeblock in eine neue Methode/Funktion auslagern (fragt den Namen) */
        c.put("extractMethod", a -> {
            int[] r = ed.vim.visArt != 0 ? ed.vim.auswahl() : null;
            if (r == null) {
                ed.meldung = "erst einen Codeblock markieren (v oder V), dann Leertaste c m";
                return;
            }
            ed.befehl("visualExit", "");
            methodeExtrahieren(r[0], r[1]);
        });
        /** @binding Fehlende Importe der Datei hinzufuegen; bei mehreren Kandidaten waehlt man */
        c.put("autoImport", a -> importe());
        /** @binding Alle Code Actions an der Stelle bzw. fuer die Auswahl (Quickfix, Import, Refactoring) */
        c.put("codeActions", a -> {
            int[] r = ed.vim.visArt != 0 ? ed.vim.auswahl() : null;
            if (r != null) ed.befehl("visualExit", "");
            int cur = ed.d().cursor;
            alle(r == null ? cur : r[0], r == null ? cur : r[1]);
        });
        /** @binding Eingabe fuer Code Actions (Name der Methode) */
        c.put("codeActionInput", a -> {
            Consumer<String> w = eingabe;
            eingabe = null;
            if (w != null) w.accept(a);
        });
    }
}
