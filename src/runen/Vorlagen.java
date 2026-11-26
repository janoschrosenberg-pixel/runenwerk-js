package runen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Templates: Textbausteine in templates/NAME.template neben config.txt (review.template ist vorgegeben). Platzhalter:
 * &lt;anrede&gt; &lt;name&gt; &lt;id&gt; &lt;mail&gt; &lt;mattermost&gt; &lt;scm&gt; aus personen.tsv und &lt;bank0&gt; bis
 * &lt;bank9&gt; aus den Speicherbaenken (bank1 = Systemzwischenablage). "template review anna" fuegt an der Cursorstelle ein.
 */
final class Vorlagen {
    static final List<String> VORGABEN = List.of("review");

    private final Editor ed;

    Vorlagen(Editor ed) {
        this.ed = ed;
    }

    Path ordner() {
        return ed.konfig.resolve("templates");
    }

    /** vorgegebene Templates anlegen, wenn es sie noch nicht gibt (eigene Aenderungen bleiben) */
    private void vorgaben() {
        for (String n : VORGABEN) {
            Path p = ordner().resolve(n + ".template");
            if (Files.exists(p)) continue;
            try (InputStream in = Vorlagen.class.getResourceAsStream("/vorgaben/templates/" + n + ".template")) {
                if (in == null) continue;
                Files.createDirectories(ordner());
                Files.write(p, in.readAllBytes());
            } catch (IOException e) {
                ed.fehler("Template " + n + " nicht angelegt: " + e.getMessage());
            }
        }
    }

    List<String> namen() {
        vorgaben();
        List<String> r = new ArrayList<>();
        try (var l = Files.list(ordner())) {
            l.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".template")).sorted().forEach(n -> r.add(n.substring(0, n.length() - 9)));
        } catch (IOException e) {
            // keine
        }
        return r;
    }

    /** Text des Templates (eine abschliessende Zeilenumbruch weg), null wenn es fehlt */
    String text(String name) {
        vorgaben();
        try {
            Path p = ordner().resolve(name + ".template");
            if (!Files.exists(p)) return null;
            return Files.readString(p, StandardCharsets.UTF_8).replace("\r", "").replaceFirst("\n$", "");
        } catch (IOException e) {
            return null;
        }
    }

    private static final Pattern PLATZ = Pattern.compile("<(anrede|name|id|mail|mattermost|scm|bank(\\d))>");

    /** Platzhalter ersetzen; banken: feste Inhalte (z. B. bank1 = PR-Adresse), sonst die Speicherbaenke */
    String fuellen(String t, Personen.Person p, Map<Integer, String> banken) {
        Matcher m = PLATZ.matcher(t);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String w;
            if (m.group(2) != null) {
                int n = Integer.parseInt(m.group(2));
                if (banken != null && banken.containsKey(n)) w = banken.get(n);
                else {
                    Vim.Reg g = ed.vim.bank(n);
                    w = g == null ? "" : g.text().replaceFirst("\n$", "");
                }
            } else if (p == null) {
                w = m.group();
            } else {
                w = switch (m.group(1)) {
                    case "anrede" -> p.anrede();
                    case "name" -> p.name();
                    case "id" -> p.id();
                    case "mail" -> p.mail();
                    case "mattermost" -> p.mattermost().isEmpty() ? "" : "@" + p.mattermost();
                    default -> p.scm();
                };
            }
            m.appendReplacement(b, Matcher.quoteReplacement(w));
        }
        m.appendTail(b);
        return b.toString();
    }

    static boolean brauchtPerson(String t) {
        return Pattern.compile("<(anrede|name|id|mail|mattermost|scm)>").matcher(t).find();
    }

    /** "template NAME [ID]": an der Cursorstelle einfuegen; ohne Angaben erst Template, dann Person waehlen */
    void einfuegen(String args) {
        String[] a = args.strip().isEmpty() ? new String[0] : args.strip().split("\\s+");
        if (a.length == 0) {
            List<String> l = namen();
            if (l.isEmpty()) {
                ed.fehler("keine Templates in " + ordner());
                return;
            }
            ed.wz.auswahl("Template einfuegen (" + ordner() + ")", l, null, n -> einfuegen(n));
            return;
        }
        String t = text(a[0]);
        if (t == null) {
            ed.fehler("kein Template " + a[0] + " (" + ordner().resolve(a[0] + ".template") + ")");
            return;
        }
        Personen.Person p = null;
        if (brauchtPerson(t)) {
            if (a.length < 2) {
                List<String> e = new ArrayList<>();
                for (Personen.Person x : ed.personen.alle()) e.add(x.id() + "  (" + x.anrede() + " " + x.name() + ")");
                if (e.isEmpty()) {
                    ed.fehler("keine Personen (personenAbgleich ordnet die SCM-Benutzer zu)");
                    return;
                }
                ed.wz.auswahl("Template " + a[0] + " fuer", e, null, w -> einfuegen(a[0] + " " + w.substring(0, w.indexOf("  ("))));
                return;
            }
            p = ed.personen.mitId(a[1]);
            if (p == null) {
                ed.fehler("keine Person mit der ID " + a[1] + " (personen.tsv)");
                return;
            }
        }
        Doc d = ed.d();
        String text = fuellen(t, p, null);
        if (d.nurLesen) {
            ed.fehler(d.name() + " ist schreibgeschuetzt");
            return;
        }
        if (!ed.modus.equals("insert")) d.merken();
        d.einfuegen(d.cursor, text);
        ed.meldung = "Template " + a[0] + " eingefuegt" + (p == null ? "" : " fuer " + p.id());
    }

    void befehle(Map<String, Editor.Cmd> c) {
        /** @binding Template an der Cursorstelle einfuegen: template NAME [PERSON-ID] (ohne Angaben: Auswahl) */
        c.put("template", this::einfuegen);
        /** @binding Templates: Ordner mit den .template-Dateien oeffnen (erstes Template) */
        c.put("templates", a -> {
            List<String> l = namen();
            ed.wz.auswahl("Template bearbeiten (" + ordner() + ")", l, null, n -> ed.oeffnen(ordner().resolve(n + ".template")));
        });
    }
}
