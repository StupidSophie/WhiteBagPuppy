package whitebag;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static whitebag.AboutMe.heading;
import static whitebag.AboutMe.left;
import static whitebag.AboutMe.note;

/**
 * Holidays (under Loot): the event-only items, grouped by the holiday or event they drop in, with the ones you have
 * ticked off. The holiday that's usually on this month comes first, marked "On now".
 */
final class HolidayPage extends MoreTabs.Page {
    static final class Event {
        final String name;
        final List<Integer> months = new ArrayList<>();
        final List<String[]> items = new ArrayList<>(); // {tier, name}

        Event(String name) {
            this.name = name;
        }

        boolean onNow(int month) {
            return months.contains(month);
        }

        /** How many months until it's usually on (0 if it is now); events with no set time come last. */
        int monthsAway(int month) {
            int best = 99;
            for (int m : months) best = Math.min(best, (m - month + 12) % 12);
            return best;
        }

        String when() {
            if (months.isEmpty()) return "now and then";
            String[] m = {"", "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"};
            StringBuilder sb = new StringBuilder("usually ");
            for (int i = 0; i < months.size(); i++) {
                if (i > 0) sb.append(i == months.size() - 1 ? " and " : ", ");
                sb.append(m[months.get(i)]);
            }
            return sb.toString();
        }
    }

    private static List<Event> events;

    static synchronized List<Event> events() {
        if (events != null) return events;
        Map<String, Event> out = new LinkedHashMap<>();
        try (InputStream in = HolidayPage.class.getResourceAsStream("/whitebag/event-items.txt")) {
            if (in != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String l;
                while ((l = r.readLine()) != null) {
                    if (l.startsWith("#") || l.trim().isEmpty()) continue;
                    String[] f = l.split("\\|", -1);
                    if (f.length < 4) continue;
                    Event e = out.computeIfAbsent(f[0], Event::new);
                    if (e.months.isEmpty() && !f[1].isEmpty())
                        for (String m : f[1].split(",")) e.months.add(Integer.parseInt(m.trim()));
                    e.items.add(new String[]{f[2], f[3]});
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        events = new ArrayList<>(out.values());
        return events;
    }

    private final Predicate<LootStats.Entry> mine;

    HolidayPage(Predicate<LootStats.Entry> mine) {
        this.mine = mine;
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) changed();
        });
    }

    @Override
    void fill() {
        List<LootStats.Entry> bags = new ArrayList<>();
        for (LootStats.Entry e : LootStats.all()) if (mine == null || mine.test(e)) bags.add(e);
        Map<LootBook.Item, LootBook.Found> found = LootBook.found(bags, a -> true);
        int month = java.time.LocalDate.now().getMonthValue();
        List<Event> all = new ArrayList<>(events());
        all.sort(java.util.Comparator.comparingInt(e -> e.monthsAway(month))); // what's on now first, then what's next
        int total = 0, have = 0;
        for (Event e : all) for (String[] it : e.items) {
            total++;
            LootBook.Item item = LootBook.byName(it[1]);
            if (item != null && found.containsKey(item)) have++;
        }
        list.add(heading("holidays", total == 0 ? null : have + " of " + total + " event items"));
        gap(8);
        if (all.isEmpty()) {
            list.add(note("The holiday item list is missing from this copy of the pup."));
            return;
        }
        list.add(note("Items that only drop during holidays and special events, by event. During an event most bosses drop "
                + "its items, so the months show when to hunt. Ones you've looted (or have in your vault or on a character) are ticked."));
        gap(12);
        for (Event e : all) {
            int got = 0;
            for (String[] it : e.items) {
                LootBook.Item item = LootBook.byName(it[1]);
                if (item != null && found.containsKey(item)) got++;
            }
            JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            head.setOpaque(false);
            JLabel name = new JLabel(e.name + "   ");
            name.setFont(Cute.font(Font.BOLD, 15));
            name.setForeground(Pixel.INK);
            head.add(name);
            if (e.onNow(month)) head.add(new Tag("On now"));
            head.add(Box.createHorizontalStrut(8));
            JLabel sub = new JLabel(e.when() + "  ·  " + got + " of " + e.items.size());
            sub.setFont(Cute.font(Font.PLAIN, 12));
            sub.setForeground(Theme.c(0x8A7F99));
            head.add(sub);
            head.setMaximumSize(new Dimension(Integer.MAX_VALUE, head.getPreferredSize().height));
            list.add(left(head));
            gap(6);
            JPanel grid = new JPanel(new GridLayout(0, 3, 8, 8));
            grid.setOpaque(false);
            List<String[]> items = new ArrayList<>(e.items);
            items.sort((a, b) -> {
                boolean ga = has(a[1], found), gb = has(b[1], found);
                return ga != gb ? (ga ? 1 : -1) : a[1].compareToIgnoreCase(b[1]); // still needed first
            });
            for (String[] it : items) grid.add(new Chip(it[0], it[1], found));
            grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, grid.getPreferredSize().height));
            list.add(left(grid));
            gap(16);
        }
    }

    private static boolean has(String name, Map<LootBook.Item, LootBook.Found> found) {
        LootBook.Item item = LootBook.byName(name);
        return item != null && found.containsKey(item);
    }

    /** A little "On now" pill. */
    private static final class Tag extends JComponent {
        private final String text;

        Tag(String text) {
            this.text = text;
            setFont(Cute.font(Font.BOLD, 11));
            FontMetrics fm = getFontMetrics(getFont());
            setPreferredSize(new Dimension(fm.stringWidth(text) + 16, 20));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Cute.smooth(g);
            g.setColor(Pixel.PINK);
            g.fill(new RoundRectangle2D.Double(0, 1, getWidth() - 1, getHeight() - 2, 18, 18));
            g.setColor(Theme.WHITE);
            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text, 8, (getHeight() + fm.getAscent()) / 2 - 2);
            g.dispose();
        }
    }

    /** One event item: its picture and name, in colour with a heart once you have it. */
    private static final class Chip extends JComponent {
        private final String tier, name;
        private final LootBook.Found f;
        private final BufferedImage icon;

        Chip(String tier, String name, Map<LootBook.Item, LootBook.Found> found) {
            this.tier = tier;
            this.name = name;
            LootBook.Item item = LootBook.byName(name);
            this.f = item == null ? null : found.get(item);
            this.icon = item != null && item.id > 0 ? AboutMe.sprite(item.id, 28) : null;
            setPreferredSize(new Dimension(180, 40));
            setToolTipText("<html><b>" + name.replace("<", "&lt;") + "</b> (" + tier + ")<br>"
                    + (f == null ? "Still needed" : LootBook.describe(f)) + "<br><i>" + RealmEye.HINT + "</i></html>");
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    RealmEye.open(name);
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            boolean got = f != null;
            Color accent = tier.equals("ST") ? Theme.c(0xE0A100) : Theme.c(0xA35BFF);
            Pixel.frame(g, 0, 0, getWidth(), getHeight(), got ? Cute.mix(Theme.WHITE, accent, 0.14) : Theme.c(0xF4F2F7),
                    got ? accent : Theme.c(0xE2DCEA), 2);
            int x = 6;
            if (icon != null) {
                Composite old = g.getComposite();
                if (!got) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
                g.drawImage(icon, x, (getHeight() - icon.getHeight()) / 2, null);
                g.setComposite(old);
                x += 34;
            }
            Cute.smooth(g);
            g.setFont(Cute.font(got ? Font.BOLD : Font.PLAIN, 12));
            FontMetrics fm = g.getFontMetrics();
            String s = name;
            int room = getWidth() - x - (got ? 22 : 8);
            while (fm.stringWidth(s) > room && s.length() > 3) s = s.substring(0, s.length() - 1);
            if (!s.equals(name)) s = s.trim() + "…";
            g.setColor(got ? Pixel.INK : Theme.c(0x9C92AC));
            g.drawString(s, x, (getHeight() + fm.getAscent()) / 2 - 2);
            if (got) Cute.drawHeart(g, getWidth() - 18, (getHeight() - 10) / 2, 10, Pixel.PINK);
            g.dispose();
        }
    }
}
