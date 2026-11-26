package whitebag;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;
import java.util.Locale;

import static whitebag.AboutMe.heading;
import static whitebag.AboutMe.left;
import static whitebag.AboutMe.note;
import static whitebag.AboutMe.small;

/**
 * UPE Contest (under Loot): the same contest scoring as Loot Puppy. One character until it dies, UT and ST items
 * only, each item once (a better modification adds the difference; the first shiny is +4), posted to a contest webhook.
 */
final class ContestPage extends MoreTabs.Page {
    /** What the page needs from the app. */
    interface Ctl {
        Contest contest();

        java.util.Properties settings();

        void save();

        /** Makes this account's current character the contest character (false if it isn't known yet). */
        boolean pick(Account a);

        void postTotal();

        void test(String url);

        /** The mini UPE window. */
        UpeMini mini();

        /** A line in the diary. */
        default void log(String line) {
        }
    }

    private final Account account;
    private final Ctl ctl;
    private final Pixel.Check on;
    private final JTextField url;
    private Pixel.Check mini;

    ContestPage(Account account, Ctl ctl) {
        this.account = account;
        this.ctl = ctl;
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setOpaque(false);
        top.setBorder(new EmptyBorder(0, 0, 12, 0));
        on = new Pixel.Check("Contest mode (UPE: UT and ST items only)", ctl.contest().on());
        on.addActionListener(e -> {
            ctl.settings().setProperty("contest.on", String.valueOf(on.isSelected()));
            ctl.save();
            changed();
        });
        top.add(row(on));
        top.add(Box.createVerticalStrut(6));
        Pixel.Check only = new Pixel.Check("Contest only (pause my normal bag posts)",
                "true".equals(ctl.settings().getProperty("contest.only")));
        only.setToolTipText("Your usual bag posts stop while you have a contest character. The diary, sounds and contest posts keep going.");
        only.addActionListener(e -> {
            ctl.settings().setProperty("contest.only", String.valueOf(only.isSelected()));
            ctl.save();
            changed();
        });
        top.add(row(only));
        top.add(Box.createVerticalStrut(8));
        url = Pixel.field(ctl.settings().getProperty("contest.webhook", ""), 30, "Contest channel webhook URL");
        url.setToolTipText("The webhook for the contest channel. Your normal bag posts keep going to your usual webhook.");
        url.addActionListener(e -> saveUrl());
        url.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                saveUrl();
            }
        });
        Pixel.Button test = new Pixel.Button("Send test post", false);
        test.addActionListener(e -> {
            saveUrl();
            ctl.test(url.getText().trim());
        });
        top.add(row(url, test));
        top.add(Box.createVerticalStrut(8));
        Pixel.Button pick = new Pixel.Button("Use the character I'm on now", false);
        pick.addActionListener(e -> {
            Contest c = ctl.contest();
            if (c.charId() >= 0 && c.charId() != account.me.charId() && c.total() > 0) {
                int ok = JOptionPane.showConfirmDialog(this, "Start a new contest run on this character?\nYour current " + c.who() + " has "
                        + c.total() + " points, which will be reset to 0.", "New contest character", JOptionPane.OK_CANCEL_OPTION);
                if (ok != JOptionPane.OK_OPTION) return;
            }
            if (!ctl.pick(account)) {
                JOptionPane.showMessageDialog(this, "Load in on your UPE character while the puppy is watching\n(change area once if you're already in game), then press this again.",
                        "Contest character", JOptionPane.INFORMATION_MESSAGE);
            }
            on.setSelected(ctl.contest().on());
            changed();
        });
        Pixel.Button total = new Pixel.Button("Post my contest total", false);
        total.addActionListener(e -> ctl.postTotal());
        top.add(row(pick, total));
        String[] back = ctl.contest().previousRun();
        if (back != null) {
            // picked a new character by mistake? go back to the run before (only while the new one has nothing scored)
            Pixel.Button undo = new Pixel.Button("Undo: go back to " + back[0] + " (" + back[1] + " points)", false);
            undo.setToolTipText("Picked a new UPE character by mistake? This puts your last run back, with its points and items "
                    + "(drops since then join it, without counting what it already had twice).");
            undo.addActionListener(e -> {
                int ok = JOptionPane.showConfirmDialog(this, "Go back to your " + back[0] + " run, with its " + back[1] + " points?",
                        "Undo new contest character", JOptionPane.OK_CANCEL_OPTION);
                if (ok != JOptionPane.OK_OPTION) return;
                if (ctl.contest().undoNewRun()) {
                    ctl.save();
                    ctl.log("*happy wiggle* UPE: back to " + ctl.contest().who() + " with " + ctl.contest().total() + " points.");
                } else {
                    JOptionPane.showMessageDialog(this, "I couldn't put the last run back.", "Undo", JOptionPane.WARNING_MESSAGE);
                }
                on.setSelected(ctl.contest().on());
                changed();
            });
            top.add(Box.createVerticalStrut(6));
            top.add(row(undo));
        }
        top.add(Box.createVerticalStrut(8));
        mini = new Pixel.Check("Mini UPE window (on top of the game)", ctl.mini().isOn());
        mini.setToolTipText("A small always-on-top box with your contest total and the last item that scored. Drag it anywhere.");
        mini.addActionListener(e -> ctl.mini().setOn(mini.isSelected()));
        ctl.mini().onClosed = () -> SwingUtilities.invokeLater(() -> mini.setSelected(false));
        Pixel.Check rip = new Pixel.Check("Add a game picture to my RIP post", !"false".equals(ctl.settings().getProperty("contest.ripShot")));
        rip.setToolTipText("When your contest character dies, the RIP post shows the game at that moment.");
        rip.addActionListener(e -> {
            ctl.settings().setProperty("contest.ripShot", String.valueOf(rip.isSelected()));
            ctl.save();
        });
        top.add(row(mini));
        top.add(Box.createVerticalStrut(6));
        top.add(row(rip));
        top.add(Box.createVerticalStrut(10));
        // item check: type a name, see what it would score on this character
        JTextField find = Pixel.field("", 22, "Check an item: type its name");
        find.setToolTipText("Type a UT or ST item's name to see what it would score on your contest character");
        JPanel answer = new JPanel();
        answer.setLayout(new BoxLayout(answer, BoxLayout.Y_AXIS));
        answer.setOpaque(false);
        answer.setAlignmentX(LEFT_ALIGNMENT);
        answer.setBorder(new EmptyBorder(4, 10, 0, 0));
        Runnable look = () -> {
            answer.removeAll();
            String q = find.getText().trim();
            if (q.length() >= 2) {
                List<ItemInfo> hits = Contest.find(q, 6);
                if (hits.isEmpty()) answer.add(small("No UT or ST item called that."));
                else {
                    ItemInfo it = hits.get(0);
                    JLabel name = new JLabel("<html><b>" + esc(it.name) + "</b> <span style='color:" + Theme.hex(0x8A7F99) + "'>" + it.rarity + "</span></html>");
                    name.setFont(Cute.font(Font.PLAIN, 13));
                    name.setForeground(Pixel.INK);
                    answer.add(left(name));
                    for (String l : ctl.contest().preview(it, classType())) answer.add(small(l));
                    if (hits.size() > 1) {
                        StringBuilder also = new StringBuilder();
                        for (int i = 1; i < hits.size(); i++) also.append(i > 1 ? ", " : "").append(hits.get(i).name);
                        answer.add(small("Also: " + also));
                    }
                }
            }
            answer.revalidate();
            answer.repaint();
        };
        find.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { look.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { look.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { look.run(); }
        });
        top.add(row(find));
        top.add(answer);
        add(top, BorderLayout.NORTH);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) changed();
        });
    }

    /** The class to check items against: the character you're on now (-1 if not known). */
    private int classType() {
        MeTracker.Thing ch = account.me.character();
        return ch == null ? -1 : ch.objectType;
    }

    private void saveUrl() {
        String u = url.getText().trim();
        if (u.equals(ctl.settings().getProperty("contest.webhook", ""))) return;
        ctl.settings().setProperty("contest.webhook", u);
        ctl.save();
    }

    private static JPanel row(JComponent... parts) {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        r.setOpaque(false);
        for (JComponent c : parts) r.add(c);
        r.setAlignmentX(LEFT_ALIGNMENT);
        return r;
    }

    @Override
    void fill() {
        Contest c = ctl.contest();
        if (on.isSelected() != c.on()) on.setSelected(c.on());
        if (mini != null && mini.isSelected() != ctl.mini().isOn()) mini.setSelected(ctl.mini().isOn());
        list.add(heading("UPE contest", c.on() && c.charId() >= 0 ? c.total() + " points" : null));
        gap(8);
        if (!c.on()) {
            list.add(note("Tick Contest mode to score a UPE: one character until it dies, UT and ST items only. Each item counts "
                    + "once: its dismantling tier (5 = 15, 4 = 10, 3 = 7, 2 = 5, 1 = 1, no tier = 0) plus its "
                    + "modification (uncommon +1, rare +2, legendary +3, divine +5). A shiny adds +4 on top (a shiny divine is 5 + 4 = 9). ST items are 7 (9 from the Shatters) instead of their tier. Only items your class can equip count. Neo Reality Reactor is +2 extra and Neo Laser Rifle +1, and each wearable item you forge is worth 1 (2 with Concentrated Soul Fire), once per item, and a forged UT/ST also scores its tier like a drop. Plus a bonus for a white bag from Oryx's Sanctuary (+1), "
                    + "Moonlight Village (+1, or +2 from Umi) or the Shatters (+2). A better modification later adds the difference, and your first shiny copy adds +4."));
            return;
        }
        if (c.charId() < 0) {
            String died = ctl.settings().getProperty("contest.diedTo");
            list.add(note(died != null
                    ? "Your last contest character died" + (died.isEmpty() ? "" : " to " + died) + " with " + c.total() + " points. Load in on a new one and press Use the character I'm on now."
                    : "No contest character yet: load in on your UPE character while the puppy is watching, then press Use the character I'm on now."));
        } else {
            JLabel l = new JLabel("<html>Contest character: <b>" + esc(c.who()) + "</b>  ·  <b>" + c.total() + " points</b> from "
                    + c.items() + (c.items() == 1 ? " item" : " items") + "</html>");
            l.setFont(Cute.font(Font.PLAIN, 13));
            l.setForeground(Pixel.INK);
            list.add(left(l));
            if ("true".equals(ctl.settings().getProperty("contest.only"))) {
                gap(4);
                list.add(small("Contest only is on: your normal bag posts are paused until the contest character dies or you turn it off."));
            }
            if (!DiscordWebhook.isDiscordWebhook(ctl.settings().getProperty("contest.webhook", ""))) {
                gap(4);
                list.add(small("No contest webhook yet, so drops are only scored here (and in the diary), not posted."));
            }
        }
        List<String[]> best = c.best();
        if (best.isEmpty()) return;
        gap(16);
        list.add(heading("this run's items", String.valueOf(best.size())));
        gap(6);
        for (String[] b : best) {
            JLabel l = new JLabel("<html><b>" + esc(b[0]) + "</b>  ·  " + b[1] + " pts" + (b[2].isEmpty() ? "" : "  <span style='color:"
                    + Theme.hex(0x8A7F99) + "'>" + esc(b[2]) + "</span>") + "</html>");
            l.setFont(Cute.font(Font.PLAIN, 13));
            l.setForeground(Pixel.INK);
            l.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
            list.add(left(l));
        }
        gap(10);
        list.add(small(String.format(Locale.ROOT, "Every scored drop is in upe-contest.txt next to the app.")));
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
