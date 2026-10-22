package runen;

import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.desktop.QuitStrategy;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** Startet den Editor im Vollbild; Java zeigt nur das fertige Bild, gemalt wird in {@link Screen}. */
public final class Main {
    private static final String[] VORGABEN = {"editor.config", "keymap.config", "mapping/beispiel.map"};

    private Editor ed;
    private JFrame fenster;
    private JPanel flaeche;
    private boolean vollbild = true;
    private boolean geschluckt;

    private static Path logPfad;
    private static boolean fehlerGezeigt;

    public static void main(String[] args) {
        System.setProperty("sun.java2d.uiScale.enabled", "true");
        System.setProperty("apple.awt.application.name", "RUNENWERK JS");
        Path konfig = konfigOrdner();
        logPfad = konfig.resolve("start.log");
        try {
            Files.writeString(logPfad, "");
        } catch (IOException e) {
            logPfad = null;
        }
        System.out.println("RUNENWERK JS startet (Log: " + (logPfad == null ? "-" : logPfad) + ")");
        log("Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + "), " + System.getProperty("os.name") + " "
                + System.getProperty("os.version") + ", Argumente: " + String.join(" ", args));
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> fehler("Fehler in " + t.getName(), e));
        SwingUtilities.invokeLater(() -> {
            try {
                new Main().start(konfig, args);
            } catch (Throwable e) {
                fehler("Start fehlgeschlagen", e);
            }
        });
    }

    /** Startschritte nach ~/.runenjs/start.log (zum Nachsehen, wenn kein Fenster kommt) */
    static void log(String s) {
        if (logPfad == null) return;
        try {
            Files.writeString(logPfad, s + System.lineSeparator(), java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            // ohne Log
        }
    }

    private static void fehler(String was, Throwable e) {
        java.io.StringWriter w = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(w));
        System.err.println(was + ": " + w);
        log(was + ": " + w);
        if (fehlerGezeigt || java.awt.GraphicsEnvironment.isHeadless()) return;
        fehlerGezeigt = true;
        SwingUtilities.invokeLater(() -> javax.swing.JOptionPane.showMessageDialog(null,
                was + ":\n" + e + "\n\nDetails: " + (logPfad == null ? "Konsole" : logPfad), "RUNENWERK JS", javax.swing.JOptionPane.ERROR_MESSAGE));
    }

    /** ~/.runenjs mit den mitgelieferten Dateien (nur fehlende werden angelegt) */
    static Path konfigOrdner() {
        String eigen = System.getenv("RUNENJS_CONFIG");
        Path k = eigen != null ? Paths.get(eigen) : Paths.get(System.getProperty("user.home"), ".runenjs");
        for (String v : VORGABEN) {
            Path z = k.resolve(v);
            if (Files.exists(z)) continue;
            try (InputStream in = Main.class.getResourceAsStream("/vorgaben/" + v)) {
                if (in == null) continue;
                Files.createDirectories(z.getParent());
                Files.copy(in, z);
            } catch (IOException e) {
                System.err.println("Vorgabe nicht angelegt: " + z + ": " + e.getMessage());
            }
        }
        return k;
    }

    private void start(Path konfig, String[] args) {
        ed = new Editor(konfig);
        fenster = new JFrame("RUNENWERK JS");
        fenster.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        fenster.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                ed.quit(false);
                flaeche.repaint();
            }
        });
        flaeche = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                malen((Graphics2D) g);
            }
        };
        flaeche.setFocusable(true);
        flaeche.setFocusTraversalKeysEnabled(false);
        fenster.setFocusTraversalKeysEnabled(false);
        fenster.setContentPane(flaeche);
        ed.neuMalen = flaeche::repaint;
        ed.beenden = () -> {
            fenster.dispose();
            System.exit(0);
        };
        ed.vollbild = this::vollbildUmschalten;
        tastatur();
        maus();
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
            // Cmd+Q auf dem Mac: erst nach ungespeicherten Aenderungen fragen
            Desktop.getDesktop().setQuitStrategy(QuitStrategy.CLOSE_ALL_WINDOWS);
            Desktop.getDesktop().setQuitHandler((e, antwort) -> {
                antwort.cancelQuit();
                SwingUtilities.invokeLater(() -> {
                    ed.quit(false);
                    flaeche.repaint();
                });
            });
        }
        skalaSetzen();
        log("Konfiguration: " + konfig + ", Skalierung " + ed.skala + ", Schrift " + ed.scr.fontName() + " " + ed.scr.cw + "x" + ed.scr.lh);
        ed.start(args);
        log("Editor gestartet, Arbeitsordner: " + ed.arbeitsordner);
        vollbildAn(true);
        log("Fenster sichtbar: " + fenster.isShowing() + ", " + fenster.getBounds());
    }

    private void skalaSetzen() {
        double s = fenster.getGraphicsConfiguration().getDefaultTransform().getScaleX();
        if (s != ed.skala || ed.scr.cw == 0) {
            ed.skala = s;
            ed.schriftAn();
        }
    }

    /**
     * Vollbild: auf dem Mac der Exklusiv-Modus (blendet Menue und Dock aus), sonst ein randloses
     * Fenster in Bildschirmgroesse. Unter Windows wird ein Exklusiv-Fenster minimiert, sobald es den
     * Fokus verliert (z. B. an die Konsole, aus der es gestartet wurde).
     */
    private void vollbildAn(boolean an) {
        vollbild = an;
        GraphicsDevice gd = fenster.getGraphicsConfiguration().getDevice();
        if (gd.getFullScreenWindow() == fenster) gd.setFullScreenWindow(null);
        fenster.dispose();
        fenster.setUndecorated(an);
        boolean mac = System.getProperty("os.name").toLowerCase().contains("mac");
        if (an && mac && gd.isFullScreenSupported()) {
            gd.setFullScreenWindow(fenster);
        } else {
            Rectangle r = gd.getDefaultConfiguration().getBounds();
            if (an) fenster.setBounds(r);
            else fenster.setBounds(r.x + r.width / 10, r.y + r.height / 10, r.width * 4 / 5, r.height * 4 / 5);
        }
        fenster.setExtendedState(JFrame.NORMAL);
        fenster.setVisible(true);
        fenster.toFront();
        fenster.requestFocus();
        flaeche.requestFocusInWindow();
    }

    private void vollbildUmschalten() {
        vollbildAn(!vollbild);
    }

    private void malen(Graphics2D g) {
        skalaSetzen();
        double s = ed.skala;
        int w = (int) Math.round(flaeche.getWidth() * s), h = (int) Math.round(flaeche.getHeight() * s);
        ed.scr.resize(w, h);
        ed.malen();
        BufferedImage img = ed.scr.img;
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        // Bildpunkte 1:1 aufs Geraet (die Grafik ist schon mit s skaliert)
        g.scale(1 / s, 1 / s);
        g.drawImage(img, 0, 0, null);
        g.scale(s, s);
        Toolkit.getDefaultToolkit().sync();
    }

    // ---------------------------------------------------------------- Tastatur
    private void tastatur() {
        KeyAdapter k = new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                gedrueckt(e);
            }

            @Override
            public void keyTyped(KeyEvent e) {
                getippt(e);
            }
        };
        flaeche.addKeyListener(k);
    }

    private static boolean strg(KeyEvent e) {
        boolean altGr = e.isAltGraphDown() || (e.isControlDown() && e.isAltDown() && Lsp.WINDOWS);
        return (e.isControlDown() || e.isMetaDown()) && !altGr;
    }

    private void gedrueckt(KeyEvent e) {
        int c = e.getKeyCode();
        String n = switch (c) {
            case KeyEvent.VK_ESCAPE -> "ESC";
            case KeyEvent.VK_ENTER -> "ENTER";
            case KeyEvent.VK_TAB -> e.isShiftDown() ? "Shift-TAB" : "TAB";
            case KeyEvent.VK_BACK_SPACE -> "BACKSPACE";
            case KeyEvent.VK_DELETE -> "DELETE";
            case KeyEvent.VK_INSERT -> "INSERT";
            case KeyEvent.VK_UP -> "UP";
            case KeyEvent.VK_DOWN -> "DOWN";
            case KeyEvent.VK_LEFT -> "LEFT";
            case KeyEvent.VK_RIGHT -> "RIGHT";
            case KeyEvent.VK_HOME -> "HOME";
            case KeyEvent.VK_END -> "END";
            case KeyEvent.VK_PAGE_UP -> "PGUP";
            case KeyEvent.VK_PAGE_DOWN -> "PGDN";
            default -> null;
        };
        if (c >= KeyEvent.VK_F1 && c <= KeyEvent.VK_F12) n = "F" + (c - KeyEvent.VK_F1 + 1);
        if (n == null && strg(e)) {
            if (c >= KeyEvent.VK_A && c <= KeyEvent.VK_Z) n = "Ctrl-" + (char) ((e.isShiftDown() ? 'A' : 'a') + c - KeyEvent.VK_A);
            else if (c >= KeyEvent.VK_0 && c <= KeyEvent.VK_9) n = "Ctrl-" + (char) ('0' + c - KeyEvent.VK_0);
            else if (c == KeyEvent.VK_SPACE) n = "Ctrl-SPACE";
            else if (c == KeyEvent.VK_MINUS) n = "Ctrl--";
            else if (c == KeyEvent.VK_PLUS || c == KeyEvent.VK_EQUALS || c == KeyEvent.VK_ADD) n = "Ctrl-+";
            else if (c == KeyEvent.VK_SUBTRACT) n = "Ctrl--";
            else if (c == KeyEvent.VK_PERIOD) n = "Ctrl-.";
            else if (c == KeyEvent.VK_COMMA) n = "Ctrl-,";
            else if (c == KeyEvent.VK_OPEN_BRACKET) n = "ESC";
        }
        if (n == null) return;
        e.consume();
        geschluckt = true;
        ed.taste(n, 0);
    }

    private void getippt(KeyEvent e) {
        char c = e.getKeyChar();
        if (geschluckt) {
            geschluckt = false;
            if (c < 32 || c == 127 || strg(e)) return;
        }
        if (c == KeyEvent.CHAR_UNDEFINED || c < 32 || c == 127 || strg(e)) return;
        ed.taste(c == ' ' ? "SPACE" : String.valueOf(c), c);
    }

    // ---------------------------------------------------------------- Maus
    private void maus() {
        MouseAdapter m = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                flaeche.requestFocusInWindow();
                if (e.getButton() == MouseEvent.BUTTON1) ed.maus(px(e.getPoint()).x, px(e.getPoint()).y, true, 0);
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                int r = e.getWheelRotation();
                if (r == 0) r = e.getPreciseWheelRotation() > 0 ? 1 : e.getPreciseWheelRotation() < 0 ? -1 : 0;
                if (r != 0) ed.maus(px(e.getPoint()).x, px(e.getPoint()).y, false, r);
            }
        };
        flaeche.addMouseListener(m);
        flaeche.addMouseWheelListener(m);
        flaeche.setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
    }

    private Point px(Point p) {
        return new Point((int) Math.round(p.x * ed.skala), (int) Math.round(p.y * ed.skala));
    }
}
