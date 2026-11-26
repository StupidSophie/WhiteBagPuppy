package whitebag;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import static whitebag.AboutMe.heading;
import static whitebag.AboutMe.note;
import static whitebag.AboutMe.small;

/**
 * Fights > History: old DPS leaderboards (fights-log.txt), searchable by boss, dungeon or player and narrowed to
 * bosses, fights you were in, fights someone died in, kills and time. Idea from RealmHound's Combat History.
 */
final class FightHistoryPage extends MoreTabs.Page {
    private static final String[] KINDS = {"All fights", "Bosses", "Minibosses", "Others"};
    private static final String[] WHEN = {"All time", "Today", "This week", "This month"};
    private static final int SHOW = 20;
    private final Predicate<String> mine;
    private final JTextField search = Pixel.field("", 22, "Search: boss, dungeon or player");
    private final Pixel.Check withMe = new Pixel.Check("I was in it", false);
    private final Pixel.Check someoneDied = new Pixel.Check("Someone died", false);
    private final Pixel.Check killsOnly = new Pixel.Check("Only kills", false);
    private int kind = 0, when = 0;

    FightHistoryPage(Predicate<String> mine) {
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
        r2.add(new LogTabs.Segments(KINDS, 0, i -> {
            kind = i;
            changed();
        }));
        r2.add(new LogTabs.Segments(WHEN, 0, i -> {
            when = i;
            changed();
        }));
        r2.setAlignmentX(LEFT_ALIGNMENT);
        JPanel r3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        r3.setOpaque(false);
        for (Pixel.Check c : new Pixel.Check[]{withMe, someoneDied, killsOnly}) {
            c.addActionListener(e -> changed());
            r3.add(c);
        }
        r3.setAlignmentX(LEFT_ALIGNMENT);
        top.add(r1);
        top.add(Box.createVerticalStrut(8));
        top.add(r2);
        top.add(Box.createVerticalStrut(6));
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

    private long since() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
        java.time.ZonedDateTime start;
        switch (when) {
            case 1: start = now.toLocalDate().atStartOfDay(now.getZone()); break;
            case 2: start = now.toLocalDate().minusDays(6).atStartOfDay(now.getZone()); break;
            case 3: start = now.toLocalDate().minusDays(29).atStartOfDay(now.getZone()); break;
            default: return 0;
        }
        return start.toEpochSecond() * 1000L;
    }

    private boolean kindMatches(DpsTracker.Kind k) {
        switch (kind) {
            case 1: return k == DpsTracker.Kind.BOSS;
            case 2: return k == DpsTracker.Kind.MINIBOSS;
            case 3: return k == DpsTracker.Kind.OTHER;
            default: return true;
        }
    }

    static boolean matches(FightLog.Fight f, String q) {
        if (q.isEmpty()) return true;
        if (f.board.title.toLowerCase(Locale.ROOT).contains(q)) return true;
        if (f.map.toLowerCase(Locale.ROOT).contains(q) || RunLog.label(f.map).toLowerCase(Locale.ROOT).contains(q)) return true;
        for (DpsTracker.Row r : f.board.rows) {
            if (r.name.toLowerCase(Locale.ROOT).contains(q)) return true;
            if (r.classType > 0 && AboutMe.objectName(r.classType, "").toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        return false;
    }

    @Override
    void fill() {
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        long from = since();
        List<FightLog.Fight> found = new ArrayList<>();
        int all = 0;
        for (FightLog.Fight f : FightLog.all()) {
            if (mine != null && !mine.test(f.account)) continue;
            all++;
            if (f.at < from || !kindMatches(f.board.kind)) continue;
            if (killsOnly.isSelected() && !f.board.killed) continue;
            boolean me = false, died = false;
            for (DpsTracker.Row r : f.board.rows) {
                if (r.you) me = true;
                if (r.died) died = true;
            }
            if (withMe.isSelected() && !me) continue;
            if (someoneDied.isSelected() && !died) continue;
            if (!matches(f, q)) continue;
            found.add(f);
        }
        found.sort((a, b) -> Long.compare(b.at, a.at));
        list.add(heading("fight history", found.isEmpty() ? null : String.format(Locale.ROOT, found.size() == 1 ? "%,d fight" : "%,d fights", found.size())));
        gap(8);
        if (found.isEmpty()) {
            list.add(note(all == 0
                    ? "Every DPS leaderboard is saved here from now on (2.30.40), with everyone's class, gear and enchantments, "
                    + "and who died or left. Search by boss, dungeon or player name."
                    : "No fights match that. Try fewer words, or untick a box."));
            return;
        }
        SimpleDateFormat date = new SimpleDateFormat("EEE d MMM yyyy, h:mm a", Locale.ENGLISH);
        for (int i = 0; i < Math.min(SHOW, found.size()); i++) {
            FightLog.Fight f = found.get(i);
            JLabel where = new JLabel((f.map.isEmpty() ? "" : RunLog.label(f.map) + "  ·  ") + date.format(new Date(f.at)));
            where.setFont(Cute.font(Font.PLAIN, 12));
            where.setForeground(Pixel.MUTED);
            where.setAlignmentX(LEFT_ALIGNMENT);
            list.add(where);
            list.add(new LogTabs.BoardView(f.board, false));
            gap(6);
        }
        if (found.size() > SHOW) {
            gap(4);
            list.add(small("Showing the newest " + SHOW + ". Search or tick a box to narrow it down."));
        }
    }
}
