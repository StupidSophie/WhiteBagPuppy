package whitebag;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The app's icon (window title bar and taskbar): a few pixel-art presets to pick from on Home. Kept as "app.icon" in
 * the settings.
 */
final class AppIcon {
    private AppIcon() {
    }

    enum Preset {
        CAT("Golden cat"), PUP("Your pup"), PAW("Pink paw"),
        HEART("Heart"), BONE("Bone"), STAR("Shiny star"),
        // unlocked with badges
        MOON("Sleepy moon", "nightpup"), GEM("Divine gem", "divine"), RAINBOW("Rainbow", "rainbow"),
        SWORD("Sword", "boss500"), CROWN("Crown", "white100"), TROPHY("Trophy", "badges25");
        final String label;
        /** The badge that unlocks it, or null if it's always there. */
        final String badge;

        Preset(String label) {
            this(label, null);
        }

        Preset(String label, String badge) {
            this.label = label;
            this.badge = badge;
        }

        /** True if any account has the badge for it (like the wardrobe). */
        boolean open() {
            return badge == null || Badges.anyUnlocked(badge);
        }

        String badgeName() {
            Badges.Badge b = badge == null ? null : Badges.byId(badge);
            return b == null ? badge : b.name;
        }
    }

    /** The icon this badge unlocks ("the Crown app icon"), or null. */
    static String unlockedBy(String badgeId) {
        for (Preset p : Preset.values()) if (badgeId.equals(p.badge)) return "the " + p.label + " app icon";
        return null;
    }

    static Preset of(String name) {
        try {
            return Preset.valueOf(name == null ? "CAT" : name.trim());
        } catch (IllegalArgumentException e) {
            return Preset.CAT;
        }
    }

    private static final Map<Character, Integer> PAL = new HashMap<>();

    static {
        PAL.put('O', 0xFF3A2B4E); // outline
        PAL.put('W', 0xFFFFFFFF); // white
        PAL.put('G', 0xFFD9CFE6); // white shade
        PAL.put('P', 0xFFFF8DBA); // pink
        PAL.put('Q', 0xFFE0609A); // pink shade
        PAL.put('Y', 0xFFFFCF4D); // gold
        PAL.put('D', 0xFFD69A1F); // gold shade
        PAL.put('R', 0xFFFF6FA8); // ribbon
        PAL.put('B', 0xFF6EC6FF); // blue
        PAL.put('b', 0xFF3F95D9); // blue shade
        PAL.put('V', 0xFFB388FF); // violet
        PAL.put('M', 0xFF7BDCB5); // mint
        PAL.put('A', 0xFFFFB347); // orange
        PAL.put('L', 0xFFFFE066); // lemon
        PAL.put('S', 0xFFE3E8F0); // steel
        PAL.put('s', 0xFF8E9AB0); // steel shade
        PAL.put('H', 0xFF8A5A3C); // handle
    }

    private static final String[] PAW = {
            "................",
            "....OO....OO....",
            "...OPPO..OPPO...",
            "...OPPO..OPPO...",
            ".OO.OO....OO.OO.",
            "OPPO........OPPO",
            "OPPO..OOOO..OPPO",
            ".OO..OPPPPO..OO.",
            "....OPPPPPPO....",
            "...OPPPPPPPPO...",
            "...OPPPPPPPQO...",
            "...OPPPPPPQQO...",
            "....OPPOOPQO....",
            ".....OO..OO.....",
            "................",
            "................",
    };
    private static final String[] HEART = {
            "................",
            "................",
            "..OOOO....OOOO..",
            ".OPPPPO..OPPPPO.",
            "OPPWWPPOOPPPPPPO",
            "OPWWPPPPPPPPPPPO",
            "OPWPPPPPPPPPPPPO",
            "OPPPPPPPPPPPPPQO",
            ".OPPPPPPPPPPPQO.",
            "..OPPPPPPPPPQO..",
            "...OPPPPPPPQO...",
            "....OPPPPPQO....",
            ".....OPPPQO.....",
            "......OPQO......",
            ".......OO.......",
            "................",
    };
    private static final String[] BONE = {
            "................",
            "................",
            "................",
            "..OO........OO..",
            ".OWWO......OWWO.",
            "OWWWWOOOOOOWWWWO",
            "OWWWWWWWWWWWWWGO",
            ".OWWWWWWWWWWWGO.",
            "OWWWWWWWWWWWWWGO",
            "OWWGGOOOOOOWWGGO",
            ".OGGO......OGGO.",
            "..OO........OO..",
            "................",
            "................",
            "................",
            "................",
    };
    private static final String[] STAR = {
            ".......OO.......",
            "......OYYO......",
            "......OYYO......",
            ".....OYYYYO.....",
            "OOOOOOYWYYOOOOOO",
            "OYYYYYWYYYYYYYYO",
            ".OYYYYYYYYYYYYO.",
            "..OYYYYYYYYYDO..",
            "...OYYYYYYYYO...",
            "...OYYYYYYYDO...",
            "..OYYYYOOYYYDO..",
            "..OYYYO..OYYDO..",
            ".OYYYO....OYYDO.",
            ".OYYO......OYDO.",
            ".OOO........OOO.",
            "................",
    };

    private static final String[] MOON = {
            "................",
            "......OOOO......",
            "....OOLLLO......",
            "...OLLLLO.......",
            "..OLLLLO........",
            "..OLLLO.....O...",
            ".OLLLLO....OLO..",
            ".OLLLLO.....O...",
            ".OLLLLO.........",
            ".OLLLLLO........",
            "..OLLLLLO.....O.",
            "..OLLLLLLOOOOO..",
            "...OLLLLLLLLLO..",
            "....OOLLLLLOO...",
            "......OOOOO.....",
            "................",
    };
    private static final String[] GEM = {
            "................",
            "................",
            "....OOOOOOOO....",
            "...OWBBWBBBBO...",
            "..OWBBBWBBBbbO..",
            ".OOOOOOOOOOOOOO.",
            ".OWBBBBWBBBBbbO.",
            "..OWBBBWBBBbbO..",
            "...OWBBWBBbbO...",
            "....OWBWBbbO....",
            ".....OWWbbO.....",
            "......OWbO......",
            ".......OO.......",
            "................",
            "................",
            "................",
    };
    private static final String[] RAINBOW = {
            "................",
            "................",
            "....OOOOOOOO....",
            "..OOPPPPPPPPOO..",
            ".OPPAAAAAAAAPPO.",
            ".OPAALLLLLLAAPO.",
            "OPALLMMMMMMLLAPO",
            "OPALMMBBBBMMLAPO",
            "OPALMBVVVVBMLAPO",
            "OPALMBVOOVBMLAPO",
            "OPALMBO..OBMLAPO",
            "OPALMBO..OBMLAPO",
            "OOOOOOO..OOOOOOO",
            "................",
            "................",
            "................",
    };
    private static final String[] SWORD = {
            "............OOO.",
            "...........OSSO.",
            "..........OSSsO.",
            ".........OSSsO..",
            "........OSSsO...",
            ".......OSSsO....",
            "..OO..OSSsO.....",
            "..OYOOSSsO......",
            "...OYYSsO.......",
            "....OYYO........",
            "...OHOYYO.......",
            "..OHO.OYYO......",
            ".OHO...OO.......",
            "OHO.............",
            "OO..............",
            "................",
    };
    private static final String[] CROWN = {
            "................",
            "................",
            "................",
            "..O....OO....O..",
            ".OYO..OYYO..OYO.",
            ".OYYO.OYYO.OYYO.",
            ".OYYYOYYYYOYYYO.",
            ".OYYYYYYYYYYYYO.",
            ".OYYPYYYBYYYPYO.",
            ".OYYYYYYYYYYYYO.",
            ".ODDDDDDDDDDDDO.",
            ".OOOOOOOOOOOOOO.",
            "................",
            "................",
            "................",
            "................",
    };
    private static final String[] TROPHY = {
            "................",
            "..OOOOOOOOOOOO..",
            ".OOYYYYYYYYWYOO.",
            "OYOYYYYYYYYWYOYO",
            "OYOYYYYYYYYWYOYO",
            ".OOYYYYYYYYYYOO.",
            "...OYYYYYYYYDO..",
            "....OYYYYYYDO...",
            ".....OYYYYDO....",
            "......OYYDO.....",
            ".......OYO......",
            ".......OYO......",
            ".....OOOYOOO....",
            "....ODDDDDDDO...",
            "....OOOOOOOOO...",
            "................",
    };

    private static BufferedImage sprite(String[] rows) {
        BufferedImage img = new BufferedImage(rows[0].length(), rows.length, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < rows.length; y++)
            for (int x = 0; x < rows[y].length(); x++) {
                Integer c = PAL.get(rows[y].charAt(x));
                img.setRGB(x, y, c == null ? 0 : c);
            }
        return img;
    }

    /** The preset's pixel art at its own size (16 x 16, or the pup's frame), on a transparent background. */
    static BufferedImage art(Preset p) {
        switch (p) {
            case PUP: {
                BufferedImage f = PixelPup.frame(PixelPup.Face.HAPPY, PixelPup.Ears.UP, PixelPup.Tail.UP,
                        PixelPup.hatFor(java.time.LocalDate.now()), PixelPup.outfitNow());
                // trim the empty edges, then put it in a square (the pup is taller than wide)
                int x0 = f.getWidth(), y0 = f.getHeight(), x1 = -1, y1 = -1;
                for (int y = 0; y < f.getHeight(); y++)
                    for (int x = 0; x < f.getWidth(); x++)
                        if ((f.getRGB(x, y) >>> 24) != 0) {
                            x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y);
                        }
                if (x1 < 0) return sprite(PAW);
                int w = x1 - x0 + 1, h = y1 - y0 + 1, s = Math.max(w, h);
                BufferedImage sq = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = sq.createGraphics();
                g.drawImage(f.getSubimage(x0, y0, w, h), (s - w) / 2, s - h, null);
                g.dispose();
                return sq;
            }
            case PAW: return sprite(PAW);
            case HEART: return sprite(HEART);
            case BONE: return sprite(BONE);
            case STAR: return sprite(STAR);
            case MOON: return sprite(MOON);
            case GEM: return sprite(GEM);
            case RAINBOW: return sprite(RAINBOW);
            case SWORD: return sprite(SWORD);
            case CROWN: return sprite(CROWN);
            case TROPHY: return sprite(TROPHY);
            default: return null; // the cat is Cute.catIcon
        }
    }

    /** The preset drawn at this size with crisp pixels. */
    static Image at(Preset p, int size) {
        if (p == Preset.CAT) return Cute.catIcon(size);
        BufferedImage a = art(p);
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        int scale = size / a.getWidth();
        if (scale >= 2 || a.getWidth() == size) { // whole pixels, centred
            int w = a.getWidth() * scale;
            g.drawImage(a, (size - w) / 2, (size - w) / 2, w, w, null);
        } else {
            g.drawImage(a, 0, 0, size, size, null); // otherwise fill the square
        }
        g.dispose();
        return img;
    }

    /** Every size the window and taskbar might ask for. */
    static List<Image> images(Preset p) {
        List<Image> out = new ArrayList<>();
        for (int s : new int[]{16, 32, 48, 64, 128, 256}) out.add(at(p, s));
        return out;
    }

    /** A row of the presets to click, with the chosen one marked. */
    static JComponent picker(Preset chosen, Consumer<Preset> pick) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.setOpaque(false);
        Preset[] now = {chosen};
        List<Tile> tiles = new ArrayList<>();
        for (Preset p : Preset.values()) {
            Tile t = new Tile(p, () -> now[0] == p);
            t.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (!p.open()) return;
                    now[0] = p;
                    pick.accept(p);
                    for (Tile x : tiles) x.repaint();
                }
            });
            tiles.add(t);
            row.add(t);
        }
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    private static final class Tile extends JComponent {
        private final Preset p;
        private final java.util.function.BooleanSupplier on;
        private boolean over;

        Tile(Preset p, java.util.function.BooleanSupplier on) {
            this.p = p;
            this.on = on;
            setPreferredSize(new Dimension(46, 46));
            setToolTipText(null);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseEntered(java.awt.event.MouseEvent e) {
                    over = true;
                    repaint();
                }

                @Override
                public void mouseExited(java.awt.event.MouseEvent e) {
                    over = false;
                    repaint();
                }
            });
        }

        @Override
        public String getToolTipText(java.awt.event.MouseEvent e) {
            if (!p.open()) return p.label + ": unlock it with the " + p.badgeName() + " badge";
            return p.label + (p == Preset.PUP ? " (dressed like it is on the Wardrobe page)" : "");
        }

        @Override
        public void addNotify() {
            super.addNotify();
            ToolTipManager.sharedInstance().registerComponent(this);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            boolean sel = on.getAsBoolean();
            if (!p.open()) {
                // locked: a grey ghost of it with a little padlock
                Pixel.frame(g, 0, 0, getWidth(), getHeight(), Theme.c(0xF4F2F7), Theme.c(0xE2DCEA), 2);
                Image img = at(p, 32);
                BufferedImage grey = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
                Graphics2D gg = grey.createGraphics();
                gg.drawImage(img, 0, 0, null);
                gg.dispose();
                for (int y = 0; y < 32; y++)
                    for (int x = 0; x < 32; x++) {
                        int c = grey.getRGB(x, y);
                        if ((c >>> 24) == 0) continue;
                        int v = (((c >> 16) & 255) + ((c >> 8) & 255) + (c & 255)) / 3;
                        v = 150 + v / 4;
                        grey.setRGB(x, y, (0x90 << 24) | (v << 16) | (v << 8) | v);
                    }
                g.drawImage(grey, (getWidth() - 32) / 2, (getHeight() - 32) / 2, null);
                int lx = getWidth() - 15, ly = getHeight() - 15;
                g.setColor(Pixel.INK);
                g.fillRect(lx, ly + 4, 10, 8);
                g.drawRect(lx + 2, ly, 5, 5);
                g.dispose();
                return;
            }
            Pixel.frame(g, 0, 0, getWidth(), getHeight(), sel ? Pixel.PINK_SOFT : over ? Theme.c(0xF7F3FB) : Pixel.SHEET,
                    sel ? Pixel.PINK : over ? Pixel.INK : Pixel.LINE, sel ? 3 : 2);
            g.drawImage(at(p, 32), (getWidth() - 32) / 2, (getHeight() - 32) / 2, null);
            g.dispose();
        }
    }
}
