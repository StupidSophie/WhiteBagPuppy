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

    private static final int W = 410, SMALL_H = 66, MIN_W = 300, MAX_W = 1000;
    /** How wide it is before scaling (drag the corner sideways for more room for the words). */
    private int width = W;

    /** The rows you can show (right-click > Rows), in the order they're drawn. */
    private static final String[][] ROWS = {
            {"run", "This run"}, {"white", "Last white"}, {"dry", "Dry streak"}, {"fame", "Fame per hour"},
            {"bags", "Bags this session"}, {"boost", "Loot boosts"}, {"badge", "Next badge"}};
    private final java.util.Set<String> shownRows = new java.util.LinkedHashSet<>();
    /** Flash the overlay (and make the pup jump) when a white bag drops. */
    private boolean flash;
    private long flashUntil;
    private Timer flashTimer;
    private volatile String dry = "";

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
        flash = !"false".equals(settings.get("overlay.flash", "true"));
        String rows = settings.get("overlay.rows", "run,white,dry,fame,bags,boost,badge");
        for (String r : rows.split(",")) if (!r.trim().isEmpty()) shownRows.add(r.trim());
        try {
            scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, Double.parseDouble(settings.get("overlay.scale", "1"))));
            width = Math.max(MIN_W, Math.min(MAX_W, Integer.parseInt(settings.get("overlay.width", String.valueOf(W)))));
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
                if (flash) startFlash();
            }
            badgeCheckedAt = 0; // a bag can bring a badge closer
            painter.repaint();
        });
    }

    /** A few seconds of glow and a jumping pup. */
    void startFlash() {
        flashUntil = System.currentTimeMillis() + 3500;
        if (flashTimer == null) {
            flashTimer = new Timer(40, e -> {
                if (System.currentTimeMillis() > flashUntil) ((Timer) e.getSource()).stop();
                painter.repaint();
            });
        }
        flashTimer.restart();
    }

    /** 0..1 while flashing: how bright the glow is right now. */
    private double flashLevel() {
        long left = flashUntil - System.currentTimeMillis();
        if (left <= 0) return 0;
        double t = (3500 - left) / 1000.0;
        return Math.min(1, left / 800.0) * (0.55 + 0.45 * Math.sin(t * Math.PI * 3));
    }

    private int fullHeight() {
        int n = 0;
        for (String[] r : ROWS) if (shownRows.contains(r[0])) n++;
        return n == 0 ? SMALL_H : 89 + 25 * n;
    }

    private void setRow(String id, boolean show) {
        if (show) shownRows.add(id);
        else shownRows.remove(id);
        StringBuilder b = new StringBuilder();
        for (String[] r : ROWS) {
            if (!shownRows.contains(r[0])) continue;
            if (b.length() > 0) b.append(',');
            b.append(r[0]);
        }
        settings.set("overlay.rows", b.toString());
        applySize();
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
                Thread t = new Thread(() -> {
                    nextBadge = closestBadge(key, a);
                    dry = dryStreak(a);
                }, "overlay-badge");
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

    /** "12 runs dry in Snake Pit  ·  longest 30", for the dungeon you're in (or all dungeons). */
    private static String dryStreak(Account a) {
        try {
            List<RunLog.Run> runs = new java.util.ArrayList<>();
            for (RunLog.Run r : RunLog.all()) if (a.isMine(r.account)) runs.add(r);
            List<LootStats.Entry> bags = new java.util.ArrayList<>();
            for (LootStats.Entry e : LootStats.all()) if (a.isMine(e.account)) bags.add(e);
            Map<String, DryStreak.Streak> all = DryStreak.compute(runs, bags);
            String map = a.visitMap();
            DryStreak.Streak s = map != null && RunLog.isDungeon(map) ? all.get(map) : null;
            String where = s != null ? " in " + map : " (all dungeons)";
            if (s == null && map != null && RunLog.isDungeon(map)) return "First run here!";
            if (s == null) s = all.get("");
            if (s == null || s.runs == 0) return "-";
            return s.current + (s.current == 1 ? " run" : " runs") + " dry" + where + "  ·  longest " + s.longest;
        } catch (Throwable e) {
            return "";
        }
    }

    private static String left(long ends) {
        long s = Math.max(0, (ends - System.currentTimeMillis()) / 1000);
        if (s >= 3600) return s / 3600 + "h " + two(s / 60 % 60) + "m";
        return s / 60 + ":" + two(s % 60);
    }

    private String runText() {
        Account a = current();
        String map = a == null ? null : a.visitMap();
        if (map == null) return "Not in game yet";
        long secs = Math.max(0, (System.currentTimeMillis() - a.visitStart()) / 1000);
        return RunLog.label(map) + "  ·  " + (secs >= 3600 ? secs / 3600 + ":" + two(secs / 60 % 60) : String.valueOf(secs / 60)) + ":" + two(secs % 60);
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
        String boost = "None active";
        if (a != null) {
            long now = System.currentTimeMillis();
            String ld = a.me.lootDropEnds > now ? "Drop " + left(a.me.lootDropEnds) : "";
            String lt = a.me.lootTierEnds > now ? "Tier " + left(a.me.lootTierEnds) : "";
            if (!ld.isEmpty() || !lt.isEmpty()) boost = ld + (!ld.isEmpty() && !lt.isEmpty() ? "  ·  " : "") + lt;
        }
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (String[] r : ROWS) {
            if (!shownRows.contains(r[0])) continue;
            String v;
            switch (r[0]) {
                case "run": v = run; break;
                case "white": v = white; break;
                case "dry": v = dry.isEmpty() ? "-" : dry; break;
                case "fame": v = fame; break;
                case "bags": v = b.toString(); break;
                case "boost": v = boost; break;
                default: v = nextBadge.isEmpty() ? "-" : nextBadge;
            }
            out.add(new String[]{r[1], v});
        }
        return out.toArray(new String[0][]);
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
                if (uy < 40 && ux > width - 64) {
                    if (ux < width - 40) { // –
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
                if (resizing) {
                    settings.set("overlay.scale", String.format(java.util.Locale.ROOT, "%.3f", scale));
                    settings.set("overlay.width", String.valueOf(width));
                }
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
                    // up and down sets how big everything is; sideways sets how much room the words get
                    double sy = e.getY() / (double) (small ? SMALL_H : fullHeight());
                    scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, sy));
                    width = (int) Math.max(MIN_W, Math.min(MAX_W, Math.round(e.getX() / scale)));
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

    private JDialog menuWindow;

    /** The options, drawn like the rest of the app: a card with the pup's tick boxes. */
    private void menu(MouseEvent e) {
        if (menuWindow != null) menuWindow.dispose();
        JDialog d = new JDialog((Frame) null);
        menuWindow = d;
        d.setUndecorated(true);
        d.setAlwaysOnTop(true);
        boolean see = false;
        try {
            GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            if (rounded && gd.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
                d.setBackground(new Color(0, 0, 0, 0));
                see = true;
            }
        } catch (Exception ignored) {
        }
        final boolean roundCard = see;
        JPanel card = new JPanel() {
            @Override
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Cute.smooth(g);
                int w = getWidth(), h = getHeight();
                if (!roundCard) {
                    g.setColor(Pixel.SHEET);
                    g.fillRect(0, 0, w, h);
                }
                RoundRectangle2D r = new RoundRectangle2D.Double(1, 1, w - 3, h - 3, roundCard ? 18 : 4, roundCard ? 18 : 4);
                g.setColor(Pixel.SHEET);
                g.fill(r);
                g.setColor(Pixel.LINE);
                g.setStroke(new BasicStroke(2f));
                g.draw(r);
                g.dispose();
            }
        };
        card.setOpaque(false);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));

        card.add(menuHeading("Show"));
        for (String[] r : ROWS) {
            Pixel.Check c = new Pixel.Check(r[1], shownRows.contains(r[0]));
            c.addActionListener(x -> setRow(r[0], c.isSelected()));
            card.add(menuItem(c));
        }
        card.add(menuDivider());
        card.add(menuHeading("Options"));
        Pixel.Check flashBox = new Pixel.Check("Flash on a white bag", flash);
        flashBox.addActionListener(x -> {
            flash = flashBox.isSelected();
            settings.set("overlay.flash", String.valueOf(flash));
            if (flash) startFlash(); // a preview
        });
        Pixel.Check dungeons = new Pixel.Check("Only in dungeons", onlyDungeons);
        dungeons.addActionListener(x -> setOnlyDungeons(dungeons.isSelected()));
        Pixel.Check seeBox = new Pixel.Check("See-through", seeThrough);
        seeBox.addActionListener(x -> {
            seeThrough = seeBox.isSelected();
            settings.set("overlay.seeThrough", String.valueOf(seeThrough));
            applyOpacity();
        });
        Pixel.Check fold = new Pixel.Check("Just the title", small);
        fold.addActionListener(x -> {
            small = fold.isSelected();
            settings.set("overlay.small", String.valueOf(small));
            applySize();
        });
        for (Pixel.Check c : new Pixel.Check[]{flashBox, dungeons, seeBox, fold}) card.add(menuItem(c));
        card.add(menuDivider());
        card.add(menuAction("Normal size", () -> {
            scale = 1;
            width = W;
            settings.set("overlay.scale", "1");
            settings.set("overlay.width", String.valueOf(W));
            applySize();
        }));
        card.add(menuAction("Hide the overlay", () -> {
            d.dispose();
            setOn(false);
            onClosed.run();
        }));
        d.setContentPane(card);
        d.pack();
        // next to the mouse, but on the screen
        Point at = e.getLocationOnScreen();
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int x = Math.min(at.x, screen.x + screen.width - d.getWidth() - 4);
        int y = Math.min(at.y, screen.y + screen.height - d.getHeight() - 4);
        d.setLocation(Math.max(screen.x, x), Math.max(screen.y, y));
        // it closes when you click anywhere else (or press Esc)
        d.addWindowFocusListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowLostFocus(java.awt.event.WindowEvent ev) {
                d.dispose();
            }
        });
        d.getRootPane().registerKeyboardAction(ev -> d.dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.setVisible(true);
        d.toFront();
        d.requestFocus();
    }

    private static JComponent menuHeading(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Cute.font(Font.BOLD, 12));
        l.setForeground(Theme.c(0x8A7F99));
        l.setBorder(BorderFactory.createEmptyBorder(2, 2, 4, 0));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static JComponent menuItem(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.setFocusable(false); // no focus underline: it's a mouse menu
        return c;
    }

    private static JComponent menuDivider() {
        JComponent line = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Pixel.LINE);
                g.fillRect(0, getHeight() / 2, getWidth(), 1);
            }
        };
        line.setPreferredSize(new Dimension(10, 13));
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 13));
        line.setAlignmentX(Component.LEFT_ALIGNMENT);
        return line;
    }

    /** A plain line of text that does something when clicked, with a soft highlight under the mouse. */
    private static JComponent menuAction(String text, Runnable run) {
        JLabel l = new JLabel(text) {
            boolean hover;

            {
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                    @Override public void mouseClicked(MouseEvent e) { Squeak.boop(); run.run(); }
                });
            }

            @Override
            protected void paintComponent(Graphics g0) {
                if (hover) {
                    Graphics2D g = (Graphics2D) g0.create();
                    Cute.smooth(g);
                    g.setColor(Pixel.PINK_SOFT);
                    g.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 10, 10));
                    g.dispose();
                }
                super.paintComponent(g0);
            }
        };
        l.setFont(Cute.font(Font.PLAIN, 13));
        l.setForeground(Pixel.INK);
        l.setBorder(BorderFactory.createEmptyBorder(5, 2, 5, 6));
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setMaximumSize(new Dimension(Integer.MAX_VALUE, l.getPreferredSize().height));
        return l;
    }

    private void applySize() {
        if (window == null) return;
        window.setSize((int) Math.round(width * scale), (int) Math.round((small ? SMALL_H : fullHeight()) * scale));
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
            double glow = flashLevel();
            if (glow > 0) {
                // a soft white-bag glow inside the edge
                for (int k = 6; k >= 1; k--) {
                    g.setColor(new Color(Pixel.PINK.getRed(), Pixel.PINK.getGreen(), Pixel.PINK.getBlue(), (int) (glow * 48 * (7 - k) / 6.0)));
                    g.setStroke(new BasicStroke(k * 2.2f));
                    g.draw(card);
                }
            }
            g.setColor(glow > 0 ? Cute.mix(Pixel.LINE, Pixel.PINK, glow) : Pixel.LINE);
            g.setStroke(new BasicStroke(2f + (float) (glow * 1.5)));
            g.draw(card);
            if (pup == null) pup = PixelPup.frame(PixelPup.Face.HAPPY, PixelPup.Ears.UP, PixelPup.Tail.UP,
                    PixelPup.hatFor(java.time.LocalDate.now()), PixelPup.outfitNow());
            Graphics2D p = (Graphics2D) g.create();
            p.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            int pupScale = small ? 1 : 2;
            int hop = glow > 0 ? (int) Math.round(-Math.abs(Math.sin((3500 - (flashUntil - System.currentTimeMillis())) / 1000.0 * Math.PI * 4)) * 5) : 0;
            p.drawImage(pup, 12, 6 + hop, pup.getWidth() * pupScale, pup.getHeight() * pupScale, null);
            p.dispose();
            int tx = small ? 44 : 62;
            g.setColor(Pixel.INK);
            g.setFont(Cute.font(Font.BOLD, 15));
            g.drawString("White Bag Puppy", tx, 28);
            boolean awake = watching.getAsBoolean();
            g.setColor(awake ? Pixel.TEAL : Theme.c(0xC9BFD6));
            g.fillOval(tx, 38, 8, 8);
            g.setColor(Theme.c(0x8A7F99));
            g.setFont(Cute.font(Font.PLAIN, 11));
            String sub = !awake ? "asleep: press Start watching" : small || shownRows.isEmpty() ? runText() : "watching" + (onlyDungeons ? "  ·  only in dungeons" : "");
            g.drawString(sub, tx + 13, 46);
            g.setFont(Cute.font(Font.BOLD, 15));
            g.drawString("–", w - 56, 28);
            g.drawString("×", w - 32, 28);
            if (!small && !shownRows.isEmpty()) {
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
