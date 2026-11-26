package whitebag;

import javax.swing.*;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import static whitebag.AboutMe.heading;
import static whitebag.AboutMe.left;
import static whitebag.AboutMe.note;
import static whitebag.AboutMe.small;

/**
 * Shinies (under Loot): every shiny item the puppy has seen drop for you, how many, how often, and where.
 * (Loot > Collection has the shiny book: which ones you've found at least once.)
 */
final class ShinyPage extends MoreTabs.Page {
    private static final int SHOW = 100;
    private final Predicate<String> mine;

    ShinyPage(Predicate<String> mine) {
        this.mine = mine;
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) changed();
        });
    }

    private static final class Drop {
        final long at;
        final String map;
        final int id;

        Drop(long at, String map, int id) {
            this.at = at;
            this.map = map;
            this.id = id;
        }
    }

    @Override
    void fill() {
        List<Drop> drops = new ArrayList<>();
        int bags = 0;
        for (LootStats.Entry e : LootStats.all()) {
            if (mine != null && !mine.test(e.account)) continue;
            bags++;
            for (int id : e.items) if (LootBook.isShiny(id)) drops.add(new Drop(e.at, e.map, id));
        }
        drops.sort((a, b) -> Long.compare(b.at, a.at));
        list.add(heading("shinies", drops.isEmpty() ? null : String.format(Locale.ROOT, "%,d found", drops.size())));
        gap(8);
        if (drops.isEmpty()) {
            list.add(note("No shiny items yet. Every shiny that drops in one of your bags while the puppy is watching is counted here, "
                    + "with a sparkle sound and a gold flash on the overlay. Loot > Collection > Items > Shiny items shows which ones you still need."));
            return;
        }
        // the numbers
        java.util.Set<String> kinds = new java.util.HashSet<>(); // by name: the game can have two ids for one shiny
        for (Drop d : drops) kinds.add(LootBook.norm(ItemInfo.of(d.id).name));
        long now = System.currentTimeMillis() / 1000, weekAgo = now - 7 * 86400, monthAgo = now - 30 * 86400;
        int week = 0, month = 0;
        for (Drop d : drops) {
            if (d.at >= weekAgo) week++;
            if (d.at >= monthAgo) month++;
        }
        list.add(stat("Different shinies", String.format(Locale.ROOT, "%,d", kinds.size())));
        list.add(stat("This week / this month", week + " / " + month));
        list.add(stat("How often", String.format(Locale.ROOT, "about 1 in every %,d bags", Math.max(1, Math.round(bags / (double) drops.size())))));
        list.add(stat("Last one", ItemInfo.of(drops.get(0).id).name + ", " + ago(drops.get(0).at)));
        // where they've dropped
        Map<String, Integer> where = new LinkedHashMap<>();
        for (Drop d : drops) where.merge(d.map.isEmpty() ? "Somewhere" : RunLog.label(d.map), 1, Integer::sum);
        List<Map.Entry<String, Integer>> places = new ArrayList<>(where.entrySet());
        places.sort((a, b) -> b.getValue() - a.getValue());
        gap(16);
        list.add(heading("where they dropped", String.valueOf(places.size())));
        gap(6);
        for (int i = 0; i < Math.min(10, places.size()); i++)
            list.add(stat(places.get(i).getKey(), places.get(i).getValue() + (places.get(i).getValue() == 1 ? " shiny" : " shinies")));
        if (places.size() > 10) list.add(small("and " + (places.size() - 10) + " more places"));
        // every one, newest first
        gap(16);
        list.add(heading("every shiny", null));
        gap(6);
        SimpleDateFormat date = new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ROOT);
        for (int i = 0; i < Math.min(SHOW, drops.size()); i++) {
            Drop d = drops.get(i);
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
            row.setOpaque(false);
            List<Integer> one = new ArrayList<>();
            one.add(d.id);
            row.add(new AboutMe.ItemStrip(one, null));
            JLabel l = new JLabel("<html><b>" + esc(ItemInfo.of(d.id).name) + "</b>" + (d.map.isEmpty() ? "" : " in " + esc(RunLog.label(d.map)))
                    + "<br><span style='color:" + Theme.hex(0x8A7F99) + "'>" + date.format(new Date(d.at * 1000L)) + "</span></html>");
            l.setFont(Cute.font(Font.PLAIN, 13));
            l.setForeground(Pixel.INK);
            row.add(l);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
            list.add(left(row));
            gap(4);
        }
        if (drops.size() > SHOW) list.add(small("Showing the newest " + SHOW + "."));
    }

    private static JComponent stat(String label, String value) {
        JLabel l = new JLabel("<html><span style='color:" + Theme.hex(0x8A7F99) + "'>" + esc(label) + ":</span>&nbsp; <b>" + esc(value) + "</b></html>");
        l.setFont(Cute.font(Font.PLAIN, 13));
        l.setForeground(Pixel.INK);
        l.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        return left(l);
    }

    private static String ago(long at) {
        long m = (System.currentTimeMillis() / 1000 - at) / 60;
        if (m < 60) return m < 1 ? "just now" : m + " min ago";
        if (m < 60 * 24) return m / 60 + " h ago";
        long d = m / 60 / 24;
        return d == 1 ? "yesterday" : d + " days ago";
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
