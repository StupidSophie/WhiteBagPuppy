package whitebag;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.IntConsumer;

/** A chunky pixel volume bar (0 to 100): drag or click it, or use the arrow keys. A speaker shows how loud it is. */
final class VolumeSlider extends JComponent {
    private static final int SPEAKER = 30, BAR_H = 14, KNOB = 18;
    private int value;
    private final IntConsumer moved, done;

    /**
     * @param moved called while it's moved (to change the volume)
     * @param done  called when you let go (to play a preview)
     */
    VolumeSlider(int value, IntConsumer moved, IntConsumer done) {
        this.value = Math.max(0, Math.min(100, value));
        this.moved = moved;
        this.done = done;
        setPreferredSize(new Dimension(320, 30));
        setFocusable(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText("How loud the pup's sounds are");
        MouseAdapter m = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                set(at(e.getX()));
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                set(at(e.getX()));
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                done.accept(VolumeSlider.this.value);
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int d = e.getKeyCode() == KeyEvent.VK_LEFT || e.getKeyCode() == KeyEvent.VK_DOWN ? -5
                        : e.getKeyCode() == KeyEvent.VK_RIGHT || e.getKeyCode() == KeyEvent.VK_UP ? 5 : 0;
                if (d != 0) {
                    set(VolumeSlider.this.value + d);
                    done.accept(VolumeSlider.this.value);
                }
            }
        });
    }

    private int left() {
        return SPEAKER + 8;
    }

    private int right() {
        return getWidth() - 52;
    }

    private int at(int x) {
        return (int) Math.round((x - left()) * 100.0 / Math.max(1, right() - left()));
    }

    private void set(int v) {
        v = Math.max(0, Math.min(100, v));
        if (v == value) return;
        value = v;
        moved.accept(v);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        int h = getHeight(), mid = h / 2;
        // speaker, with 0 to 3 waves
        g.setColor(Pixel.INK);
        g.fillRect(4, mid - 4, 6, 8);
        int[] xs = {10, 18, 18, 10}, ys = {mid - 4, mid - 10, mid + 10, mid + 4};
        g.fillPolygon(xs, ys, 4);
        int waves = value == 0 ? 0 : value < 34 ? 1 : value < 67 ? 2 : 3;
        g.setStroke(new BasicStroke(2f));
        Cute.smooth(g);
        for (int w = 0; w < waves; w++) g.drawArc(14 + w * 4 - 4, mid - 4 - w * 3, 8 + w * 4, 8 + w * 6, -50, 100);
        if (value == 0) {
            g.setColor(Pixel.PINK_INK);
            g.drawLine(21, mid - 5, 29, mid + 5);
            g.drawLine(29, mid - 5, 21, mid + 5);
        }
        // the bar
        int x0 = left(), x1 = right(), y = mid - BAR_H / 2;
        Pixel.frame(g, x0, y, x1 - x0, BAR_H, Pixel.TRACK, Pixel.LINE, 2);
        int fill = (int) Math.round((x1 - x0 - 6) * value / 100.0);
        if (fill > 0) {
            g.setColor(Pixel.PINK);
            g.fillRect(x0 + 3, y + 3, fill, BAR_H - 6);
        }
        // knob
        int kx = x0 + (int) Math.round((x1 - x0) * value / 100.0) - KNOB / 2;
        Pixel.frame(g, kx, mid - KNOB / 2, KNOB, KNOB, Pixel.SHEET, isFocusOwner() ? Pixel.PINK : Pixel.INK, 2);
        // percent
        g.setFont(Cute.font(Font.BOLD, 13));
        g.setColor(Pixel.INK);
        String t = value + "%";
        FontMetrics fm = g.getFontMetrics();
        g.drawString(t, getWidth() - fm.stringWidth(t) - 4, mid + fm.getAscent() / 2 - 2);
        g.dispose();
    }
}
