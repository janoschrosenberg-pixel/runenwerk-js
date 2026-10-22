package runen;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Bildpuffer, in den alles auf der CPU gemalt wird: Rechtecke, Rahmen und Text.
 * Die Schrift wird einmal je Zeichen in eine Alpha-Maske gerastert; danach mischt
 * {@link #glyph} die Masken selbst in den Puffer (gleiche Zellbreite fuer alle Zeichen).
 */
final class Screen {
    int w, h;
    int[] px = new int[0];
    BufferedImage img;
    /** Zellbreite, Zeilenhoehe, Grundlinie */
    int cw, lh, asc;
    private Font font, ersatz;
    private final Map<Integer, byte[]> glyphen = new HashMap<>();
    // Clip-Bereich
    private int cx0, cy0, cx1, cy1;

    void resize(int w, int h) {
        if (w == this.w && h == this.h && img != null) return;
        this.w = Math.max(1, w);
        this.h = Math.max(1, h);
        img = new BufferedImage(this.w, this.h, BufferedImage.TYPE_INT_RGB);
        px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        noClip();
    }

    void setFont(String namen, int groesse) {
        Set<String> da = new HashSet<>(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        String wahl = Font.MONOSPACED;
        for (String n : namen.split(",")) {
            n = n.trim();
            if (da.contains(n)) {
                wahl = n;
                break;
            }
        }
        font = new Font(wahl, Font.PLAIN, groesse);
        ersatz = new Font(Font.DIALOG, Font.PLAIN, groesse);
        BufferedImage t = new BufferedImage(4, 4, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = t.createGraphics();
        FontMetrics fm = g.getFontMetrics(font);
        cw = Math.max(1, fm.charWidth('M'));
        int pad = Math.max(1, groesse / 6);
        lh = fm.getAscent() + fm.getDescent() + 2 * pad;
        asc = pad + fm.getAscent();
        g.dispose();
        glyphen.clear();
    }

    String fontName() {
        return font.getFamily();
    }

    // ---------------------------------------------------------------- Clip
    void clip(int x, int y, int ww, int hh) {
        cx0 = Math.max(0, x);
        cy0 = Math.max(0, y);
        cx1 = Math.min(w, x + ww);
        cy1 = Math.min(h, y + hh);
    }

    void noClip() {
        cx0 = 0;
        cy0 = 0;
        cx1 = w;
        cy1 = h;
    }

    // ---------------------------------------------------------------- Flaechen
    void clear(int farbe) {
        Arrays.fill(px, farbe);
    }

    void rect(int x, int y, int ww, int hh, int farbe) {
        int x0 = Math.max(cx0, x), y0 = Math.max(cy0, y);
        int x1 = Math.min(cx1, x + ww), y1 = Math.min(cy1, y + hh);
        if (x0 >= x1) return;
        for (int yy = y0; yy < y1; yy++) {
            int o = yy * w;
            Arrays.fill(px, o + x0, o + x1, farbe);
        }
    }

    /** halbdurchsichtig (a 0..255) */
    void blend(int x, int y, int ww, int hh, int farbe, int a) {
        int x0 = Math.max(cx0, x), y0 = Math.max(cy0, y);
        int x1 = Math.min(cx1, x + ww), y1 = Math.min(cy1, y + hh);
        for (int yy = y0; yy < y1; yy++) {
            int o = yy * w;
            for (int xx = x0; xx < x1; xx++) px[o + xx] = mix(px[o + xx], farbe, a);
        }
    }

    void frame(int x, int y, int ww, int hh, int farbe) {
        rect(x, y, ww, 1, farbe);
        rect(x, y + hh - 1, ww, 1, farbe);
        rect(x, y, 1, hh, farbe);
        rect(x + ww - 1, y, 1, hh, farbe);
    }

    /** Wellenlinie unter Fehlern */
    void wave(int x, int y, int ww, int farbe) {
        for (int k = 0; k < ww; k++) {
            int yy = y + ((k / 2) % 2);
            int xx = x + k;
            if (xx >= cx0 && xx < cx1 && yy >= cy0 && yy < cy1) px[yy * w + xx] = farbe;
        }
    }

    static int mix(int d, int s, int a) {
        int na = 255 - a;
        int r = (((s >> 16) & 255) * a + ((d >> 16) & 255) * na) / 255;
        int g = (((s >> 8) & 255) * a + ((d >> 8) & 255) * na) / 255;
        int b = ((s & 255) * a + (d & 255) * na) / 255;
        return r << 16 | g << 8 | b;
    }

    // ---------------------------------------------------------------- Text
    private byte[] maske(int cp) {
        byte[] m = glyphen.get(cp);
        if (m != null) return m;
        BufferedImage gi = new BufferedImage(cw, lh, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gi.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(java.awt.Color.WHITE);
        Font f = font.canDisplay(cp) ? font : ersatz;
        g.setFont(f);
        String s = new String(Character.toChars(cp));
        int sw = g.getFontMetrics().stringWidth(s);
        g.drawString(s, Math.max(0, (cw - sw) / 2), asc);
        g.dispose();
        m = ((DataBufferByte) gi.getRaster().getDataBuffer()).getData().clone();
        glyphen.put(cp, m);
        return m;
    }

    void glyph(int x, int y, int cp, int farbe) {
        if (cp <= 32) return;
        if (x >= cx1 || y >= cy1 || x + cw <= cx0 || y + lh <= cy0) return;
        byte[] m = maske(cp);
        int x0 = Math.max(cx0, x), y0 = Math.max(cy0, y);
        int x1 = Math.min(cx1, x + cw), y1 = Math.min(cy1, y + lh);
        for (int yy = y0; yy < y1; yy++) {
            int mo = (yy - y) * cw - x;
            int o = yy * w;
            for (int xx = x0; xx < x1; xx++) {
                int a = m[mo + xx] & 255;
                if (a == 0) continue;
                px[o + xx] = a == 255 ? farbe : mix(px[o + xx], farbe, a);
            }
        }
    }

    /** Text in Zellen; gibt das x hinter dem Text zurueck */
    int text(int x, int y, CharSequence s, int farbe) {
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            int cp = c;
            if (Character.isHighSurrogate(c) && k + 1 < s.length()) {
                cp = Character.toCodePoint(c, s.charAt(++k));
            }
            glyph(x, y, cp == '\t' ? ' ' : cp, farbe);
            x += cw;
        }
        return x;
    }

    int textWidth(CharSequence s) {
        return s.toString().codePointCount(0, s.length()) * cw;
    }
}
