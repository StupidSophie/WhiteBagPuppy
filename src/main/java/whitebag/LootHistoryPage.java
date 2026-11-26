package whitebag;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import static whitebag.AboutMe.heading;
import static whitebag.AboutMe.left;
import static whitebag.AboutMe.note;
import static whitebag.AboutMe.small;

/**
 * History (under Loot): every bag the puppy has logged, newest first, searchable by item, dungeon, enemy or bag colour
 * and narrowed by bag colour, time, the character that looted it and the enemy that dropped it (those two are saved
 * from 2.30.40 on). Idea from RealmHound's Loot History.
 */
final class LootHistoryPage extends MoreTabs.Page {
    private static final String[] COLOURS = {"Any bag", "White", "Red", "Orange", "Gold", "Other"};
    private static final String[] WHEN = {"All time", "Today", "This week", "This month"};
    private static final int SHOW = 150;
    private final Predicate<String> mine;
    private final JTextField search = Pixel.field("", 22, "Search: item, dungeon, enemy or bag");
    private int colour = 0, when = 0;
    /** "Any character" / "Any enemy" or the picked class name / enemy name. */
    private final JComboBox<String> who = new JComboBox<>(new String[]{ANY_CHAR});
    private final JComboBox<String> enemyBox = new JComboBox<>(new String[]{ANY_ENEMY});
    private static final String ANY_CHAR = "Any character", ANY_ENEMY = "Any enemy";
    private boolean filling;

    LootHistoryPage(Predicate<String> mine) {
        this.mine = mine;
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setOpaque(false);
        top.setBorder(new EmptyBorder(0, 0, 10, 0));
        JPanel r1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        r1.setOpaque(false);
        r1.add(search);
        r1.setAlignmentX(LEFT_ALIGNMENT);
        JPanel r2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        r2.setOpaque(false);
        r2.add(new LogTabs.Segments(COLOURS, 0, i -> {
            colour = i;
            changed();
        }));
        r2.add(new LogTabs.Segments(WHEN, 0, i -> {
            when = i;
            changed();
        }));
        r2.setAlignmentX(LEFT_ALIGNMENT);
        JPanel r3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        r3.setOpaque(false);
        for (JComboBox<String> c : java.util.Arrays.asList(who, enemyBox)) {
            c.setFont(Cute.font(Font.PLAIN, 13));
            c.setMaximumRowCount(20);
            c.addActionListener(e -> {
                if (!filling) changed();
            });
        }
        who.setToolTipText("Only bags looted on this class (saved for bags from 2.30.40 on)");
        enemyBox.setToolTipText("Only bags this enemy dropped (saved for bags from 2.30.40 on)");
        r3.add(who);
        r3.add(enemyBox);
        r3.setAlignmentX(LEFT_ALIGNMENT);
        top.add(r1);
        top.add(Box.createVerticalStrut(8));
        top.add(r2);
        top.add(Box.createVerticalStrut(8));
        top.add(r3);
        add(top, BorderLayout.NORTH);
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { changed(); }
            public void removeUpdate(DocumentEvent e) { changed(); }
            public void changedUpdate(DocumentEvent e) { changed(); }
        });
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) changed();
        });
    }

    /** Fills in the search box (from the header's search). */
    void search(String q) {
        search.setText(q);
        changed();
    }

    /** True if any bag in the history matches. */
    boolean has(String q) {
        String s = q.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return false;
        for (LootStats.Entry e : LootStats.all()) {
            if (mine != null && !mine.test(e.account)) continue;
            if (e.map.toLowerCase(Locale.ROOT).contains(s) || e.enemy.toLowerCase(Locale.ROOT).contains(s)) return true;
            for (int id : e.items) if (name(id).toLowerCase(Locale.ROOT).contains(s)) return true;
        }
        return false;
    }

    private static String name(int id) {
        try {
            return ItemInfo.of(id).name;
        } catch (Throwable e) {
            return "Item #" + id;
        }
    }

    private static String className(int type) {
        return type <= 0 ? "" : AboutMe.objectName(type, "Class " + type);
    }

    /** Refills a filter's choices (most bags first), keeping what's picked. */
    private void refill(JComboBox<String> box, String any, Map<String, Integer> counts) {
        Object picked = box.getSelectedItem();
        List<Map.Entry<String, Integer>> l = new ArrayList<>(counts.entrySet());
        l.sort((a, b) -> b.getValue() != a.getValue().intValue() ? b.getValue() - a.getValue() : a.getKey().compareToIgnoreCase(b.getKey()));
        List<String> want = new ArrayList<>();
        want.add(any);
        for (Map.Entry<String, Integer> x : l) want.add(x.getKey());
        if (picked != null && !want.contains(picked.toString())) want.add(picked.toString());
        boolean same = box.getItemCount() == want.size();
        for (int i = 0; same && i < want.size(); i++) same = want.get(i).equals(box.getItemAt(i));
        if (same) return;
        filling = true;
        try {
            box.removeAllItems();
            for (String w : want) box.addItem(w);
            box.setSelectedItem(picked == null ? any : picked);
        } finally {
            filling = false;
        }
    }

    private boolean colourMatches(BagType t) {
        BagType c = LootStats.colour(t);
        switch (colour) {
            case 1: return c == BagType.WHITE;
            case 2: return c == BagType.RED;
            case 3: return c == BagType.ORANGE;
            case 4: return c == BagType.GOLD;
            case 5: return c != BagType.WHITE && c != BagType.RED && c != BagType.ORANGE && c != BagType.GOLD;
            default: return true;
        }
    }

    private long since() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
        java.time.ZonedDateTime start;
        switch (when) {
            case 1: start = now.toLocalDate().atStartOfDay(now.getZone()); break;
            case 2: start = now.toLocalDate().minusDays(6).atStartOfDay(now.getZone()); break;
            case 3: start = now.toLocalDate().minusDays(29).atStartOfDay(now.getZone()); break;
            default: return 0;
        }
        return start.toEpochSecond();
    }

    @Override
    void fill() {
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        long from = since();
        // the character and enemy choices: whatever your bags have
        Map<String, Integer> classes = new HashMap<>(), enemies = new HashMap<>();
        for (LootStats.Entry e : LootStats.all()) {
            if (mine != null && !mine.test(e.account)) continue;
            String c = className(e.charClass);
            if (!c.isEmpty()) classes.merge(c, 1, Integer::sum);
            if (!e.enemy.isEmpty()) enemies.merge(e.enemy, 1, Integer::sum);
        }
        refill(who, ANY_CHAR, classes);
        refill(enemyBox, ANY_ENEMY, enemies);
        String wantClass = ANY_CHAR.equals(who.getSelectedItem()) ? null : (String) who.getSelectedItem();
        String wantEnemy = ANY_ENEMY.equals(enemyBox.getSelectedItem()) ? null : (String) enemyBox.getSelectedItem();
        List<LootStats.Entry> found = new ArrayList<>();
        for (LootStats.Entry e : LootStats.all()) {
            if (mine != null && !mine.test(e.account)) continue;
            if (e.at < from || !colourMatches(e.type)) continue;
            if (wantClass != null && !wantClass.equals(className(e.charClass))) continue;
            if (wantEnemy != null && !wantEnemy.equals(e.enemy)) continue;
            if (!q.isEmpty()) {
                boolean hit = e.map.toLowerCase(Locale.ROOT).contains(q) || e.type.displayName.toLowerCase(Locale.ROOT).contains(q)
                        || e.enemy.toLowerCase(Locale.ROOT).contains(q) || className(e.charClass).toLowerCase(Locale.ROOT).contains(q);
                for (int id : e.items) if (!hit && name(id).toLowerCase(Locale.ROOT).contains(q)) hit = true;
                if (!hit) continue;
            }
            found.add(e);
        }
        found.sort((a, b) -> Long.compare(b.at, a.at));
        list.add(heading("loot history", found.isEmpty() ? null : String.format(Locale.ROOT, "%,d bags", found.size())));
        gap(8);
        if (found.isEmpty()) {
            list.add(note(q.isEmpty() && colour == 0 && when == 0 && wantClass == null && wantEnemy == null ? "Every bag the puppy sees while it's watching shows up here."
                    : "No bags match that. Try fewer words, or a different bag or time."));
            return;
        }
        SimpleDateFormat date = new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ROOT);
        for (int i = 0; i < Math.min(SHOW, found.size()); i++) {
            LootStats.Entry e = found.get(i);
            // the items move under the name when the window is too narrow for both, and the row grows to fit them
            JPanel row = new JPanel(new WidgetBar.WrapLayout(8, 0)) {
                @Override
                public Dimension getMaximumSize() {
                    return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
                }
            };
            row.addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent ev) {
                    if (row.getPreferredSize().height != row.getHeight()) SwingUtilities.invokeLater(() -> {
                        Container p = row.getParent();
                        if (p != null) {
                            p.revalidate();
                            p.repaint();
                        }
                    });
                }
            });
            row.setOpaque(false);
            row.add(new Dot(e.type));
            String cls = className(e.charClass);
            String extra = (e.enemy.isEmpty() ? "" : "from " + esc(e.enemy)) + (cls.isEmpty() ? "" : (e.enemy.isEmpty() ? "" : " · ") + esc(cls));
            JLabel l = new JLabel("<html><b>" + esc(e.type.displayName) + " bag</b>" + (e.map.isEmpty() ? "" : " in " + esc(RunLog.label(e.map)))
                    + "<br><span style='color:" + Theme.hex(0x8A7F99) + "'>" + date.format(new Date(e.at * 1000L))
                    + (extra.isEmpty() ? "" : "<br>" + extra) + "</span></html>");
            l.setFont(Cute.font(Font.PLAIN, 13));
            l.setForeground(Pixel.INK);
            l.setPreferredSize(new Dimension(260, l.getPreferredSize().height));
            row.add(l);
            if (!e.items.isEmpty()) row.add(new AboutMe.ItemStrip(e.items.subList(0, Math.min(8, e.items.size())), null));
            list.add(left(row));
            gap(6);
        }
        if (found.size() > SHOW) {
            gap(4);
            list.add(small("Showing the newest " + SHOW + ". Search or pick a bag or time to narrow it down."));
        }
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** The bag's colour as a round dot. */
    private static final class Dot extends JComponent {
        private final BagType type;

        Dot(BagType type) {
            this.type = type;
            setPreferredSize(new Dimension(16, 36));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Cute.smooth(g);
            g.setColor(new Color(type.color));
            g.fill(new Ellipse2D.Double(1, getHeight() / 2.0 - 7, 14, 14));
            g.setColor(Pixel.LINE);
            g.draw(new Ellipse2D.Double(1, getHeight() / 2.0 - 7, 14, 14));
            g.dispose();
        }
    }
}
