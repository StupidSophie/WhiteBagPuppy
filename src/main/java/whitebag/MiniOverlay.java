package whitebag;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The mini overlay: a small always-on-top window with this run's timer, your last white bag, fame per hour, bags this
 * session and your next badge. Turned on and off with the switch on Home ("overlay.on"); the × hides it (and turns the
 * switch off), the – folds it down to its title. "Only in dungeons" hides it in the Nexus, vault and realm.
 */
final class MiniOverlay {
    interface Settings {
        String get(String key, String fallback);

        void set(String key, String value);
    }

    private static final int W = 410, SMALL_H = 66, MIN_W = 300, MAX_W = 1000;
    /** How wide it is before scaling (drag the corner sideways for more room for the words). */
    private int width = W;

    /** The rows you can show (right-click > Rows), in the order they're drawn. */
    private static final String[][] ROWS = {
            {"run", "This run"}, {"dps", "Live DPS (top 3)"}, {"rundps", "Run DPS"}, {"white", "Last white"}, {"shiny", "Shinies"}, {"dry", "Dry streak"}, {"fame", "Fame per hour"},
            {"bags", "Bags this session"}, {"boost", "Loot boosts"}, {"mods", "Dungeon mods"}, {"dust", "Dust"}, {"quests", "Tinker quests"}, {"badge", "Next badge"}};
    /** Green, red and purple enchanting dust for the Dust row (null when the game hasn't said). */
    private volatile int[] dustNow;
    private final java.util.Set<String> shownRows = new java.util.LinkedHashSet<>();
    /** Flash the overlay (and make the pup jump) when a white bag drops. */
    private boolean flash;
    /** Show your own DPS next to the zone when the overlay is folded to just its title. */
    private boolean foldedDps;
    private long flashUntil;
    private Timer flashTimer;
    /** The glow colour of the current flash: pink for a white bag, gold for a shiny, red for danger. */
    private volatile Color flashColour = Pixel.PINK;
    /** "Careful: Septavius the Ghost God (killed you 2×)" in red under the title for a little while. */
    private volatile String dangerText = "";
    private volatile long dangerUntil;
    private volatile String shinyText = "";
    private long shinyAt;
    private volatile String dry = "";

    private final Supplier<List<Account>> accounts;
    private final Settings settings;
    /** Called when the × turns the overlay off, so the switch on Home can follow. */
    Runnable onClosed = () -> { };
    /** Whether the pup is watching the game right now. */
    java.util.function.BooleanSupplier watching = () -> true;

    private JWindow window;
    private final Painter painter = new Painter();
    private boolean on, onlyDungeons, small, seeThrough;
    /** How big the overlay is drawn (drag the bottom-right corner): 1 = normal. */
    private double scale = 1;
    private static final double MIN_SCALE = 0.7, MAX_SCALE = 2.5;
    private Timer tick;
    private boolean rounded;

    // this session
    private final Map<BagType, Integer> bags = new EnumMap<>(BagType.class);
    private int bagCount;
    private String lastWhite = "";
    private long lastWhiteAt;
    private volatile String nextBadge = "";
    private long badgeCheckedAt;

    MiniOverlay(Supplier<List<Account>> accounts, Settings settings) {
        this.accounts = accounts;
        this.settings = settings;
        on = "true".equals(settings.get("overlay.on", "false"));
        onlyDungeons = "true".equals(settings.get("overlay.dungeonsOnly", "false"));
        small = "true".equals(settings.get("overlay.small", "false"));
        seeThrough = "true".equals(settings.get("overlay.seeThrough", "false"));
        flash = !"false".equals(settings.get("overlay.flash", "true"));
        foldedDps = !"false".equals(settings.get("overlay.foldedDps", "true"));
        String rows = settings.get("overlay.rows", "run,dps,rundps,white,dry,fame,bags,boost,dust,quests,badge");
        for (String r : rows.split(",")) if (!r.trim().isEmpty()) shownRows.add(r.trim());
        if (!"true".equals(settings.get("overlay.questsAdded", "false"))) { // new row (2.17.0): on for everyone once
            shownRows.add("quests");
            settings.set("overlay.questsAdded", "true");
        }
        if (!"true".equals(settings.get("overlay.dpsAdded", "false"))) { // new rows (2.16.4): on for everyone once
            shownRows.add("dps");
            shownRows.add("rundps");
            settings.set("overlay.dpsAdded", "true");
        }
        if (!"true".equals(settings.get("overlay.shinyAdded", "false"))) { // new row (2.21.0): on for everyone once
            shownRows.add("shiny");
            settings.set("overlay.shinyAdded", "true");
        }
        if (!"true".equals(settings.get("overlay.dustAdded", "false"))) { // new row (2.16.2): on for everyone once
            shownRows.add("dust");
            settings.set("overlay.dustAdded", "true");
        }
        try {
            scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, Double.parseDouble(settings.get("overlay.scale", "1"))));
            width = Math.max(MIN_W, Math.min(MAX_W, Integer.parseInt(settings.get("overlay.width", String.valueOf(W)))));
        } catch (NumberFormatException e) {
            scale = 1;
        }
    }

    boolean isOn() {
        return on;
    }

    boolean onlyDungeons() {
        return onlyDungeons;
    }

    /** Starts the once-a-second refresh (call on the Swing thread once the app is up). */
    void start() {
        if (tick != null) return;
        tick = new Timer(1000, e -> refresh());
        tick.start();
        refresh();
    }

    void setOn(boolean v) {
        on = v;
        settings.set("overlay.on", String.valueOf(v));
        refresh();
    }

    void setOnlyDungeons(boolean v) {
        onlyDungeons = v;
        settings.set("overlay.dungeonsOnly", String.valueOf(v));
        refresh();
    }

    /** A bag was looted (any thread). */
    void noteBag(BagDrop drop) {
        SwingUtilities.invokeLater(() -> {
            bagCount++;
            bags.merge(drop.type, 1, Integer::sum);
            if (drop.type == BagType.WHITE) {
                List<String> names = drop.itemNames();
                lastWhite = names.isEmpty() ? "White bag" : names.get(0);
                lastWhiteAt = System.currentTimeMillis();
                if (flash) startFlash(Pixel.PINK);
            }
            badgeCheckedAt = 0; // a bag can bring a badge closer
            painter.repaint();
        });
    }

    /** Something that killed you before is here: a red flash and a warning under the title (any thread). */
    void danger(String text) {
        SwingUtilities.invokeLater(() -> {
            dangerText = text;
            dangerUntil = System.currentTimeMillis() + 12_000;
            startFlash(new Color(0xE0483E));
        });
    }

    /** A shiny item dropped: a gold flash, and the Shinies row counts it (any thread). */
    void shiny() {
        SwingUtilities.invokeLater(() -> {
            shinyAt = 0;
            if (flash) startFlash(new Color(0xE8B12A));
            painter.repaint();
        });
    }

    void startFlash(Color c) {
        flashColour = c;
        startFlash();
    }

    /** A few seconds of glow and a jumping pup. */
    void startFlash() {
        flashUntil = System.currentTimeMillis() + 3500;
        if (flashTimer == null) {
            flashTimer = new Timer(40, e -> {
                if (System.currentTimeMillis() > flashUntil) ((Timer) e.getSource()).stop();
                painter.repaint();
            });
        }
        flashTimer.restart();
    }

    /** 0..1 while flashing: how bright the glow is right now. */
    private double flashLevel() {
        long left = flashUntil - System.currentTimeMillis();
        if (left <= 0) return 0;
        double t = (3500 - left) / 1000.0;
        return Math.min(1, left / 800.0) * (0.55 + 0.45 * Math.sin(t * Math.PI * 3));
    }

    private int fullHeight() {
        int n = 0;
        for (String[] r : ROWS) {
            if (!shownRows.contains(r[0])) continue;
            if (r[0].equals("dps")) n += 4; // the boss, then the top 3
            else if (r[0].equals("quests")) n += Math.max(1, pinnedQuests(current()).size()) + (resetText(current()) == null ? 0 : 1); // a line per pinned quest, and the reset
            else n++;
        }
        return n == 0 ? SMALL_H : 89 + 25 * n;
    }

    boolean foldedDps() {
        return foldedDps;
    }

    /** Your DPS next to the zone on the folded overlay, on or off (Home and the overlay's menu). */
    void setFoldedDps(boolean on) {
        foldedDps = on;
        settings.set("overlay.foldedDps", String.valueOf(on));
        onFoldedDpsChanged.accept(on);
        painter.repaint();
    }

    java.util.function.Consumer<Boolean> onFoldedDpsChanged = on -> { };

    /**
     * "Sophie  ·  30,400 dmg  ·  2,069/s  ·  " for the folded overlay: your name, then your damage and damage per second on
     * the fight going on (else over the whole run). Just the name before you've hit anything.
     */
    private String myDps() {
        Account a = current();
        if (a == null) return "";
        MeTracker.Thing me = a.me.character();
        String name = me == null ? "" : DpsTracker.cleanName(me.s(packets.data.enums.StatType.NAME_STAT));
        String head = name == null || name.isEmpty() ? "" : name + "  ·  ";
        DpsTracker.Live live = live(a);
        if (live == null) return head;
        DpsTracker.Board b = live.fight != null && live.active ? live.fight : live.run;
        if (b == null) return head;
        for (DpsTracker.Row r : b.rows) {
            if (r.you) return head + String.format(java.util.Locale.ROOT, "%,d dmg  ·  %,.0f/s  ·  ", r.damage, r.dps);
        }
        return head;
    }

    /** Whether this row is on (for the switch on Home). */
    boolean showing(String id) {
        return shownRows.contains(id);
    }

    /** Turns a row on or off (from Home); the overlay's own menu does the same. */
    void show(String id, boolean on) {
        SwingUtilities.invokeLater(() -> setRow(id, on));
    }

    /** Runs when a row is turned on or off from the overlay's menu, so the switch on Home can follow. */
    java.util.function.BiConsumer<String, Boolean> onRowChanged = (id, on) -> { };

    private void setRow(String id, boolean show) {
        if (show) shownRows.add(id);
        else shownRows.remove(id);
        StringBuilder b = new StringBuilder();
        for (String[] r : ROWS) {
            if (!shownRows.contains(r[0])) continue;
            if (b.length() > 0) b.append(',');
            b.append(r[0]);
        }
        settings.set("overlay.rows", b.toString());
        applySize();
        onRowChanged.accept(id, show);
    }

    // ---------------------------------------------------------------- what's shown

    /** The account you're playing on: the one that changed map most recently. */
    private Account current() {
        Account best = null;
        for (Account a : accounts.get()) if (a.visitStart() > 0 && (best == null || a.visitStart() > best.visitStart())) best = a;
        return best;
    }

    private void refresh() {
        Account a = current();
        String map = a == null ? null : a.visitMap();
        boolean show = on && (!onlyDungeons || (map != null && RunLog.isDungeon(map)));
        if (!show) {
            if (window != null) window.setVisible(false);
            return;
        }
        if (window == null) build();
        if (!window.isVisible()) window.setVisible(true);
        int wantH = (int) Math.round((small ? SMALL_H : fullHeight()) * scale);
        if (window.getHeight() != wantH) applySize(); // e.g. a quest was pinned or unpinned
        long now = System.currentTimeMillis();
        if (a != null && now - badgeCheckedAt > 60_000) {
            badgeCheckedAt = now;
            String key = a.key();
            if (key != null) {
                Thread t = new Thread(() -> {
                    nextBadge = closestBadge(key, a);
                    dry = dryStreak(a);
                }, "overlay-badge");
                t.setDaemon(true);
                t.start();
            }
        }
        painter.repaint();
    }

    private static String closestBadge(String key, Account a) {
        try {
            Badges.Stats s = Badges.stats(key, a::isMine);
            Map<String, Badges.Unlock> got = Badges.unlocked(key);
            Badges.Badge best = null;
            double bestP = -1;
            for (Badges.Badge b : Badges.ALL) {
                if (got.containsKey(b.id)) continue;
                long have = b.count.of(s);
                double p = Math.min(1, have / (double) b.need);
                if (p > bestP) {
                    bestP = p;
                    best = b;
                }
            }
            if (best == null) return "Every badge unlocked!";
            long left = Math.max(0, best.need - best.count.of(s));
            return best.name + (left <= 0 ? "  ·  ready!" : "  ·  " + String.format(Locale.ROOT, "%,d", left) + " to go");
        } catch (Throwable e) {
            return "";
        }
    }

    /** "12 runs dry in Snake Pit  ·  longest 30", for the dungeon you're in (or all dungeons). */
    private static String dryStreak(Account a) {
        try {
            List<RunLog.Run> runs = new java.util.ArrayList<>();
            for (RunLog.Run r : RunLog.all()) if (a.isMine(r.account)) runs.add(r);
            List<LootStats.Entry> bags = new java.util.ArrayList<>();
            for (LootStats.Entry e : LootStats.all()) if (a.isMine(e.account)) bags.add(e);
            Map<String, DryStreak.Streak> all = DryStreak.compute(runs, bags);
            String map = a.visitMap();
            DryStreak.Streak s = map != null && RunLog.isDungeon(map) ? all.get(map) : null;
            String where = s != null ? " in " + map : " (all dungeons)";
            if (s == null && map != null && RunLog.isDungeon(map)) return "First run here!";
            if (s == null) s = all.get("");
            if (s == null || s.runs == 0) return "-";
            return s.current + (s.current == 1 ? " run" : " runs") + " dry" + where + "  ·  longest " + s.longest;
        } catch (Throwable e) {
            return "";
        }
    }

    private static String left(long ends) {
        long s = Math.max(0, (ends - System.currentTimeMillis()) / 1000);
        if (s >= 3600) return s / 3600 + "h " + two(s / 60 % 60) + "m";
        return s / 60 + ":" + two(s % 60);
    }

    /** Whose line is yours in the top 3 (drawn in pink). */
    static final String YOU_MARK = "\u0001";

    /** The Live DPS block: the boss you're fighting (or the last one), then the three biggest hitters. */
    private List<String[]> topThree(Account a) {
        List<String[]> out = new java.util.ArrayList<>();
        DpsTracker.Live live = live(a);
        DpsTracker.Board b = live == null ? null : live.fight;
        out.add(new String[]{"Live DPS", b == null ? (live == null ? "No damage seen yet this run" : "Nothing hit lately")
                : b.title + (live.active ? "" : b.killed ? "  (dead)" : "  (ended)")});
        String[] places = {"1st", "2nd", "3rd"};
        for (int i = 0; i < 3; i++) {
            DpsTracker.Row r = b != null && i < b.rows.size() ? b.rows.get(i) : null;
            if (r == null) {
                out.add(new String[]{"   " + places[i], "-"});
                continue;
            }
            String line = String.format(java.util.Locale.ROOT, "%s   %,.0f/s   %.0f%%", r.name, r.dps, r.percent);
            out.add(new String[]{"   " + places[i], (r.you ? YOU_MARK : "") + line});
        }
        // not in the top 3: say where you are
        if (b != null) {
            for (int i = 3; i < b.rows.size(); i++) {
                DpsTracker.Row r = b.rows.get(i);
                if (r.you) {
                    String[] last = out.get(3);
                    out.set(3, new String[]{last[0], last[1] + "      (you: #" + (i + 1) + ", " + String.format(java.util.Locale.ROOT, "%,.0f/s)", r.dps)});
                }
            }
        }
        return out;
    }

    private DpsTracker.Live live(Account a) {
        long now = System.currentTimeMillis();
        if (a != liveFor || now - liveAt > 700) { // worked out at most a few times a second (the flash redraws much faster)
            cachedLive = a == null ? null : a.dps.live();
            liveFor = a;
            liveAt = now;
        }
        return cachedLive;
    }

    private DpsTracker.Live cachedLive;
    private Account liveFor;
    private long liveAt;

    /** "2,340/s #1/4 38% · Malphas" (your part of the fight going on, or the last one) or the whole run's. */
    private String dpsText(Account a, boolean wholeRun) {
        DpsTracker.Live live = live(a);
        DpsTracker.Board b = live == null ? null : wholeRun ? live.run : live.fight;
        if (b == null) return wholeRun ? "No damage yet this run" : "No boss fight yet";
        DpsTracker.Row mine = null;
        int rank = 0;
        for (int i = 0; i < b.rows.size(); i++) {
            if (b.rows.get(i).you) {
                mine = b.rows.get(i);
                rank = i + 1;
            }
        }
        // the numbers first, so a long boss name is what gets cut off when the overlay is narrow
        String who = wholeRun ? "" : "  ·  " + b.title + (live.active ? "" : b.killed ? " (dead)" : " (ended)");
        if (mine == null) return "You haven't hit it" + who;
        return String.format(java.util.Locale.ROOT, "%,.0f/s   #%d/%d   %.0f%%", mine.dps, rank, b.rows.size(), mine.percent) + who;
    }

    /** "3 to bring: Potion of Life, Pumpkin Treat" across your Tinker quests, or "ready to hand in". */
    /** Quests you've pinned on the Tinker Quests tab (not hidden, not done), at most 4. */
    private static List<packets.data.QuestData> pinnedQuests(Account a) {
        List<packets.data.QuestData> out = new java.util.ArrayList<>();
        if (a == null) return out;
        String acc = a.me.accountId();
        List<packets.data.QuestData> all = new java.util.ArrayList<>(a.me.quests());
        all.sort(java.util.Comparator.comparingInt(AboutMe::questRank)); // daily ones first
        for (packets.data.QuestData q : all) {
            String key = acc + "/" + AboutMe.questKey(q);
            if (q.completed || !AboutMe.Ticks.flag(key + "#pin") || AboutMe.Ticks.flag(key + "#hide")) continue;
            out.add(q);
            if (out.size() == 4) break;
        }
        return out;
    }

    /** "Potion of Life ×2, Pumpkin Treat" still to bring for one quest, or "Ready to hand in!". */
    private static String questLeft(Account a, packets.data.QuestData q) {
        if (q.requirements == null || q.requirements.length == 0) return "-";
        java.util.Map<Integer, Integer> pool = new java.util.HashMap<>();
        for (int id : a.me.carried()) pool.merge(id, 1, Integer::sum);
        String key = a.me.accountId() + "/" + AboutMe.questKey(q);
        java.util.Map<String, Integer> left = new java.util.LinkedHashMap<>();
        for (int i = 0; i < q.requirements.length; i++) {
            int id = q.requirements[i];
            if (id <= 0) continue;
            Integer n = pool.get(id);
            if (n != null && n > 0) {
                pool.put(id, n - 1);
                continue;
            }
            if (AboutMe.Ticks.has(key, i)) continue;
            String nm;
            try {
                nm = ItemInfo.of(id).name;
            } catch (Throwable e) {
                nm = "Item #" + id;
            }
            left.merge(nm, 1, Integer::sum);
        }
        if (left.isEmpty()) return "Ready to hand in!";
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : left.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append(e.getValue() > 1 ? " ×" + e.getValue() : "");
        }
        return sb.toString();
    }

    /** "12 found  ·  last: Doom Bow (Shiny), 3d ago" (worked out at most every 15 seconds). */
    private String shinyText(Account a) {
        long now = System.currentTimeMillis();
        if (now - shinyAt < 15_000) return shinyText;
        shinyAt = now;
        int n = 0;
        long last = 0;
        String lastName = "";
        try {
            for (LootStats.Entry e : LootStats.all()) {
                if (a != null && !a.isMine(e.account)) continue;
                for (int id : e.items) {
                    if (!LootBook.isShiny(id)) continue;
                    n++;
                    if (e.at >= last) {
                        last = e.at;
                        lastName = ItemInfo.of(id).name;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        shinyText = n == 0 ? "None yet" : String.format(java.util.Locale.ROOT, "%,d found  ·  last: %s, %s", n, lastName, agoDays(last * 1000));
        return shinyText;
    }

    private static String agoDays(long at) {
        long m = (System.currentTimeMillis() - at) / 60_000;
        if (m < 60) return m < 1 ? "just now" : m + "m ago";
        if (m < 60 * 24) return m / 60 + "h ago";
        return m / 60 / 24 + "d ago";
    }

    /** "in 3h 12m" until the daily quests reset, "passed: see the Tinkerer" after, or null before quests are known. */
    private static String resetText(Account a) {
        if (a == null || a.me.questsAt() == 0) return null;
        long left = AboutMe.dailyReset(a.me.questsAt()) - System.currentTimeMillis();
        return left > 0 ? "in " + AboutMe.countdown(left) : "passed: see the Tinkerer";
    }

    private static String questsText(Account a) {
        if (a == null) return "-";
        List<packets.data.QuestData> quests = a.me.quests();
        if (a.me.questsAt() == 0) return "Open the Tinkerer's quests once";
        java.util.Map<Integer, Integer> pool = new java.util.HashMap<>();
        for (int id : a.me.carried()) pool.merge(id, 1, Integer::sum);
        List<String> missing = new java.util.ArrayList<>();
        int open = 0, ready = 0;
        // pinned quests only, if you've pinned any; hidden ones never
        boolean anyPinned = false;
        for (packets.data.QuestData q : quests) if (AboutMe.Ticks.flag(a.me.accountId() + "/" + AboutMe.questKey(q) + "#pin")) anyPinned = true;
        for (packets.data.QuestData q : quests) {
            if (q.completed || q.requirements == null) continue;
            String key = a.me.accountId() + "/" + AboutMe.questKey(q);
            if (AboutMe.Ticks.flag(key + "#hide")) continue;
            if (anyPinned && !AboutMe.Ticks.flag(key + "#pin")) continue;
            open++;
            int left = 0;
            for (int i = 0; i < q.requirements.length; i++) {
                int id = q.requirements[i];
                if (id <= 0) continue;
                Integer n = pool.get(id);
                if (n != null && n > 0) {
                    pool.put(id, n - 1);
                    continue;
                }
                if (AboutMe.Ticks.has(key, i)) continue;
                left++;
                String nm;
                try {
                    nm = ItemInfo.of(id).name;
                } catch (Throwable e) {
                    nm = "Item #" + id;
                }
                missing.add(nm);
            }
            if (left == 0) ready++;
        }
        if (open == 0) return "All done!";
        if (missing.isEmpty()) return "Everything's ready to hand in!";
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String m : missing) counts.merge(m, 1, Integer::sum);
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append(e.getValue() > 1 ? " ×" + e.getValue() : "");
        }
        return missing.size() + " to bring" + (ready > 0 ? " (" + ready + " ready)" : "") + ": " + sb;
    }

    /** "1,646 · 2,000 · 542" (green, red, purple): the dots are drawn with it. */
    private String dustText(Account a) {
        int[] d = null;
        MeTracker.Thing c = a == null ? null : a.me.character();
        if (c != null) {
            String s = c.s(packets.data.enums.StatType.DUST_STAT);
            if (!s.isEmpty()) {
                d = new int[3];
                for (String part : s.split(",")) {
                    String[] kv = part.split(":");
                    if (kv.length != 2) continue;
                    try {
                        int i = Integer.parseInt(kv[0].trim()), n = Integer.parseInt(kv[1].trim());
                        if (i == 1) d[0] = n; // green
                        else if (i == 5) d[1] = n; // red
                        else if (i == 4) d[2] = n; // purple
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        dustNow = d;
        return d == null ? "-" : "";
    }

    private String runText() {
        Account a = current();
        String map = a == null ? null : a.visitMap();
        if (map == null) return "Not in game yet";
        long secs = Math.max(0, (System.currentTimeMillis() - a.visitStart()) / 1000);
        return RunLog.label(map) + "  ·  " + (secs >= 3600 ? secs / 3600 + ":" + two(secs / 60 % 60) : String.valueOf(secs / 60)) + ":" + two(secs % 60);
    }

    private String[][] rows() {
        Account a = current();
        String map = a == null ? null : a.visitMap();
        String run;
        if (map == null) run = "Not in game yet";
        else {
            long secs = Math.max(0, (System.currentTimeMillis() - a.visitStart()) / 1000);
            run = RunLog.label(map) + "  ·  " + (secs >= 3600 ? secs / 3600 + ":" + two(secs / 60 % 60) : String.valueOf(secs / 60)) + ":" + two(secs % 60);
        }
        String white = lastWhite.isEmpty() ? "None yet this session" : lastWhite + "  ·  " + ago(lastWhiteAt);
        String fame = "-";
        long[] fs = a == null ? null : a.me.fameSession();
        if (fs != null && fs[1] > 60_000) {
            fame = String.format(Locale.ROOT, "%,d   (%+,d this session)", Math.round(fs[0] / (fs[1] / 3_600_000.0)), fs[0]);
        }
        StringBuilder b = new StringBuilder(String.valueOf(bagCount));
        if (bagCount > 0) {
            StringBuilder kinds = new StringBuilder();
            for (BagType t : new BagType[]{BagType.WHITE, BagType.RED, BagType.ORANGE, BagType.GOLD}) {
                Integer n = bags.get(t);
                if (n == null) continue;
                if (kinds.length() > 0) kinds.append(", ");
                kinds.append(n).append(" ").append(t.name().toLowerCase(Locale.ROOT));
            }
            if (kinds.length() > 0) b.append("   (").append(kinds).append(")");
        }
        String boost = "None active";
        if (a != null) {
            long now = System.currentTimeMillis();
            long dl = a.me.lootDropLeft(), tl = a.me.lootTierLeft();
            String ld = dl > 0 ? "Drop " + left(now + dl) : "";
            String lt = tl > 0 ? "Tier " + left(now + tl) : "";
            if (!ld.isEmpty() || !lt.isEmpty()) boost = ld + (!ld.isEmpty() && !lt.isEmpty() ? "  ·  " : "") + lt
                    + (a.me.lootPaused() ? "  (paused here)" : "");
        }
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (String[] r : ROWS) {
            if (!shownRows.contains(r[0])) continue;
            String v;
            switch (r[0]) {
                case "run": v = run; break;
                case "white": v = white; break;
                case "dry": v = dry.isEmpty() ? "-" : dry; break;
                case "fame": v = fame; break;
                case "bags": v = b.toString(); break;
                case "boost": v = boost; break;
                case "mods": {
                    DungeonMods.Here h = a == null ? null : a.mods;
                    v = h == null || h.isEmpty() ? "None here" : h.totalText() + "  (" + h.names() + ")";
                    break;
                }
                case "dust": v = dustText(a); break;
                case "shiny": v = shinyText(a); break;
                case "quests": {
                    List<packets.data.QuestData> pinned = pinnedQuests(a);
                    String reset = resetText(a);
                    if (pinned.isEmpty()) {
                        out.add(new String[]{r[1], questsText(a)});
                        if (reset != null) out.add(new String[]{"Daily reset", reset});
                        continue;
                    }
                    // each pinned quest on its own line: its name, then what's left to bring
                    for (packets.data.QuestData q : pinned) {
                        String n = q.name == null || q.name.isEmpty() ? "Quest" : q.name;
                        out.add(new String[]{n, questLeft(a, q)});
                    }
                    if (reset != null) out.add(new String[]{"Daily reset", reset});
                    continue;
                }
                case "dps":
                    for (String[] line : topThree(a)) out.add(line);
                    continue;
                case "rundps": v = dpsText(a, true); break;
                default: v = nextBadge.isEmpty() ? "-" : nextBadge;
            }
            out.add(new String[]{r[1], v});
        }
        return out.toArray(new String[0][]);
    }

    private static String two(long v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    private static String ago(long at) {
        long m = (System.currentTimeMillis() - at) / 60_000;
        return m < 1 ? "just now" : m < 60 ? m + " min ago" : m / 60 + " h ago";
    }

    // ---------------------------------------------------------------- the window

    private void build() {
        window = new JWindow();
        window.setAlwaysOnTop(true);
        window.setFocusableWindowState(false); // never takes the keyboard away from the game
        // round corners need a see-through window; where the desktop can't do that, it's a plain rectangle
        try {
            GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            if (!Boolean.getBoolean("whitebag.squareOverlay")
                    && gd.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
                window.setBackground(new Color(0, 0, 0, 0));
                rounded = true;
            }
        } catch (Exception ignored) {
        }
        window.setContentPane(painter);
        applySize();
        applyOpacity();
        int x = parse(settings.get("overlay.x", ""), -1), y = parse(settings.get("overlay.y", ""), -1);
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int ww = window.getWidth();
        if (x < 0 || y < 0 || !screen.intersects(new Rectangle(x, y, ww, SMALL_H))) {
            x = screen.x + screen.width - ww - 24;
            y = screen.y + 24;
        }
        // keep it on the screen (it may have grown since last time)
        x = Math.max(screen.x, Math.min(x, screen.x + screen.width - ww));
        y = Math.max(screen.y, Math.min(y, screen.y + screen.height - Math.min(window.getHeight(), screen.height)));
        window.setLocation(x, y);
        MouseAdapter drag = new MouseAdapter() {
            private Point grab;
            private boolean resizing;

            /** Is this point (in window pixels) on the resize corner? */
            private boolean onGrip(Point p) {
                return p.x > window.getWidth() - 20 && p.y > window.getHeight() - 20;
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger()) {
                    menu(e);
                    return;
                }
                if (onGrip(e.getPoint())) {
                    resizing = true;
                    return;
                }
                double ux = e.getX() / scale, uy = e.getY() / scale; // in unscaled overlay pixels
                if (uy < 40 && ux > width - 64) {
                    if (ux < width - 40) { // –
                        small = !small;
                        settings.set("overlay.small", String.valueOf(small));
                        applySize();
                    } else { // ×
                        setOn(false);
                        onClosed.run();
                    }
                    return;
                }
                grab = e.getPoint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) menu(e);
                if (grab != null) {
                    settings.set("overlay.x", String.valueOf(window.getX()));
                    settings.set("overlay.y", String.valueOf(window.getY()));
                }
                if (resizing) {
                    settings.set("overlay.scale", String.format(java.util.Locale.ROOT, "%.3f", scale));
                    settings.set("overlay.width", String.valueOf(width));
                }
                grab = null;
                resizing = false;
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                painter.setCursor(Cursor.getPredefinedCursor(onGrip(e.getPoint()) ? Cursor.SE_RESIZE_CURSOR : Cursor.DEFAULT_CURSOR));
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (resizing) {
                    // up and down sets how big everything is; sideways sets how much room the words get
                    double sy = e.getY() / (double) (small ? SMALL_H : fullHeight());
                    scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, sy));
                    width = (int) Math.max(MIN_W, Math.min(MAX_W, Math.round(e.getX() / scale)));
                    applySize();
                    return;
                }
                if (grab == null) return;
                Point p = e.getLocationOnScreen();
                window.setLocation(p.x - grab.x, p.y - grab.y);
            }
        };
        painter.addMouseListener(drag);
        painter.addMouseMotionListener(drag);
        painter.setToolTipText("Drag to move, drag the bottom-right corner to resize. Right-click for options.");
    }

    private JDialog menuWindow;

    /** The options, drawn like the rest of the app: a card with the pup's tick boxes. */
    private void menu(MouseEvent e) {
        if (menuWindow != null) menuWindow.dispose();
        JDialog d = new JDialog((Frame) null);
        menuWindow = d;
        d.setUndecorated(true);
        d.setAlwaysOnTop(true);
        boolean see = false;
        try {
            GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            if (rounded && gd.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
                d.setBackground(new Color(0, 0, 0, 0));
                see = true;
            }
        } catch (Exception ignored) {
        }
        final boolean roundCard = see;
        JPanel card = new JPanel() {
            @Override
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Cute.smooth(g);
                int w = getWidth(), h = getHeight();
                if (!roundCard) {
                    g.setColor(Pixel.SHEET);
                    g.fillRect(0, 0, w, h);
                }
                RoundRectangle2D r = new RoundRectangle2D.Double(1, 1, w - 3, h - 3, roundCard ? 18 : 4, roundCard ? 18 : 4);
                g.setColor(Pixel.SHEET);
                g.fill(r);
                g.setColor(Pixel.LINE);
                g.setStroke(new BasicStroke(2f));
                g.draw(r);
                g.dispose();
            }
        };
        card.setOpaque(false);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));

        card.add(menuHeading("Show"));
        for (String[] r : ROWS) {
            Pixel.Check c = new Pixel.Check(r[1], shownRows.contains(r[0]));
            c.addActionListener(x -> setRow(r[0], c.isSelected()));
            card.add(menuItem(c));
        }
        card.add(menuDivider());
        card.add(menuHeading("Options"));
        Pixel.Check flashBox = new Pixel.Check("Flash on a white bag", flash);
        flashBox.addActionListener(x -> {
            flash = flashBox.isSelected();
            settings.set("overlay.flash", String.valueOf(flash));
            if (flash) startFlash(Pixel.PINK); // a preview
        });
        Pixel.Check dungeons = new Pixel.Check("Only in dungeons", onlyDungeons);
        dungeons.addActionListener(x -> setOnlyDungeons(dungeons.isSelected()));
        Pixel.Check seeBox = new Pixel.Check("See-through", seeThrough);
        seeBox.addActionListener(x -> {
            seeThrough = seeBox.isSelected();
            settings.set("overlay.seeThrough", String.valueOf(seeThrough));
            applyOpacity();
        });
        Pixel.Check fold = new Pixel.Check("Just the title", small);
        fold.addActionListener(x -> {
            small = fold.isSelected();
            settings.set("overlay.small", String.valueOf(small));
            applySize();
        });
        Pixel.Check dpsBox = new Pixel.Check("My name, damage and DPS when folded", foldedDps);
        dpsBox.addActionListener(x -> setFoldedDps(dpsBox.isSelected()));
        for (Pixel.Check c : new Pixel.Check[]{flashBox, dungeons, seeBox, fold, dpsBox}) card.add(menuItem(c));
        card.add(menuDivider());
        card.add(menuAction("Normal size", () -> {
            scale = 1;
            width = W;
            settings.set("overlay.scale", "1");
            settings.set("overlay.width", String.valueOf(W));
            applySize();
        }));
        card.add(menuAction("Hide the overlay", () -> {
            d.dispose();
            setOn(false);
            onClosed.run();
        }));
        d.setContentPane(card);
        d.pack();
        // next to the mouse, but on the screen
        Point at = e.getLocationOnScreen();
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int x = Math.min(at.x, screen.x + screen.width - d.getWidth() - 4);
        int y = Math.min(at.y, screen.y + screen.height - d.getHeight() - 4);
        d.setLocation(Math.max(screen.x, x), Math.max(screen.y, y));
        // it closes when you click anywhere else (or press Esc)
        d.addWindowFocusListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowLostFocus(java.awt.event.WindowEvent ev) {
                d.dispose();
            }
        });
        d.getRootPane().registerKeyboardAction(ev -> d.dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.setVisible(true);
        d.toFront();
        d.requestFocus();
    }

    private static JComponent menuHeading(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Cute.font(Font.BOLD, 12));
        l.setForeground(Theme.c(0x8A7F99));
        l.setBorder(BorderFactory.createEmptyBorder(2, 2, 4, 0));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static JComponent menuItem(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.setFocusable(false); // no focus underline: it's a mouse menu
        return c;
    }

    private static JComponent menuDivider() {
        JComponent line = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Pixel.LINE);
                g.fillRect(0, getHeight() / 2, getWidth(), 1);
            }
        };
        line.setPreferredSize(new Dimension(10, 13));
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 13));
        line.setAlignmentX(Component.LEFT_ALIGNMENT);
        return line;
    }

    /** A plain line of text that does something when clicked, with a soft highlight under the mouse. */
    private static JComponent menuAction(String text, Runnable run) {
        JLabel l = new JLabel(text) {
            boolean hover;

            {
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                    @Override public void mouseClicked(MouseEvent e) { Squeak.boop(); run.run(); }
                });
            }

            @Override
            protected void paintComponent(Graphics g0) {
                if (hover) {
                    Graphics2D g = (Graphics2D) g0.create();
                    Cute.smooth(g);
                    g.setColor(Pixel.PINK_SOFT);
                    g.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 10, 10));
                    g.dispose();
                }
                super.paintComponent(g0);
            }
        };
        l.setFont(Cute.font(Font.PLAIN, 13));
        l.setForeground(Pixel.INK);
        l.setBorder(BorderFactory.createEmptyBorder(5, 2, 5, 6));
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setMaximumSize(new Dimension(Integer.MAX_VALUE, l.getPreferredSize().height));
        return l;
    }

    private void applySize() {
        if (window == null) return;
        window.setSize((int) Math.round(width * scale), (int) Math.round((small ? SMALL_H : fullHeight()) * scale));
        painter.repaint();
    }

    private void applyOpacity() {
        if (window == null) return;
        try {
            window.setOpacity(seeThrough ? 0.78f : 1f);
        } catch (Exception ignored) { // not every desktop can do see-through windows
        }
    }

    private static int parse(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Draws the overlay (the same look as the app's cards). */
    private final class Painter extends JComponent {
        private BufferedImage pup;

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Cute.smooth(g);
            g.scale(scale, scale);
            int w = (int) Math.round(getWidth() / scale), h = (int) Math.round(getHeight() / scale);
            if (!rounded) {
                g.setColor(Pixel.SHEET);
                g.fillRect(0, 0, w, h);
            }
            RoundRectangle2D card = new RoundRectangle2D.Double(1, 1, w - 3, h - 3, rounded ? 22 : 4, rounded ? 22 : 4);
            g.setColor(Pixel.SHEET);
            g.fill(card);
            double glow = flashLevel();
            if (glow > 0) {
                // a soft white-bag glow inside the edge
                for (int k = 6; k >= 1; k--) {
                    Color fc = flashColour;
                    g.setColor(new Color(fc.getRed(), fc.getGreen(), fc.getBlue(), (int) (glow * 48 * (7 - k) / 6.0)));
                    g.setStroke(new BasicStroke(k * 2.2f));
                    g.draw(card);
                }
            }
            g.setColor(glow > 0 ? Cute.mix(Pixel.LINE, flashColour, glow) : Pixel.LINE);
            g.setStroke(new BasicStroke(2f + (float) (glow * 1.5)));
            g.draw(card);
            if (pup == null) pup = PixelPup.frame(PixelPup.Face.HAPPY, PixelPup.Ears.UP, PixelPup.Tail.UP,
                    PixelPup.hatFor(java.time.LocalDate.now()), PixelPup.outfitNow());
            Graphics2D p = (Graphics2D) g.create();
            p.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            int pupScale = small ? 1 : 2;
            int hop = glow > 0 ? (int) Math.round(-Math.abs(Math.sin((3500 - (flashUntil - System.currentTimeMillis())) / 1000.0 * Math.PI * 4)) * 5) : 0;
            p.drawImage(pup, 12, 6 + hop, pup.getWidth() * pupScale, pup.getHeight() * pupScale, null);
            p.dispose();
            int tx = small ? 44 : 62;
            g.setColor(Pixel.INK);
            g.setFont(Cute.font(Font.BOLD, 15));
            // folded with your numbers on: the zone and timer take the title line, so nothing gets squeezed out
            boolean folded = small || shownRows.isEmpty();
            boolean zoneTitle = folded && foldedDps && watching.getAsBoolean() && current() != null && current().visitMap() != null;
            String title = zoneTitle ? runText() : "White Bag Puppy";
            {
                FontMetrics tfm = g.getFontMetrics();
                String t = title;
                while (tfm.stringWidth(t) > w - tx - 70 && t.length() > 6) t = t.substring(0, t.length() - 2);
                g.drawString(t.equals(title) ? title : t + "…", tx, 28);
            }
            boolean awake = watching.getAsBoolean();
            g.setColor(awake ? Pixel.TEAL : Theme.c(0xC9BFD6));
            g.fillOval(tx, 38, 8, 8);
            g.setColor(Theme.c(0x8A7F99));
            g.setFont(Cute.font(Font.PLAIN, 11));
            String mine = foldedDps ? myDps() : "";
            if (mine.endsWith("  ·  ")) mine = mine.substring(0, mine.length() - 5);
            boolean danger = System.currentTimeMillis() < dangerUntil && !dangerText.isEmpty();
            if (danger) {
                g.setColor(new Color(0xD8342E));
                g.setFont(Cute.font(Font.BOLD, 11));
            }
            String sub = danger ? dangerText : !awake ? "asleep: press Start watching" : folded ? (zoneTitle ? (mine.isEmpty() ? "watching" : mine) : runText()) : "watching" + (onlyDungeons ? "  ·  only in dungeons" : "");
            {
                FontMetrics sfm = g.getFontMetrics();
                int room = w - (tx + 13) - 70; // stop before the – and × buttons
                String cut = sub;
                while (sfm.stringWidth(cut) > room && cut.length() > 6) cut = cut.substring(0, cut.length() - 2);
                g.drawString(cut.equals(sub) ? sub : cut + "…", tx + 13, 46);
            }
            g.setFont(Cute.font(Font.BOLD, 15));
            g.drawString("–", w - 56, 28);
            g.drawString("×", w - 32, 28);
            if (!small && !shownRows.isEmpty()) {
                g.setColor(Pixel.LINE);
                g.fillRect(14, 64, w - 30, 1);
                int y = 88;
                for (String[] r : rows()) {
                    g.setFont(Cute.font(Font.PLAIN, 12));
                    g.setColor(Theme.c(0x8A7F99));
                    {
                        FontMetrics lfm = g.getFontMetrics();
                        String lab = r[0];
                        while (lfm.stringWidth(lab) > 102 && lab.length() > 4) lab = lab.substring(0, lab.length() - 2);
                        g.drawString(lab.equals(r[0]) ? lab : lab + "…", 16, y);
                    }
                    g.setFont(Cute.font(Font.BOLD, 12));
                    g.setColor(r[0].equals("Last white") && !lastWhite.isEmpty() ? Pixel.PINK_INK : Pixel.INK);
                    int[] dust = dustNow;
                    if (r[0].equals("Dust") && dust != null) {
                        // three coloured dots, each with its amount
                        FontMetrics fm = g.getFontMetrics();
                        int dx = 124;
                        Color[] dots = {new Color(0x4CC46A), new Color(0xE0483E), new Color(0x9B5CFF)};
                        for (int k = 0; k < 3; k++) {
                            g.setColor(dots[k]);
                            g.fill(new java.awt.geom.Ellipse2D.Double(dx, y - 9, 8, 8));
                            dx += 12;
                            g.setColor(Pixel.INK);
                            String n = String.format(java.util.Locale.ROOT, "%,d", dust[k]);
                            g.drawString(n, dx, y);
                            dx += fm.stringWidth(n) + 14;
                        }
                        y += 25;
                        continue;
                    }
                    String full = r[1];
                    if (full.startsWith(YOU_MARK)) {
                        full = full.substring(1);
                        g.setColor(Pixel.PINK_INK);
                    }
                    String v = full;
                    FontMetrics fm = g.getFontMetrics();
                    while (fm.stringWidth(v) > w - 140 && v.length() > 6) v = v.substring(0, v.length() - 2);
                    g.drawString(v.equals(full) ? v : v + "…", 124, y);
                    y += 25;
                }
            }
            // the resize corner: three little diagonal lines
            g.setColor(Pixel.LINE);
            g.setStroke(new BasicStroke(1.5f));
            for (int k = 0; k < 3; k++) g.drawLine(w - 6 - k * 4, h - 4, w - 4, h - 6 - k * 4);
            g.dispose();
        }
    }
}
