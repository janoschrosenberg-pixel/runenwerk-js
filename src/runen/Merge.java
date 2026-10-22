package runen;

import static runen.Editor.E_GRUND;
import static runen.Editor.E_HELL;
import static runen.Editor.E_KONSOLE;
import static runen.Editor.E_LEISTE;
import static runen.Editor.E_MATT;
import static runen.Editor.E_TEXT;
import static runen.Editor.E_WARN;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Merge-Editor nach dem Vorbild von Emacs Ediff (3-Wege): oben A (unsere Seite) und B (ihre Seite),
 * unten C, das Ergebnis (die Datei selbst). n/p springen von Konflikt zu Konflikt, a/b uebernehmen
 * eine Seite, + beide, r stellt die Konfliktmarken wieder her, q beendet.
 */
final class Merge {
    /** ein Stueck der Datei: fester Text oder ein Konflikt (a, basis, b mit wahl) */
    static final class Teil {
        final String fest, a, basis, b, nameA, nameB;
        /** null = offen, sonst a, b, ab, ba */
        String wahl;

        Teil(String fest) {
            this.fest = fest;
            a = basis = b = nameA = nameB = null;
        }

        Teil(String a, String basis, String b, String nameA, String nameB) {
            fest = null;
            this.a = a;
            this.basis = basis;
            this.b = b;
            this.nameA = nameA;
            this.nameB = nameB;
        }

        boolean konflikt() {
            return fest == null;
        }

        /** Text mit Konfliktmarken (offener Konflikt in C) */
        String marken() {
            return "<<<<<<< " + nameA + "\n" + a + (basis != null ? "||||||| basis\n" + basis : "") + "=======\n" + b + ">>>>>>> " + nameB + "\n";
        }
    }

    /** gebauter Text einer Seite mit den Zeilen jedes Konflikts {von, bis (exklusiv)} */
    record Sicht(String text, List<int[]> bereiche, byte[] farben) {}

    private final Editor ed;
    Doc doc;
    private List<Teil> teile = List.of();
    private final List<Teil> konflikte = new ArrayList<>();
    int k;
    private String gebaut;
    private int versatz;
    private boolean zweitesQ;
    boolean aktiv;
    private Sicht sa, sb, sc;

    Merge(Editor ed) {
        this.ed = ed;
    }

    /** in der aktuellen Ansicht: aktiv und dieselbe Datei */
    boolean sichtbar() {
        return aktiv && ed.d() == doc && ed.modus.equals("merge");
    }

    static boolean hatKonflikte(CharSequence t) {
        String s = t.toString();
        return s.startsWith("<<<<<<< ") || s.contains("\n<<<<<<< ");
    }

    static List<Teil> zerlegen(String t) {
        List<Teil> r = new ArrayList<>();
        StringBuilder fest = new StringBuilder();
        String[] zeilen = t.split("(?<=\n)");
        int i = 0;
        while (i < zeilen.length) {
            String z = zeilen[i];
            if (!z.startsWith("<<<<<<<")) {
                fest.append(z);
                i++;
                continue;
            }
            // Konflikt bis >>>>>>> suchen
            StringBuilder a = new StringBuilder(), basis = null, b = new StringBuilder();
            String nameA = z.substring(7).strip(), nameB = "";
            int teil = 0, j = i + 1;
            boolean ende = false;
            for (; j < zeilen.length; j++) {
                String y = zeilen[j];
                if (y.startsWith("|||||||") && teil == 0) {
                    basis = new StringBuilder();
                    teil = 1;
                } else if (y.strip().equals("=======") && teil < 2) {
                    teil = 2;
                } else if (y.startsWith(">>>>>>>") && teil == 2) {
                    nameB = y.substring(7).strip();
                    ende = true;
                    break;
                } else {
                    (teil == 0 ? a : teil == 1 ? basis : b).append(y.endsWith("\n") ? y : y + "\n");
                }
            }
            if (!ende) {
                // keine vollstaendigen Marken: als Text lassen
                fest.append(z);
                i++;
                continue;
            }
            if (fest.length() > 0) r.add(new Teil(fest.toString()));
            fest.setLength(0);
            r.add(new Teil(a.toString(), basis == null ? null : basis.toString(), b.toString(), nameA, nameB));
            i = j + 1;
        }
        if (fest.length() > 0) r.add(new Teil(fest.toString()));
        return r;
    }

    /** 0 = A, 1 = B, 2 = C (Ergebnis) */
    private Sicht bauen(int seite) {
        StringBuilder t = new StringBuilder();
        List<int[]> bereiche = new ArrayList<>();
        int zeile = 0;
        for (Teil x : teile) {
            String s;
            if (!x.konflikt()) {
                s = x.fest;
            } else {
                s = switch (seite) {
                    case 0 -> x.a;
                    case 1 -> x.b;
                    default -> x.wahl == null ? x.marken() : switch (x.wahl) {
                        case "a" -> x.a;
                        case "b" -> x.b;
                        case "ab" -> x.a + x.b;
                        default -> x.b + x.a;
                    };
                };
                int n = (int) s.chars().filter(c -> c == '\n').count();
                bereiche.add(new int[] {zeile, zeile + Math.max(1, n)});
            }
            t.append(s);
            zeile += (int) s.chars().filter(c -> c == '\n').count();
        }
        String text = t.toString();
        String sprache = doc.sprache();
        byte[] f = sprache == null ? null : Highlight.faerben(text, sprache);
        return new Sicht(text, bereiche, f);
    }

    private void neuBauen() {
        sa = bauen(0);
        sb = bauen(1);
        sc = bauen(2);
    }

    // ---------------------------------------------------------------- Steuern
    void starten(Doc d) {
        if (aktiv && d == doc && d.t.toString().equals(gebaut)) {
            ed.modus = "merge";
            return;
        }
        doc = d;
        teile = zerlegen(d.t.toString());
        konflikte.clear();
        for (Teil t : teile) if (t.konflikt()) konflikte.add(t);
        if (konflikte.isEmpty()) {
            aktiv = false;
            ed.meldung = "keine Konfliktmarken in " + d.name();
            return;
        }
        gebaut = d.t.toString();
        k = 0;
        versatz = 0;
        aktiv = true;
        zweitesQ = false;
        neuBauen();
        ed.modus = "merge";
        cursorZumKonflikt();
        ed.meldung = konflikte.size() + " Konflikte in " + d.name() + " - n/p springen, a/b uebernehmen, + beide, r zuruecksetzen, q fertig";
    }

    private int offen() {
        int n = 0;
        for (Teil t : konflikte) if (t.wahl == null) n++;
        return n;
    }

    private void anwenden() {
        String neu = bauen(2).text();
        doc.merken();
        doc.setText(neu);
        gebaut = neu;
        neuBauen();
        cursorZumKonflikt();
        zweitesQ = false;
    }

    private void cursorZumKonflikt() {
        if (k < sc.bereiche().size()) doc.cursor = doc.stelle(sc.bereiche().get(k)[0], 0);
    }

    void befehle(Map<String, Editor.Cmd> c) {
        c.put("mergeNext", a -> {
            k = Math.min(konflikte.size() - 1, k + 1);
            versatz = 0;
            cursorZumKonflikt();
        });
        c.put("mergePrev", a -> {
            k = Math.max(0, k - 1);
            versatz = 0;
            cursorZumKonflikt();
        });
        c.put("mergeNextOpen", a -> {
            for (int i = 1; i <= konflikte.size(); i++) {
                int x = (k + i) % konflikte.size();
                if (konflikte.get(x).wahl == null) {
                    k = x;
                    versatz = 0;
                    cursorZumKonflikt();
                    return;
                }
            }
            ed.meldung = "alle Konflikte geloest - q speichert und merkt die Datei vor";
        });
        c.put("mergeTake", a -> {
            if (k >= konflikte.size()) return;
            konflikte.get(k).wahl = a.trim().isEmpty() ? "a" : a.trim();
            anwenden();
            int o = offen();
            ed.meldung = o == 0 ? "alle Konflikte geloest - q speichert und merkt die Datei vor" : o + " Konflikte offen";
        });
        c.put("mergeRestore", a -> {
            if (k >= konflikte.size()) return;
            konflikte.get(k).wahl = null;
            anwenden();
        });
        c.put("mergeScroll", a -> {
            try {
                versatz += Integer.parseInt(a.trim());
            } catch (NumberFormatException e) {
                // nichts
            }
        });
        c.put("mergeEdit", a -> {
            // C von Hand bearbeiten; Leertaste g m kommt zurueck
            ed.modus = "normal";
            ed.meldung = "C bearbeiten - Leertaste g m zurueck in den Merge-Editor";
        });
        c.put("mergeQuit", a -> {
            int o = offen();
            if (o > 0 && !zweitesQ) {
                zweitesQ = true;
                ed.meldung = o + " Konflikte sind noch offen - q noch einmal beendet trotzdem";
                return;
            }
            aktiv = false;
            ed.modus = "normal";
            if (o > 0) {
                ed.meldung = "Merge-Editor beendet, " + o + " Konflikte offen";
                return;
            }
            ed.befehl("save", "");
            Path repo = doc.pfad == null ? null : Git.wurzel(doc.pfad);
            if (repo != null && !doc.geaendert) {
                Git.Lauf l = Git.git(repo, "add", "--", repo.relativize(doc.pfad).toString());
                ed.meldung = l.code() == 0 ? doc.name() + " geloest und vorgemerkt (git add) - Leertaste g c committet" : "git add: " + l.ausgabe();
            }
        });
    }

    // ---------------------------------------------------------------- Malen
    void malen(int y0, int y1) {
        Screen s = ed.scr;
        int W = s.w;
        int h = y1 - y0;
        int obenH = h / 2;
        Teil t = k < konflikte.size() ? konflikte.get(k) : null;
        String zustand = t == null ? "" : t.wahl == null ? "offen" : switch (t.wahl) {
            case "a" -> "A gewaehlt";
            case "b" -> "B gewaehlt";
            case "ab" -> "A+B";
            default -> "B+A";
        };
        String kopfA = " A: " + (t != null ? t.nameA : "unsere Seite") + " ";
        String kopfB = " B: " + (t != null ? t.nameB : "ihre Seite") + " ";
        String kopfC = " C: Ergebnis " + doc.name() + "   Konflikt " + (k + 1) + "/" + konflikte.size() + " (" + zustand + "), offen " + offen()
                + "   n/p a/b + * r j/k e q ";
        pane(s, sa, 0, y0, W / 2 - 2, obenH - 2, kopfA, 0x0E2A40);
        pane(s, sb, W / 2 + 2, y0, W - W / 2 - 2, obenH - 2, kopfB, 0x3A1832);
        pane(s, sc, 0, y0 + obenH + 2, W, h - obenH - 2, kopfC, t != null && t.wahl == null ? 0x4A1414 : 0x123A1E);
        s.rect(W / 2 - 1, y0, 2, obenH - 2, E_MATT);
        s.rect(0, y0 + obenH - 1, W, 2, E_MATT);
    }

    private void pane(Screen s, Sicht v, int x, int y, int w, int h, String kopf, int markFarbe) {
        int lh = s.lh, cw = s.cw;
        s.rect(x, y, w, lh, E_LEISTE);
        s.clip(x, y, w, lh);
        s.text(x + cw, y, kopf, E_HELL);
        s.noClip();
        int zeilen = Math.max(1, (h - lh) / lh);
        String[] z = v.text().split("\n", -1);
        int start = 0;
        if (k < v.bereiche().size()) start = Math.max(0, v.bereiche().get(k)[0] - zeilen / 3);
        start = Math.max(0, Math.min(Math.max(0, z.length - zeilen), start + versatz));
        int rand = String.valueOf(z.length).length() + 1;
        int[] anf = new int[z.length + 1];
        for (int i = 1; i <= z.length; i++) anf[i] = anf[i - 1] + z[i - 1].length() + 1;
        s.clip(x, y + lh, w, h - lh);
        s.rect(x, y + lh, w, h - lh, E_GRUND);
        for (int r = 0; r < zeilen && start + r < z.length; r++) {
            int zi = start + r;
            int zy = y + lh + r * lh;
            for (int b = 0; b < v.bereiche().size(); b++) {
                int[] be = v.bereiche().get(b);
                if (zi >= be[0] && zi < be[1]) s.rect(x, zy, w, lh, b == k ? markFarbe : E_KONSOLE);
            }
            String nr = String.valueOf(zi + 1);
            s.text(x + (rand - nr.length()) * cw - cw / 2, zy, nr, E_MATT);
            int tx = x + (rand + 1) * cw;
            String zeile = z[zi];
            for (int c = 0; c < zeile.length() && tx + c * cw < x + w; c++) {
                int p = anf[zi] + c;
                int f = v.farben() != null && p < v.farben().length ? Editor.FARBEN[v.farben()[p]] : E_TEXT;
                char ch = zeile.charAt(c);
                if (zeile.startsWith("<<<<<<<") || zeile.startsWith(">>>>>>>") || zeile.startsWith("=======") || zeile.startsWith("|||||||")) f = E_WARN;
                s.glyph(tx + c * cw, zy, ch == '\t' ? ' ' : ch, f);
            }
        }
        s.noClip();
    }
}
