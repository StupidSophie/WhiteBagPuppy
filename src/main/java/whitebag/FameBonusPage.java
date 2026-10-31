package whitebag;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static whitebag.AboutMe.heading;
import static whitebag.AboutMe.left;
import static whitebag.AboutMe.note;
import static whitebag.AboutMe.small;

/**
 * Fame Bonuses (under Me): a checklist of every dungeon for one character, ticked by itself when you kill a dungeon's
 * boss on that character (or by hand), so you can see which ones are left for the dungeon fame bonuses.
 */
final class FameBonusPage extends MoreTabs.Page {
    private final Supplier<String> account;
    private final Supplier<Integer> charId;
    private final Predicate<String> mine;

    FameBonusPage(Supplier<String> account, Supplier<Integer> charId, Predicate<String> mine) {
        this.account = account;
        this.charId = charId;
        this.mine = mine;
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) changed();
        });
    }

    @Override
    void fill() {
        String acc = account.get();
        Integer id = charId.get();
        int playingId = id == null ? -1 : id;
        // only the character you're on right now
        FameDungeons.Char c = acc == null || playingId < 0 ? null : FameDungeons.get(acc, playingId);

        List<FameDungeons.Bonuses.Set> sets = FameDungeons.Bonuses.sets();
        int doneSets = 0;
        if (c != null) for (FameDungeons.Bonuses.Set s : sets) if (allDone(c, s)) doneSets++;
        list.add(heading("fame bonuses", sets.isEmpty() || c == null ? null : doneSets + " of " + sets.size() + " dungeon sets done"));
        gap(8);
        list.add(note("The game's dungeon fame bonuses for the character you're on, from the RealmEye wiki. Each time you load "
                + "in, the game tells the pup every dungeon that character has ever completed, so this fills in by itself (and "
                + "counts new completions as you go). Click a dungeon to tick or untick it by hand. Sets show what's left first; "
                + "the bonus is paid when the character dies."));
        gap(10);
        if (acc == null || playingId < 0) {
            list.add(note("Load into the game on a character and its list shows up here."));
            return;
        }
        if (c == null) c = new FameDungeons.Char(acc, playingId); // nothing known for it yet: an empty list
        list.add(small("Now playing: " + (c.label.isEmpty() ? "Character" : c.label) + " #" + c.id));
        if (!c.fromGame()) {
            gap(4);
            list.add(note("Change map once on this character and its past completions show up."));
        }
        gap(10);
        final FameDungeons.Char who = c;
        suggestions(who, sets);

        // ---- the sets: complete every dungeon in a set once
        for (FameDungeons.Bonuses.Set s : sets) {
            int got = 0;
            for (String d : s.dungeons) if (who.has(d)) got++;
            boolean all = got == s.dungeons.size();
            JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            head.setOpaque(false);
            JLabel name = new JLabel(s.name + "   ");
            name.setFont(Cute.font(Font.BOLD, 15));
            name.setForeground(Pixel.INK);
            head.add(name);
            JLabel sub = new JLabel(s.what + "  ·  +" + pct(s.percent) + "% and +" + String.format(java.util.Locale.ROOT, "%,d", s.fame) + " fame"
                    + (all ? "  ·  done!" : ""));
            sub.setFont(Cute.font(all ? Font.BOLD : Font.PLAIN, 12));
            sub.setForeground(all ? Pixel.PINK_INK : Theme.c(0x8A7F99));
            head.add(sub);
            head.setMaximumSize(new Dimension(Integer.MAX_VALUE, head.getPreferredSize().height));
            list.add(left(head));
            gap(4);
            list.add(new AboutMe.AbilityBar(all ? "All done!" : (s.dungeons.size() - got) + " left", got + " / " + s.dungeons.size(),
                    got / (double) Math.max(1, s.dungeons.size())));
            gap(6);
            JPanel grid = new JPanel(new GridLayout(0, 3, 8, 8));
            grid.setOpaque(false);
            List<String> ds = new ArrayList<>(s.dungeons);
            ds.sort((x, y) -> Boolean.compare(who.has(x), who.has(y))); // what's left first
            for (String d : ds) grid.add(new Box(who, d, this::changed));
            grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, grid.getPreferredSize().height));
            list.add(left(grid));
            gap(14);
        }

        // ---- completing each dungeon 1, 10, 20, 40 and 100 times
        Map<String, FameDungeons.Bonuses.Tiers> tiers = FameDungeons.Bonuses.tiers();
        if (!tiers.isEmpty()) {
            list.add(heading("dungeon completions", null));
            gap(4);
            list.add(note("Each dungeon also gives fame for finishing it 1, 10, 20, 40 and 100 times on a character "
                    + "(Scout, Explorer, Adventurer, Conqueror, Master). The ones you've started come first."));
            gap(6);
            List<FameDungeons.Bonuses.Tiers> ts = new ArrayList<>(tiers.values());
            // ones you've started, closest to their next bonus first; then the rest, biggest first bonus first
            ts.sort((x, y) -> {
                boolean sx = who.times(x.dungeon) > 0, sy = who.times(y.dungeon) > 0;
                if (sx != sy) return sx ? -1 : 1;
                if (sx) return toNext(who, x) != toNext(who, y) ? Integer.compare(toNext(who, x), toNext(who, y)) : x.dungeon.compareTo(y.dungeon);
                return x.fame[0] != y.fame[0] ? Integer.compare(y.fame[0], x.fame[0]) : x.dungeon.compareTo(y.dungeon);
            });
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{"Dungeon", "Done", "Next bonus", "Fame"});
            for (FameDungeons.Bonuses.Tiers t : ts) {
                int n = who.times(t.dungeon);
                int k = 0;
                while (k < t.times.length && n >= t.times[k]) k++;
                String next = k >= t.times.length ? "All 5 done!" : FameDungeons.Bonuses.TIER_NAMES[Math.min(k, 4)] + " at " + t.times[k]
                        + " (" + (t.times[k] - n) + " to go)";
                rows.add(new String[]{t.dungeon, String.valueOf(n), next, k >= t.times.length ? "-" : "+" + t.fame[k]});
            }
            list.add(table(rows));
        }
    }

    /** "Next best" dungeons for this character: what one more run of each would earn, best first. */
    private void suggestions(FameDungeons.Char who, List<FameDungeons.Bonuses.Set> sets) {
        Map<String, FameDungeons.Bonuses.Tiers> tiers = FameDungeons.Bonuses.tiers();
        java.util.Set<String> all = new java.util.LinkedHashSet<>();
        for (FameDungeons.Bonuses.Set s : sets) all.addAll(s.dungeons);
        all.addAll(tiers.keySet());
        final class Pick {
            String dungeon;
            int fame;
            double percent;
            final List<String> why = new ArrayList<>();
        }
        List<Pick> picks = new ArrayList<>();
        for (String d : all) {
            Pick p = new Pick();
            p.dungeon = d;
            int n = who.times(d);
            FameDungeons.Bonuses.Tiers t = tiers.get(d);
            if (t != null) for (int k = 0; k < t.times.length; k++) {
                if (t.times[k] == n + 1) {
                    p.fame += t.fame[k];
                    p.why.add(FameDungeons.Bonuses.TIER_NAMES[k] + " +" + t.fame[k]);
                }
            }
            if (!who.has(d)) {
                // the last one left in a set finishes it
                for (FameDungeons.Bonuses.Set s : sets) {
                    if (!s.dungeons.contains(d)) continue;
                    int left = 0;
                    for (String x : s.dungeons) if (!who.has(x)) left++;
                    if (left == 1) {
                        p.fame += s.fame;
                        p.percent += s.percent;
                        p.why.add("finishes " + s.name + " (+" + pct(s.percent) + "% and +" + String.format(java.util.Locale.ROOT, "%,d", s.fame) + ")");
                    }
                }
            }
            if (p.fame > 0) picks.add(p);
        }
        picks.sort((x, y) -> x.fame != y.fame ? Integer.compare(y.fame, x.fame) : Double.compare(y.percent, x.percent));

        // sets that are nearly done
        List<String> close = new ArrayList<>();
        for (FameDungeons.Bonuses.Set s : sets) {
            List<String> left = new ArrayList<>();
            for (String x : s.dungeons) if (!who.has(x)) left.add(x);
            if (!left.isEmpty() && left.size() <= 3)
                close.add(s.name + ": " + String.join(", ", left) + (left.size() == 1 ? " left" : " left") + " (+" + pct(s.percent) + "% and +"
                        + String.format(java.util.Locale.ROOT, "%,d", s.fame) + " fame)");
        }
        if (picks.isEmpty() && close.isEmpty()) return;
        list.add(heading("next best dungeons", null));
        gap(4);
        if (!picks.isEmpty()) {
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{"Run next", "Fame", "Why"});
            for (int i = 0; i < Math.min(6, picks.size()); i++) {
                Pick p = picks.get(i);
                rows.add(new String[]{p.dungeon, "+" + String.format(java.util.Locale.ROOT, "%,d", p.fame) + (p.percent > 0 ? " +" + pct(p.percent) + "%" : ""),
                        String.join(", ", p.why)});
            }
            list.add(table(rows));
            gap(4);
            list.add(note("What one more completion of each would add to this character's fame bonuses (a % adds that much of its base fame on top)."));
        }
        if (!close.isEmpty()) {
            gap(8);
            list.add(note("Nearly there: " + String.join("   ·   ", close)));
        }
        gap(16);
    }

    private static String pct(double p) {
        return p == Math.rint(p) ? String.valueOf((int) p) : String.valueOf(p);
    }

    private static boolean allDone(FameDungeons.Char c, FameDungeons.Bonuses.Set s) {
        for (String d : s.dungeons) if (!c.has(d)) return false;
        return true;
    }

    /** Completions until the next threshold (huge when all five are done). */
    private static int toNext(FameDungeons.Char c, FameDungeons.Bonuses.Tiers t) {
        int n = c.times(t.dungeon);
        for (int x : t.times) if (n < x) return x - n;
        return Integer.MAX_VALUE;
    }

    /** One dungeon: a tick box and its name; ticked in colour, with how many times it's been done. */
    private static final class Box extends JComponent {
        private final FameDungeons.Char c;
        private final String dungeon;
        private boolean over;

        Box(FameDungeons.Char c, String dungeon, Runnable changed) {
            this.c = c;
            this.dungeon = dungeon;
            setPreferredSize(new Dimension(190, 32));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            int n = c.times(dungeon);
            Boolean m = c.manual.get(dungeon);
            setToolTipText(dungeon + ": " + (n > 0 ? "completed " + (n == 1 ? "once" : n + " times") + " on this character" : "not completed yet")
                    + (m != null ? (m ? " (ticked by hand)" : " (unticked by hand)") : "") + ". Click to " + (c.has(dungeon) ? "untick" : "tick") + ".");
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    FameDungeons.set(c.account, c.id, dungeon, !c.has(dungeon));
                    Squeak.boop();
                    changed.run();
                }

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
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            boolean on = c.has(dungeon);
            Pixel.frame(g, 0, 0, getWidth(), getHeight(), on ? Pixel.PINK_SOFT : over ? Theme.c(0xF7F3FB) : Pixel.SHEET,
                    on ? Pixel.PINK : over ? Pixel.INK : Pixel.LINE, 2);
            // tick box
            Pixel.frame(g, 8, (getHeight() - 14) / 2, 14, 14, on ? Pixel.PINK : Pixel.SHEET, on ? Pixel.INK : Pixel.LINE, 2);
            if (on) {
                g.setColor(Theme.WHITE);
                int bx = 8, by = (getHeight() - 14) / 2;
                int[][] tick = {{4, 7}, {5, 8}, {6, 9}, {7, 8}, {8, 7}, {9, 6}, {10, 5}};
                for (int[] t : tick) g.fillRect(bx + t[0] - 1, by + t[1] - 1, 2, 2);
            }
            Cute.smooth(g);
            g.setFont(Cute.font(on ? Font.BOLD : Font.PLAIN, 12));
            FontMetrics fm = g.getFontMetrics();
            int n = c.times(dungeon);
            String count = n > 1 ? "x" + n : "";
            String s = dungeon;
            int room = getWidth() - 30 - (count.isEmpty() ? 6 : fm.stringWidth(count) + 12);
            while (fm.stringWidth(s) > room && s.length() > 3) s = s.substring(0, s.length() - 1);
            if (!s.equals(dungeon)) s = s.trim() + "…";
            g.setColor(on ? Pixel.INK : Theme.c(0x8A7F99));
            g.drawString(s, 28, (getHeight() + fm.getAscent()) / 2 - 2);
            if (!count.isEmpty()) {
                g.setColor(Pixel.PINK_INK);
                g.drawString(count, getWidth() - fm.stringWidth(count) - 8, (getHeight() + fm.getAscent()) / 2 - 2);
            }
            g.dispose();
        }
    }
}
