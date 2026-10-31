package whitebag;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Built-in backgrounds behind the app (picked on Home): soft patterns in the app's own colours, so they follow
 * light/dark mode and the accent colour. "Your picture" is background.png next to the app.
 */
final class Backgrounds {
    private Backgrounds() {
    }

    enum Preset {
        PLAIN("Plain"), PICTURE("Your picture"), PAWS("Paw prints"), HEARTS("Hearts"), STARS("Stars"), CLOUDS("Clouds"),
        GINGHAM("Gingham"), CONFETTI("Confetti"), SUNSET("Sunset");
        final String label;

        Preset(String label) {
            this.label = label;
        }

        static Preset of(String name, boolean havePicture) {
            try {
                if (name != null && !name.trim().isEmpty()) return valueOf(name.trim());
            } catch (IllegalArgumentException ignored) {
            }
            return havePicture ? PICTURE : PLAIN;
        }
    }

    private static final Map<Preset, BufferedImage> TILES = new EnumMap<>(Preset.class);

    private static Color tint(Color base, Color with, double t) {
        return Cute.mix(base, with, t);
    }

    /** One repeating tile of the pattern. */
    private static synchronized BufferedImage tile(Preset p) {
        BufferedImage t = TILES.get(p);
        if (t != null) return t;
        int s = p == Preset.GINGHAM ? 48 : p == Preset.CONFETTI ? 180 : 120;
        t = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = t.createGraphics();
        Cute.smooth(g);
        Color page = Pixel.PAGE;
        g.setColor(page);
        g.fillRect(0, 0, s, s);
        switch (p) {
            case PAWS: {
                Color c = tint(page, Pixel.PINK, Theme.DARK ? 0.22 : 0.28);
                Cute.drawPaw(g, 30, 34, 16, c);
                Cute.drawPaw(g, 90, 94, 16, c);
                break;
            }
            case HEARTS: {
                Color c = tint(page, Pixel.PINK, Theme.DARK ? 0.25 : 0.3);
                Cute.drawHeart(g, 18, 18, 22, c);
                Cute.drawHeart(g, 78, 78, 22, c);
                Cute.drawHeart(g, 84, 22, 10, tint(page, Pixel.PINK, 0.18));
                Cute.drawHeart(g, 24, 84, 10, tint(page, Pixel.PINK, 0.18));
                break;
            }
            case STARS: {
                Color gold = tint(page, new Color(0xFFCF5C), Theme.DARK ? 0.55 : 0.6);
                star(g, 26, 30, 9, gold);
                star(g, 86, 82, 12, gold);
                star(g, 92, 24, 5, gold);
                star(g, 30, 96, 5, gold);
                g.setColor(tint(page, Pixel.INK, 0.15));
                for (int[] d : new int[][]{{60, 12}, {12, 62}, {108, 54}, {64, 104}, {54, 58}}) g.fillOval(d[0], d[1], 3, 3);
                break;
            }
            case CLOUDS: {
                Color sky = tint(page, Theme.c(0x9FD3FF), Theme.DARK ? 0.2 : 0.3);
                g.setColor(sky);
                g.fillRect(0, 0, s, s);
                Color cloud = Theme.DARK ? tint(sky, Pixel.INK, 0.12) : tint(sky, Color.WHITE, 0.75);
                cloud(g, 14, 20, 1.0, cloud);
                cloud(g, 70, 76, 0.8, cloud);
                break;
            }
            case GINGHAM: {
                Color band = new Color(Pixel.PINK.getRed(), Pixel.PINK.getGreen(), Pixel.PINK.getBlue(), Theme.DARK ? 34 : 44);
                g.setColor(band);
                g.fillRect(0, 0, s / 2, s);
                g.fillRect(0, 0, s, s / 2);
                break;
            }
            case CONFETTI: {
                Random r = new Random(7);
                Color[] cs = {new Color(0xFF6FA8), new Color(0xFFB347), new Color(0xFFE066), new Color(0x7BDCB5), new Color(0x6EC6FF), new Color(0xB388FF)};
                for (int i = 0; i < 26; i++) {
                    g.setColor(tint(page, cs[i % cs.length], Theme.DARK ? 0.4 : 0.45));
                    int x = r.nextInt(s), y = r.nextInt(s), w = 4 + r.nextInt(5);
                    if (r.nextBoolean()) g.fillOval(x, y, w, w);
                    else g.fillRoundRect(x, y, w + 3, 3, 3, 3);
                }
                break;
            }
            default:
        }
        g.dispose();
        TILES.put(p, t);
        return t;
    }

    private static void star(Graphics2D g, double cx, double cy, double r, Color c) {
        java.awt.geom.GeneralPath p = new java.awt.geom.GeneralPath();
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i - Math.PI / 2, rr = i % 2 == 0 ? r : r * 0.32;
            double x = cx + Math.cos(a) * rr, y = cy + Math.sin(a) * rr;
            if (i == 0) p.moveTo(x, y);
            else p.lineTo(x, y);
        }
        p.closePath();
        g.setColor(c);
        g.fill(p);
    }

    private static void cloud(Graphics2D g, double x, double y, double k, Color c) {
        g.setColor(c);
        g.fillOval((int) x, (int) (y + 10 * k), (int) (44 * k), (int) (18 * k));
        g.fillOval((int) (x + 8 * k), (int) y, (int) (20 * k), (int) (20 * k));
        g.fillOval((int) (x + 20 * k), (int) (y + 3 * k), (int) (18 * k), (int) (18 * k));
    }

    /** Paints the background into a w x h area (not used for Plain or Your picture). */
    static void paint(Graphics2D g, Preset p, int w, int h) {
        if (p == Preset.PLAIN || p == Preset.PICTURE) return;
        if (p == Preset.SUNSET) {
            Color top = tint(Pixel.PAGE, Theme.c(0xFFC3A0), Theme.DARK ? 0.25 : 0.55);
            Color mid = tint(Pixel.PAGE, Pixel.PINK, Theme.DARK ? 0.18 : 0.3);
            g.setPaint(new LinearGradientPaint(0, 0, 0, Math.max(1, h), new float[]{0f, 0.45f, 1f}, new Color[]{top, mid, Pixel.PAGE}));
            g.fillRect(0, 0, w, h);
            return;
        }
        BufferedImage t = tile(p);
        g.setPaint(new TexturePaint(t, new Rectangle(0, 0, t.getWidth(), t.getHeight())));
        g.fillRect(0, 0, w, h);
    }

    /** A row of the presets to click (Your picture only if there is a background.png). */
    static JComponent picker(Preset chosen, boolean havePicture, BufferedImage picture, Consumer<Preset> pick) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.setOpaque(false);
        Preset[] now = {chosen};
        List<JComponent> tiles = new ArrayList<>();
        for (Preset p : Preset.values()) {
            if (p == Preset.PICTURE && !havePicture) continue;
            JComponent t = new JComponent() {
                {
                    setPreferredSize(new Dimension(58, 46));
                    setToolTipText(p.label + (p == Preset.PICTURE ? " (background.png)" : ""));
                    setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                }

                @Override
                protected void paintComponent(Graphics g0) {
                    Graphics2D g = (Graphics2D) g0.create();
                    int w = getWidth(), h = getHeight();
                    g.setClip(3, 3, w - 6, h - 6);
                    g.setColor(Pixel.PAGE);
                    g.fillRect(0, 0, w, h);
                    if (p == Preset.PICTURE && picture != null) g.drawImage(picture, 3, 3, w - 6, h - 6, null);
                    else {
                        g.translate(3, 3);
                        g.scale(0.5, 0.5);
                        Backgrounds.paint(g, p, (w - 6) * 2, (h - 6) * 2);
                    }
                    g.dispose();
                    Graphics2D f = (Graphics2D) g0.create();
                    boolean on = now[0] == p;
                    f.setColor(on ? Pixel.PINK : Pixel.LINE);
                    f.setStroke(new BasicStroke(on ? 3f : 2f));
                    f.drawRect(1, 1, w - 3, h - 3);
                    f.dispose();
                }
            };
            t.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    now[0] = p;
                    pick.accept(p);
                    for (JComponent x : tiles) x.repaint();
                }
            });
            tiles.add(t);
            row.add(t);
        }
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }
}
