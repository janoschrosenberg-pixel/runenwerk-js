package runen;

import static runen.Editor.E_FEHLER;
import static runen.Editor.E_GRUND;
import static runen.Editor.E_HELL;
import static runen.Editor.E_INFO;
import static runen.Editor.E_KONSOLE;
import static runen.Editor.E_LEISTE;
import static runen.Editor.E_MATT;
import static runen.Editor.E_TEXT;
import static runen.Editor.E_WARN;
import static runen.Editor.E_ZEILE;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAPI-Ansicht fuer YAML-Dateien (OpenAPI 3 und Swagger 2): links ein Baum aus Info, Servern,
 * Pfaden mit Operationen, Parametern und Antworten, Schemas mit Feldern und den uebrigen Komponenten,
 * rechts der YAML-Text des gewaehlten Knotens. Anlegen, Umbenennen (mit allen $ref), Loeschen und
 * Verschieben aendern die Datei selbst (Kommentare und Formatierung bleiben), ein u nimmt es zurueck.
 * Die Pruefung ($ref ins Leere, Pfadparameter, doppelte operationId ...) erscheint auch im normalen
 * Puffer als Diagnose; gd folgt dort einem $ref.
 */
final class OpenApi {
    static final List<String> METHODEN = List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");
    private static final List<String> TYPEN = List.of("string", "integer", "integer (int64)", "number", "number (double)", "boolean", "string (date)",
            "string (date-time)", "string (uuid)", "string (email)", "array of string", "object");

    record Problem(int zeile, int schwere, String text) {}

    static final class Eintrag {
        final String id, text, art;
        final int tiefe, farbe;
        final Yaml.Knoten k;
        String rechts = "";
        boolean kinder;
        int zeile;

        Eintrag(String id, int tiefe, String text, Yaml.Knoten k, String art, int farbe) {
            this.id = id;
            this.tiefe = tiefe;
            this.text = text;
            this.k = k;
            this.art = art;
            this.farbe = farbe;
            zeile = k == null ? -1 : k.zeile;
        }
    }

    private final Editor ed;
    Doc doc;
    boolean aktiv;
    private Yaml.Knoten wurzel;
    private int stand = -1;
    private boolean v2;
    private int einheit = 2;
    private List<Problem> probleme = List.of();
    /** $ref (wie geschrieben) -> Zeilen */
    private Map<String, List<Integer>> refs = Map.of();
    private final List<Eintrag> eintraege = new ArrayList<>();
    private int wahl, oben, versatz;
    private String wahlId;
    private final Set<String> offen = new HashSet<>(), zu = new HashSet<>();
    private String filter = "";
    private boolean allesOffen, loeschenFrage;
    private Consumer<String> eingabe;

    OpenApi(Editor ed) {
        this.ed = ed;
    }

    boolean sichtbar() {
        return aktiv && ed.d() == doc && ed.modus.equals("openapi");
    }

    // ================================================================ Erkennen und Pruefen
    static boolean istYaml(Doc d) {
        return d != null && "yaml".equals(d.sprache());
    }

    /** openapi: oder swagger: auf oberster Ebene (in den ersten 400 Zeilen) */
    static boolean istOpenApi(CharSequence t) {
        int n = Math.min(t.length(), 40000);
        Matcher m = Pattern.compile("(?m)^[\"']?(openapi|swagger)[\"']?\\s*:").matcher(t.subSequence(0, n));
        return m.find();
    }

    /** Diagnosen fuer eine OpenAPI-YAML (auch im normalen Puffer), nach jeder Aenderung */
    void pruefenFalls(Doc d) {
        if (!istYaml(d) || d.oaVersion == d.version || d.t.length() > 3_000_000) return;
        d.oaVersion = d.version;
        if (d.lspOffen) return;
        if (!istOpenApi(d.t)) {
            if (d.oaDiags) {
                d.diags = new ArrayList<>();
                d.oaDiags = false;
            }
            return;
        }
        List<Problem> p = d == doc && stand == d.version ? probleme : pruefen(Yaml.lesen(d.t.toString()), d.t.toString());
        List<Doc.Diag> l = new ArrayList<>();
        for (Problem x : p) {
            int z = Math.max(0, Math.min(x.zeile(), d.zeilen() - 1));
            String zt = d.zeile(z);
            l.add(new Doc.Diag(z, Yaml.einzug(zt), z, Math.max(Yaml.einzug(zt) + 1, zt.length()), x.schwere(), x.text()));
        }
        d.diags = l;
        d.oaDiags = true;
    }

    private void aktualisieren() {
        if (doc == null || stand == doc.version) return;
        String t = doc.t.toString();
        wurzel = Yaml.lesen(t);
        einheit = Yaml.einheit(t);
        v2 = wurzel.kind("swagger") != null;
        probleme = pruefen(wurzel, t);
        refs = new HashMap<>();
        refsSammeln(wurzel, refs);
        stand = doc.version;
        aufbauen();
    }

    private static void refsSammeln(Yaml.Knoten k, Map<String, List<Integer>> r) {
        for (Yaml.Knoten x : k.kinder) {
            if ("$ref".equals(x.schluessel)) r.computeIfAbsent(Yaml.skalar(x.wert), q -> new ArrayList<>()).add(x.zeile);
            refsSammeln(x, r);
        }
    }

    static List<Problem> pruefen(Yaml.Knoten w, String text) {
        List<Problem> r = new ArrayList<>();
        boolean zwei = w.kind("swagger") != null;
        Yaml.Knoten info = w.kind("info");
        if (info == null) {
            r.add(new Problem(0, 1, "info fehlt (title und version sind Pflicht)"));
        } else {
            if (info.text("title") == null) r.add(new Problem(info.zeile, 1, "info.title fehlt"));
            if (info.text("version") == null) r.add(new Problem(info.zeile, 1, "info.version fehlt"));
        }
        doppelte(w, r);
        refsPruefen(w, w, r);
        Map<String, Integer> ids = new HashMap<>();
        Yaml.Knoten paths = w.kind("paths");
        if (paths != null) {
            for (Yaml.Knoten p : paths.kinder) {
                if (p.schluessel == null || !p.schluessel.startsWith("/")) continue;
                List<String> vorlage = new ArrayList<>();
                Matcher m = Pattern.compile("\\{([^}/]+)}").matcher(p.schluessel);
                while (m.find()) vorlage.add(m.group(1));
                Set<String> pfadEbene = pfadParameter(w, p.kind("parameters"));
                for (Yaml.Knoten op : p.kinder) {
                    if (op.schluessel == null || !METHODEN.contains(op.schluessel)) continue;
                    String opId = op.text("operationId");
                    if (opId != null) {
                        Integer vor = ids.putIfAbsent(opId, op.zeile);
                        if (vor != null) r.add(new Problem(op.kind("operationId").zeile, 1, "operationId " + opId + " gibt es schon (Zeile " + (vor + 1) + ")"));
                    }
                    if (op.kind("responses") == null) r.add(new Problem(op.zeile, 1, op.schluessel.toUpperCase() + " " + p.schluessel + ": responses fehlt"));
                    Set<String> dekl = new HashSet<>(pfadEbene);
                    dekl.addAll(pfadParameter(w, op.kind("parameters")));
                    for (String v : vorlage) {
                        if (!dekl.contains(v)) r.add(new Problem(op.zeile, 1, "Pfadparameter {" + v + "} ist nicht deklariert (in: path)"));
                    }
                    for (String x : dekl) {
                        if (!vorlage.contains(x)) r.add(new Problem(op.zeile, 2, "Parameter " + x + " (in: path) kommt im Pfad " + p.schluessel + " nicht vor"));
                    }
                }
            }
        }
        Yaml.Knoten schemas = zwei ? w.kind("definitions") : w.pfad("components", "schemas");
        if (schemas != null) {
            for (Yaml.Knoten s : schemas.kinder) {
                Yaml.Knoten req = s.kind("required"), props = s.kind("properties");
                if (req == null || props == null || props.wert.startsWith("{") && !props.wert.equals("{}")) continue;
                for (String n : req.werte()) {
                    if (props.kind(n) == null) r.add(new Problem(req.zeile, 2, s.schluessel + ": Pflichtfeld " + n + " fehlt in properties"));
                }
            }
        }
        r.sort((a, b) -> Integer.compare(a.zeile(), b.zeile()));
        return r;
    }

    /** Namen der Parameter mit in: path (auch ueber $ref auf components/parameters) */
    private static Set<String> pfadParameter(Yaml.Knoten w, Yaml.Knoten l) {
        Set<String> r = new HashSet<>();
        if (l == null) return r;
        for (Yaml.Knoten p : l.kinder) {
            Yaml.Knoten x = p;
            String ref = p.text("$ref");
            if (ref != null) x = aufloesen(w, ref);
            if (x != null && "path".equals(x.text("in")) && x.text("name") != null) r.add(x.text("name"));
        }
        return r;
    }

    private static void doppelte(Yaml.Knoten k, List<Problem> r) {
        Set<String> s = new HashSet<>();
        for (Yaml.Knoten x : k.kinder) {
            if (x.schluessel != null && !s.add(x.schluessel)) r.add(new Problem(x.zeile, 1, "Schluessel " + x.schluessel + " gibt es auf dieser Ebene schon"));
            doppelte(x, r);
        }
    }

    private static void refsPruefen(Yaml.Knoten w, Yaml.Knoten k, List<Problem> r) {
        for (Yaml.Knoten x : k.kinder) {
            if ("$ref".equals(x.schluessel)) {
                String ref = Yaml.skalar(x.wert);
                if (ref.startsWith("#") && aufloesen(w, ref) == null) r.add(new Problem(x.zeile, 1, "$ref zeigt ins Leere: " + ref));
            }
            refsPruefen(w, x, r);
        }
    }

    /** lokaler JSON-Pointer (#/components/schemas/X) -> Knoten, sonst null */
    static Yaml.Knoten aufloesen(Yaml.Knoten w, String ref) {
        int h = ref.indexOf('#');
        if (h < 0) return w;
        String zeiger = ref.substring(h + 1);
        Yaml.Knoten x = w;
        for (String teil : zeiger.split("/")) {
            if (teil.isEmpty()) continue;
            String s = java.net.URLDecoder.decode(teil.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8).replace("~1", "/").replace("~0", "~");
            Yaml.Knoten n = x.kind(s);
            if (n == null && s.matches("\\d+") && x.liste && Integer.parseInt(s) < x.kinder.size()) n = x.kinder.get(Integer.parseInt(s));
            if (n == null) return null;
            x = n;
        }
        return x;
    }

    static String refName(String ref) {
        String r = ref.strip();
        int h = r.indexOf('#');
        String datei = h > 0 ? r.substring(0, h) : h < 0 ? r : "";
        String name = r.substring(r.lastIndexOf('/') + 1);
        if (h < 0) return Path.of(r).getFileName().toString();
        return name + (datei.isEmpty() ? "" : " (" + datei + ")");
    }

    private String schemaPraefix() {
        return v2 ? "#/definitions/" : "#/components/schemas/";
    }

    // ================================================================ Baum
    private boolean istOffen(String id, boolean vorgabe) {
        if (allesOffen || !filter.isEmpty()) return true;
        return vorgabe ? !zu.contains(id) : offen.contains(id);
    }

    private Eintrag neu(List<Eintrag> l, String id, int tiefe, String text, Yaml.Knoten k, String art, int farbe) {
        Eintrag e = new Eintrag(id, tiefe, text, k, art, farbe);
        l.add(e);
        return e;
    }

    private static int methodenFarbe(String m) {
        return switch (m) {
            case "get" -> 0x5CFFE6;
            case "post" -> E_WARN;
            case "put" -> E_INFO;
            case "patch" -> 0xE8FF5C;
            case "delete" -> E_FEHLER;
            default -> E_TEXT;
        };
    }

    private void aufbauen() {
        List<Eintrag> l = new ArrayList<>();
        Yaml.Knoten w = wurzel;
        Yaml.Knoten info = w.kind("info");
        String ver = v2 ? "Swagger " + nz(w.text("swagger")) : "OpenAPI " + nz(w.text("openapi"));
        Eintrag ie = neu(l, "info", 0, "Info  " + (info == null ? "?" : nz(info.text("title")) + "  " + nz(info.text("version"))), info, "info", E_HELL);
        ie.rechts = ver;
        if (info == null) ie.zeile = 0;
        Yaml.Knoten srv = w.kind("servers");
        if (srv != null || !v2) {
            Eintrag s = neu(l, "servers", 0, "Server (" + (srv == null ? 0 : srv.kinder.size()) + ")", srv, "servers", E_HELL);
            s.kinder = srv != null && !srv.kinder.isEmpty();
            if (s.kinder && istOffen("servers", false)) {
                int i = 0;
                for (Yaml.Knoten x : srv.kinder) neu(l, "servers\u001f" + i++, 1, nz(x.text("url")), x, "server", E_TEXT).rechts = nz(x.text("description"));
            }
        }
        // ---------------------------------------- Pfade
        Yaml.Knoten paths = w.kind("paths");
        int np = paths == null ? 0 : (int) paths.kinder.stream().filter(x -> x.schluessel != null && x.schluessel.startsWith("/")).count();
        Eintrag pe = neu(l, "paths", 0, "Pfade (" + np + ")", paths, "pfade", E_HELL);
        pe.kinder = np > 0;
        if (paths != null && istOffen("paths", true)) {
            for (Yaml.Knoten p : paths.kinder) {
                if (p.schluessel == null) continue;
                String pid = "paths\u001f" + p.schluessel;
                Eintrag e = neu(l, pid, 1, p.schluessel, p, "pfad", E_TEXT);
                StringBuilder ms = new StringBuilder();
                for (Yaml.Knoten op : p.kinder) if (op.schluessel != null && METHODEN.contains(op.schluessel)) ms.append(op.schluessel.toUpperCase()).append(' ');
                e.kinder = !p.kinder.isEmpty();
                boolean auf = istOffen(pid, true);
                if (!auf) e.rechts = ms.toString().strip();
                if (!auf) continue;
                Yaml.Knoten pp = p.kind("parameters");
                if (pp != null) parameterEintraege(l, pid + "\u001fparameters", 2, pp, "Parameter (Pfad)");
                for (Yaml.Knoten op : p.kinder) {
                    if (op.schluessel == null || !METHODEN.contains(op.schluessel)) continue;
                    String oid = pid + "\u001f" + op.schluessel;
                    String id = op.text("operationId");
                    Eintrag oe = neu(l, oid, 2, String.format("%-7s", op.schluessel.toUpperCase()) + (id == null ? "" : id), op, "op", methodenFarbe(op.schluessel));
                    oe.rechts = nz(op.text("summary"));
                    if (op.kind("deprecated") != null && "true".equals(op.text("deprecated"))) oe.rechts = "(veraltet) " + oe.rechts;
                    oe.kinder = true;
                    if (!istOffen(oid, false)) continue;
                    Yaml.Knoten tags = op.kind("tags");
                    if (tags != null) neu(l, oid + "\u001ftags", 3, "Tags: " + String.join(", ", tags.werte()), tags, "optags", E_MATT);
                    Yaml.Knoten par = op.kind("parameters");
                    if (par != null) parameterEintraege(l, oid + "\u001fparameters", 3, par, "Parameter");
                    Yaml.Knoten body = op.kind("requestBody");
                    if (body != null) {
                        String ref = body.text("$ref");
                        neu(l, oid + "\u001fbody", 3, "Request-Body  " + (ref != null ? "-> " + refName(ref) : inhaltTyp(body)), body, "body", E_TEXT).rechts =
                                "true".equals(body.text("required")) ? "Pflicht" : "";
                    }
                    Yaml.Knoten res = op.kind("responses");
                    if (res != null) {
                        String rid = oid + "\u001fresponses";
                        Eintrag re = neu(l, rid, 3, "Antworten (" + res.kinder.size() + ")", res, "antworten", E_MATT);
                        re.kinder = !res.kinder.isEmpty();
                        if (istOffen(rid, true)) {
                            for (Yaml.Knoten a : res.kinder) {
                                if (a.schluessel == null) continue;
                                String ref = a.text("$ref");
                                String typ = ref != null ? "-> " + refName(ref) : v2 ? schemaTyp(a.kind("schema")) : inhaltTyp(a);
                                int f = a.schluessel.startsWith("2") ? E_TEXT : a.schluessel.startsWith("4") || a.schluessel.startsWith("5") ? E_WARN : E_MATT;
                                neu(l, rid + "\u001f" + a.schluessel, 4, String.format("%-5s", a.schluessel) + typ, a, "antwort", f).rechts = nz(a.text("description"));
                            }
                        }
                    }
                }
            }
        }
        // ---------------------------------------- Schemas
        Yaml.Knoten schemas = v2 ? w.kind("definitions") : w.pfad("components", "schemas");
        Eintrag se = neu(l, "schemas", 0, "Schemas (" + (schemas == null ? 0 : schemas.kinder.size()) + ")", schemas, "schemas", E_HELL);
        se.kinder = schemas != null && !schemas.kinder.isEmpty();
        if (schemas != null && istOffen("schemas", true)) {
            for (Yaml.Knoten s : schemas.kinder) {
                if (s.schluessel == null) continue;
                String sid = "schemas\u001f" + s.schluessel;
                Eintrag e = neu(l, sid, 1, s.schluessel + "  " + schemaTyp(s), s, "schema", E_INFO);
                List<Integer> benutzt = refs.get(schemaPraefix() + s.schluessel);
                e.rechts = benutzt == null ? "unbenutzt" : benutzt.size() + "x benutzt";
                Yaml.Knoten props = s.kind("properties");
                e.kinder = props != null && !props.kinder.isEmpty() || s.kind("allOf") != null || s.kind("oneOf") != null || s.kind("anyOf") != null
                        || s.kind("enum") != null;
                if (!istOffen(sid, false)) continue;
                feldEintraege(l, sid, 2, s);
            }
        }
        // ---------------------------------------- weitere Komponenten
        List<Yaml.Knoten> komp = new ArrayList<>();
        Yaml.Knoten c = w.kind("components");
        if (c != null) for (Yaml.Knoten x : c.kinder) if (x.schluessel != null && !x.schluessel.equals("schemas")) komp.add(x);
        if (v2) {
            for (String n : List.of("parameters", "responses", "securityDefinitions")) if (w.kind(n) != null) komp.add(w.kind(n));
        }
        for (Yaml.Knoten x : komp) {
            String kid = "komp\u001f" + x.schluessel;
            Eintrag e = neu(l, kid, 0, x.schluessel + " (" + x.kinder.size() + ")", x, "komp", E_HELL);
            e.kinder = !x.kinder.isEmpty();
            if (!istOffen(kid, false)) continue;
            for (Yaml.Knoten y : x.kinder) {
                if (y.schluessel == null) continue;
                String t = x.schluessel.equals("parameters") ? parameterText(y) : x.schluessel.startsWith("security") ? nz(y.text("type")) + " " + nz(y.text("scheme"))
                        : x.schluessel.equals("responses") ? inhaltTyp(y) : "";
                neu(l, kid + "\u001f" + y.schluessel, 1, y.schluessel + "  " + t.strip(), y, "kompEintrag", E_TEXT).rechts = nz(y.text("description"));
            }
        }
        // ---------------------------------------- Tags
        Yaml.Knoten tags = w.kind("tags");
        if (tags != null) {
            Eintrag e = neu(l, "tags", 0, "Tags (" + tags.kinder.size() + ")", tags, "tags", E_HELL);
            e.kinder = !tags.kinder.isEmpty();
            if (istOffen("tags", false)) {
                int i = 0;
                for (Yaml.Knoten x : tags.kinder) neu(l, "tags\u001f" + i++, 1, nz(x.text("name")), x, "tag", E_TEXT).rechts = nz(x.text("description"));
            }
        }
        // ---------------------------------------- uebrige Schluessel der obersten Ebene
        Set<String> bekannt = Set.of("openapi", "swagger", "info", "servers", "paths", "components", "tags", "definitions", "parameters", "responses",
                "securityDefinitions");
        for (Yaml.Knoten x : w.kinder) {
            if (x.schluessel != null && !bekannt.contains(x.schluessel)) neu(l, "top\u001f" + x.schluessel, 0, x.schluessel + (x.wert.isEmpty() ? "" : ": " + x.wert), x, "sonst", E_MATT);
        }
        // ---------------------------------------- Probleme
        int fehler = (int) probleme.stream().filter(p -> p.schwere() == 1).count();
        Eintrag pr = neu(l, "probleme", 0, "Probleme (" + probleme.size() + ")", null, "probleme", probleme.isEmpty() ? E_MATT : fehler > 0 ? E_FEHLER : E_WARN);
        pr.kinder = !probleme.isEmpty();
        pr.zeile = probleme.isEmpty() ? -1 : probleme.get(0).zeile();
        if (istOffen("probleme", true)) {
            int i = 0;
            for (Problem p : probleme) {
                Eintrag e = neu(l, "probleme\u001f" + i++, 1, "Zeile " + (p.zeile() + 1) + ": " + p.text(), null, "problem", p.schwere() == 1 ? E_FEHLER : E_WARN);
                e.zeile = p.zeile();
            }
        }
        // ---------------------------------------- Filter: Treffer und ihre Eltern
        if (!filter.isEmpty()) {
            String f = filter.toLowerCase();
            List<Eintrag> g = new ArrayList<>();
            int braucht = Integer.MAX_VALUE;
            for (int i = l.size() - 1; i >= 0; i--) {
                Eintrag e = l.get(i);
                boolean treffer = (e.text + " " + e.rechts).toLowerCase().contains(f);
                if (treffer || e.tiefe < braucht) {
                    if (treffer) {
                        g.add(0, e);
                        braucht = e.tiefe;
                    } else if (braucht != Integer.MAX_VALUE && e.tiefe < braucht) {
                        g.add(0, e);
                        braucht = e.tiefe;
                    }
                }
                if (e.tiefe == 0 && !treffer) braucht = Integer.MAX_VALUE;
            }
            l = g;
        }
        eintraege.clear();
        eintraege.addAll(l);
        // Auswahl behalten
        int i = -1;
        if (wahlId != null) for (int k = 0; k < eintraege.size() && i < 0; k++) if (eintraege.get(k).id.equals(wahlId)) i = k;
        wahl = Math.max(0, Math.min(i >= 0 ? i : wahl, eintraege.size() - 1));
        if (!eintraege.isEmpty()) wahlId = eintraege.get(wahl).id;
    }

    private void parameterEintraege(List<Eintrag> l, String id, int tiefe, Yaml.Knoten par, String titel) {
        Eintrag e = neu(l, id, tiefe, titel + " (" + par.kinder.size() + ")", par, "params", E_MATT);
        e.kinder = !par.kinder.isEmpty();
        if (!istOffen(id, true)) return;
        int i = 0;
        for (Yaml.Knoten p : par.kinder) neu(l, id + "\u001f" + i++, tiefe + 1, parameterText(p), p, "param", E_TEXT).rechts = nz(p.text("description"));
    }

    private void feldEintraege(List<Eintrag> l, String sid, int tiefe, Yaml.Knoten s) {
        Yaml.Knoten en = s.kind("enum");
        if (en != null) neu(l, sid + "\u001fenum", tiefe, "enum: " + String.join(", ", en.werte()), en, "enum", E_TEXT);
        for (String art : List.of("allOf", "oneOf", "anyOf")) {
            Yaml.Knoten a = s.kind(art);
            if (a == null) continue;
            int i = 0;
            for (Yaml.Knoten x : a.kinder) {
                String tid = sid + "\u001f" + art + i++;
                Eintrag e = neu(l, tid, tiefe, art + "  " + schemaTyp(x), x, "teil", E_MATT);
                Yaml.Knoten p = x.kind("properties");
                e.kinder = p != null && !p.kinder.isEmpty();
                if (e.kinder && istOffen(tid, true)) feldEintraege(l, tid, tiefe + 1, x);
            }
        }
        Yaml.Knoten props = s.kind("properties");
        if (props == null) return;
        List<String> req = s.kind("required") == null ? List.of() : s.kind("required").werte();
        for (Yaml.Knoten p : props.kinder) {
            if (p.schluessel == null) continue;
            String fid = sid + "\u001f" + p.schluessel;
            Eintrag e = neu(l, fid, tiefe, p.schluessel + (req.contains(p.schluessel) ? " *" : "") + "  " + schemaTyp(p), p, "prop", E_TEXT);
            e.rechts = nz(p.text("description"));
            Yaml.Knoten innen = p.kind("properties") != null ? p : p.kind("items") != null && p.kind("items").kind("properties") != null ? p.kind("items") : null;
            e.kinder = innen != null;
            if (innen != null && istOffen(fid, false)) feldEintraege(l, fid, tiefe + 1, innen);
        }
    }

    private String parameterText(Yaml.Knoten p) {
        String ref = p.text("$ref");
        if (ref != null) return "-> " + refName(ref);
        String typ = v2 ? schemaTyp(p) : schemaTyp(p.kind("schema"));
        return nz(p.text("name")) + ("true".equals(p.text("required")) ? " *" : "") + "  (" + nz(p.text("in")) + ")  " + typ;
    }

    /** Typ des Inhalts einer Antwort oder eines Request-Body (erster Medientyp) */
    private static String inhaltTyp(Yaml.Knoten a) {
        Yaml.Knoten c = a.kind("content");
        if (c == null || c.kinder.isEmpty()) return "";
        Yaml.Knoten m = c.kinder.get(0);
        return schemaTyp(m.kind("schema")) + (c.kinder.size() > 1 ? "  +" + (c.kinder.size() - 1) : "") + "  " + nz(m.schluessel);
    }

    static String schemaTyp(Yaml.Knoten s) {
        if (s == null) return "";
        String ref = s.text("$ref");
        if (ref != null) return "-> " + refName(ref);
        if (s.kinder.isEmpty() && !s.wert.isEmpty()) return s.wert;
        String t = s.text("type");
        if ("array".equals(t)) return (s.kind("items") == null ? "?" : schemaTyp(s.kind("items"))) + "[]";
        for (String k : List.of("allOf", "oneOf", "anyOf")) {
            Yaml.Knoten a = s.kind(k);
            if (a == null) continue;
            List<String> n = new ArrayList<>();
            for (Yaml.Knoten x : a.kinder) n.add(schemaTyp(x));
            return k + "(" + String.join(", ", n) + ")";
        }
        if (t == null && s.kind("properties") != null) t = "object";
        if (s.kind("enum") != null) return (t == null ? "" : t + " ") + "enum";
        String f = s.text("format");
        if (t == null) return "";
        return t + (f != null ? " (" + f + ")" : "") + ("true".equals(s.text("nullable")) ? "?" : "");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private Eintrag gewaehlt() {
        aktualisieren();
        return eintraege.isEmpty() ? null : eintraege.get(Math.max(0, Math.min(wahl, eintraege.size() - 1)));
    }

    /** den tiefsten Eintrag waehlen, dessen Knoten die Zeile enthaelt (Eltern werden aufgeklappt) */
    private void waehleZeile(int zeile) {
        aktualisieren();
        allesOffen = true;
        aufbauen();
        allesOffen = false;
        int beste = -1;
        for (int i = 0; i < eintraege.size(); i++) {
            Eintrag e = eintraege.get(i);
            if (e.k != null && e.k.zeile <= zeile && zeile < Math.max(e.k.ende, e.k.zeile + 1) && (beste < 0 || e.tiefe >= eintraege.get(beste).tiefe)) beste = i;
        }
        if (beste < 0) {
            aufbauen();
            return;
        }
        // Eltern des Treffers aufklappen
        Eintrag ziel = eintraege.get(beste);
        int t = ziel.tiefe;
        for (int i = beste - 1; i >= 0 && t > 0; i--) {
            Eintrag e = eintraege.get(i);
            if (e.tiefe < t) {
                offen.add(e.id);
                zu.remove(e.id);
                t = e.tiefe;
            }
        }
        wahlId = ziel.id;
        aufbauen();
        versatz = 0;
    }

    // ================================================================ Steuern
    void starten(Doc d) {
        if (doc != d) {
            offen.clear();
            zu.clear();
            filter = "";
            wahlId = null;
            wahl = 0;
            oben = 0;
        }
        doc = d;
        stand = -1;
        aktiv = true;
        loeschenFrage = false;
        ed.modus = "openapi";
        aktualisieren();
        waehleZeile(d.zeileVon(d.cursor));
        ed.meldung = (v2 ? "Swagger 2" : "OpenAPI") + ": " + d.name() + " - j/k waehlen, l/h auf/zu, a anlegen, r umbenennen, d loeschen, ENTER bearbeiten, Leertaste y zurueck";
    }

    /** zurueck in den normalen Puffer, Cursor auf dem gewaehlten Knoten */
    private void verlassen() {
        Eintrag e = gewaehlt();
        aktiv = false;
        ed.modus = "normal";
        if (e != null && e.zeile >= 0) {
            int z = Math.min(e.zeile, doc.zeilen() - 1);
            doc.cursor = doc.stelle(z, Yaml.einzug(doc.zeile(z)));
        }
        ed.meldung = "Puffer " + doc.name() + " - Leertaste y zeigt wieder die OpenAPI-Ansicht";
    }

    /** neuer Text fuer die Datei (ein Undo-Schritt), danach die Zeile waehlen */
    private void aendern(String neu, int zeile) {
        if (doc.nurLesen) {
            ed.meldung = doc.name() + " ist schreibgeschuetzt";
            return;
        }
        if (neu == null || neu.equals(doc.t.toString())) return;
        doc.merken();
        Editor.zeilenweiseErsetzen(doc, neu);
        ed.lspSync();
        if (zeile >= 0) waehleZeile(zeile);
        else aktualisieren();
    }

    private void frage(String titel, String vorgabe, Consumer<String> weiter) {
        eingabe = weiter;
        ed.popupOpen(titel, "openapiInput", vorgabe);
    }

    private void waehlen(String titel, List<String> l, Consumer<String> weiter) {
        ed.wz.auswahl(titel, l, null, weiter);
    }

    /** Baum als Text (Auswahl mit >), zum Pruefen */
    List<String> baum() {
        aktualisieren();
        List<String> r = new ArrayList<>();
        for (int i = 0; i < eintraege.size(); i++) {
            Eintrag e = eintraege.get(i);
            r.add((i == wahl ? "> " : "  ") + "  ".repeat(e.tiefe) + (e.kinder ? istOffen(e.id, standardOffen(e)) ? "- " : "+ " : "  ") + e.text
                    + (e.rechts.isEmpty() ? "" : "   [" + e.rechts + "]"));
        }
        return r;
    }

    String kopierText() {
        Eintrag e = gewaehlt();
        if (e == null) return null;
        if (e.k != null && e.k.schluessel != null) return e.k.schluessel;
        return e.text.strip();
    }

    void befehle(Map<String, Editor.Cmd> c) {
        /** @binding OpenAPI-Ansicht an/aus (YAML mit openapi: oder swagger:) */
        c.put("openapiView", a -> {
            Doc d = ed.d();
            if (sichtbar()) {
                verlassen();
                return;
            }
            if (!istYaml(d) || !istOpenApi(d.t)) {
                ed.meldung = d.name() + " ist keine OpenAPI-YAML (openapi: oder swagger: auf oberster Ebene)";
                return;
            }
            starten(d);
        });
        /** @binding OpenAPI: Auswahl hoch/runter (Zahl), auch seitenweise */
        c.put("openapiMove", a -> {
            aktualisieren();
            int n = zahl(a, 1);
            int seite = Math.max(1, ed.textZeilen() - 3);
            if (a.strip().equals("page")) n = seite;
            if (a.strip().equals("-page")) n = -seite;
            wahl = Math.max(0, Math.min(eintraege.size() - 1, wahl + n));
            if (!eintraege.isEmpty()) wahlId = eintraege.get(wahl).id;
            versatz = 0;
            loeschenFrage = false;
        });
        /** @binding OpenAPI: klappt auf (oder geht zum ersten Kind) */
        c.put("openapiOpen", a -> {
            Eintrag e = gewaehlt();
            if (e == null || !e.kinder) return;
            if (istOffen(e.id, standardOffen(e))) {
                if (wahl + 1 < eintraege.size() && eintraege.get(wahl + 1).tiefe > e.tiefe) c.get("openapiMove").run("1");
                return;
            }
            umschalten(e);
        });
        /** @binding OpenAPI: klappt zu (oder geht zum Elternknoten) */
        c.put("openapiClose", a -> {
            Eintrag e = gewaehlt();
            if (e == null) return;
            if (e.kinder && istOffen(e.id, standardOffen(e)) && filter.isEmpty()) {
                umschalten(e);
                return;
            }
            for (int i = wahl - 1; i >= 0; i--) {
                if (eintraege.get(i).tiefe < e.tiefe) {
                    wahl = i;
                    wahlId = eintraege.get(i).id;
                    return;
                }
            }
        });
        /** @binding OpenAPI: klappt auf oder zu */
        c.put("openapiToggle", a -> {
            Eintrag e = gewaehlt();
            if (e != null && e.kinder) umschalten(e);
        });
        /** @binding OpenAPI: den gewaehlten Knoten im normalen Puffer bearbeiten */
        c.put("openapiEdit", a -> {
            if (!filter.isEmpty() && a.strip().equals("esc")) {
                filter = "";
                stand = -1;
                aktualisieren();
                ed.meldung = "Filter aus";
                return;
            }
            verlassen();
        });
        /** @binding OpenAPI: rollt den YAML-Text rechts */
        c.put("openapiScroll", a -> versatz += zahl(a, 3));
        /** @binding OpenAPI: legt an, was zur Auswahl passt (Pfad, Operation, Parameter, Antwort, Schema, Feld, Tag, Server) */
        c.put("openapiAdd", a -> anlegen());
        /** @binding OpenAPI: benennt um (Schema mit allen $ref, Pfad, Feld, operationId, Parameter, Tag) */
        c.put("openapiRename", a -> umbenennen());
        /** @binding OpenAPI: loescht den gewaehlten Knoten (zweimal d) */
        c.put("openapiDelete", a -> loeschen());
        /** @binding OpenAPI: verschiebt den Knoten unter seinen Geschwistern (1 = runter, -1 = hoch) */
        c.put("openapiShift", a -> verschieben(zahl(a, 1)));
        /** @binding OpenAPI: wo wird das Schema benutzt ($ref)? */
        c.put("openapiUsages", a -> verwendungen());
        /** @binding OpenAPI: folgt dem $ref des gewaehlten Knotens */
        c.put("openapiFollow", a -> folgen());
        /** @binding OpenAPI: naechstes Problem */
        c.put("openapiNextProblem", a -> {
            aktualisieren();
            if (probleme.isEmpty()) {
                ed.meldung = "keine Probleme";
                return;
            }
            Eintrag e = gewaehlt();
            int ab = e == null ? -1 : e.zeile;
            Problem p = probleme.get(0);
            for (Problem x : probleme) {
                if (x.zeile() > ab) {
                    p = x;
                    break;
                }
            }
            waehleZeile(p.zeile());
            ed.meldung = "Zeile " + (p.zeile() + 1) + ": " + p.text();
        });
        /** @binding OpenAPI: filtert den Baum (leer = alles) */
        c.put("openapiFilter", a -> frage("Filter (leer = alles):", filter, f -> {
            filter = f.strip();
            stand = -1;
            aktualisieren();
            ed.meldung = filter.isEmpty() ? "Filter aus" : eintraege.size() + " Eintraege passen zu " + filter + " (ESC: Filter aus)";
        }));
        /** @binding OpenAPI: Antwort aus dem Eingabefenster (intern) */
        c.put("openapiInput", a -> {
            Consumer<String> w = eingabe;
            eingabe = null;
            if (w != null) w.accept(a);
        });
    }

    private static int zahl(String a, int vorgabe) {
        try {
            return Integer.parseInt(a.strip());
        } catch (NumberFormatException e) {
            return vorgabe;
        }
    }

    /** Vorgabe beim Aufbau: Abschnitte, Pfade, Parameter- und Antwortlisten, allOf-Teile offen */
    private static boolean standardOffen(Eintrag e) {
        return switch (e.art) {
            case "pfade", "schemas", "pfad", "params", "antworten", "teil", "probleme" -> true;
            default -> false;
        };
    }

    private void umschalten(Eintrag e) {
        boolean vorgabe = standardOffen(e);
        if (istOffen(e.id, vorgabe)) {
            if (vorgabe) zu.add(e.id);
            else offen.remove(e.id);
        } else {
            if (vorgabe) zu.remove(e.id);
            else offen.add(e.id);
        }
        wahlId = e.id;
        aufbauen();
    }

    // ================================================================ Bearbeiten
    /** Zeilen mit Einzug in Schritten von 2 (Vorlage) auf die Einrueckung der Datei ab Spalte basis */
    private String block(int basis, String... vorlage) {
        StringBuilder b = new StringBuilder();
        for (String v : vorlage) {
            for (String z : v.split("\n")) {
                int e = Yaml.einzug(z);
                b.append(" ".repeat(basis + e / 2 * einheit)).append(z.substring(e)).append('\n');
            }
        }
        return b.toString();
    }

    /**
     * Text unter eltern anhaengen (nach dem letzten Kind); ein {} oder [] als Wert faellt weg. Liefert den
     * neuen Text, die Zeile der Einfuegung steht danach in eingefuegt.
     */
    private int eingefuegt;

    private String anhaengen(String text, Yaml.Knoten eltern, String zeilenBlock) {
        List<String> z = new ArrayList<>(List.of(text.split("\n", -1)));
        int bei;
        if (eltern.zeile < 0) {
            bei = z.size();
            while (bei > 0 && z.get(bei - 1).isBlank()) bei--;
        } else {
            bei = Math.max(eltern.ende, eltern.zeile + 1);
            String w = eltern.wert.replace(" ", "");
            if (w.equals("{}") || w.equals("[]") || w.equals("null") || w.equals("~")) {
                String zl = z.get(eltern.zeile);
                int dp = zl.indexOf(':', eltern.schluesselSpalte + eltern.schluesselLaenge);
                if (dp > 0) z.set(eltern.zeile, zl.substring(0, dp + 1) + zl.substring(dp + 1).replaceFirst("^\\s*(\\{\\s*}|\\[\\s*]|null|~)", ""));
            }
        }
        List<String> neu = new ArrayList<>(List.of(zeilenBlock.split("\n", -1)));
        if (!neu.isEmpty() && neu.get(neu.size() - 1).isEmpty()) neu.remove(neu.size() - 1);
        z.addAll(bei, neu);
        eingefuegt = bei;
        return String.join("\n", z);
    }

    /** Spalte der Kinder von k (oberste Ebene: 0) */
    private int kindSpalte(Yaml.Knoten k) {
        if (k.zeile < 0) return 0;
        return k.kindEinzug(einheit);
    }

    /** Knoten ueber den Pfad sicherstellen (fehlende Schluessel anlegen); liefert den neuen Text */
    private String sicherstellen(String text, String... pfad) {
        Yaml.Knoten w = Yaml.lesen(text);
        Yaml.Knoten x = w;
        for (String s : pfad) {
            Yaml.Knoten n = x.kind(s);
            if (n == null) {
                text = anhaengen(text, x, block(kindSpalte(x), s + ":"));
                w = Yaml.lesen(text);
                n = knotenInZeile(w, eingefuegt);
                if (n == null) return text;
            }
            x = n;
        }
        return text;
    }

    private static Yaml.Knoten knotenInZeile(Yaml.Knoten k, int zeile) {
        for (Yaml.Knoten x : k.kinder) {
            if (x.zeile == zeile) return x;
            if (x.zeile < zeile && zeile < x.ende) {
                Yaml.Knoten r = knotenInZeile(x, zeile);
                return r != null ? r : x;
            }
        }
        return null;
    }

    /** Knoten an derselben Stelle im neu gelesenen Text (nach Schluesseln und Listenpositionen) */
    private static Yaml.Knoten wiederfinden(Yaml.Knoten neu, Yaml.Knoten alt) {
        List<Integer> weg = new ArrayList<>();
        for (Yaml.Knoten x = alt; x.eltern != null; x = x.eltern) weg.add(0, x.eltern.kinder.indexOf(x));
        Yaml.Knoten y = neu;
        for (int i : weg) {
            if (i < 0 || i >= y.kinder.size()) return null;
            y = y.kinder.get(i);
        }
        return y;
    }

    private void anlegen() {
        Eintrag e = gewaehlt();
        if (e == null) return;
        Yaml.Knoten k = e.k;
        switch (e.art) {
            case "pfade", "info" -> neuerPfad();
            case "pfad" -> neueOperation(k);
            case "op", "params", "param", "body", "antworten", "antwort", "optags" -> {
                Yaml.Knoten op = k;
                while (op != null && !(op.schluessel != null && METHODEN.contains(op.schluessel) && op.eltern != null && op.eltern.eltern == wurzel.kind("paths"))) op = op.eltern;
                if (op == null) {
                    // Parameter auf Pfad-Ebene
                    neuerParameter(k.eltern != null && "parameters".equals(k.schluessel) ? k.eltern : k.eltern != null ? k.eltern.eltern : k);
                    return;
                }
                Yaml.Knoten o = op;
                List<String> was = new ArrayList<>(List.of("Parameter", "Antwort"));
                if (o.kind("requestBody") == null && !v2) was.add("Request-Body");
                String vor = e.art.equals("antworten") || e.art.equals("antwort") ? "Antwort" : e.art.startsWith("param") ? "Parameter" : null;
                if (vor != null) {
                    hinzufuegen(o, vor);
                    return;
                }
                waehlen("Neu in " + o.schluessel.toUpperCase() + " " + o.eltern.schluessel, was, x -> hinzufuegen(o, x));
            }
            case "schemas" -> neuesSchema();
            case "schema" -> neuesFeld(k);
            case "prop", "enum", "teil" -> {
                Yaml.Knoten s = k;
                while (s != null && !(s.eltern != null && s.eltern == schemasKnoten())) s = s.eltern;
                if (e.art.equals("prop") && k.kind("properties") != null) s = k;
                if (s != null) neuesFeld(s);
            }
            case "tags", "tag" -> frage("Neuer Tag:", "", n -> {
                if (n.isBlank()) return;
                String t = sicherstellen(doc.t.toString(), "tags");
                Yaml.Knoten tags = Yaml.lesen(t).kind("tags");
                aendern(anhaengen(t, tags, block(kindSpalte(tags), "- name: " + Yaml.zitat(n.strip()))), -2);
                waehleZeile(eingefuegt);
            });
            case "servers", "server" -> frage("Neuer Server (URL):", "https://", n -> {
                if (n.isBlank()) return;
                String t = sicherstellen(doc.t.toString(), "servers");
                Yaml.Knoten s = Yaml.lesen(t).kind("servers");
                aendern(anhaengen(t, s, block(kindSpalte(s), "- url: " + Yaml.zitat(n.strip()))), -2);
                waehleZeile(eingefuegt);
            });
            default -> ed.meldung = "a: hier gibt es nichts anzulegen (Pfade, Operationen, Schemas, Felder, Tags, Server)";
        }
    }

    private Yaml.Knoten schemasKnoten() {
        return v2 ? wurzel.kind("definitions") : wurzel.pfad("components", "schemas");
    }

    /** operationId aus Methode und Pfad: get /kunden/{id} -> getKundenById */
    static String operationId(String methode, String pfad) {
        StringBuilder b = new StringBuilder(methode);
        for (String s : pfad.split("/")) {
            if (s.isEmpty()) continue;
            boolean p = s.startsWith("{") && s.endsWith("}");
            String w = p ? s.substring(1, s.length() - 1) : s;
            StringBuilder c = new StringBuilder();
            for (String t : w.split("[^A-Za-z0-9]+")) if (!t.isEmpty()) c.append(Character.toUpperCase(t.charAt(0))).append(t.substring(1));
            b.append(p ? "By" : "").append(c);
        }
        return b.toString();
    }

    private String operationVorlage(String m, String pfad) {
        StringBuilder b = new StringBuilder(m + ":\n  operationId: " + operationId(m, pfad) + "\n");
        Matcher pm = Pattern.compile("\\{([^}/]+)}").matcher(pfad);
        boolean erster = true;
        while (pm.find()) {
            if (erster) b.append("  parameters:\n");
            erster = false;
            b.append("    - name: ").append(pm.group(1)).append("\n      in: path\n      required: true\n");
            b.append(v2 ? "      type: string\n" : "      schema:\n        type: string\n");
        }
        if (!v2 && (m.equals("post") || m.equals("put") || m.equals("patch"))) {
            b.append("  requestBody:\n    required: true\n    content:\n      application/json:\n        schema:\n          type: object\n");
        }
        b.append("  responses:\n    '").append(m.equals("post") ? "201" : m.equals("delete") ? "204" : "200").append("':\n      description: ")
                .append(m.equals("post") ? "angelegt" : m.equals("delete") ? "geloescht" : "OK").append('\n');
        return b.toString().stripTrailing();
    }

    private void neuerPfad() {
        frage("Neuer Pfad:", "/", p -> {
            String pfad = p.strip();
            if (pfad.isEmpty() || pfad.equals("/")) return;
            if (!pfad.startsWith("/")) pfad = "/" + pfad;
            Yaml.Knoten paths = wurzel.kind("paths");
            if (paths != null && paths.kind(pfad) != null) {
                waehleZeile(paths.kind(pfad).zeile);
                ed.meldung = pfad + " gibt es schon";
                return;
            }
            String pf = pfad;
            waehlen("Methode fuer " + pfad, List.of("get", "post", "put", "patch", "delete"), m -> {
                String t = sicherstellen(doc.t.toString(), "paths");
                Yaml.Knoten ps = Yaml.lesen(t).kind("paths");
                String bl = block(kindSpalte(ps), Yaml.zitat(pf) + ":") + block(kindSpalte(ps) + einheit, operationVorlage(m, pf));
                String neu = anhaengen(t, ps, bl);
                aendern(neu, eingefuegt + 1);
            });
        });
    }

    private void neueOperation(Yaml.Knoten pfad) {
        List<String> frei = new ArrayList<>();
        for (String m : List.of("get", "post", "put", "patch", "delete", "head", "options")) if (pfad.kind(m) == null) frei.add(m);
        if (frei.isEmpty()) {
            ed.meldung = "alle Methoden sind schon da";
            return;
        }
        waehlen("Neue Operation in " + pfad.schluessel, frei, m -> {
            String t = doc.t.toString();
            Yaml.Knoten p = wiederfinden(Yaml.lesen(t), pfad);
            if (p == null) return;
            aendern(anhaengen(t, p, block(kindSpalte(p), operationVorlage(m, pfad.schluessel))), -2);
            waehleZeile(eingefuegt);
        });
    }

    /** Typauswahl: einfache Typen, Verweise auf Schemas, Listen */
    private List<String> typen() {
        List<String> l = new ArrayList<>(TYPEN);
        Yaml.Knoten s = schemasKnoten();
        if (s != null) {
            for (Yaml.Knoten x : s.kinder) if (x.schluessel != null) l.add("-> " + x.schluessel);
            for (Yaml.Knoten x : s.kinder) if (x.schluessel != null) l.add("array of -> " + x.schluessel);
        }
        return l;
    }

    /** YAML-Zeilen (Vorlage mit 2er-Einzug) fuer einen gewaehlten Typ */
    private String typVorlage(String typ) {
        String t = typ.strip();
        if (t.startsWith("array of ")) return "type: array\nitems:\n" + einruecken(typVorlage(t.substring(9)));
        if (t.startsWith("-> ")) return "$ref: '" + schemaPraefix() + t.substring(3).strip() + "'";
        Matcher m = Pattern.compile("(\\w+) \\((\\S+)\\)").matcher(t);
        if (m.matches()) return "type: " + m.group(1) + "\nformat: " + m.group(2);
        if (t.equals("object")) return "type: object\nproperties: {}";
        return "type: " + (t.isEmpty() ? "string" : t);
    }

    private static String einruecken(String s) {
        return "  " + s.replace("\n", "\n  ");
    }

    private void hinzufuegen(Yaml.Knoten op, String was) {
        switch (was) {
            case "Parameter" -> neuerParameter(op);
            case "Antwort" -> frage("Neue Antwort fuer " + op.schluessel.toUpperCase() + " " + op.eltern.schluessel + " (Statuscode):", "404", code -> {
                String cd = code.strip();
                if (cd.isEmpty()) return;
                List<String> l = new ArrayList<>(List.of("(ohne Inhalt)"));
                l.addAll(typen());
                waehlen("Inhalt der Antwort " + cd, l, typ -> {
                    String t = doc.t.toString();
                    Yaml.Knoten o = wiederfinden(Yaml.lesen(t), op);
                    if (o == null) return;
                    if (o.kind("responses") == null) {
                        t = anhaengen(t, o, block(kindSpalte(o), "responses:"));
                        o = wiederfinden(Yaml.lesen(t), op);
                    }
                    Yaml.Knoten res = o.kind("responses");
                    String v = "'" + cd + "':\n  description: " + Yaml.zitat(beschreibung(cd));
                    if (!typ.startsWith("(")) {
                        v += v2 ? "\n  schema:\n" + einruecken(einruecken(typVorlage(typ))).substring(2)
                                : "\n  content:\n    application/json:\n      schema:\n" + einruecken(einruecken(einruecken(einruecken(typVorlage(typ)))));
                    }
                    aendern(anhaengen(t, res, block(kindSpalte(res), v)), -2);
                    waehleZeile(eingefuegt);
                });
            });
            case "Request-Body" -> waehlen("Request-Body fuer " + op.schluessel.toUpperCase() + " " + op.eltern.schluessel, typen(), typ -> {
                String t = doc.t.toString();
                Yaml.Knoten o = wiederfinden(Yaml.lesen(t), op);
                if (o == null) return;
                String v = "requestBody:\n  required: true\n  content:\n    application/json:\n      schema:\n" + einruecken(einruecken(einruecken(einruecken(typVorlage(typ)))));
                aendern(anhaengen(t, o, block(kindSpalte(o), v)), -2);
                waehleZeile(eingefuegt);
            });
            default -> {
                // nichts
            }
        }
    }

    private static String beschreibung(String code) {
        return switch (code) {
            case "200" -> "OK";
            case "201" -> "angelegt";
            case "204" -> "ohne Inhalt";
            case "400" -> "ungueltige Anfrage";
            case "401" -> "nicht angemeldet";
            case "403" -> "verboten";
            case "404" -> "nicht gefunden";
            case "409" -> "Konflikt";
            case "500" -> "Serverfehler";
            default -> "Antwort " + code;
        };
    }

    /** Parameter an eine Operation (oder einen Pfad) haengen */
    private void neuerParameter(Yaml.Knoten op) {
        frage("Neuer Parameter (Name):", "", name -> {
            String n = name.strip();
            if (n.isEmpty()) return;
            waehlen("Wo steht " + n + "?", List.of("query", "path", "header", "cookie"), in -> waehlen("Typ von " + n, typen(), typ -> {
                String t = doc.t.toString();
                Yaml.Knoten o = wiederfinden(Yaml.lesen(t), op);
                if (o == null) return;
                if (o.kind("parameters") == null) {
                    t = anhaengen(t, o, block(kindSpalte(o), "parameters:"));
                    o = wiederfinden(Yaml.lesen(t), op);
                }
                Yaml.Knoten par = o.kind("parameters");
                String v = "- name: " + Yaml.zitat(n) + "\n  in: " + in + (in.equals("path") ? "\n  required: true" : "")
                        + (v2 ? "\n" + einruecken(typVorlage(typ)) : "\n  schema:\n" + einruecken(einruecken(typVorlage(typ))));
                int sp = par.kinder.isEmpty() ? kindSpalte(par) : par.kinder.get(0).einzug;
                aendern(anhaengen(t, par, block(sp, v)), -2);
                waehleZeile(eingefuegt);
            }));
        });
    }

    private void neuesSchema() {
        frage("Neues Schema (Name):", "", name -> {
            String n = name.strip();
            if (n.isEmpty()) return;
            Yaml.Knoten s = schemasKnoten();
            if (s != null && s.kind(n) != null) {
                waehleZeile(s.kind(n).zeile);
                ed.meldung = n + " gibt es schon";
                return;
            }
            String t = v2 ? sicherstellen(doc.t.toString(), "definitions") : sicherstellen(doc.t.toString(), "components", "schemas");
            Yaml.Knoten ss = v2 ? Yaml.lesen(t).kind("definitions") : Yaml.lesen(t).pfad("components", "schemas");
            aendern(anhaengen(t, ss, block(kindSpalte(ss), Yaml.zitat(n) + ":\n  type: object\n  properties: {}")), -2);
            int z = eingefuegt;
            waehleZeile(z);
            Yaml.Knoten neu = knotenInZeile(wurzel, z);
            if (neu != null) neuesFeld(neu);
        });
    }

    private void neuesFeld(Yaml.Knoten schema) {
        frage("Neues Feld in " + schema.schluessel + " (Name):", "", name -> {
            String n = name.strip();
            if (n.isEmpty()) return;
            waehlen("Typ von " + schema.schluessel + "." + n, typen(), typ -> waehlen(n + " ist", List.of("optional", "Pflichtfeld"), pflicht -> {
                String t = doc.t.toString();
                Yaml.Knoten s = wiederfinden(Yaml.lesen(t), schema);
                if (s == null) return;
                if (s.kind("properties") == null) {
                    t = anhaengen(t, s, block(kindSpalte(s), "properties:"));
                    s = wiederfinden(Yaml.lesen(t), schema);
                }
                if (s.kind("properties").kind(n) != null) {
                    ed.meldung = n + " gibt es schon in " + schema.schluessel;
                    return;
                }
                Yaml.Knoten props = s.kind("properties");
                t = anhaengen(t, props, block(kindSpalte(props), Yaml.zitat(n) + ":\n" + einruecken(typVorlage(typ))));
                int z = eingefuegt;
                if (pflicht.equals("Pflichtfeld")) {
                    t = pflichtSetzen(t, schema, n, null);
                    if (eingefuegt <= z) z++;
                }
                aendern(t, -2);
                waehleZeile(z);
            }));
        });
    }

    /** Feld in required eintragen (neu = null), umbenennen (alt -> neu) oder entfernen (neu = "") */
    private String pflichtSetzen(String t, Yaml.Knoten schema, String name, String alt) {
        Yaml.Knoten s = wiederfinden(Yaml.lesen(t), schema);
        if (s == null) return t;
        Yaml.Knoten req = s.kind("required");
        List<String> z = new ArrayList<>(List.of(t.split("\n", -1)));
        if (alt == null) {
            if (req == null) {
                // required vor properties, wie es die meisten schreiben
                Yaml.Knoten props = s.kind("properties");
                int bei = props != null ? props.zeile : Math.max(s.ende, s.zeile + 1);
                int sp = kindSpalte(s);
                List<String> neu = List.of(" ".repeat(sp) + "required:", " ".repeat(sp + einheit) + "- " + Yaml.zitat(name));
                z.addAll(bei, neu);
                eingefuegt = bei;
                return String.join("\n", z);
            }
            if (req.werte().contains(name)) return t;
            if (req.wert.startsWith("[")) {
                List<String> w = new ArrayList<>(req.werte());
                w.add(name);
                z.set(req.zeile, z.get(req.zeile).replaceFirst("\\[.*]", Matcher.quoteReplacement(flow(w))));
                eingefuegt = Integer.MAX_VALUE;
                return String.join("\n", z);
            }
            int sp = req.kinder.isEmpty() ? req.einzug + einheit : req.kinder.get(0).einzug;
            z.add(Math.max(req.ende, req.zeile + 1), " ".repeat(sp) + "- " + Yaml.zitat(name));
            eingefuegt = Math.max(req.ende, req.zeile + 1);
            return String.join("\n", z);
        }
        eingefuegt = Integer.MAX_VALUE;
        if (req == null || !req.werte().contains(alt)) return t;
        if (req.wert.startsWith("[")) {
            List<String> w = new ArrayList<>();
            for (String x : req.werte()) if (!x.equals(alt) || !name.isEmpty()) w.add(x.equals(alt) ? name : x);
            String neu = w.isEmpty() ? "[]" : flow(w);
            z.set(req.zeile, z.get(req.zeile).replaceFirst("\\[.*]", Matcher.quoteReplacement(neu)));
            return String.join("\n", z);
        }
        for (Yaml.Knoten x : req.kinder) {
            if (!alt.equals(Yaml.skalar(x.wert))) continue;
            if (name.isEmpty()) {
                if (req.kinder.size() == 1) {
                    z.subList(req.zeile, Math.max(req.ende, req.zeile + 1)).clear();
                } else {
                    z.remove(x.zeile);
                }
            } else {
                String zl = z.get(x.zeile);
                int d = zl.indexOf('-');
                z.set(x.zeile, zl.substring(0, d + 2) + Yaml.zitat(name));
            }
            break;
        }
        return String.join("\n", z);
    }

    private static String flow(List<String> w) {
        List<String> q = new ArrayList<>();
        for (String x : w) q.add(Yaml.zitat(x));
        return "[" + String.join(", ", q) + "]";
    }

    /** Schluessel in seiner Zeile ersetzen */
    private static String schluesselErsetzen(String t, Yaml.Knoten k, String neu) {
        List<String> z = new ArrayList<>(List.of(t.split("\n", -1)));
        String zl = z.get(k.zeile);
        z.set(k.zeile, zl.substring(0, k.schluesselSpalte) + neu + zl.substring(k.schluesselSpalte + k.schluesselLaenge));
        return String.join("\n", z);
    }

    /** Wert eines Kindes (name: wert) in seiner Zeile ersetzen */
    private static String wertErsetzen(String t, Yaml.Knoten kind, String neu) {
        List<String> z = new ArrayList<>(List.of(t.split("\n", -1)));
        String zl = z.get(kind.zeile);
        int dp = zl.indexOf(':', kind.schluesselSpalte + kind.schluesselLaenge);
        String rest = zl.substring(dp + 1);
        String kommentar = Yaml.ohneKommentar(rest).length() < rest.stripTrailing().length() ? rest.substring(Yaml.ohneKommentar(rest).length()) : "";
        z.set(kind.zeile, zl.substring(0, dp + 1) + " " + neu + kommentar);
        return String.join("\n", z);
    }

    private void umbenennen() {
        Eintrag e = gewaehlt();
        if (e == null || e.k == null) return;
        Yaml.Knoten k = e.k;
        switch (e.art) {
            case "schema" -> frage("Schema " + k.schluessel + " umbenennen (mit allen $ref):", k.schluessel, n -> {
                String neu = n.strip();
                if (neu.isEmpty() || neu.equals(k.schluessel)) return;
                if (schemasKnoten().kind(neu) != null) {
                    ed.meldung = neu + " gibt es schon";
                    return;
                }
                String t = schluesselErsetzen(doc.t.toString(), k, Yaml.zitat(neu));
                Matcher m = Pattern.compile("(" + Pattern.quote(schemaPraefix()) + ")" + Pattern.quote(k.schluessel) + "(?=['\"\\s]|$)", Pattern.MULTILINE).matcher(t);
                int[] n2 = {0};
                StringBuilder b = new StringBuilder();
                while (m.find()) {
                    n2[0]++;
                    m.appendReplacement(b, Matcher.quoteReplacement(m.group(1) + neu));
                }
                m.appendTail(b);
                aendern(b.toString(), k.zeile);
                ed.meldung = k.schluessel + " -> " + neu + " (" + n2[0] + " $ref angepasst; Verweise aus anderen Dateien nicht)";
            });
            case "pfad" -> frage("Pfad umbenennen:", k.schluessel, n -> {
                String neu = n.strip();
                if (neu.isEmpty() || neu.equals(k.schluessel)) return;
                aendern(schluesselErsetzen(doc.t.toString(), k, Yaml.zitat(neu.startsWith("/") ? neu : "/" + neu)), k.zeile);
            });
            case "prop" -> frage("Feld " + k.schluessel + " umbenennen:", k.schluessel, n -> {
                String neu = n.strip();
                if (neu.isEmpty() || neu.equals(k.schluessel)) return;
                String t = schluesselErsetzen(doc.t.toString(), k, Yaml.zitat(neu));
                Yaml.Knoten schema = k.eltern == null ? null : k.eltern.eltern;
                if (schema != null) t = pflichtSetzen(t, schema, neu, k.schluessel);
                aendern(t, k.zeile);
            });
            case "op" -> {
                Yaml.Knoten id = k.kind("operationId");
                frage("operationId:", id == null ? operationId(k.schluessel, k.eltern.schluessel) : Yaml.skalar(id.wert), n -> {
                    String neu = n.strip();
                    if (neu.isEmpty()) return;
                    String t = doc.t.toString();
                    if (id != null) t = wertErsetzen(t, id, Yaml.zitat(neu));
                    else t = anhaengen(t, k, block(kindSpalte(k), "operationId: " + Yaml.zitat(neu)));
                    aendern(t, k.zeile);
                });
            }
            case "param", "tag", "server", "antwort", "kompEintrag" -> {
                String feld = e.art.equals("server") ? "url" : e.art.equals("antwort") || e.art.equals("kompEintrag") ? null : "name";
                if (feld == null) {
                    frage("Umbenennen:", k.schluessel, n -> {
                        String neu = n.strip();
                        if (neu.isEmpty() || neu.equals(k.schluessel)) return;
                        aendern(schluesselErsetzen(doc.t.toString(), k, e.art.equals("antwort") ? "'" + neu + "'" : Yaml.zitat(neu)), k.zeile);
                    });
                    return;
                }
                Yaml.Knoten f = k.kind(feld);
                if (f == null) {
                    ed.meldung = "kein " + feld + " zum Umbenennen";
                    return;
                }
                frage(feld + ":", Yaml.skalar(f.wert), n -> {
                    if (!n.strip().isEmpty()) aendern(wertErsetzen(doc.t.toString(), f, Yaml.zitat(n.strip())), k.zeile);
                });
            }
            default -> ed.meldung = "r: hier gibt es nichts umzubenennen";
        }
    }

    private static final Set<String> GANZE_ZEILEN = Set.of("pfad", "op", "schema", "prop", "param", "antwort", "tag", "server", "kompEintrag", "body", "optags",
            "params", "enum", "teil");

    private void loeschen() {
        Eintrag e = gewaehlt();
        if (e == null || e.k == null || !GANZE_ZEILEN.contains(e.art)) {
            ed.meldung = "d: hier gibt es nichts zu loeschen";
            return;
        }
        Yaml.Knoten k = e.k;
        List<Integer> benutzt = e.art.equals("schema") ? refs.get(schemaPraefix() + k.schluessel) : null;
        if (!loeschenFrage) {
            loeschenFrage = true;
            ed.meldung = "d noch einmal loescht " + e.text.strip() + " (Zeilen " + (k.zeile + 1) + "-" + k.ende + ")"
                    + (benutzt == null ? "" : " - Achtung: " + benutzt.size() + " $ref zeigen darauf");
            return;
        }
        loeschenFrage = false;
        String t = doc.t.toString();
        List<String> z = new ArrayList<>(List.of(t.split("\n", -1)));
        Yaml.Knoten eltern = k.eltern;
        z.subList(k.zeile, Math.max(k.ende, k.zeile + 1)).clear();
        // war es das letzte Kind: {} bzw. [] hinter den Elternschluessel, sonst waere der Wert null
        if (eltern != null && eltern.zeile >= 0 && eltern.kinder.size() == 1) z.set(eltern.zeile, z.get(eltern.zeile) + (eltern.liste ? " []" : " {}"));
        t = String.join("\n", z);
        if (e.art.equals("prop") && eltern != null && eltern.eltern != null) t = pflichtSetzen(t, eltern.eltern, "", k.schluessel);
        aendern(t, Math.max(0, k.zeile - 1));
        ed.meldung = e.text.strip() + " geloescht (u nimmt es zurueck)";
    }

    private void verschieben(int richtung) {
        Eintrag e = gewaehlt();
        if (e == null || e.k == null || e.k.eltern == null || !GANZE_ZEILEN.contains(e.art)) return;
        Yaml.Knoten k = e.k;
        List<Yaml.Knoten> g = k.eltern.kinder;
        int i = g.indexOf(k), j = i + richtung;
        if (j < 0 || j >= g.size()) return;
        Yaml.Knoten a = g.get(Math.min(i, j)), b = g.get(Math.max(i, j));
        // die erste Zeile eines Listeneintrags (- name: x) gehoert zum Eintrag, nicht zum Schluessel
        if (a.zeile == a.eltern.zeile || b.zeile == b.eltern.zeile) return;
        List<String> z = new ArrayList<>(List.of(doc.t.toString().split("\n", -1)));
        List<String> ba = new ArrayList<>(z.subList(a.zeile, a.ende)), zw = new ArrayList<>(z.subList(a.ende, b.zeile)), bb = new ArrayList<>(z.subList(b.zeile, b.ende));
        List<String> neu = new ArrayList<>(bb);
        neu.addAll(zw);
        neu.addAll(ba);
        z.subList(a.zeile, b.ende).clear();
        z.addAll(a.zeile, neu);
        int ziel = richtung > 0 ? a.zeile + bb.size() + zw.size() : a.zeile;
        aendern(String.join("\n", z), ziel);
    }

    private void verwendungen() {
        Eintrag e = gewaehlt();
        if (e == null || e.k == null) return;
        String ref = null;
        if (e.art.equals("schema")) ref = schemaPraefix() + e.k.schluessel;
        else if (e.art.equals("kompEintrag") && e.k.eltern != null) ref = "#/" + (v2 ? "" : "components/") + e.k.eltern.schluessel + "/" + e.k.schluessel;
        if (ref == null) {
            ed.meldung = "U: Verwendungen gibt es fuer Schemas und Komponenten";
            return;
        }
        List<Integer> l = refs.getOrDefault(ref, List.of());
        if (l.isEmpty()) {
            ed.meldung = e.k.schluessel + " wird nirgends benutzt";
            return;
        }
        Map<String, Integer> wahlen = new LinkedHashMap<>();
        for (int z : l) wahlen.put("Zeile " + (z + 1) + ": " + ort(z), z);
        waehlen(l.size() + " Verwendungen von " + e.k.schluessel, new ArrayList<>(wahlen.keySet()), w -> waehleZeile(wahlen.get(w)));
    }

    /** Ort einer Zeile als Pfad: GET /kunden/{id} > responses > 200 */
    private String ort(int zeile) {
        List<String> teile = new ArrayList<>();
        Yaml.Knoten x = wurzel;
        while (true) {
            Yaml.Knoten n = null;
            for (Yaml.Knoten y : x.kinder) if (y.zeile <= zeile && zeile < Math.max(y.ende, y.zeile + 1)) n = y;
            if (n == null) break;
            if (n.schluessel != null && !n.schluessel.equals("paths") && !n.schluessel.equals("content") && !n.schluessel.equals("schema")) {
                teile.add(METHODEN.contains(n.schluessel) ? n.schluessel.toUpperCase() : n.schluessel);
            }
            x = n;
        }
        return String.join(" > ", teile);
    }

    private void folgen() {
        Eintrag e = gewaehlt();
        if (e == null || e.k == null) return;
        String ref = ersterRef(e.k);
        if (ref == null) {
            ed.meldung = "f: kein $ref in " + e.text.strip();
            return;
        }
        refOeffnen(ref, doc);
    }

    private static String ersterRef(Yaml.Knoten k) {
        if (k.text("$ref") != null) return k.text("$ref");
        for (Yaml.Knoten x : k.kinder) {
            String r = ersterRef(x);
            if (r != null) return r;
        }
        return null;
    }

    /** $ref oeffnen: lokal in der Ansicht waehlen, sonst die andere Datei (dort ebenfalls die Ansicht) */
    private boolean refOeffnen(String ref, Doc von) {
        int h = ref.indexOf('#');
        String datei = h < 0 ? ref : ref.substring(0, h);
        Doc ziel = von;
        if (!datei.isEmpty()) {
            if (von.pfad == null) return false;
            Path p = von.pfad.getParent().resolve(datei).normalize();
            if (!Files.isRegularFile(p)) {
                ed.meldung = "Datei fehlt: " + p;
                return false;
            }
            ed.springMerken();
            ziel = ed.oeffnen(p);
            if (ziel == null) return false;
        }
        Yaml.Knoten w = ziel == doc && stand == doc.version ? wurzel : Yaml.lesen(ziel.t.toString());
        Yaml.Knoten k = h < 0 ? w : aufloesen(w, ref.substring(h));
        if (k == null) {
            ed.meldung = "$ref zeigt ins Leere: " + ref;
            return false;
        }
        if (sichtbar() && ziel == doc) {
            waehleZeile(k.zeile);
        } else if (ziel != von || !ed.modus.equals("openapi")) {
            if (ziel == von) ed.springMerken();
            int z = Math.max(0, k.zeile);
            ziel.cursor = ziel.stelle(z, Yaml.einzug(ziel.zeile(z)));
            if (ed.modus.equals("openapi") && istOpenApi(ziel.t)) starten(ziel);
        }
        return true;
    }

    // ================================================================ normaler Puffer: gd und gr
    /** gd im normalen Puffer einer OpenAPI-YAML: dem $ref in der Zeile folgen; false = nicht zustaendig */
    boolean definition(Doc d) {
        if (!istYaml(d) || !istOpenApi(d.t)) return false;
        String z = d.zeile(d.zeileVon(d.cursor));
        Matcher m = Pattern.compile("\\$ref\\s*:\\s*['\"]?([^'\"#\\s]*#?[^'\"\\s]*)").matcher(z);
        if (!m.find()) {
            ed.meldung = "gd: kein $ref in dieser Zeile";
            return true;
        }
        refOeffnen(m.group(1), d);
        return true;
    }

    /** gr im normalen Puffer: Verwendungen des Schemas (bzw. der Komponente) in der Zeile */
    boolean verwendungenImPuffer(Doc d) {
        if (!istYaml(d) || !istOpenApi(d.t)) return false;
        Yaml.Knoten w = Yaml.lesen(d.t.toString());
        int zeile = d.zeileVon(d.cursor);
        Yaml.Knoten k = knotenInZeile(w, zeile);
        boolean zwei = w.kind("swagger") != null;
        String ref = null;
        if (k != null && k.zeile == zeile && k.eltern != null && k.eltern.eltern != null) {
            Yaml.Knoten p = k.eltern;
            if (zwei && p.eltern == w) ref = "#/" + p.schluessel + "/" + k.schluessel;
            else if (!zwei && p.eltern.eltern == w && "components".equals(p.eltern.schluessel)) ref = "#/components/" + p.schluessel + "/" + k.schluessel;
        }
        if (ref == null) {
            ed.meldung = "gr: Cursor auf den Namen eines Schemas bzw. einer Komponente setzen";
            return true;
        }
        Map<String, List<Integer>> r = new HashMap<>();
        refsSammeln(w, r);
        List<Integer> l = r.getOrDefault(ref, List.of());
        if (l.isEmpty()) {
            ed.meldung = k.schluessel + " wird nirgends benutzt";
            return true;
        }
        Map<String, Integer> wahlen = new LinkedHashMap<>();
        for (int z : l) wahlen.put("Zeile " + (z + 1) + ": " + d.zeile(z).strip(), z);
        ed.wz.auswahl(l.size() + " Verwendungen von " + k.schluessel, new ArrayList<>(wahlen.keySet()), null, x -> {
            ed.springMerken();
            int z = wahlen.get(x);
            d.cursor = d.stelle(z, Yaml.einzug(d.zeile(z)));
        });
        return true;
    }

    // ================================================================ Malen
    void malen(int y0, int y1) {
        aktualisieren();
        Screen s = ed.scr;
        int W = s.w, cw = s.cw, lh = s.lh;
        int lw = Math.max(32 * cw, Math.min(W * 45 / 100, 90 * cw));
        int h = y1 - y0;
        Eintrag g = eintraege.isEmpty() ? null : eintraege.get(wahl);
        // ---------------------------------------- Baum links
        Yaml.Knoten info = wurzel.kind("info");
        String kopf = " " + (v2 ? "Swagger" : "OpenAPI") + ": " + (info == null ? doc.name() : nz(info.text("title")) + " " + nz(info.text("version")))
                + (filter.isEmpty() ? "" : "   Filter: " + filter) + " ";
        s.rect(0, y0, lw, lh, E_LEISTE);
        s.clip(0, y0, lw, lh);
        s.text(cw, y0, kopf, E_HELL);
        s.noClip();
        int zeilen = Math.max(1, (h - 2 * lh) / lh);
        if (wahl < oben) oben = wahl;
        if (wahl >= oben + zeilen) oben = wahl - zeilen + 1;
        oben = Math.max(0, Math.min(oben, Math.max(0, eintraege.size() - zeilen)));
        s.rect(0, y0 + lh, lw, h - lh, E_KONSOLE);
        s.clip(0, y0 + lh, lw - cw, h - 2 * lh);
        for (int r = 0; r < zeilen && oben + r < eintraege.size(); r++) {
            Eintrag e = eintraege.get(oben + r);
            int y = y0 + lh + r * lh;
            boolean gw = oben + r == wahl;
            if (gw) s.rect(0, y, lw - cw, lh, E_TEXT);
            int x = cw + e.tiefe * 2 * cw;
            String marke = e.kinder ? istOffen(e.id, standardOffen(e)) ? "- " : "+ " : "  ";
            x = s.text(x, y, marke, gw ? E_GRUND : E_MATT);
            x = s.text(x, y, e.text, gw ? E_GRUND : e.farbe);
            if (!e.rechts.isEmpty()) s.text(x + 2 * cw, y, e.rechts, gw ? E_LEISTE : E_MATT);
        }
        if (eintraege.isEmpty()) s.text(2 * cw, y0 + lh, "(nichts passt zum Filter)", E_MATT);
        s.noClip();
        int hy = y1 - lh;
        s.rect(0, hy, lw, lh, E_LEISTE);
        s.clip(0, hy, lw, lh);
        String hilfe = "a neu  r umbenennen  d loeschen  u undo  J/K schieben  f $ref  U benutzt  / Filter  p Problem";
        if (s.textWidth(hilfe) > lw - 2 * cw) hilfe = "a neu  r umbenennen  d loeschen  u undo  f $ref  / Filter";
        if (s.textWidth(hilfe) > lw - 2 * cw) hilfe = "a r d u f /  (bindings openapi)";
        s.text(cw, hy, loeschenFrage ? "d: loeschen bestaetigen, j/k: abbrechen" : hilfe, loeschenFrage ? E_WARN : E_MATT);
        s.noClip();
        s.rect(lw - 1, y0, 2, h, E_MATT);
        // ---------------------------------------- YAML rechts
        int rx = lw + 2, rw = W - rx;
        int von = g == null || g.zeile < 0 ? 0 : g.zeile, bis = g != null && g.k != null ? Math.max(g.k.ende, g.k.zeile + 1) : von + 1;
        String rk = " " + doc.name() + (doc.geaendert ? "+" : "") + "   Zeile " + (von + 1) + (bis - von > 1 ? "-" + bis : "") + "   ENTER bearbeiten, Ctrl-j/k rollen ";
        s.rect(rx, y0, rw, lh, E_LEISTE);
        s.clip(rx, y0, rw, lh);
        s.text(rx + cw, y0, rk, E_HELL);
        s.noClip();
        int n = doc.zeilen();
        int rz = Math.max(1, (h - lh) / lh);
        int start = Math.max(0, von - Math.min(3, rz / 4)) + versatz;
        start = Math.max(0, Math.min(start, Math.max(0, n - rz)));
        int rand = String.valueOf(n).length() + 1;
        s.rect(rx, y0 + lh, rw, h - lh, E_GRUND);
        s.clip(rx, y0 + lh, rw, h - lh);
        Set<Integer> problemZeilen = new HashSet<>();
        for (Problem p : probleme) problemZeilen.add(p.zeile());
        for (int r = 0; r < rz && start + r < n; r++) {
            int zi = start + r;
            int y = y0 + lh + r * lh;
            if (zi >= von && zi < bis) s.rect(rx, y, rw, lh, E_ZEILE);
            String nr = String.valueOf(zi + 1);
            s.text(rx + (rand - nr.length()) * cw, y, nr, problemZeilen.contains(zi) ? E_FEHLER : E_MATT);
            int tx = rx + (rand + 1) * cw;
            int a = doc.anf(zi);
            String zl = doc.zeile(zi);
            for (int c = 0; c < zl.length() && tx + c * cw < W; c++) {
                int p = a + c;
                int f = doc.farben != null && doc.farbenVersion == doc.version && p < doc.farben.length ? Editor.FARBEN[doc.farben[p]] : E_TEXT;
                char ch = zl.charAt(c);
                s.glyph(tx + c * cw, y, ch == '\t' ? ' ' : ch, f);
            }
        }
        s.noClip();
    }
}
