package runen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Emoji-Auswahl (Strg+E im Insert-Mode, Leertaste m e): Raster mit Suchfeld, Strg+H/J/K/L (oder Pfeile) waehlt,
 * ENTER fuegt das Zeichen an der Cursorstelle ein (eigene Emojis des Mattermost-Servers als :name:).
 * Zuletzt benutzte stehen vorn (emoji-zuletzt.txt neben config.txt).
 */
final class EmojiWahl {
    private final Editor ed;
    boolean offen;
    private String vorModus = "normal";
    private Doc ziel;
    private final StringBuilder filter = new StringBuilder();
    private List<Emojis.Emoji> liste = List.of();
    private int wahl, oben;
    /** Spalten des Rasters (beim Malen gesetzt, fuer Strg+J/K) */
    private int spalten = 12;
    private final List<String> zuletzt = new ArrayList<>();
    private boolean geladen;

    EmojiWahl(Editor ed) {
        this.ed = ed;
    }

    private Path datei() {
        return ed.konfig.resolve("emoji-zuletzt.txt");
    }

    void oeffnen() {
        if (!geladen) {
            geladen = true;
            try {
                if (Files.exists(datei())) for (String z : Files.readAllLines(datei(), StandardCharsets.UTF_8)) if (!z.isBlank()) zuletzt.add(z.strip());
            } catch (IOException e) {
                // ohne Verlauf
            }
        }
        ziel = ed.d();
        if (!offen) vorModus = ed.modus;
        offen = true;
        filter.setLength(0);
        filtern();
        ed.modus = "emoji";
        ed.meldung = "Emoji: tippen sucht, Strg+H/J/K/L oder Pfeile waehlen, ENTER fuegt ein, ESC schliesst";
    }

    private void zu() {
        offen = false;
        if (ed.modus.equals("emoji")) ed.modus = vorModus;
    }

    /** alle Emojis: zuletzt benutzte, die eingebauten, die eigenen des Servers (ohne Zeichen) */
    private List<Emojis.Emoji> alle() {
        Set<String> namen = new LinkedHashSet<>();
        List<Emojis.Emoji> r = new ArrayList<>();
        List<String> eigene = ed.mm.eigeneEmojis;
        for (String n : zuletzt) {
            String z = Emojis.zeichen(n);
            if (z == null && !eigene.contains(n)) continue;
            if (namen.add(n)) r.add(new Emojis.Emoji(n, z));
        }
        for (Emojis.Emoji e : Emojis.ALLE) if (namen.add(e.name())) r.add(e);
        for (String n : eigene) if (namen.add(n)) r.add(new Emojis.Emoji(n, null));
        return r;
    }

    private void filtern() {
        String q = filter.toString().toLowerCase().replace(":", "");
        List<Emojis.Emoji> a = alle();
        if (q.isEmpty()) {
            liste = a;
        } else {
            List<Emojis.Emoji> r = new ArrayList<>();
            for (int w = 3; w >= 1; w--) for (Emojis.Emoji e : a) if (Emojis.wertung(e, q) == w) r.add(e);
            liste = r;
        }
        wahl = 0;
        oben = 0;
    }

    private void einfuegen() {
        if (liste.isEmpty()) return;
        Emojis.Emoji e = liste.get(wahl);
        Doc d = ziel;
        zu();
        if (d == null || !ed.tabs.contains(d)) return;
        String s = e.zeichen() != null ? e.zeichen() : ":" + e.name() + ":";
        if (!vorModus.equals("insert")) d.merken();
        d.einfuegen(d.cursor, s);
        zuletzt.remove(e.name());
        zuletzt.add(0, e.name());
        while (zuletzt.size() > 24) zuletzt.remove(zuletzt.size() - 1);
        try {
            Files.write(datei(), zuletzt, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            // Verlauf nur fuer diese Sitzung
        }
        ed.meldung = ":" + e.name() + ": eingefuegt";
    }

    private void bewegen(int dx, int dy) {
        if (liste.isEmpty()) return;
        int n = liste.size();
        int w = wahl + dx + dy * spalten;
        if (dy != 0 && (w < 0 || w >= n)) return;
        wahl = Math.max(0, Math.min(n - 1, w));
    }

    void befehle(Map<String, Editor.Cmd> c) {
        /** @binding Emoji-Auswahl oeffnen (Raster mit Suchfeld; fuegt an der Cursorstelle ein) */
        c.put("emojiPicker", a -> oeffnen());
        /** @binding Emoji-Auswahl: Zeichen ins Suchfeld */
        c.put("emojiChar", a -> {
            filter.append(a);
            filtern();
        });
        /** @binding Emoji-Auswahl: letztes Zeichen der Suche loeschen */
        c.put("emojiBackspace", a -> {
            if (filter.length() > 0) filter.setLength(filter.length() - 1);
            filtern();
        });
        /** @binding Emoji-Auswahl: bewegen (links, rechts, hoch, runter) */
        c.put("emojiMove", a -> {
            switch (a.strip()) {
                case "h" -> bewegen(-1, 0);
                case "l" -> bewegen(1, 0);
                case "k" -> bewegen(0, -1);
                case "j" -> bewegen(0, 1);
                default -> { }
            }
        });
        /** @binding Emoji-Auswahl: gewaehltes Emoji einfuegen */
        c.put("emojiInsert", a -> einfuegen());
        /** @binding Emoji-Auswahl: schliessen */
        c.put("emojiClose", a -> zu());
    }

    void malen() {
        Screen s = ed.scr;
        int cw = s.cw, lh = s.lh;
        int zelle = 4 * cw;
        spalten = Math.max(4, Math.min(16, (s.w - 8 * cw) / zelle));
        int zeilen = Math.max(1, Math.min(8, (liste.size() + spalten - 1) / spalten));
        String titel = "Emoji (" + liste.size() + ")  tippen sucht  Strg+H/J/K/L waehlt  ENTER fuegt ein  ESC zu";
        int w = Math.min(s.w - 2 * cw, Math.max(spalten * zelle + 4 * cw, s.textWidth(titel) + 4 * cw)), h = (zeilen + 4) * lh + 24;
        int x = (s.w - w) / 2, y = (s.h - h) / 3;
        s.rect(x + 6, y + 6, w, h, 0);
        s.rect(x, y, w, h, Editor.E_KONSOLE);
        s.frame(x, y, w, h, Editor.E_HELL);
        s.frame(x + 3, y + 3, w - 6, h - 6, Editor.E_MATT);
        s.clip(x, y, w, h);
        s.text(x + 2 * cw, y + 8, titel, Editor.E_HELL);
        int fy = y + 8 + lh + 4;
        int fx = s.text(x + 2 * cw, fy, "> " + filter, Editor.E_TEXT);
        s.rect(fx + 1, fy + 1, cw - 2, lh - 2, Editor.E_TEXT);
        // die Zeile mit der Auswahl sichtbar halten
        int zw = wahl / spalten;
        if (zw < oben) oben = zw;
        if (zw >= oben + zeilen) oben = zw - zeilen + 1;
        int gy = fy + lh + 8;
        int gx = x + (w - spalten * zelle) / 2;
        for (int r = 0; r < zeilen; r++) {
            for (int k = 0; k < spalten; k++) {
                int i = (oben + r) * spalten + k;
                if (i >= liste.size()) break;
                Emojis.Emoji e = liste.get(i);
                int ex = gx + k * zelle, ey = gy + r * lh;
                if (i == wahl) {
                    s.rect(ex, ey, zelle - cw / 2, lh, Editor.E_TEXT);
                    s.rect(ex + 1, ey + 1, zelle - cw / 2 - 2, lh - 2, Editor.E_AUSWAHL);
                }
                if (e.zeichen() != null) s.emoji(ex + cw, ey, e.zeichen().codePointAt(0), 2);
                else s.text(ex + cw / 2, ey, e.name().length() > 3 ? e.name().substring(0, 3) : e.name(), Editor.E_INFO);
            }
        }
        if (liste.isEmpty()) s.text(x + 2 * cw, gy, "(nichts passt)", Editor.E_MATT);
        int ny = gy + zeilen * lh + 6;
        if (!liste.isEmpty()) {
            Emojis.Emoji e = liste.get(wahl);
            List<String> al = Emojis.ALIASE.getOrDefault(e.name(), List.of());
            String t = ":" + e.name() + ":" + (al.isEmpty() ? "" : "  auch :" + String.join(": :", al) + ":") + (e.zeichen() == null ? "  (eigenes Emoji des Servers)" : "");
            s.text(x + 2 * cw, ny, t, Editor.E_HELL);
            String pos = (wahl + 1) + "/" + liste.size();
            s.text(x + w - 2 * cw - s.textWidth(pos), ny, pos, Editor.E_MATT);
        }
        s.noClip();
    }
}
