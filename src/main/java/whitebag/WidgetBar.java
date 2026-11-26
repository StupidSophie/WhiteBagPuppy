package whitebag;

import packets.data.enums.StatType;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The widget bar under the header: little chips with your character, account fame, gold, rank, enchanting dust,
 * forge fire, forge materials and play time, read from what the game sends about you. Right-click it to choose the
 * chips. Idea from RealmHound's Widget Bar; the dungeon mods chip is based on RealmHound's
 * (github.com/pillarzz/RealmHound, MIT licence, by Lovens and Pillar).
 */
final class WidgetBar extends JPanel {
    interface Settings {
        String get(String key, String fallback);

        void set(String key, String value);
    }

    /** id, name in the menu. */
    private static final String[][] WIDGETS = {
            {"char", "Character"}, {"fame", "Account fame"}, {"gold", "Gold"}, {"rank", "Rank"},
            {"dust", "Enchanting dust"}, {"fire", "Forge fire"}, {"materials", "Forge materials"}, {"time", "Play time"},
            {"mods", "Dungeon mods"}};

    private final Supplier<MeTracker> account;
    private final Supplier<DungeonMods.Here> mods;
    private final Settings settings;
    private final Set<String> hidden = new LinkedHashSet<>();
    private String lastKey = "";

    WidgetBar(Supplier<MeTracker> account, Supplier<DungeonMods.Here> mods, Settings settings) {
        super(new WrapLayout(6, 3));
        this.account = account;
        this.mods = mods;
        this.settings = settings;
        setOpaque(false);
        setBorder(new EmptyBorder(0, -6, 3, 0));
        for (String h : settings.get("widgets.hidden", "").split(",")) if (!h.trim().isEmpty()) hidden.add(h.trim());
        MouseAdapter menu = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger()) showMenu(e);
            }
        };
        addMouseListener(menu);
        new Timer(2000, e -> refresh()).start();
        refresh();
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); // never stretched taller than its rows
    }

    /** Rebuilds the chips when something they show has changed. */
    void refresh() {
        MeTracker a = account.get();
        MeTracker.Thing c = a == null ? null : a.character();
        List<JComponent> chips = new ArrayList<>();
        StringBuilder key = new StringBuilder();
        if (c != null) {
            for (String[] w : WIDGETS) {
                if (hidden.contains(w[0])) continue;
                JComponent chip = chip(w[0], c, a);
                if (chip == null) continue;
                chips.add(chip);
                key.append(w[0]).append('=').append(chip.getToolTipText()).append(((Chip) chip).text).append(';');
            }
        }
        boolean show = Boolean.parseBoolean(settings.get("widgets.on", "true"));
        String k = show + "|" + key;
        if (k.equals(lastKey)) return;
        lastKey = k;
        removeAll();
        if (show) for (JComponent chip : chips) add(chip);
        setVisible(show && !chips.isEmpty());
        revalidate();
        repaint();
    }

    private Chip chip(String id, MeTracker.Thing c, MeTracker a) {
        switch (id) {
            case "char": {
                String cls = AboutMe.objectName(c.objectType, "Character");
                Chip ch = new Chip(AboutMe.sprite(c.objectType, 22), null, "Lv " + c.i(StatType.LEVEL_STAT, 1) + " " + cls);
                ch.setToolTipText("<html><b>" + esc(DpsTracker.cleanName(c.s(StatType.NAME_STAT))) + "</b><br>"
                        + "ATT " + c.i(StatType.ATTACK_STAT, 0) + "  DEF " + c.i(StatType.DEFENSE_STAT, 0)
                        + "  SPD " + c.i(StatType.SPEED_STAT, 0) + "<br>DEX " + c.i(StatType.DEXTERITY_STAT, 0)
                        + "  VIT " + c.i(StatType.VITALITY_STAT, 0) + "  WIS " + c.i(StatType.WISDOM_STAT, 0)
                        + "<br>HP " + c.i(StatType.MAX_HP_STAT, 0) + "  MP " + c.i(StatType.MAX_MP_STAT, 0) + "</html>");
                return ch;
            }
            case "fame": {
                int v = c.i(StatType.FAME_STAT, -1);
                if (v < 0) return null;
                Chip ch = new Chip(null, Theme.c(0xEBB43C), num(v) + " fame");
                ch.setToolTipText("Your account fame");
                return ch;
            }
            case "gold": {
                int v = c.i(StatType.CREDITS_STAT, -1);
                if (v < 0) return null;
                Chip ch = new Chip(null, Theme.c(0xF2C94C), num(v) + " gold");
                ch.setToolTipText("Your gold");
                return ch;
            }
            case "rank": {
                int v = c.i(StatType.NUM_STARS_STAT, -1);
                if (v < 0) return null;
                Chip ch = new Chip(null, starColour(v), String.valueOf(v));
                ch.star = true;
                ch.setToolTipText("Your rank: " + v + " stars");
                return ch;
            }
            case "dust": {
                int[] have = slots(c.s(StatType.DUST_STAT)), cap = slots(c.s(StatType.DUST_AMOUNT_STAT));
                if (have == null) return null;
                // the game's slots: 1 green (common), 5 red (rare), 4 purple (legendary)
                Chip ch = new Chip(null, null, "Dust");
                ch.parts(new Color(0x4CC46A), num(have[1]), new Color(0xE0483E), num(have[5]), new Color(0x9B5CFF), num(have[4]));
                ch.setToolTipText("<html>Enchanting dust<br>Green " + num(have[1]) + of(cap, 1) + "<br>Red " + num(have[5]) + of(cap, 5)
                        + "<br>Purple " + num(have[4]) + of(cap, 4) + "</html>");
                return ch;
            }
            case "fire": {
                int v = c.i(StatType.FORGE_FIRE, -1);
                if (v < 0) return null;
                Chip ch = new Chip(null, Theme.c(0xFF7A3C), num(v) + " fire");
                ch.setToolTipText("Forge fire");
                return ch;
            }
            case "materials": {
                int[] m = slots(c.s(StatType.MATERIAL_STAT)), cap = slots(c.s(StatType.MATERIAL_CAP_STAT));
                if (m == null) return null;
                // slots 1 common, 2 rare, 3 legendary, 4 mythical; shown in the game's order and colours:
                // mythical (blue crystal), legendary (red), rare (gold), common (silver)
                Chip ch = new Chip(null, null, "Materials");
                ch.parts(new Color(0x5B6CFF), num(m[4]), new Color(0xD8342E), num(m[3]), new Color(0xE8B12A), num(m[2]), new Color(0xB9BDC6), num(m[1]));
                ch.setToolTipText("<html>Forge materials<br>Mythical (blue) " + num(m[4]) + of(cap, 4) + "<br>Legendary (red) " + num(m[3]) + of(cap, 3)
                        + "<br>Rare (gold) " + num(m[2]) + of(cap, 2) + "<br>Common (silver) " + num(m[1]) + of(cap, 1) + "</html>");
                return ch;
            }
            case "time": {
                long[] pt = a.playTime();
                Chip ch = new Chip(null, Pixel.TEAL, AboutMe.duration(pt[1]) + " today");
                ch.setToolTipText("Play time: " + AboutMe.duration(pt[0]) + " this session, " + AboutMe.duration(pt[3]) + " in total");
                return ch;
            }
            case "mods": {
                DungeonMods.Here h = mods.get();
                if (h == null || h.isEmpty()) return null;
                String t = h.loot != 0 ? String.format(Locale.ROOT, "%+d%% loot", h.loot)
                        : h.xp != 0 ? String.format(Locale.ROOT, "%+d%% XP", h.xp) : h.mods.size() + (h.mods.size() == 1 ? " mod" : " mods");
                Chip ch = new Chip(null, h.loot > 0 ? Theme.c(0xE8B12A) : Theme.c(0x9B8FB0), t);
                ch.setToolTipText(h.tooltip());
                return ch;
            }
            default:
                return null;
        }
    }

    /** "1:1935,4:1990,5:1800" -> amounts by slot (index 0..9), or null when the game hasn't sent it. */
    private static int[] slots(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        int[] out = new int[10];
        for (String part : s.split(",")) {
            String[] kv = part.split(":");
            if (kv.length != 2) continue;
            try {
                int i = Integer.parseInt(kv[0].trim());
                if (i >= 0 && i < out.length) out[i] = Integer.parseInt(kv[1].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static String of(int[] cap, int i) {
        return cap == null || cap[i] <= 0 ? "" : " / " + num(cap[i]);
    }

    private static String num(long v) {
        return String.format(Locale.ROOT, "%,d", v);
    }

    /** The rank badge colours: light blue, blue, red, orange, yellow, white. */
    static Color starColour(int stars) {
        int[][] tiers = {{95, 0xE0E0E0}, {76, 0xE6C800}, {57, 0xF6921D}, {38, 0xC0262C}, {19, 0x304CDA}, {0, 0x8997DD}};
        for (int[] t : tiers) if (stars >= t[0]) return new Color(t[1]);
        return new Color(0x8997DD);
    }

    /** A five-pointed star around (cx, cy). */
    private static Shape starShape(double cx, double cy, double r) {
        java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5, rr = i % 2 == 0 ? r : r * 0.45;
            double x = cx + Math.cos(a) * rr, y = cy + Math.sin(a) * rr;
            if (i == 0) p.moveTo(x, y);
            else p.lineTo(x, y);
        }
        p.closePath();
        return p;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void showMenu(MouseEvent e) {
        JPopupMenu m = new JPopupMenu();
        m.setBorder(BorderFactory.createLineBorder(Pixel.LINE, 2));
        m.setBackground(Pixel.SHEET);
        JLabel head = new JLabel("  Show");
        head.setFont(Cute.font(Font.BOLD, 12));
        head.setForeground(Theme.c(0x8A7F99));
        head.setBorder(new EmptyBorder(6, 6, 4, 6));
        m.add(head);
        for (String[] w : WIDGETS) {
            Pixel.Check box = new Pixel.Check(w[1], !hidden.contains(w[0]));
            box.setFocusable(false);
            box.setBorder(new EmptyBorder(3, 36, 3, 14));
            box.addActionListener(x -> {
                if (box.isSelected()) hidden.remove(w[0]);
                else hidden.add(w[0]);
                settings.set("widgets.hidden", String.join(",", hidden));
                lastKey = "";
                refresh();
            });
            JPanel holder = new JPanel(new BorderLayout());
            holder.setOpaque(false);
            holder.setBorder(new EmptyBorder(0, 8, 0, 0));
            holder.add(box);
            m.add(holder);
        }
        m.show(e.getComponent(), e.getX(), e.getY());
    }

    /**
     * A left-to-right row that wraps onto more lines when the window is narrow, and asks for enough height for all of
     * them (a plain FlowLayout wraps too, but only asks for one line, so the chips slid under the tabs).
     */
    static final class WrapLayout extends FlowLayout {
        WrapLayout(int hgap, int vgap) {
            super(FlowLayout.LEFT, hgap, vgap);
        }

        @Override
        public Dimension preferredLayoutSize(Container target) {
            return size(target, true);
        }

        @Override
        public Dimension minimumLayoutSize(Container target) {
            return size(target, false);
        }

        private Dimension size(Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                int width = target.getWidth();
                Container p = target.getParent();
                if (width == 0 && p != null) width = p.getWidth();
                if (width == 0) width = Integer.MAX_VALUE;
                Insets in = target.getInsets();
                int max = width - in.left - in.right;
                int w = 0, h = 0, rowW = 0, rowH = 0;
                for (Component c : target.getComponents()) {
                    if (!c.isVisible()) continue;
                    Dimension d = preferred ? c.getPreferredSize() : c.getMinimumSize();
                    if (rowW > 0 && rowW + getHgap() + d.width > max) {
                        w = Math.max(w, rowW);
                        h += rowH + getVgap();
                        rowW = 0;
                        rowH = 0;
                    }
                    rowW += (rowW > 0 ? getHgap() : 0) + d.width;
                    rowH = Math.max(rowH, d.height);
                }
                w = Math.max(w, rowW);
                h += rowH;
                // FlowLayout leaves a gap above the first row and below the last one
                return new Dimension(w + in.left + in.right + getHgap() * 2, h + in.top + in.bottom + getVgap() * 2);
            }
        }

        @Override
        public void layoutContainer(Container target) {
            // FlowLayout's own placement already wraps; it just needs the right height above
            super.layoutContainer(target);
        }
    }

    /** One chip: an optional picture or coloured dot, and its text, on a soft rounded card. */
    final class Chip extends JComponent {
        final String text;
        private final BufferedImage icon;
        private final Color dot;
        /** Draw the dot as a little star (the rank). */
        boolean star;
        /** Extra coloured dot + number pairs after the text (dust and materials). */
        private final List<Object[]> parts = new ArrayList<>();

        void parts(Object... colourText) {
            for (int i = 0; i + 1 < colourText.length; i += 2) parts.add(new Object[]{colourText[i], colourText[i + 1]});
            FontMetrics fm = getFontMetrics(getFont());
            int extra = 0;
            for (Object[] p : parts) extra += 8 + 12 + fm.stringWidth((String) p[1]);
            Dimension d = getPreferredSize();
            setPreferredSize(new Dimension(d.width + extra, d.height));
        }

        Chip(BufferedImage icon, Color dot, String text) {
            this.icon = icon;
            this.dot = dot;
            this.text = text;
            setFont(Cute.font(Font.BOLD, 12));
            FontMetrics fm = getFontMetrics(getFont());
            int lead = icon != null ? 26 : dot != null ? 18 : 10;
            setPreferredSize(new Dimension(lead + fm.stringWidth(text) + 12, 28));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger()) showMenu(e);
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Cute.smooth(g);
            int w = getWidth(), h = getHeight();
            RoundRectangle2D r = new RoundRectangle2D.Double(0.5, 0.5, w - 1.5, h - 1.5, 12, 12);
            g.setColor(Pixel.SHEET);
            g.fill(r);
            g.setColor(Pixel.LINE);
            g.setStroke(new BasicStroke(1.4f));
            g.draw(r);
            int x = 8;
            if (icon != null) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(icon, 4, (h - 22) / 2, 22, 22, null);
                x = 28;
            } else if (dot != null) {
                g.setColor(dot);
                if (star) g.fill(starShape(13, h / 2.0, 6));
                else g.fill(new Ellipse2D.Double(9, h / 2.0 - 4, 8, 8));
                x = 21;
            }
            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            int base = (h - fm.getHeight()) / 2 + fm.getAscent();
            g.setColor(Pixel.INK);
            g.drawString(text, x, base);
            x += fm.stringWidth(text);
            for (Object[] p : parts) {
                x += 8;
                g.setColor((Color) p[0]);
                g.fill(new Ellipse2D.Double(x, h / 2.0 - 4, 8, 8));
                x += 12;
                g.setColor(Pixel.INK);
                g.drawString((String) p[1], x, base);
                x += fm.stringWidth((String) p[1]);
            }
            g.dispose();
        }
    }
}
