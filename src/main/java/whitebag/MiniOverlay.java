package whitebag;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The mini overlay: a small always-on-top window with this run's timer, your last white bag, fame per hour, bags this
 * session and your next badge. Turned on and off with the switch on Home ("overlay.on"); the × hides it (and turns the
 * switch off), the – folds it down to its title. "Only in dungeons" hides it in the Nexus, vault and realm.
 */
final class MiniOverlay {
    interface Settings {
        String get(String key, String fallback);

        void set(String key, String value);
    }

    private static final int W = 410, FULL_H = 214, SMALL_H = 66;

    private final Supplier<List<Account>> accounts;
    private final Settings settings;
    /** Called when the × turns the overlay off, so the switch on Home can follow. */
    Runnable onClosed = () -> { };
    /** Whether the pup is watching the game right now. */
    java.util.function.BooleanSupplier watching = () -> true;

    private JWindow window;
    private final Painter painter = new Painter();
    private boolean on, onlyDungeons, small, seeThrough;
    /** How big the overlay is drawn (drag the bottom-right corner): 1 = normal. */
    private double scale = 1;
    private static final double MIN_SCALE = 0.7, MAX_SCALE = 2.5;
    private Timer tick;
    private boolean rounded;

    // this session
    private final Map<BagType, Integer> bags = new EnumMap<>(BagType.class);
    private int bagCount;
    private String lastWhite = "";
    private long lastWhiteAt;
    private volatile String nextBadge = "";
    private long badgeCheckedAt;

    MiniOverlay(Supplier<List<Account>> accounts, Settings settings) {
        this.accounts = accounts;
        this.settings = settings;
        on = "true".equals(settings.get("overlay.on", "false"));
        onlyDungeons = "true".equals(settings.get("overlay.dungeonsOnly", "false"));
        small = "true".equals(settings.get("overlay.small", "false"));
        seeThrough = "true".equals(settings.get("overlay.seeThrough", "false"));
        try {
            scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, Double.parseDouble(settings.get("overlay.scale", "1"))));
        } catch (NumberFormatException e) {
            scale = 1;
        }
    }

    boolean isOn() {
        return on;
    }

    boolean onlyDungeons() {
        return onlyDungeons;
    }

    /** Starts the once-a-second refresh (call on the Swing thread once the app is up). */
    void start() {
        if (tick != null) return;
        tick = new Timer(1000, e -> refresh());
        tick.start();
        refresh();
    }

    void setOn(boolean v) {
        on = v;
        settings.set("overlay.on", String.valueOf(v));
        refresh();
    }

    void setOnlyDungeons(boolean v) {
        onlyDungeons = v;
        settings.set("overlay.dungeonsOnly", String.valueOf(v));
        refresh();
    }

    /** A bag was looted (any thread). */
    void noteBag(BagDrop drop) {
        SwingUtilities.invokeLater(() -> {
            bagCount++;
            bags.merge(drop.type, 1, Integer::sum);
            if (drop.type == BagType.WHITE) {
                List<String> names = drop.itemNames();
                lastWhite = names.isEmpty() ? "White bag" : names.get(0);
                lastWhiteAt = System.currentTimeMillis();
            }
            badgeCheckedAt = 0; // a bag can bring a badge closer
            painter.repaint();
        });
    }

    // ---------------------------------------------------------------- what's shown

    /** The account you're playing on: the one that changed map most recently. */
    private Account current() {
        Account best = null;
        for (Account a : accounts.get()) if (a.visitStart() > 0 && (best == null || a.visitStart() > best.visitStart())) best = a;
        return best;
    }

    private void refresh() {
        Account a = current();
        String map = a == null ? null : a.visitMap();
        boolean show = on && (!onlyDungeons || (map != null && RunLog.isDungeon(map)));
        if (!show) {
            if (window != null) window.setVisible(false);
            return;
        }
        if (window == null) build();
        if (!window.isVisible()) window.setVisible(true);
        long now = System.currentTimeMillis();
        if (a != null && now - badgeCheckedAt > 60_000) {
            badgeCheckedAt = now;
            String key = a.key();
            if (key != null) {
                Thread t = new Thread(() -> nextBadge = closestBadge(key, a), "overlay-badge");
                t.setDaemon(true);
                t.start();
            }
        }
        painter.repaint();
    }

    private static String closestBadge(String key, Account a) {
        try {
            Badges.Stats s = Badges.stats(key, a::isMine);
            Map<String, Badges.Unlock> got = Badges.unlocked(key);
            Badges.Badge best = null;
            double bestP = -1;
            for (Badges.Badge b : Badges.ALL) {
                if (got.containsKey(b.id)) continue;
                long have = b.count.of(s);
                double p = Math.min(1, have / (double) b.need);
                if (p > bestP) {
                    bestP = p;
                    best = b;
                }
            }
            if (best == null) return "Every badge unlocked!";
            long left = Math.max(0, best.need - best.count.of(s));
            return best.name + (left <= 0 ? "  ·  ready!" : "  ·  " + String.format(Locale.ROOT, "%,d", left) + " to go");
        } catch (Throwable e) {
            return "";
        }
    }

    private String[][] rows() {
        Account a = current();
        String map = a == null ? null : a.visitMap();
        String run;
        if (map == null) run = "Not in game yet";
        else {
            long secs = Math.max(0, (System.currentTimeMillis() - a.visitStart()) / 1000);
            run = RunLog.label(map) + "  ·  " + (secs >= 3600 ? secs / 3600 + ":" + two(secs / 60 % 60) : String.valueOf(secs / 60)) + ":" + two(secs % 60);
        }
        String white = lastWhite.isEmpty() ? "None yet this session" : lastWhite + "  ·  " + ago(lastWhiteAt);
        String fame = "-";
        long[] fs = a == null ? null : a.me.fameSession();
        if (fs != null && fs[1] > 60_000) {
            fame = String.format(Locale.ROOT, "%,d   (%+,d this session)", Math.round(fs[0] / (fs[1] / 3_600_000.0)), fs[0]);
        }
        StringBuilder b = new StringBuilder(String.valueOf(bagCount));
        if (bagCount > 0) {
            StringBuilder kinds = new StringBuilder();
            for (BagType t : new BagType[]{BagType.WHITE, BagType.RED, BagType.ORANGE, BagType.GOLD}) {
                Integer n = bags.get(t);
                if (n == null) continue;
                if (kinds.length() > 0) kinds.append(", ");
                kinds.append(n).append(" ").append(t.name().toLowerCase(Locale.ROOT));
            }
            if (kinds.length() > 0) b.append("   (").append(kinds).append(")");
        }
        return new String[][]{
                {"This run", run},
                {"Last white", white},
                {"Fame per hour", fame},
                {"Bags this session", b.toString()},
                {"Next badge", nextBadge.isEmpty() ? "-" : nextBadge}};
    }

    private static String two(long v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    private static String ago(long at) {
        long m = (System.currentTimeMillis() - at) / 60_000;
        return m < 1 ? "just now" : m < 60 ? m + " min ago" : m / 60 + " h ago";
    }

    // ---------------------------------------------------------------- the window

    private void build() {
        window = new JWindow();
        window.setAlwaysOnTop(true);
        window.setFocusableWindowState(false); // never takes the keyboard away from the game
        // round corners need a see-through window; where the desktop can't do that, it's a plain rectangle
        try {
            GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            if (!Boolean.getBoolean("whitebag.squareOverlay")
                    && gd.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
                window.setBackground(new Color(0, 0, 0, 0));
                rounded = true;
            }
        } catch (Exception ignored) {
        }
        window.setContentPane(painter);
        applySize();
        applyOpacity();
        int x = parse(settings.get("overlay.x", ""), -1), y = parse(settings.get("overlay.y", ""), -1);
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int ww = window.getWidth();
        if (x < 0 || y < 0 || !screen.intersects(new Rectangle(x, y, ww, SMALL_H))) {
            x = screen.x + screen.width - ww - 24;
            y = screen.y + 24;
        }
        // keep it on the screen (it may have grown since last time)
        x = Math.max(screen.x, Math.min(x, screen.x + screen.width - ww));
        y = Math.max(screen.y, Math.min(y, screen.y + screen.height - Math.min(window.getHeight(), screen.height)));
        window.setLocation(x, y);
        MouseAdapter drag = new MouseAdapter() {
            private Point grab;
            private boolean resizing;

            /** Is this point (in window pixels) on the resize corner? */
            private boolean onGrip(Point p) {
                return p.x > window.getWidth() - 20 && p.y > window.getHeight() - 20;
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger()) {
                    menu(e);
                    return;
                }
                if (onGrip(e.getPoint())) {
                    resizing = true;
                    return;
                }
                double ux = e.getX() / scale, uy = e.getY() / scale; // in unscaled overlay pixels
                if (uy < 40 && ux > W - 64) {
                    if (ux < W - 40) { // –
                        small = !small;
                        settings.set("overlay.small", String.valueOf(small));
                        applySize();
                    } else { // ×
                        setOn(false);
                        onClosed.run();
                    }
                    return;
                }
                grab = e.getPoint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) menu(e);
                if (grab != null) {
                    settings.set("overlay.x", String.valueOf(window.getX()));
                    settings.set("overlay.y", String.valueOf(window.getY()));
                }
                if (resizing) settings.set("overlay.scale", String.format(java.util.Locale.ROOT, "%.3f", scale));
                grab = null;
                resizing = false;
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                painter.setCursor(Cursor.getPredefinedCursor(onGrip(e.getPoint()) ? Cursor.SE_RESIZE_CURSOR : Cursor.DEFAULT_CURSOR));
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (resizing) {
                    // keeps its shape: whichever way you pull further sets the size
                    double sx = e.getX() / (double) W, sy = e.getY() / (double) (small ? SMALL_H : FULL_H);
                    scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, Math.max(sx, sy)));
                    applySize();
                    return;
                }
                if (grab == null) return;
                Point p = e.getLocationOnScreen();
                window.setLocation(p.x - grab.x, p.y - grab.y);
            }
        };
        painter.addMouseListener(drag);
        painter.addMouseMotionListener(drag);
        painter.setToolTipText("Drag to move, drag the bottom-right corner to resize. Right-click for options.");
    }

    private void menu(MouseEvent e) {
        JPopupMenu m = new JPopupMenu();
        JCheckBoxMenuItem dungeons = new JCheckBoxMenuItem("Only in dungeons", onlyDungeons);
        dungeons.addActionListener(x -> setOnlyDungeons(dungeons.isSelected()));
        JCheckBoxMenuItem see = new JCheckBoxMenuItem("See-through", seeThrough);
        see.addActionListener(x -> {
            seeThrough = see.isSelected();
            settings.set("overlay.seeThrough", String.valueOf(seeThrough));
            applyOpacity();
        });
        JCheckBoxMenuItem fold = new JCheckBoxMenuItem("Just the title", small);
        fold.addActionListener(x -> {
            small = fold.isSelected();
            settings.set("overlay.small", String.valueOf(small));
            applySize();
        });
        JMenuItem reset = new JMenuItem("Normal size");
        reset.addActionListener(x -> {
            scale = 1;
            settings.set("overlay.scale", "1");
            applySize();
        });
        JMenuItem hide = new JMenuItem("Hide the overlay");
        hide.addActionListener(x -> {
            setOn(false);
            onClosed.run();
        });
        m.add(dungeons);
        m.add(see);
        m.add(fold);
        m.add(reset);
        m.addSeparator();
        m.add(hide);
        m.show(painter, e.getX(), e.getY());
    }

    private void applySize() {
        if (window == null) return;
        window.setSize((int) Math.round(W * scale), (int) Math.round((small ? SMALL_H : FULL_H) * scale));
        painter.repaint();
    }

    private void applyOpacity() {
        if (window == null) return;
        try {
            window.setOpacity(seeThrough ? 0.78f : 1f);
        } catch (Exception ignored) { // not every desktop can do see-through windows
        }
    }

    private static int parse(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Draws the overlay (the same look as the app's cards). */
    private final class Painter extends JComponent {
        private BufferedImage pup;

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Cute.smooth(g);
            g.scale(scale, scale);
            int w = (int) Math.round(getWidth() / scale), h = (int) Math.round(getHeight() / scale);
            if (!rounded) {
                g.setColor(Pixel.SHEET);
                g.fillRect(0, 0, w, h);
            }
            RoundRectangle2D card = new RoundRectangle2D.Double(1, 1, w - 3, h - 3, rounded ? 22 : 4, rounded ? 22 : 4);
            g.setColor(Pixel.SHEET);
            g.fill(card);
            g.setColor(Pixel.LINE);
            g.setStroke(new BasicStroke(2f));
            g.draw(card);
            if (pup == null) pup = PixelPup.frame(PixelPup.Face.HAPPY, PixelPup.Ears.UP, PixelPup.Tail.UP,
                    PixelPup.hatFor(java.time.LocalDate.now()), PixelPup.outfitNow());
            Graphics2D p = (Graphics2D) g.create();
            p.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            int pupScale = small ? 1 : 2;
            p.drawImage(pup, 12, small ? 6 : 6, pup.getWidth() * pupScale, pup.getHeight() * pupScale, null);
            p.dispose();
            int tx = small ? 44 : 62;
            g.setColor(Pixel.INK);
            g.setFont(Cute.font(Font.BOLD, 15));
            g.drawString("White Bag Pup", tx, 28);
            boolean awake = watching.getAsBoolean();
            g.setColor(awake ? Pixel.TEAL : Theme.c(0xC9BFD6));
            g.fillOval(tx, 38, 8, 8);
            g.setColor(Theme.c(0x8A7F99));
            g.setFont(Cute.font(Font.PLAIN, 11));
            String sub = !awake ? "asleep: press Start watching" : small ? rows()[0][1] : "watching" + (onlyDungeons ? "  ·  only in dungeons" : "");
            g.drawString(sub, tx + 13, 46);
            g.setFont(Cute.font(Font.BOLD, 15));
            g.drawString("–", w - 56, 28);
            g.drawString("×", w - 32, 28);
            if (!small) {
                g.setColor(Pixel.LINE);
                g.fillRect(14, 64, w - 30, 1);
                int y = 88;
                for (String[] r : rows()) {
                    g.setFont(Cute.font(Font.PLAIN, 12));
                    g.setColor(Theme.c(0x8A7F99));
                    g.drawString(r[0], 16, y);
                    g.setFont(Cute.font(Font.BOLD, 12));
                    g.setColor(r[0].equals("Last white") && !lastWhite.isEmpty() ? Pixel.PINK_INK : Pixel.INK);
                    String v = r[1];
                    FontMetrics fm = g.getFontMetrics();
                    while (fm.stringWidth(v) > w - 140 && v.length() > 6) v = v.substring(0, v.length() - 2);
                    g.drawString(v.equals(r[1]) ? v : v + "…", 124, y);
                    y += 25;
                }
            }
            // the resize corner: three little diagonal lines
            g.setColor(Pixel.LINE);
            g.setStroke(new BasicStroke(1.5f));
            for (int k = 0; k < 3; k++) g.drawLine(w - 6 - k * 4, h - 4, w - 4, h - 6 - k * 4);
            g.dispose();
        }
    }
}
