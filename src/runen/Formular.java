package runen;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Formular als Fenster ueber dem Editor: mehrere Textfelder untereinander. TAB/Shift-TAB oder Pfeile wechseln das Feld,
 * Strg+Leertaste geht die Vorschlaege des Feldes durch, ENTER uebernimmt, Strg+X ueberspringt, ESC bricht ab.
 */
final class Formular {
    private final Editor ed;
    boolean offen;
    private String vorModus = "normal", titel = "";
    private String[] namen = new String[0];
    private StringBuilder[] werte = new StringBuilder[0];
    private int feld;
    private BiFunction<String, Integer, List<String>> vorschlaege;
    private Consumer<String[]> fertig;
    private Runnable abbruch;
    /** Vorschlaege des aktuellen Feldes und welcher gerade drin steht */
    private List<String> liste = List.of();
    private int listeWahl = -1;
    private String listeBasis;

    Formular(Editor ed) {
        this.ed = ed;
    }

    /** felder: {Name, Wert}; vorschlaege(eingabe, feld) liefert Vorschlaege; fertig(null) = uebersprungen */
    void oeffnen(String titel, String[][] felder, BiFunction<String, Integer, List<String>> vorschlaege, Consumer<String[]> fertig, Runnable abbruch) {
        if (!offen) vorModus = ed.modus.equals("formular") ? "normal" : ed.modus;
        offen = true;
        this.titel = titel;
        namen = new String[felder.length];
        werte = new StringBuilder[felder.length];
        for (int i = 0; i < felder.length; i++) {
            namen[i] = felder[i][0];
            werte[i] = new StringBuilder(felder[i][1] == null ? "" : felder[i][1]);
        }
        feld = 0;
        this.vorschlaege = vorschlaege;
        this.fertig = fertig;
        this.abbruch = abbruch;
        listeZu();
        ed.modus = "formular";
    }

    private void zu() {
        offen = false;
        if (ed.modus.equals("formular")) ed.modus = vorModus;
    }

    private void listeZu() {
        liste = List.of();
        listeWahl = -1;
        listeBasis = null;
    }

    private void vorschlag(int richtung) {
        if (vorschlaege == null) return;
        if (listeBasis == null) {
            listeBasis = werte[feld].toString();
            liste = vorschlaege.apply(listeBasis, feld);
            listeWahl = -1;
        }
        if (liste.isEmpty()) {
            ed.meldung = "keine Vorschlaege fuer \"" + listeBasis + "\"";
            return;
        }
        listeWahl = Math.floorMod(listeWahl + richtung, liste.size());
        werte[feld].setLength(0);
        werte[feld].append(liste.get(listeWahl));
    }

    void befehle(Map<String, Editor.Cmd> c) {
        /** @binding Formular: Zeichen ins Feld */
        c.put("formChar", a -> {
            werte[feld].append(a);
            listeZu();
        });
        /** @binding Formular: letztes Zeichen loeschen */
        c.put("formBackspace", a -> {
            if (werte[feld].length() > 0) werte[feld].setLength(werte[feld].length() - 1);
            listeZu();
        });
        /** @binding Formular: Feld leeren */
        c.put("formClear", a -> {
            werte[feld].setLength(0);
            listeZu();
        });
        /** @binding Formular: naechstes (1) bzw. voriges (-1) Feld */
        c.put("formField", a -> {
            feld = Math.floorMod(feld + ("-1".equals(a.strip()) ? -1 : 1), werte.length);
            listeZu();
        });
        /** @binding Formular: naechster (1) bzw. voriger (-1) Vorschlag fuer das Feld */
        c.put("formSuggest", a -> vorschlag("-1".equals(a.strip()) ? -1 : 1));
        /** @binding Formular: uebernehmen */
        c.put("formAccept", a -> {
            String[] w = new String[werte.length];
            for (int i = 0; i < w.length; i++) w[i] = werte[i].toString();
            Consumer<String[]> f = fertig;
            zu();
            if (f != null) f.accept(w);
        });
        /** @binding Formular: diesen Eintrag ueberspringen */
        c.put("formSkip", a -> {
            Consumer<String[]> f = fertig;
            zu();
            if (f != null) f.accept(null);
        });
        /** @binding Formular: abbrechen */
        c.put("formCancel", a -> {
            Runnable r = abbruch;
            zu();
            if (r != null) r.run();
        });
    }

    void malen() {
        Screen s = ed.scr;
        int cw = s.cw, lh = s.lh;
        int lab = 0;
        for (String n : namen) lab = Math.max(lab, n.length());
        String hilfe = "TAB Feld   Strg+Leertaste Vorschlag   ENTER speichern   Strg+X ueberspringen   ESC abbrechen";
        int w = Math.min(s.w - 4 * cw, Math.max(Math.max(titel.length(), hilfe.length()) + 6, 70) * cw);
        int h = (namen.length + 4) * lh + 24;
        int x = (s.w - w) / 2, y = (s.h - h) / 3;
        s.rect(x + 6, y + 6, w, h, 0);
        s.rect(x, y, w, h, Editor.E_KONSOLE);
        s.frame(x, y, w, h, Editor.E_HELL);
        s.frame(x + 3, y + 3, w - 6, h - 6, Editor.E_MATT);
        s.clip(x, y, w, h);
        s.text(x + 2 * cw, y + 8, titel, Editor.E_HELL);
        for (int i = 0; i < namen.length; i++) {
            int fy = y + 8 + (i + 2) * lh;
            boolean an = i == feld;
            s.text(x + 2 * cw, fy, namen[i] + ":", an ? Editor.E_HELL : Editor.E_MATT);
            int fx = x + (lab + 4) * cw;
            if (an) s.rect(fx - cw / 2, fy, w - (lab + 6) * cw + cw / 2, lh, Editor.E_ZEILE);
            int ex = s.text(fx, fy, werte[i].toString(), Editor.E_TEXT);
            if (an) {
                s.rect(ex + 1, fy + 1, cw - 2, lh - 2, Editor.E_TEXT);
                if (listeWahl >= 0 && !liste.isEmpty()) s.text(ex + 2 * cw, fy, "(" + (listeWahl + 1) + "/" + liste.size() + ")", Editor.E_MATT);
            }
        }
        s.text(x + 2 * cw, y + 8 + (namen.length + 2) * lh + 6, hilfe, Editor.E_MATT);
        s.noClip();
    }
}
