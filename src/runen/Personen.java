package runen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/**
 * Personen: Zuordnung von SCM-Benutzern (Quelle) zu Mail-Adresse und Mattermost-Benutzer, dazu Anrede, Name und eine
 * eigene ID (fuer "template review ID"). Gespeichert in personen.tsv neben config.txt (Tabulator getrennt).
 * personenAbgleich zeigt fuer jeden noch nicht zugeordneten SCM-Benutzer ein Formular.
 */
final class Personen {
    record Person(String id, String scm, String anrede, String name, String mail, String mattermost, String scmName) {}

    private final Editor ed;
    private List<Person> liste;
    /** Aenderungszeit der Datei beim Lesen (im Editor geaendert: neu lesen) */
    private long gelesen = -1;

    Personen(Editor ed) {
        this.ed = ed;
    }

    Path datei() {
        return ed.konfig.resolve("personen.tsv");
    }

    /** alle Personen (aus der Datei, einmal gelesen) */
    synchronized List<Person> alle() {
        long zeit = -2;
        try {
            zeit = Files.exists(datei()) ? Files.getLastModifiedTime(datei()).toMillis() : -2;
        } catch (IOException e) {
            // wie gehabt
        }
        if (liste != null && zeit == gelesen) return liste;
        gelesen = zeit;
        List<Person> l = new ArrayList<>();
        try {
            if (Files.exists(datei())) {
                for (String z : Files.readAllLines(datei(), StandardCharsets.UTF_8)) {
                    if (z.isBlank() || z.startsWith("#")) continue;
                    String[] t = z.split("\t", -1);
                    if (t.length < 2) continue;
                    l.add(new Person(t[0].strip(), t[1].strip(), feld(t, 2), feld(t, 3), feld(t, 4), feld(t, 5), feld(t, 6)));
                }
            }
        } catch (IOException e) {
            ed.fehler("personen.tsv nicht gelesen: " + e.getMessage());
        }
        liste = l;
        return l;
    }

    private static String feld(String[] t, int i) {
        return i < t.length ? t[i].strip() : "";
    }

    /** neu lesen (nach Aenderungen an der Datei im Editor) */
    synchronized void neuLesen() {
        liste = null;
    }

    Person mitId(String id) {
        for (Person p : alle()) if (p.id().equalsIgnoreCase(id.strip())) return p;
        return null;
    }

    Person mitScm(String scm) {
        for (Person p : alle()) if (p.scm().equalsIgnoreCase(scm)) return p;
        return null;
    }

    /** Person speichern (gleiche SCM-Kennung wird ersetzt) und die Datei schreiben */
    synchronized void speichern(Person neu) {
        List<Person> l = new ArrayList<>(alle());
        l.removeIf(p -> p.scm().equalsIgnoreCase(neu.scm()));
        l.add(neu);
        l.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
        StringBuilder b = new StringBuilder("# Personen: ID, SCM-Benutzer, Anrede, Name, Mail, Mattermost, Name im SCM (Tabulator getrennt; personenAbgleich ergaenzt)\n");
        for (Person p : l) b.append(String.join("\t", rein(p.id()), rein(p.scm()), rein(p.anrede()), rein(p.name()), rein(p.mail()), rein(p.mattermost()), rein(p.scmName()))).append('\n');
        try {
            Files.writeString(datei(), b.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ed.fehler("personen.tsv nicht gespeichert: " + e.getMessage());
            return;
        }
        liste = l;
        try {
            gelesen = Files.getLastModifiedTime(datei()).toMillis();
        } catch (IOException e) {
            gelesen = -1;
        }
    }

    private static String rein(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').strip();
    }

    // ================================================================ Abgleich
    /** SCM-Benutzer laden; fuer jeden noch nicht zugeordneten das Formular */
    void abgleich() {
        if (ed.kf.get("scm.url", "").isBlank()) {
            ed.fehler("scm.url fehlt in config.txt (die SCM-Benutzer sind die Quelle der Zuordnung)");
            return;
        }
        ed.meldung = "SCM-Benutzer werden geladen ...";
        Thread t = new Thread(() -> {
            List<String[]> scm = ed.scm.benutzerLaden();
            SwingUtilities.invokeLater(() -> {
                List<String[]> offen = new ArrayList<>();
                for (String[] u : scm) if (mitScm(u[0]) == null) offen.add(u);
                if (scm.isEmpty()) {
                    ed.fehler("keine SCM-Benutzer gefunden (Recht zum Lesen der Benutzer im SCM-Manager?)");
                } else if (offen.isEmpty()) {
                    ed.meldung = "alle " + scm.size() + " SCM-Benutzer sind zugeordnet (personen.tsv; personEdit aendert einen)";
                } else {
                    ed.meldung = offen.size() + " SCM-Benutzer ohne Zuordnung";
                    naechster(offen, 0, 0);
                }
                ed.neuMalen.run();
            });
        }, "personen");
        t.setDaemon(true);
        t.start();
    }

    private void naechster(List<String[]> offen, int i, int gespeichert) {
        if (i >= offen.size()) {
            ed.meldung = gespeichert + " Personen zugeordnet (personen.tsv)";
            return;
        }
        String[] u = offen.get(i);
        formular("SCM-Benutzer " + (i + 1) + "/" + offen.size() + ": " + u[0] + (u[1].equals(u[0]) ? "" : " (" + u[1] + ")"), vorschlag(u), p -> {
            if (p == null) {
                // uebersprungen
                naechster(offen, i + 1, gespeichert);
                return;
            }
            speichern(p);
            naechster(offen, i + 1, gespeichert + 1);
        }, () -> ed.meldung = gespeichert + " Personen zugeordnet, " + (offen.size() - i) + " offen (personenAbgleich macht weiter)");
    }

    /** Vorschlag fuer einen SCM-Benutzer {kennung, anzeigename, mail}: ID, Anrede, Vorname, Mail, passender Mattermost-Benutzer */
    Person vorschlag(String[] u) {
        String id = u[0].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "");
        String anzeige = u[1] == null || u[1].isBlank() ? u[0] : u[1];
        String vorname = anzeige.contains(",") ? anzeige.substring(anzeige.indexOf(',') + 1).strip().split("\\s+")[0] : anzeige.split("\\s+")[0];
        return new Person(id, u[0], ed.kf.get("personen.anrede", "Hallo"), vorname, u.length > 2 && u[2] != null ? u[2] : "", ed.mm.passend(u[0], anzeige, u.length > 2 ? u[2] : null), anzeige);
    }

    /** Person mit dem Formular bearbeiten */
    void bearbeiten() {
        List<Person> l = alle();
        if (l.isEmpty()) {
            ed.fehler("noch keine Personen (personenAbgleich ordnet die SCM-Benutzer zu)");
            return;
        }
        List<String> e = new ArrayList<>();
        for (Person p : l) e.add(p.id() + "  (" + p.scmName() + ", SCM " + p.scm() + ")");
        ed.wz.auswahl("Person bearbeiten", e, null, w -> {
            Person p = mitId(w.substring(0, w.indexOf("  (")));
            if (p != null) formular("Person " + p.id() + " (SCM " + p.scm() + ")", p, neu -> {
                if (neu != null) {
                    speichern(neu);
                    ed.meldung = "gespeichert: " + neu.id();
                }
            }, () -> ed.meldung = "nicht geaendert");
        });
    }

    private void formular(String titel, Person p, Consumer<Person> fertig, Runnable abbruch) {
        String[][] felder = {{"ID", p.id()}, {"Anrede", p.anrede()}, {"Name", p.name()}, {"Mail", p.mail()}, {"Mattermost", p.mattermost()}};
        ed.formular.oeffnen(titel, felder, (wert, feld) -> feld == 4 ? ed.mm.vorschlaege(wert) : List.of(), w -> {
            if (w == null) {
                fertig.accept(null);
                return;
            }
            if (w[0].isBlank()) {
                ed.fehler("die ID darf nicht leer sein");
                formular(titel, p, fertig, abbruch);
                return;
            }
            Person andere = mitId(w[0]);
            if (andere != null && !andere.scm().equalsIgnoreCase(p.scm())) {
                ed.fehler("die ID " + w[0] + " hat schon " + andere.scm());
                formular(titel, new Person(w[0], p.scm(), w[1], w[2], w[3], w[4], p.scmName()), fertig, abbruch);
                return;
            }
            fertig.accept(new Person(w[0].strip(), p.scm(), w[1].strip(), w[2].strip(), w[3].strip(), w[4].strip().replaceFirst("^@", ""), p.scmName()));
        }, abbruch);
    }

    void befehle(Map<String, Editor.Cmd> c) {
        /** @binding Personen: fuer jeden noch nicht zugeordneten SCM-Benutzer ein Formular (ID, Anrede, Name, Mail, Mattermost) */
        c.put("personenAbgleich", a -> abgleich());
        /** @binding Personen: eine Person waehlen und im Formular bearbeiten */
        c.put("personEdit", a -> bearbeiten());
        /** @binding Personen: personen.tsv oeffnen */
        c.put("personen", a -> {
            neuLesen();
            ed.oeffnen(datei());
        });
    }
}
