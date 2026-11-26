package whitebag;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Properties;

/**
 * The mini UPE window: a small always-on-top box with your contest total and the last item that scored. Drag it
 * anywhere (it remembers where); the × hides it. Turned on and off on the UPE tab ("upe.mini").
 */
final class UpeMini {
    private final Contest contest;
    private final Properties settings;
    private final Runnable save;
    /** Called when the × hides it, so the UPE tab's switch can follow. */
    Runnable onClosed = () -> { };

    private JWindow window;
    private JLabel who, total, last;
    private Timer tick;
    private Point grab;

    UpeMini(Contest contest, Properties settings, Runnable save) {
        this.contest = contest;
        this.settings = settings;
        this.save = save;
    }

    boolean isOn() {
        return "true".equals(settings.getProperty("upe.mini"));
    }

    /** Shows or hides it, and remembers which. */
    void setOn(boolean on) {
        settings.setProperty("upe.mini", String.valueOf(on));
        save.run();
        SwingUtilities.invokeLater(() -> {
            if (on) show();
            else hide();
        });
    }

    /** At start-up: shows it if it was on last time. */
    void start() {
        if (isOn()) SwingUtilities.invokeLater(this::show);
    }

    private void show() {
        if (window == null) build();
        refresh();
        window.setVisible(true);
        if (tick == null) {
            tick = new Timer(1000, e -> refresh());
            tick.start();
        }
    }

    private void hide() {
        if (tick != null) {
            tick.stop();
            tick = null;
        }
        if (window != null) window.setVisible(false);
    }

    private void build() {
        window = new JWindow();
        window.setAlwaysOnTop(true);
        window.setFocusableWindowState(false);
        JPanel box = new JPanel(new BorderLayout(0, 2));
        box.setBackground(Pixel.PINK_SOFT);
        box.setBorder(new CompoundBorder(new LineBorder(Pixel.PINK, 2), new EmptyBorder(6, 10, 8, 8)));
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setOpaque(false);
        who = label(Font.BOLD, 12, Pixel.PINK_INK);
        JLabel close = label(Font.BOLD, 13, Pixel.INK);
        close.setText("×");
        close.setToolTipText("Hide (turn it back on in the UPE tab)");
        close.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        close.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                setOn(false);
                onClosed.run();
            }
        });
        top.add(who, BorderLayout.CENTER);
        top.add(close, BorderLayout.EAST);
        total = label(Font.BOLD, 20, Pixel.INK);
        last = label(Font.PLAIN, 12, Pixel.INK);
        box.add(top, BorderLayout.NORTH);
        box.add(total, BorderLayout.CENTER);
        box.add(last, BorderLayout.SOUTH);
        // drag anywhere to move it
        MouseAdapter drag = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                grab = e.getPoint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (grab == null) return;
                Point p = e.getLocationOnScreen();
                window.setLocation(p.x - grab.x, p.y - grab.y);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                grab = null;
                settings.setProperty("upe.mini.x", String.valueOf(window.getX()));
                settings.setProperty("upe.mini.y", String.valueOf(window.getY()));
                save.run();
            }
        };
        box.addMouseListener(drag);
        box.addMouseMotionListener(drag);
        window.setContentPane(box);
        window.setSize(260, 92);
        try {
            int x = Integer.parseInt(settings.getProperty("upe.mini.x", "")), y = Integer.parseInt(settings.getProperty("upe.mini.y", ""));
            if (onScreen(x, y)) window.setLocation(x, y);
            else window.setLocationRelativeTo(null);
        } catch (NumberFormatException e) {
            Rectangle s = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            window.setLocation(s.x + s.width - 290, s.y + 40);
        }
    }

    private static boolean onScreen(int x, int y) {
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices())
            if (d.getDefaultConfiguration().getBounds().contains(x + 20, y + 20)) return true;
        return false;
    }

    private static JLabel label(int style, float size, Color c) {
        JLabel l = new JLabel(" ");
        l.setFont(Cute.font(style, size));
        l.setForeground(c);
        return l;
    }

    /** What the box shows right now. */
    String[] text() {
        if (!contest.on()) return new String[]{"UPE contest", "off", "tick Contest mode on the UPE tab"};
        if (contest.charId() < 0) {
            String died = settings.getProperty("contest.diedTo");
            return died != null
                    ? new String[]{"UPE  ·  RIP" + (died.isEmpty() ? "" : " to " + died), contest.total() + " points", "pick your next character on the UPE tab"}
                    : new String[]{"UPE contest", "no character yet", "pick one on the UPE tab"};
        }
        String l = contest.last;
        return new String[]{"UPE  ·  " + contest.who(), contest.total() + (contest.total() == 1 ? " point" : " points"),
                l == null || l.isEmpty() ? "nothing scored yet" : "last: " + l};
    }

    private void refresh() {
        if (window == null) return;
        String[] t = text();
        who.setText(t[0]);
        total.setText(t[1]);
        last.setText(t[2]);
    }
}
