package whitebag;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import static whitebag.AboutMe.heading;
import static whitebag.AboutMe.left;
import static whitebag.AboutMe.note;
import static whitebag.AboutMe.small;

/**
 * The Realms tab: every open realm on every server (closest to closing first), alien invasions with their wormholes,
 * and realm bosses with their HP, from atti.network's realm tracker (used with atti's permission).
 */
final class RealmsPage extends MoreTabs.Page {
    RealmsPage() {
        AttiFeed.addListener(this::changed);
        AttiFeed.start();
        // the feed only checks while someone's looking (or pings are on); the "ago" times tick along too
        Timer t = new Timer(1000, e -> {
            if (isShowing()) AttiFeed.lookedAt = System.currentTimeMillis();
        });
        t.start();
        // the "Updated 5s ago" ticks every second
        Timer tick = new Timer(1000, e -> {
            if (isShowing() && top != null) top.setRight(updatedText());
        });
        tick.start();
    }

    private Pixel.Heading top;

    private static String updatedText() {
        long ok = AttiFeed.lastOk();
        return ok > 0 ? "Updated " + ago(ok) : (AttiFeed.problem() == null ? "Loading..." : "");
    }

        private static String ago(long ms) {
        if (ms <= 0) return "";
        long s = Math.max(0, (System.currentTimeMillis() - ms) / 1000);
        if (s < 60) return s + "s ago";
        if (s < 3600) return (s / 60) + "m ago";
        return (s / 3600) + "h ago";
    }

    private static Pixel.Button button(String text, Runnable r) {
        Pixel.Button b = new Pixel.Button(text, false);
        b.addActionListener(e -> r.run());
        return b;
    }

    private static JComponent row(Component... cs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        p.setOpaque(false);
        for (Component c : cs) p.add(c);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return left(p);
    }

    /** "The Lich King 38%, Beer God" */
    private static String bossText(List<AttiFeed.Boss> bs) {
        List<String> parts = new ArrayList<>();
        for (AttiFeed.Boss b : bs) {
            int pc = b.percent();
            parts.add(b.name + (pc >= 0 && pc < 100 ? " " + pc + "%" : ""));
        }
        return String.join(", ", parts);
    }

    @Override
    void fill() {
        long ok = AttiFeed.lastOk();
        String problem = AttiFeed.problem();
        top = heading("realms", updatedText());
        list.add(top);
        gap(6);
        list.add(note("Every open realm on every server (closest to closing first), alien invasions with their wormholes, and realm bosses, "
                + "from atti.network's realm tracker. It checks every 10 seconds while this tab is open."));
        gap(8);
        list.add(row(button("Refresh", AttiFeed::refresh), Box.createHorizontalStrut(8), button("Open atti.network", () -> {
            try {
                Desktop.getDesktop().browse(new java.net.URI(AttiFeed.PAGE_URL));
            } catch (Exception ignored) {
            }
        })));
        gap(6);
        Pixel.Check ping = new Pixel.Check("Ping me for what's ticked below", AttiFeed.pings());
        ping.addActionListener(e -> AttiFeed.setPings(ping.isSelected()));
        list.add(row(ping));
        if (problem != null) {
            gap(6);
            list.add(note(problem));
        }

        gap(12);
        String[] regions = {"ALL", "US", "EU", "AS", "AU"};
        int sel = java.util.Arrays.asList(regions).indexOf(AttiFeed.region());
        LogTabs.Segments reg = new LogTabs.Segments(new String[]{"All servers", "US", "EU", "Asia", "Australia"}, Math.max(0, sel),
                i -> AttiFeed.setRegion(regions[i]));
        reg.setAlignmentX(Component.LEFT_ALIGNMENT);
        reg.setMaximumSize(new Dimension(Integer.MAX_VALUE, reg.getPreferredSize().height));
        list.add(reg);

        // ---- what the pup itself saw in your realm
        if (AttiFeed.myRealm != null) {
            gap(12);
            JLabel mine = small("Wormholes you've seen in " + AttiFeed.myRealm);
            mine.setForeground(Pixel.PINK_INK);
            list.add(mine);
        }

        // ---- invasions
        List<AttiFeed.Invasion> inv = new ArrayList<>();
        for (AttiFeed.Invasion x : AttiFeed.invasions()) if (AttiFeed.inRegion(x.server)) inv.add(x);
        if (!inv.isEmpty()) {
            gap(14);
            list.add(heading(inv.get(0).event.toLowerCase(), null));
            gap(6);
            for (AttiFeed.Invasion x : inv) {
                JLabel title = small(x.server + " " + x.realm + (x.players >= 0 ? "  ·  " + x.players + " players" : ""));
                if (!x.wormholes.isEmpty() && !x.complete) title.setForeground(Pixel.PINK_INK);
                list.add(title);
                String prog = x.progress();
                String w = x.wormholeText();
                list.add(note((w.isEmpty() ? "No wormholes open yet" : "Wormholes: " + w) + (prog.isEmpty() ? "" : "  ·  " + prog)));
                gap(6);
            }
        }

        // ---- realms
        gap(10);
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Server", "Realm", "Players", "Closing", "Events"});
        int closedCount = 0;
        for (AttiFeed.Realm x : AttiFeed.realms()) {
            if (!AttiFeed.inRegion(x.server)) continue;
            if (x.closed) {
                closedCount++;
                continue;
            }
            String b = bossText(AttiFeed.bosses(x.key()));
            if (b.length() > 48) b = b.substring(0, 46) + "…";
            rows.add(new String[]{x.server, x.name, x.players < 0 ? "?" : String.valueOf(x.players),
                    x.percent < 0 ? "?" : x.percent + "%" + (x.percent >= 90 ? "  soon!" : ""), b});
        }
        if (rows.size() == 1) {
            java.util.Set<String> servers = new java.util.TreeSet<>();
            for (AttiFeed.Realm x : AttiFeed.realms()) if (!x.closed) servers.add(x.server);
            list.add(note(ok == 0 ? "Fetching the realms..." : "atti.network isn't tracking any open realms on these servers right now"
                    + (servers.isEmpty() ? "." : ". It has realms on: " + String.join(", ", servers) + ".")));
        } else {
            list.add(table(rows));
            if (closedCount > 0) {
                gap(6);
                list.add(note(closedCount + (closedCount == 1 ? " realm here is closed and not shown." : " realms here are closed and not shown.")));
            }
        }
        latest();
        watching();
    }

    // ---------------------------------------------------------------- like the Events tab: latest + tick boxes

    private static String time(long ms) {
        return new java.text.SimpleDateFormat("h:mm a", java.util.Locale.ROOT).format(new java.util.Date(ms));
    }

    private void latest() {
        gap(16);
        list.add(heading("latest", null));
        gap(6);
        boolean everything = AttiFeed.showAll();
        LogTabs.Segments show = new LogTabs.Segments(new String[]{"Only what I'm watching", "Everything"}, everything ? 1 : 0,
                i -> AttiFeed.setShowAll(i == 1));
        show.setAlignmentX(Component.LEFT_ALIGNMENT);
        show.setMaximumSize(new Dimension(Integer.MAX_VALUE, show.getPreferredSize().height));
        list.add(show);
        gap(8);
        int n = 0;
        for (AttiFeed.Sighting s : AttiFeed.sightings()) {
            if (!AttiFeed.inRegion(s.server)) continue;
            boolean ticked = AttiFeed.ticked(s.name);
            if (!everything && !ticked) continue;
            JLabel title = small(time(s.atMs) + "   " + s.name);
            if (ticked) title.setForeground(Pixel.PINK_INK);
            list.add(title);
            List<String> d = new ArrayList<>();
            d.add(s.where());
            if (!s.detail.isEmpty()) d.add(s.detail);
            if (s.players >= 0) d.add(s.players + (s.players == 1 ? " player" : " players"));
            list.add(note(String.join("  ·  ", d)));
            gap(6);
            if (++n >= 25) break;
        }
        if (n == 0) list.add(note(AttiFeed.lastOk() == 0 ? "Waiting for the first check..."
                : everything ? "Nothing new yet. Realm events, wormholes and realms about to close show up here as they appear."
                : "Nothing you're watching has shown up yet. Tick some below."));
    }

    /** Kept between redraws so typing isn't lost. */
    private final JTextField search = Pixel.field("", 18, "Find an event");

    {
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent ev) { changed(); }
            public void removeUpdate(javax.swing.event.DocumentEvent ev) { changed(); }
            public void changedUpdate(javax.swing.event.DocumentEvent ev) { }
        });
    }

    /** Tick boxes in two columns. */
    private void ticks(List<String> names) {
        String q = EventTracker.norm(search.getText());
        JPanel grid = new JPanel(new GridLayout(0, 2, 12, 0));
        grid.setOpaque(false);
        int n = 0;
        for (String name : names) {
            if (!q.isEmpty() && !EventTracker.norm(name).contains(q)) continue;
            Pixel.Check c = new Pixel.Check(name, AttiFeed.ticked(name));
            c.addActionListener(x -> AttiFeed.setTicked(name, c.isSelected()));
            grid.add(c);
            n++;
        }
        if (n == 0) {
            list.add(note(q.isEmpty() ? "The list hasn't loaded yet." : "Nothing matches \"" + search.getText().trim() + "\"."));
            return;
        }
        grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, grid.getPreferredSize().height));
        list.add(left(grid));
    }

    private void watching() {
        gap(16);
        list.add(heading("what to watch", null));
        gap(6);
        list.add(note("Ticked ones show in pink in Latest. With pings on, each one that shows up in your region plays a sound and goes in the pup diary, "
                + "and it keeps checking every 30 seconds while this tab is closed."));
        gap(8);
        Pixel.Check snd = new Pixel.Check("Play a sound", AttiFeed.sound());
        snd.addActionListener(x -> AttiFeed.setSound(snd.isSelected()));
        list.add(row(snd));
        gap(10);
        list.add(small("Invasions and realms"));
        gap(4);
        List<String> fixed = new ArrayList<>(java.util.Arrays.asList(AttiFeed.WORMHOLES));
        fixed.add(AttiFeed.CLOSING);
        JPanel grid = new JPanel(new GridLayout(0, 2, 12, 0));
        grid.setOpaque(false);
        for (String name : fixed) {
            Pixel.Check c = new Pixel.Check(name, AttiFeed.ticked(name));
            c.addActionListener(x -> AttiFeed.setTicked(name, c.isSelected()));
            grid.add(c);
        }
        grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, grid.getPreferredSize().height));
        list.add(left(grid));
        gap(12);
        list.add(small("Realm events and bosses"));
        gap(4);
        list.add(row(search, Box.createHorizontalStrut(8), button("Tick all", () -> AttiFeed.setAllBosses(true)), Box.createHorizontalStrut(8),
                button("Untick all", () -> AttiFeed.setAllBosses(false))));
        gap(6);
        ticks(AttiFeed.bossNames());
    }
}
