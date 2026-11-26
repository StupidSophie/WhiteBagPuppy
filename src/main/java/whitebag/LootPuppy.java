package whitebag;

import assets.AssetExtractor;
import assets.IdToAsset;
import packets.Packet;
import packets.packetcapture.PacketProcessor;
import packets.packetcapture.ConnectionRouter;
import puppy.version.Version;
import util.Util;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Loot Puppy: just the bag posts. Watches the game for loot bags and posts the ones you pick to a Discord webhook,
 * with an optional rarity filter, sound and game screenshot. Everything else White Bag Puppy does is left out.
 * Settings are kept in loot-puppy.properties next to the jar.
 */
public final class LootPuppy {
    static final String VERSION = "1.6.1";
    private static final String SETTINGS = "loot-puppy.properties";
    private static final int SHINY_ONLY = 5;
    /** The bag colours you can pick, each with its boosted version (ticking White also posts Boosted White). */
    private static final BagType[][] COLOURS = {
            {BagType.WHITE, BagType.BOOSTED_WHITE}, {BagType.RED, BagType.BOOSTED_RED}, {BagType.ORANGE, BagType.BOOSTED_ORANGE},
            {BagType.GOLD, BagType.BOOSTED_GOLD}, {BagType.EGG, BagType.BOOSTED_EGG}, {BagType.CYAN, BagType.BOOSTED_CYAN},
            {BagType.BLUE, BagType.BOOSTED_BLUE}};

    private final Properties settings = new Properties();
    private final DiscordWebhook webhook = new DiscordWebhook(this::log);
    private final Set<BagType> watched = EnumSet.noneOf(BagType.class);
    /** Each game connection gets its own bag tracker (so two games open don't mix their bags up). */
    private final Map<Integer, Bus> buses = new HashMap<>();

    private JFrame frame;
    private JTextField urlField, pingField;
    private Pixel.Check soundBox, shotBox;
    private Pixel.Button startButton;
    private Pixel.Status status;
    private JTextArea logArea;
    private volatile PacketProcessor processor;
    private int bags;
    private Contest contest;
    private JLabel contestLabel;
    /** The character you're on in each game connection (for the contest). */
    private static final class Session {
        /** The contest run ended on this connection; your own character vanished from the map. */
        volatile boolean contestEnded, leftMap;
        /** Your character's unique data: each inventory slot's modification. */
        volatile String unique;
        /** Items in your slots going up in rarity where they sit. */
        final UpgradeWatch upgrades = new UpgradeWatch();
        volatile int objectId = -1, charId = -1, classType = -1;
        volatile String name = "";
        /** Your HP (-1 until known), for noticing a death even if the game's death message is missed. */
        volatile int hp = -1;
        /** Which bosses die (so a Moonlight Village white can be told to be Umi's). */
        final DpsTracker dps = new DpsTracker(() -> { });
    }
    private final Map<Integer, Session> sessions = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Session lastSession;

    public static void main(String[] args) {
        // Java drops browser headers (sec-fetch-...) unless told not to; the Realms tab sends them for atti.network
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
        try {
            if (Crash.relaunchInOwnFolder(args)) return;
            Crash.install("Loot Puppy");
            launch(args);
        } catch (Throwable e) {
            Crash.report(e, true);
            System.exit(1);
        }
    }

    private static void launch(String[] args) {
        Util.setSaveLogs(false);
        for (int i = 0; i < args.length - 1; i++) if (args[i].equals("--path")) AssetExtractor.setRealmResPath(args[i + 1]);
        System.setProperty("awt.useSystemAAFontSettings", "on");
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        Theme.installUi();
        DiscordWebhook.USERNAME = "Loot Puppy";
        LootPuppy app = new LootPuppy();
        app.load();
        try {
            SwingUtilities.invokeAndWait(app::build);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Crash.started();
        app.readGameFiles();
        if ("true".equals(app.settings.getProperty("autostart"))) SwingUtilities.invokeLater(app::start);
    }

    // ---------------------------------------------------------------- settings

    private void load() {
        try (InputStream in = new FileInputStream(SETTINGS)) {
            settings.load(in);
        } catch (Exception ignored) {
        }
        String link = settings.getProperty("webhook.url");
        if (link != null && !link.equals(WhiteBagNotifier.movedBiscuit(link))) {
            settings.setProperty("webhook.url", WhiteBagNotifier.movedBiscuit(link)); // Biscuit moved (same code, new address)
            try (OutputStream out = new FileOutputStream(SETTINGS)) {
                settings.store(out, "Loot Puppy settings (your webhook URL is in here: keep this file to yourself)");
            } catch (Exception ignored) {
            }
        }
        String w = settings.getProperty("bags", "WHITE");
        for (String n : w.split(",")) {
            for (BagType[] c : COLOURS) if (c[0].name().equals(n.trim())) for (BagType t : c) watched.add(t);
        }
        Squeak.enabled = !"false".equals(settings.getProperty("sound", "true"));
        contest = new Contest(settings);
        BagTracker.shinyAnyBag = minRarity() == SHINY_ONLY;
    }

    private synchronized void save() {
        if (urlField != null) settings.setProperty("webhook.url", urlField.getText().trim());
        if (pingField != null) settings.setProperty("ping.userId", pingField.getText().trim());
        StringBuilder sb = new StringBuilder();
        for (BagType[] c : COLOURS) if (watched.contains(c[0])) sb.append(sb.length() > 0 ? "," : "").append(c[0].name());
        settings.setProperty("bags", sb.toString());
        try (OutputStream out = new FileOutputStream(SETTINGS)) {
            settings.store(out, "Loot Puppy settings (your webhook URL is in here: keep this file to yourself)");
        } catch (Exception e) {
            log("*whimper* couldn't save the settings: " + e.getMessage());
        }
    }

    private int minRarity() {
        try {
            int v = Integer.parseInt(settings.getProperty("minRarity", "0"));
            return v >= 0 && v <= SHINY_ONLY ? v : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- the window

    private void build() {
        frame = new JFrame("Loot Puppy");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        try {
            frame.setIconImages(AppIcon.images(AppIcon.of(null)));
        } catch (Throwable ignored) {
        }
        JPanel page = new JPanel();
        page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
        page.setBackground(Pixel.PAGE);
        page.setBorder(new EmptyBorder(18, 20, 18, 20));

        JLabel title = new JLabel("Loot Puppy");
        title.setFont(Cute.font(Font.BOLD, 22));
        title.setForeground(Pixel.INK);
        JLabel sub = new JLabel("Posts your loot bags to Discord.  v" + VERSION);
        sub.setFont(Cute.font(Font.PLAIN, 12));
        sub.setForeground(Theme.c(0x8A7F99));
        add(page, title, 0);
        add(page, sub, 2);

        // webhook
        add(page, heading("Discord webhook"), 16);
        urlField = Pixel.field(settings.getProperty("webhook.url", ""), 34, "a Discord webhook, or your Biscuit link from /pup link");
        Pixel.Button test = new Pixel.Button("Send test post", false);
        test.addActionListener(e -> sendTest());
        add(page, row(urlField, test), 6);
        pingField = Pixel.field(settings.getProperty("ping.userId", ""), 18, "Your Discord user id (optional)");
        pingField.setToolTipText("To be @mentioned on each post: Discord > Settings > Advanced > Developer Mode, then right-click your name > Copy User ID");
        add(page, row(pingField, Pixel.note("ping me on each post")), 6);

        // bag colours
        add(page, heading("Bags to post"), 16);
        JPanel colours = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        colours.setOpaque(false);
        for (BagType[] c : COLOURS) {
            Pixel.Check box = new Pixel.Check(c[0].displayName, watched.contains(c[0]));
            box.setToolTipText(c[0].displayName + " and Boosted " + c[0].displayName + " bags");
            box.addActionListener(e -> {
                for (BagType t : c) if (box.isSelected()) watched.add(t); else watched.remove(t);
                updateWatched();
                save();
            });
            colours.add(box);
        }
        add(page, colours, 6);

        // rarity
        add(page, heading("Only post when an item is at least"), 16);
        add(page, new LogTabs.Segments(new String[]{"Any", "Uncommon", "Rare", "Legendary", "Divine", "Shiny only"}, minRarity(), i -> {
            settings.setProperty("minRarity", String.valueOf(i));
            BagTracker.shinyAnyBag = i == SHINY_ONLY;
            updateWatched();
            save();
        }), 6);
        add(page, Pixel.note("\"Shiny only\" posts a bag of any colour that has a shiny item in it."), 4);

        // UPE contest
        add(page, heading("UPE contest"), 16);
        Pixel.Check contestBox = new Pixel.Check("Contest mode (UPE: UT and ST items only)", contest.on());
        contestBox.addActionListener(e -> {
            settings.setProperty("contest.on", String.valueOf(contestBox.isSelected()));
            updateWatched();
            save();
            updateContest();
        });
        add(page, contestBox, 6);
        add(page, wrapNote("Scores the UT and ST items that drop for one character, until it dies. Every bag with a UT or ST in it is posted with its points and your total, whatever its colour (the bag ticks above are skipped)."), 4);
        Pixel.Button pick = new Pixel.Button("Use the character I'm on now", false);
        pick.addActionListener(e -> pickCharacter());
        Pixel.Button summary = new Pixel.Button("Post my contest total", false);
        summary.addActionListener(e -> postSummary(false));
        add(page, row(pick, summary), 6);
        contestLabel = new JLabel();
        contestLabel.setFont(Cute.font(Font.PLAIN, 12));
        contestLabel.setForeground(Pixel.INK);
        add(page, contestLabel, 6);
        updateContest();

        // extras
        add(page, heading("Extras"), 16);
        soundBox = new Pixel.Check("Play a sound when a bag drops", Squeak.enabled);
        soundBox.addActionListener(e -> {
            Squeak.enabled = soundBox.isSelected();
            settings.setProperty("sound", String.valueOf(soundBox.isSelected()));
            save();
            if (soundBox.isSelected()) Squeak.play(BagType.WHITE);
        });
        add(page, soundBox, 6);
        shotBox = new Pixel.Check("Add a game screenshot to each post",
                "true".equals(settings.getProperty("screenshot", "false")));
        shotBox.addActionListener(e -> {
            settings.setProperty("screenshot", String.valueOf(shotBox.isSelected()));
            save();
        });
        shotBox.setToolTipText("Keep the game window in front (not minimised) so the picture can be taken");
        add(page, shotBox, 6);
        Pixel.Check auto = new Pixel.Check("Start watching when Loot Puppy opens", "true".equals(settings.getProperty("autostart")));
        auto.addActionListener(e -> {
            settings.setProperty("autostart", String.valueOf(auto.isSelected()));
            save();
        });
        add(page, auto, 6);

        // start / stop
        startButton = new Pixel.Button("Start watching", true);
        startButton.addActionListener(e -> {
            if (processor == null) start();
            else stop();
        });
        status = new Pixel.Status();
        status.set("Asleep", false);
        add(page, row(startButton, status), 18);

        // log
        add(page, heading("Log"), 16);
        logArea = new JTextArea(9, 30);
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setFont(Cute.font(Font.PLAIN, 12));
        logArea.setForeground(Pixel.INK);
        logArea.setBackground(Pixel.SHEET);
        logArea.setBorder(new EmptyBorder(8, 10, 8, 10));
        JScrollPane sp = new JScrollPane(logArea);
        sp.setBorder(BorderFactory.createLineBorder(Pixel.LINE, 2));
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        sp.setPreferredSize(new Dimension(560, 170));
        sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, 400));
        page.add(Box.createVerticalStrut(6));
        page.add(sp);

        JScrollPane outer = new JScrollPane(page, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        outer.setBorder(null);
        outer.getVerticalScrollBar().setUnitIncrement(16);
        frame.setContentPane(outer);
        frame.setSize(640, 800);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        ConnectionRouter.setHandler(this::packet);
        packets.packetcapture.PacketProcessor.onBadDeath = () -> log("*tilts head* a death message came in that I couldn't fully read (the game may have changed it). Checking it anyway...");
        packets.packetcapture.PacketProcessor.onClosedTrail = (id, trail) -> {
            Session ses = sessions.get(id);
            if (ses == null || !contest.on() || contest.charId() < 0 && !ses.contestEnded) return;
            if (ses.charId != contest.charId() && !ses.contestEnded) return;
            deathDebug(ses, trail);
        };
        ConnectionRouter.setClosedHandler(id -> {
            synchronized (buses) {
                buses.remove(id);
            }
        });
        packets.packetcapture.sniff.assembly.TcpStreamErrorHandler.INSTANCE.setErrorMessageHandler((error, dump) -> log("*whimper* sniffer error: " + error));
        packets.packetcapture.sniff.assembly.TcpStreamErrorHandler.INSTANCE.setErrorStopHandler(() -> SwingUtilities.invokeLater(this::stop));
        log("*wags tail* Loot Puppy " + VERSION + " is awake! Paste your webhook URL, pick your bags, then press Start watching.");
        int fix = contest.loadFix();
        if (fix > 0) {
            log("*happy wiggle* fixed your UPE total with the updated scoring rules: " + fix + (fix == 1 ? " missing point" : " missing points") + " added back. New total: " + contest.total() + " points.");
            save();
        }
        int joinFix = contest.repairJoinedRun();
        if (joinFix != 0) {
            log("*tilts head* UPE: I checked your put-back run again and " + (joinFix > 0 ? "gave back " + joinFix : "took off " + (-joinFix))
                    + (Math.abs(joinFix) == 1 ? " point" : " points") + ". New total: " + contest.total() + " points.");
            save();
        }
        int reskinFix = contest.loadReskinFix();
        if (reskinFix > 0) {
            log("*tilts head* UPE: reskins now count as their original item, and reskins of tiered gear don't count, so I took "
                    + reskinFix + (reskinFix == 1 ? " point" : " points") + " off your total. New total: " + contest.total() + " points (same as Biscuit's).");
            save();
        }
    }

    private static JLabel heading(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Cute.font(Font.BOLD, 14));
        l.setForeground(Pixel.INK);
        return l;
    }

    private static JPanel row(JComponent... parts) {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        r.setOpaque(false);
        for (JComponent c : parts) r.add(c);
        return r;
    }

    private static void add(JPanel page, JComponent c, int gap) {
        if (gap > 0) page.add(Box.createVerticalStrut(gap));
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (c instanceof JPanel) c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        page.add(c);
    }

    private void log(String text) {
        String line = "[" + new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "] " + text;
        System.out.println(line);
        SwingUtilities.invokeLater(() -> {
            if (logArea == null) return;
            logArea.append((logArea.getDocument().getLength() > 0 ? "\n" : "") + line);
            if (logArea.getLineCount() > 400) {
                try {
                    logArea.replaceRange("", 0, logArea.getLineEndOffset(100));
                } catch (Exception ignored) {
                }
            }
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    // ---------------------------------------------------------------- game files

    /** Item names and pictures come from the game's own files (read again when the game updates). */
    private void readGameFiles() {
        try {
            boolean quiet = false;
            try {
                if (AssetExtractor.needsUpdate(Version.VERSION)) {
                    log("*sniff sniff* reading the game's files for item names and pictures...");
                    quiet = AssetExtractor.updateQuietly(Version.VERSION, false);
                }
            } catch (Throwable e) {
                System.out.println("Quiet asset update failed: " + e);
            }
            if (!quiet) AssetExtractor.checkForExtraction(Version.VERSION);
            IdToAsset.reloadAssets();
            LootImage.gameSpritesReady = new java.io.File("assets/flatbuffer/spritesheetf").exists();
            ItemXml.reset();
            LootBook.reset();
            log("*sniffs the game files* ...got the item names and pictures!");
        } catch (Throwable e) {
            log("*whimper* couldn't read the game files (" + e.getClass().getSimpleName() + "), so posts will show item numbers instead of names. "
                    + "If the game is installed somewhere unusual, start with: java -jar LootPuppy.jar --path \"<path to resources.assets>\"");
        }
        // the game can update while Loot Puppy is open
        javax.swing.Timer t = new javax.swing.Timer(5 * 60_000, e -> new Thread(() -> {
            try {
                if (AssetExtractor.needsUpdate(Version.VERSION) && AssetExtractor.updateQuietly(Version.VERSION, false)) {
                    IdToAsset.reloadAssets();
                    LootImage.gameSpritesReady = new java.io.File("assets/flatbuffer/spritesheetf").exists();
                    if (LootImage.gameSpritesReady) LootImage.reloadSprites();
                    ItemXml.reset();
                    LootBook.reset();
                    log("*perks up* the game updated, so I read its new files: new items have names and pictures now.");
                }
            } catch (Throwable ignored) {
            }
        }, "reread-game-files").start());
        t.start();
    }

    // ---------------------------------------------------------------- watching

    private void packet(int connectionId, Packet p) {
        Bus bus;
        synchronized (buses) {
            bus = buses.get(connectionId);
            if (bus == null) {
                bus = new Bus();
                Session ses = new Session();
                sessions.put(connectionId, ses);
                watchCharacter(bus, ses);
                ses.dps.register(bus);
                BagTracker tracker = new BagTracker(d -> onBag(d, ses));
                tracker.setWatched(postable());
                tracker.register(bus);
                buses.put(connectionId, bus);
                trackers.put(bus, tracker);
            }
        }
        bus.emit(p);
    }

    private final Map<Bus, BagTracker> trackers = new java.util.concurrent.ConcurrentHashMap<>();

    /** Keeps track of which character you're on, and notices if the contest character dies. */
    private void watchCharacter(Bus bus, Session ses) {
        bus.register(packets.PacketType.CREATE_SUCCESS, p -> {
            packets.incoming.CreateSuccessPacket c = (packets.incoming.CreateSuccessPacket) p;
            ses.objectId = c.objectId;
            ses.charId = c.charId;
            lastSession = ses;
        });
        bus.register(packets.PacketType.UPDATE, p -> {
            packets.incoming.UpdatePacket u = (packets.incoming.UpdatePacket) p;
            if (u.drops != null && ses.objectId >= 0) for (int id : u.drops) if (id == ses.objectId) ses.leftMap = true;
            if (u.newObjects == null || ses.objectId < 0) return;
            for (packets.data.ObjectData o : u.newObjects) {
                if (o == null || o.status == null || o.status.objectId != ses.objectId) continue;
                ses.classType = o.objectType;
                if (o.status.stats != null) for (packets.data.StatData s : o.status.stats)
                    if (s.statType == packets.data.enums.StatType.NAME_STAT && s.stringStatValue != null) ses.name = DpsTracker.cleanName(s.stringStatValue);
                noteHp(ses, o.status.stats);
            }
        });
        // your HP: if it hits 0 on the contest character, that's a death (a backup for the game's death message)
        bus.register(packets.PacketType.NEWTICK, p -> {
            packets.incoming.NewTickPacket nt = (packets.incoming.NewTickPacket) p;
            if (nt.status == null || ses.objectId < 0) return;
            for (packets.data.ObjectStatusData s : nt.status) if (s != null && s.objectId == ses.objectId) noteHp(ses, s.stats);
        });
        bus.register(packets.PacketType.CREATE_SUCCESS, p -> ses.hp = -1);
        final boolean[] soulFire = {false};
        final int[] wants = {0};
        bus.register(packets.PacketType.FORGE_REQUEST, p -> {
            packets.outgoing.ForgeRequestPacket fr = (packets.outgoing.ForgeRequestPacket) p;
            soulFire[0] = Contest.usesSoulFire(fr);
            wants[0] = fr.resultItemType;
        });
        bus.register(packets.PacketType.FORGE_RESULT, p -> {
            packets.incoming.ForgeResultPacket r = (packets.incoming.ForgeResultPacket) p;
            boolean sf = soulFire[0];
            int item = Contest.forgedItem(wants[0], r.results);
            soulFire[0] = false;
            wants[0] = 0;
            if (!r.success || item <= 0) return; // failed, or dismantling
            if (!contest.on() || contest.charId() < 0 || ses.charId != contest.charId()) return;
            int slot = Contest.forgedSlot(item, r.results);
            // give the game a moment to send the new item's details (its modification), then score it
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                }
                forged(ses, item, sf, Contest.enchantIn(ses.unique, slot));
            }, "forge score");
            t.setDaemon(true);
            t.start();
        });
        bus.register(packets.PacketType.DEATH, p -> {
            packets.incoming.DeathPacket d = (packets.incoming.DeathPacket) p;
            String by = d.killedBy == null ? "" : DpsTracker.cleanName(d.killedBy);
            // the game only sends this to the player who died, so playing the contest character on this connection is enough
            if (d.charId == contest.charId() || ses.charId == contest.charId()) contestDeath(by);
            else if (contest.on() && contest.charId() < 0) log("*sniff* saw a death (character #" + d.charId + "), but there's no contest character picked right now.");
            else if (contest.on()) log("*sniff* a character died (#" + d.charId + (by.isEmpty() ? "" : ", to " + by) + "), but your contest character is " + contest.who()
                    + ", so the run keeps going. If that was your contest character, press Post my contest total, then pick your new one.");
        });
    }

    private synchronized void upgraded(Session ses, UpgradeWatch.Upgrade u) {
        if (!contest.on() || contest.charId() < 0 || ses.charId != contest.charId()) return;
        int before = contest.total();
        String line = contest.upgrade(ItemInfo.of(u.item), ses.classType, u.to);
        if (line == null) return;
        int got = contest.total() - before;
        save();
        SwingUtilities.invokeLater(this::updateContest);
        log("*BARK!* +" + got + " points: " + line + ". Total: " + contest.total() + ".");
        String url = settings.getProperty("webhook.url", "");
        if (got > 0 && DiscordWebhook.isValidUrl(url)) {
            String json = "{\"username\":" + DiscordWebhook.str(DiscordWebhook.USERNAME) + ",\"embeds\":[{\"title\":" + DiscordWebhook.str("Upgraded an item")
                    + ",\"description\":" + DiscordWebhook.str("**" + contest.bagNote(got) + "**\n" + line) + ",\"color\":" + 0xB98CFF + "}]}";
            webhook.sendJson(url, json, java.util.Collections.emptyMap(), "upgrade post");
        }
    }

    private synchronized void forged(Session ses, int item, boolean sf, Enchant enchant) {
        int before = contest.total();
        String line = contest.craft(ItemInfo.of(item), ses.classType, sf, enchant);
        if (line == null) return; // not a UT or ST item
        int got = contest.total() - before;
        save();
        SwingUtilities.invokeLater(this::updateContest);
        log((got > 0 ? "*BARK!* +" + got + " points: " : "*sniffs* no points: ") + line + ". Total: " + contest.total() + ".");
        String url = settings.getProperty("webhook.url", "");
        if (got > 0 && DiscordWebhook.isValidUrl(url)) {
            String json = "{\"username\":" + DiscordWebhook.str(DiscordWebhook.USERNAME) + ",\"embeds\":[{\"title\":" + DiscordWebhook.str(sf ? "Forged with Concentrated Soul Fire" : "Forged at the Forge")
                    + ",\"description\":" + DiscordWebhook.str("**" + contest.bagNote(got) + "**\n" + line) + ",\"color\":" + 0xE8742E + "}]}";
            webhook.sendJson(url, json, java.util.Collections.emptyMap(), "forge post");
        }
    }

    private void noteHp(Session ses, packets.data.StatData[] stats) {
        if (stats == null) return;
        ses.upgrades.character(ses.charId);
        for (UpgradeWatch.Upgrade u : ses.upgrades.update(stats)) upgraded(ses, u);
        for (packets.data.StatData s : stats) {
            if (s.statTypeNum == 80 && s.stringStatValue != null) ses.unique = s.stringStatValue; // each slot's modification
            if (s.statTypeNum != 1) continue; // HP
            int old = ses.hp;
            ses.hp = s.statValue;
            if (old > 0 && s.statValue <= 0 && ses.charId >= 0 && ses.charId == contest.charId()) contestDeath("");
        }
    }

    /**
     * When a game connection on the contest character closes (changing map, the Nexus, or dying), its last packet
     * types go in loot-puppy-death-debug.txt (names and sizes only), so a missed death can be tracked down.
     */
    private void deathDebug(Session ses, java.util.List<String> trail) {
        java.nio.file.Path f = java.nio.file.Paths.get("loot-puppy-death-debug.txt");
        try {
            java.util.List<String> old = java.nio.file.Files.exists(f) ? java.nio.file.Files.readAllLines(f, java.nio.charset.StandardCharsets.UTF_8) : new java.util.ArrayList<>();
            // keep the last 15 connections
            java.util.List<Integer> starts = new java.util.ArrayList<>();
            for (int i = 0; i < old.size(); i++) if (old.get(i).startsWith("=== ")) starts.add(i);
            if (starts.size() >= 15) old = new java.util.ArrayList<>(old.subList(starts.get(starts.size() - 14), old.size()));
            java.util.List<String> out = new java.util.ArrayList<>(old);
            out.add("=== Loot Puppy " + VERSION + ", connection closed " + new java.util.Date() + ", character #" + ses.charId
                    + (ses.hp >= 0 ? ", last HP " + ses.hp : "") + (ses.leftMap ? ", your character left the map first" : "")
                    + (ses.contestEnded ? ", contest run ended" : ", contest run still going") + " ===");
            out.addAll(trail);
            out.add("");
            java.nio.file.Files.write(f, out, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** The contest character died: the run ends, with a RIP post (once, however we noticed). */
    private synchronized void contestDeath(String by) {
        if (!contest.on() || contest.charId() < 0) return;
        if (lastSession != null) lastSession.contestEnded = true;
        log("*sad whimper* your contest character died" + (by.isEmpty() ? "" : " to " + by) + ". Final total: " + contest.total() + " points.");
        contest.died(by);
        save();
        postSummary(true);
        SwingUtilities.invokeLater(this::updateContest);
    }

    private void updateContest() {
        if (contestLabel == null) return;
        String text;
        if (!contest.on()) text = "Contest mode is off.";
        else if (contest.charId() < 0) {
            String died = settings.getProperty("contest.diedTo");
            text = died != null ? "<html><div style='width:440px'>Your last contest character died" + (died.isEmpty() ? "" : " to " + esc(died)) + " with <b>" + contest.total()
                    + " points</b>. Load in on a new one and press <b>Use the character I'm on now</b>.</html>"
                    : "<html><div style='width:440px'>No contest character yet: start watching, load in on your UPE character, then press <b>Use the character I'm on now</b>.</html>";
        } else {
            text = "<html>Contest character: <b>" + esc(contest.who()) + "</b>  ·  <b>" + contest.total() + " points</b> from "
                    + contest.items() + (contest.items() == 1 ? " item" : " items") + "</html>";
        }
        contestLabel.setText(text);
    }

    /** A grey note that wraps to the window's width. */
    private static JLabel wrapNote(String text) {
        JLabel l = new JLabel("<html><div style='width:440px'>" + esc(text) + "</div></html>");
        l.setFont(Cute.font(Font.PLAIN, 12));
        l.setForeground(Theme.c(0x8A7F99));
        return l;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void pickCharacter() {
        Session s = lastSession;
        if (s == null || s.charId < 0) {
            JOptionPane.showMessageDialog(frame, "Press Start watching, then load in on your UPE character\n(change area once if you're already in game), and press this again.",
                    "Contest character", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (contest.charId() >= 0 && contest.charId() != s.charId && contest.total() > 0) {
            int ok = JOptionPane.showConfirmDialog(frame, "Start a new contest run on this character?\nYour current " + contest.who() + " has "
                    + contest.total() + " points, which will be reset to 0.", "New contest character", JOptionPane.OK_CANCEL_OPTION);
            if (ok != JOptionPane.OK_OPTION) return;
        }
        String cls = AboutMe.objectName(s.classType, "");
        contest.pick("", s.charId, s.name, cls);
        settings.setProperty("contest.on", "true");
        save();
        updateWatched();
        updateContest();
        log("*wags tail* contest character set: " + contest.who() + ". Points start now!");
    }

    /** Posts the run's total and best items to Discord (and the log). */
    private void postSummary(boolean died) {
        log((died ? "Final UPE total: " : "UPE total: ") + contest.total() + " points.");
        String url = settings.getProperty("webhook.url", "");
        if (!DiscordWebhook.isValidUrl(url)) return;
        // RIP: a picture of the game at that moment
        byte[] shot = died && !"false".equals(settings.getProperty("contest.ripShot")) ? GameShot.capture() : null;
        if (died && shot == null && GameShot.lastProblem != null) log("*tilts head* no RIP picture: " + GameShot.lastProblem + ".");
        webhook.sendJson(url, contest.summaryJson(died, shot != null),
                shot == null ? java.util.Collections.emptyMap() : java.util.Collections.singletonMap("game.jpg", shot), "contest total");
    }


    /** The bags the trackers report: the ones you ticked (with "Shiny only", the tracker adds shiny bags of any colour). */
    private Set<BagType> postable() {
        if (contest != null && contest.on()) return EnumSet.allOf(BagType.class); // any bag can have a UT or ST
        return EnumSet.copyOf(watched.isEmpty() ? EnumSet.noneOf(BagType.class) : watched);
    }

    private void updateWatched() {
        for (BagTracker t : trackers.values()) t.setWatched(postable());
    }

    private void start() {
        if (processor != null) return;
        save();
        if (!DiscordWebhook.isValidUrl(urlField.getText().trim())) {
            log("*tilts head* no webhook URL yet, so bags will only show in here, not in Discord.");
        }
        synchronized (buses) {
            buses.clear();
            trackers.clear();
        }
        try {
            processor = new PacketProcessor();
            processor.setDaemon(true);
            processor.start();
        } catch (Throwable e) {
            processor = null;
            log("*whimper* couldn't start watching: " + e.getMessage() + ". Is Npcap installed? (npcap.com, tick \"WinPcap API-compatible mode\")");
            return;
        }
        startButton.setText("Stop watching");
        status.set(bags == 0 ? "Watching" : "Watching, " + bags + (bags == 1 ? " bag" : " bags"), true);
        log("*sniff sniff* watching for bags. If you're already in game, change area once (the Nexus works) so I can catch up.");
    }

    private void stop() {
        if (processor == null) return;
        try {
            processor.stopSniffer();
        } catch (Exception e) {
            log("*whimper* trouble stopping: " + e);
        }
        processor = null;
        startButton.setText("Start watching");
        status.set("Asleep", false);
        log("*curls up* stopped watching.");
    }

    private void onBag(BagDrop drop, Session ses) {
        if (contest.on()) {
            contestBag(drop, ses);
            return;
        }
        String items = String.join(", ", drop.itemNames());
        log("*BARK!* " + drop.bagName + " bag in " + (drop.mapName.isEmpty() ? "?" : drop.mapName) + "!" + (items.isEmpty() ? "" : " " + items));
        SwingUtilities.invokeLater(() -> {
            bags++;
            if (processor != null) status.set("Watching, " + bags + (bags == 1 ? " bag" : " bags"), true);
        });
        if (Squeak.enabled) Squeak.play(drop.type);
        String url = settings.getProperty("webhook.url", "");
        if (!DiscordWebhook.isValidUrl(url)) return;
        int min = minRarity();
        boolean ok = min == 0;
        for (ItemInfo it : drop.items()) {
            if (min == SHINY_ONLY ? LootBook.isShiny(it.id) : it.enchant.ordinal() >= min && it.enchant != Enchant.NONE) ok = true;
        }
        if (!ok) {
            log("*sniffs* " + (min == SHINY_ONLY ? "no shiny item in it" : "nothing " + Enchant.values()[min].label + " or better in it")
                    + ", so it stays off Discord.");
            return;
        }
        if (shotBox != null && shotBox.isSelected()) {
            drop.gameShot = GameShot.capture();
            if (drop.gameShot == null && GameShot.lastProblem != null) log("*tilts head* no game picture this time: " + GameShot.lastProblem + ".");
        }
        webhook.sendBag(url, settings.getProperty("ping.userId", ""), drop);
    }

    /** Contest mode: score the bag's UT/ST items for the contest character, and post the bag with its points. */
    private void contestBag(BagDrop drop, Session ses) {
        java.util.List<Contest.Score> scores = contest.score(drop, ses.dps.lastKill(20_000), ses.classType);
        if (scores.isEmpty()) return; // no UT or ST: doesn't count in a UPE
        boolean mine = contest.charId() >= 0 && ses.charId == contest.charId();
        String items = String.join(", ", drop.itemNames());
        if (!mine) {
            log("*sniffs* " + drop.bagName + " bag with " + items + ", but you're not on your contest character"
                    + (contest.charId() < 0 ? " (none picked yet)" : "") + ", so it doesn't count.");
            return;
        }
        if (contest.alreadyCounted(drop)) {
            log("*sniffs* that " + drop.bagName + " bag (" + items + ") was already counted, so it isn't counted again.");
            return;
        }
        int sum = 0;
        for (Contest.Score s : scores) sum += s.points;
        contest.add(scores, drop);
        if (sum == 0) {
            StringBuilder dup = new StringBuilder();
            for (Contest.Score s : scores) dup.append(dup.length() > 0 ? ", " : "").append(s.item.name);
            log("*sniffs* no points, so no post: " + Contest.lines(scores).replace("\n", "; ") + ". Total: " + contest.total() + ".");
            SwingUtilities.invokeLater(this::updateContest);
            return;
        }
        save();
        SwingUtilities.invokeLater(() -> {
            bags++;
            if (processor != null) status.set("Watching, " + bags + (bags == 1 ? " bag" : " bags"), true);
            updateContest();
        });
        StringBuilder why = new StringBuilder();
        for (Contest.Score s : scores) why.append(why.length() > 0 ? "\n" : "").append(s.line());
        log("*BARK!* +" + sum + " points (" + drop.bagName + " bag in " + (drop.mapName.isEmpty() ? "?" : drop.mapName) + "): "
                + why.toString().replace("\n", "; ") + ". Total: " + contest.total() + ".");
        if (Squeak.enabled) Squeak.play(drop.type);
        String url = settings.getProperty("webhook.url", "");
        if (!DiscordWebhook.isValidUrl(url)) return;
        drop.note = "+" + sum + (sum == 1 ? " point" : " points") + "  ·  Total: " + contest.total() + "  ·  " + contest.who();
        drop.detail = why.toString();
        if (shotBox != null && shotBox.isSelected()) {
            drop.gameShot = GameShot.capture();
            if (drop.gameShot == null && GameShot.lastProblem != null) log("*tilts head* no game picture this time: " + GameShot.lastProblem + ".");
        }
        webhook.sendBag(url, settings.getProperty("ping.userId", ""), drop);
    }

    private void sendTest() {
        String url = urlField.getText().trim();
        if (!DiscordWebhook.isValidUrl(url)) {
            JOptionPane.showMessageDialog(frame, "That doesn't look like a Discord webhook URL.\n\n"
                            + "In Discord: Server Settings > Integrations > Webhooks > New Webhook > Copy Webhook URL.\n"
                            + "It should start with https://discord.com/api/webhooks/\n\n"
                        + "Or type /pup link in Discord and paste the Biscuit link it gives you.",
                    "Webhook URL", JOptionPane.WARNING_MESSAGE);
            return;
        }
        save();
        log("*runs off to Discord with a test post*");
        byte[] shot = null;
        if (shotBox.isSelected()) {
            shot = GameShot.capture();
            if (shot == null) log("*tilts head* no game picture in the test post: " + GameShot.lastProblem + ". Open the game and try again.");
        }
        webhook.sendTest(url, pingField.getText().trim(), shot);
    }
}
