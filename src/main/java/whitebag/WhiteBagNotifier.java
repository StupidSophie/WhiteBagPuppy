package whitebag;

import assets.AssetExtractor;
import assets.IdToAsset;
import packets.packetcapture.PacketProcessor;
import packets.packetcapture.sniff.assembly.TcpStreamErrorHandler;
import puppy.version.Version;
import util.Util;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Small desktop app: sniffs RotMG traffic with Puppy and posts a Discord webhook message
 * whenever a white bag (or any other selected bag) drops for you.
 */
public class WhiteBagNotifier {
    private static final String SETTINGS_FILE = "whitebag-notifier.properties";

    /** A yes/no setting, forgiving about spaces and capitals ("true", " True ", "yes", "on"). */
    private boolean yes(String key) {
        String v = settings.getProperty(key);
        if (v == null) return false;
        v = v.trim().toLowerCase(java.util.Locale.ROOT);
        return v.equals("true") || v.equals("yes") || v.equals("on") || v.equals("1");
    }



    private final Properties settings = new Properties();
    /** The running app (for tests). */
    static volatile WhiteBagNotifier current;
    private final DiscordWebhook webhook = new DiscordWebhook(this::log);
    /** The UPE contest (Loot > UPE Contest), the same scoring as Loot Puppy. */
    private final Contest contest = new Contest(settings, "upe-contest.txt");
    /** The mini UPE window (UPE tab > Mini UPE window). */
    private final UpeMini upeMini = new UpeMini(contest, settings, () -> saveSettings());
    private final ContestPage.Ctl contestCtl = new ContestPage.Ctl() {
        @Override public void log(String line) {
            WhiteBagNotifier.this.log(line);
        }

        public Contest contest() { return contest; }
        public Properties settings() { return settings; }
        public void save() { saveSettings(); }
        public boolean pick(Account a) {
            int charId = a.me.charId();
            MeTracker.Thing ch = a.me.character();
            if (a.key() == null || charId < 0 || ch == null) return false;
            String name = DpsTracker.cleanName(ch.s(packets.data.enums.StatType.NAME_STAT));
            contest.pick(a.key(), charId, name, AboutMe.objectName(ch.objectType, ""));
            settings.setProperty("contest.on", "true");
            saveSettings();
            log("*wags tail* UPE contest character set: " + contest.who() + ". Points start now!");
            refreshContest();
            return true;
        }
        public void postTotal() { postContestTotal(false); }
        public UpeMini mini() { return upeMini; }
        public void test(String url) {
            if (!DiscordWebhook.isDiscordWebhook(url)) {
                JOptionPane.showMessageDialog(frame, "That doesn't look like a Discord webhook URL.\n\nIn Discord: Server Settings > Integrations > Webhooks > New Webhook > Copy Webhook URL.",
                        "Contest webhook", JOptionPane.WARNING_MESSAGE);
                return;
            }
            log("*runs off to the contest channel with a test post*");
            webhook.sendTest(url, settings.getProperty("ping.userId", ""));
        }
    };

    private void refreshContest() {
        SwingUtilities.invokeLater(() -> { for (Account a : accounts) if (a.contestPage != null) a.contestPage.changed(); });
    }

    private void postContestTotal(boolean died) {
        log((died ? "Final UPE total: " : "UPE total: ") + contest.total() + " points.");
        String url = settings.getProperty("contest.webhook", "");
        if (!DiscordWebhook.isDiscordWebhook(url)) return;
        // RIP: a picture of the game at that moment
        byte[] shot = died && !"false".equals(settings.getProperty("contest.ripShot")) ? GameShot.capture() : null;
        if (died && shot == null && GameShot.lastProblem != null) log("*tilts head* no RIP picture: " + GameShot.lastProblem + ".");
        webhook.sendJson(url, contest.summaryJson(died, shot != null),
                shot == null ? java.util.Collections.emptyMap() : java.util.Collections.singletonMap("game.jpg", shot), "contest total");
    }
    /** One per game account; the first exists from the start and is claimed by the first game seen. */
    private final List<Account> accounts = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** The mini overlay (made with the Home page). */
    private MiniOverlay overlay;
    /** The widget bar under the header (gold, fame, dust...). */
    private WidgetBar widgetBar;
    private volatile Account selected;
    private AccountRouter router;
    private Pixel.Tabs tabs;
    private String[] tabNames;
    private CardLayout pages;
    private Pixel.Sheet sheet;
    private JPanel accountBar;
    private volatile Set<BagType> watchedBags = EnumSet.of(BagType.WHITE, BagType.BOOSTED_WHITE);
    private final Account.Host host = new Account.Host() {
        @Override public void bag(Account a, BagDrop drop) { onBag(a, drop); }
        @Override public void badgesMaybe(Account a) { checkBadges(a); }
        @Override public void chat(Account a, ChatLog.Line line) { onChat(a, line); }
        @Override public void event(Account a, EventTracker.Entry e) { onEvent(a, e); }
        @Override public void log(String text) { WhiteBagNotifier.this.log(text); }
        @Override public void accountsChanged() { SwingUtilities.invokeLater(WhiteBagNotifier.this::refreshAccountBar); }
        @Override public synchronized String legacyOwner(String claimingKey) {
            String owner = settings.getProperty("accounts.legacyOwner", "");
            if (owner.isEmpty() && claimingKey != null) {
                owner = claimingKey;
                settings.setProperty("accounts.legacyOwner", owner);
                saveSettings();
            }
            return owner;
        }
        @Override public Set<BagType> watched() { return watchedBags; }
        @Override public void guildDeath(Account a, PlayersTracker.Gone g, String map) { onGuildDeath(a, g, map); }
        @Override public void myDeath(Account a, DeathLog.Death d, String gearData) { onMyDeath(a, d, gearData); }
        @Override public void deathNotice(Account a, DeathNotice n) { onDeathNotice(a, n); }
        @Override public void wormholes(Account a, String where, java.util.Map<String, Integer> seen, String fresh) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            for (java.util.Map.Entry<String, Integer> e : seen.entrySet()) parts.add(e.getKey() + (e.getValue() > 1 ? " \u00d7" + e.getValue() : ""));
            AttiFeed.myRealm = (where == null ? "your realm" : where) + ": " + String.join(", ", parts);
            log("*ears perk up* alien wormholes in " + (where == null ? "this realm" : where) + ": " + String.join(", ", parts)
                    + " (" + fresh + " just spotted; I only see the ones that come into view)");
            boolean ticked = false;
            for (String k : fresh.split(", ")) ticked |= AttiFeed.ticked(k + " Wormhole");
            if (ticked && AttiFeed.pings() && AttiFeed.sound()) Squeak.playEvent(fresh, "event");
        }

        @Override public void danger(Account a, String enemy, int deaths, int nearly) {
            if ("false".equals(settings.getProperty("danger.on", "true"))) return;
            java.util.List<String> why = new java.util.ArrayList<>(), shortWhy = new java.util.ArrayList<>();
            if (deaths > 0) {
                why.add(deaths == 1 ? "it killed you once" : "it's killed you " + deaths + " times");
                shortWhy.add(deaths == 1 ? "killed you once" : "killed you " + deaths + "×");
            }
            if (nearly > 0) {
                why.add(nearly == 1 ? "it nearly killed you once" : "it's nearly killed you " + nearly + " times");
                shortWhy.add(nearly == 1 ? "nearly killed you once" : "nearly killed you " + nearly + "×");
            }
            log((accounts.size() > 1 ? a.prefix() : "") + "*growls* careful! " + enemy + " is here (" + String.join(", and ", why) + ").");
            Squeak.playDanger();
            if (overlay != null) overlay.danger("Careful: " + enemy + " (" + String.join(", ", shortWhy) + ")");
        }
        @Override public ContestPage.Ctl contestCtl() { return contestCtl; }
        @Override public void contestBag(Account a, BagDrop drop) {
            if (!contest.on()) return;
            if (contest.charId() < 0) {
                // contest mode is on but no character is picked (after a RIP, say): say so instead of staying quiet
                java.util.List<String> ut = new java.util.ArrayList<>();
                for (ItemInfo it : drop.items()) if (Contest.counts(it)) ut.add(it.name);
                if (!ut.isEmpty()) log("*tilts head* UPE: " + String.join(", ", ut) + " dropped, but no UPE character is picked, so it wasn't counted. "
                        + "Press \"Use the character I'm on now\" on the UPE tab (then add this one with /upe add in Biscuit).");
                return;
            }
            MeTracker.Thing ch = a.me.character();
            java.util.List<Contest.Score> scores = contest.score(drop, a.dps.lastKill(20_000), ch == null ? -1 : ch.objectType);
            java.util.List<String> blueprints = Contest.blueprints(drop);
            if (scores.isEmpty() && blueprints.isEmpty()) return; // no UT or ST, and no blueprint
            String items = String.join(", ", drop.itemNames());
            if (a.me.charId() != contest.charId()) {
                log("*sniffs* " + drop.bagName + " bag with " + items + ", but you're not on your UPE character, so it doesn't count.");
                return;
            }
            if (contest.alreadyCounted(drop)) return;
            int sum = 0;
            for (Contest.Score s : scores) sum += s.points;
            contest.add(scores, drop);
            saveSettings();
            refreshContest();
            if (!blueprints.isEmpty()) log("*sniffs* UPE: logged " + String.join(", ", blueprints) + " (blueprints are 0 points).");
            if (sum == 0 && blueprints.isEmpty()) {
                log("*sniffs* UPE, no points: " + Contest.lines(scores).replace("\n", "; ") + ". Total: " + contest.total() + ".");
                return;
            }
            String why = Contest.lines(scores);
            if (!blueprints.isEmpty()) why = why.isEmpty() ? Contest.blueprintLines(blueprints) : why + "\n" + Contest.blueprintLines(blueprints);
            if (sum > 0) log("*BARK!* UPE +" + sum + " points: " + Contest.lines(scores).replace("\n", "; ") + ". Total: " + contest.total() + ".");
            String url = settings.getProperty("contest.webhook", "");
            if (!DiscordWebhook.isDiscordWebhook(url)) return;
            // its own post for the contest channel (the normal bag post, if any, goes to your usual webhook)
            BagDrop post = drop.copy();
            post.note = contest.bagNote(sum);
            post.detail = why;
            if (shotBox != null && shotBox.isSelected()) post.gameShot = drop.gameShot != null ? drop.gameShot : GameShot.capture();
            webhook.sendBag(url, "", post);
        }
        @Override public void contestCraft(Account a, int itemId, boolean soulFire, Enchant enchant) {
            if (!contest.on() || contest.charId() < 0) return;
            if (a.me.charId() != contest.charId()) return;
            MeTracker.Thing ch = a.me.character();
            int before = contest.total();
            String line = contest.craft(ItemInfo.of(itemId), ch == null ? -1 : ch.objectType, soulFire, enchant);
            if (line == null) return;
            int got = contest.total() - before;
            saveSettings();
            refreshContest();
            log((got > 0 ? "*BARK!* UPE +" + got + " points: " : "*sniffs* UPE, no points: ") + line + ". Total: " + contest.total() + ".");
            String url = settings.getProperty("contest.webhook", "");
            if (got > 0 && DiscordWebhook.isDiscordWebhook(url)) {
                String json = "{\"username\":" + DiscordWebhook.str(DiscordWebhook.USERNAME) + ",\"embeds\":[{\"title\":" + DiscordWebhook.str(soulFire ? "Forged with Concentrated Soul Fire" : "Forged at the Forge")
                        + ",\"description\":" + DiscordWebhook.str("**" + contest.bagNote(got) + "**\n" + line) + ",\"color\":" + 0xE8742E + "}]}";
                webhook.sendJson(url, json, java.util.Collections.emptyMap(), "forge post");
            }
        }
        @Override public void contestUpgrade(Account a, UpgradeWatch.Upgrade u) {
            if (!contest.on() || contest.charId() < 0 || a.me.charId() != contest.charId()) return;
            MeTracker.Thing ch = a.me.character();
            int before = contest.total();
            String line = contest.upgrade(ItemInfo.of(u.item), ch == null ? -1 : ch.objectType, u.to);
            if (line == null) return;
            int got = contest.total() - before;
            saveSettings();
            refreshContest();
            log("*BARK!* UPE +" + got + " points: " + line + ". Total: " + contest.total() + ".");
            String url = settings.getProperty("contest.webhook", "");
            if (got > 0 && DiscordWebhook.isDiscordWebhook(url)) {
                String json = "{\"username\":" + DiscordWebhook.str(DiscordWebhook.USERNAME) + ",\"embeds\":[{\"title\":" + DiscordWebhook.str("Upgraded an item")
                        + ",\"description\":" + DiscordWebhook.str("**" + contest.bagNote(got) + "**\n" + line) + ",\"color\":" + 0xB98CFF + "}]}";
                webhook.sendJson(url, json, java.util.Collections.emptyMap(), "upgrade post");
            }
        }
        @Override public void contestDied(Account a, int charId, String killedBy) {
            String pre = accounts.size() > 1 ? a.prefix() : "";
            if (!contest.on()) return;
            if (contest.charId() < 0) {
                if (!killedBy.isEmpty() || charId >= 0) log(pre + "*sniff* saw a death (character #" + charId + "), but there's no UPE contest character picked right now.");
                return;
            }
            if (charId != contest.charId() && a.me.charId() != contest.charId()) { // not the contest character
                log(pre + "*sniff* a character died (#" + charId + (a.me.charId() >= 0 && a.me.charId() != charId ? ", playing #" + a.me.charId() : "")
                        + "), but your UPE character is " + contest.who() + ", so the run keeps going. If that was your UPE character, press Post my contest total, then pick your new one.");
                return;
            }
            log("*sad whimper* your UPE character died" + (killedBy.isEmpty() ? "" : " to " + killedBy) + ". Final total: " + contest.total() + " points.");
            synchronized (contest) {
                if (contest.charId() < 0) return; // already noticed (the death message and HP 0 can both arrive)
                contest.died(killedBy);
            }
            saveSettings();
            postContestTotal(true);
            refreshContest();
        }
        @Override public void closeCall(Account a, CloseCalls.Call c) {
            log((accounts.size() > 1 ? a.prefix() : "") + "*phew* close call! Down to " + c.percent() + "% HP"
                    + (c.by.isEmpty() ? "" : " from " + c.by) + (c.where.isEmpty() ? "" : " in " + c.where)
                    + (c.escaped ? ", and you got out of there" : ", but you made it") + ". (Me > Characters > All characters > Close calls)");
        }
        @Override public void shiny(Account a, BagDrop drop, java.util.List<String> shinies) {
            log((accounts.size() > 1 ? a.prefix() : "") + "*sparkly zoomies* SHINY! " + String.join(", ", shinies) + " in " + (drop.mapName.isEmpty() ? "?" : drop.mapName) + "! (Loot > Collection > Shinies)");
            if (!"false".equals(settings.getProperty("shiny.sound", "true"))) {
                new javax.swing.Timer(900, e -> Squeak.playShiny()) {{ setRepeats(false); }}.start(); // after the bag's own sound
            }
            if (overlay != null) overlay.shiny();
            if (a.shinyPage != null) a.shinyPage.changed();
        }
    };
    private final Map<BagType, AbstractButton> bagBoxes = new EnumMap<>(BagType.class);

    private JFrame frame;
    private JTextField urlField;
    private JTextField pingField;
    private Pixel.Check autoStartBox;
    private Pixel.Check soundBox;
    private Pixel.Check shotBox;
    private Pixel.Button updateButton;
    private volatile String updateLink = "";
    private volatile String updateJar = null, updateTag = "";
    /** An update downloaded in the background, put in place when the pup closes (or on Restart now). */
    private volatile java.io.File pendingUpdate = null;
    private volatile String pendingTag = null, downloadingTag = null;
    private volatile boolean handedOver = false;
    private Pixel.Button startButton;
    private Pixel.Status status;
    private JLabel watchingLabel;
    private Cute.Header header;
    private JTextArea logArea;
    private PacketProcessor processor;
    private int bagsToday = 0;
    /** The background behind the app (Home), and what paints it. */
    private volatile Backgrounds.Preset backgroundPreset = Backgrounds.Preset.PLAIN;
    private JComponent backdropPanel;
    private java.awt.image.BufferedImage backgroundPicture;

    public static void main(String[] args) {
        // Java drops browser headers (sec-fetch-...) unless told not to; the Realms tab sends them for atti.network
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
        try {
            if (Crash.relaunchInOwnFolder(args)) return;
            Crash.install("White Bag Puppy");
            launch(args);
        } catch (Throwable e) {
            Crash.report(e, true);
            System.exit(1);
        }
    }

    private static void launch(String[] args) {
        System.out.println("Java " + System.getProperty("java.version") + " (" + System.getProperty("sun.arch.data.model") + "-bit)");
        Util.setSaveLogs(false);
        Updater.launchArgs = args.clone();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--path")) AssetExtractor.setRealmResPath(args[i + 1]);
        }
        System.setProperty("awt.useSystemAAFontSettings", "on");
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        Theme.installUi();
        WhiteBagNotifier app = new WhiteBagNotifier();
        current = app;
        SwingUtilities.invokeLater(app::buildGui);
        SwingUtilities.invokeLater(Crash::started); // runs after the window is built
        app.loadAssets();
        SwingUtilities.invokeLater(app::checkForUpdate); // after the window (and settings) are ready
        SwingUtilities.invokeLater(app::askForBiscuitLink);
        SwingUtilities.invokeLater(app::startRecapTimer);
        BookPage.share = app::shareCollection;
        BadgesPage.moveAccount = (from, to) -> {
            try {
                int n = AccountMerge.move(from, to, app.settings);
                app.saveSettings();
                Badges.reload();
                app.log("*happy wiggle* moved " + n + " saved lines into this account: your badges are back! Close and reopen the pup so your bags and stats catch up too.");
                JOptionPane.showMessageDialog(app.frame, "Done! Your badges are back.\nClose and reopen the pup so the bags and stats catch up too.", "Badges", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                app.log("*whimper* couldn't move the account data: " + ex.getMessage() + ". Nothing is lost: there's a backup in the backups folder.");
            }
        };
        BadgesPage.posting = () -> !"false".equals(app.settings.getProperty("badges.post"));
        BadgesPage.setPosting = on -> {
            app.settings.setProperty("badges.post", String.valueOf(on));
            app.saveSettings();
        };
    }

    // ---------------------------------------------------------------- Biscuit link

    /** Once at start: asks anyone without a Biscuit link to get one with /pup link (unless they said not to). */
    private void askForBiscuitLink() {
        if (Boolean.getBoolean("puppy.noAsk")) return;
        if (DiscordWebhook.isBiscuitLink(settings.getProperty("webhook.url", "")) || "true".equals(settings.getProperty("biscuit.dontAsk"))) return;
        Timer later = new Timer(2000, e -> {
            String msg = "<html><body style='width:340px'>"
                    + "<b>Femcore member? Let Biscuit post your bags and badges for you!</b><br><br>"
                    + "1. In the Femcore Discord, type <b>/pup link</b><br>"
                    + "2. Copy the link Biscuit gives you (only you can see it)<br>"
                    + "3. Press <b>Paste my link</b> here<br><br>"
                    + "No webhooks needed: Biscuit posts in the right channels with your Discord name and picture.</body></html>";
            Object[] options = {"Paste my link", "Later", "Don't ask again"};
            int pick = JOptionPane.showOptionDialog(frame, msg, "White Bag Puppy", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);
            if (pick == 2) {
                settings.setProperty("biscuit.dontAsk", "true");
                saveSettings();
                log("*nods* I won't ask about the Biscuit link again. You can still paste one in Home > Settings > Webhook URL.");
            } else if (pick == 0) {
                pasteBiscuitLink();
            }
        });
        later.setRepeats(false);
        later.start();
    }

    /** Asks for the /pup link link (already filled in if it's on the clipboard), saves it and sends a test post. */
    private void pasteBiscuitLink() {
        String clip = "";
        try {
            Object c = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            if (c != null && DiscordWebhook.isBiscuitLink(c.toString())) clip = c.toString().trim();
        } catch (Exception ignored) {
        }
        while (true) {
            Object got = JOptionPane.showInputDialog(frame, "Paste the link from /pup link:", "Biscuit link", JOptionPane.PLAIN_MESSAGE, null, null, clip);
            if (got == null) return;
            String u = got.toString().trim();
            if (DiscordWebhook.isBiscuitLink(u)) {
                settings.setProperty("webhook.url", u);
                // badges go through Biscuit too (it picks the badge channel), unless they have their own webhook on purpose
                if (DiscordWebhook.isDiscordWebhook(settings.getProperty("badges.url", ""))) settings.remove("badges.url");
                saveSettings();
                if (urlField != null) urlField.setText(u);
                log("*happy wiggle* Biscuit link saved! Your bags and badges go through Biscuit now. Sending a test post...");
                webhook.sendTest(u, settings.getProperty("ping.userId", ""));
                return;
            }
            JOptionPane.showMessageDialog(frame, "That isn't a Biscuit link. It looks like https://.../pup/post?code=WBP-...\n\n"
                    + "Type /pup link in the Femcore Discord and copy the whole link.", "Biscuit link", JOptionPane.WARNING_MESSAGE);
            clip = u;
        }
    }

    // ---------------------------------------------------------------- recaps

    /** The Recaps section on Home: their own webhook, how often, when, and post-now buttons. */
    private void buildRecaps(JPanel home) {
        section(home, new Pixel.Heading("recaps", 2), 22);
        JLabel intro = new JLabel("<html><div style='width:370px'>A daily or weekly summary for each account (your name and classes, bags, runs, fame "
                + "and time played), posted to its own channel, with pictures of the loot in one post. Only what happened while the app was running counts; "
                + "if it's closed when one is due, it's posted next time you open it.</div></html>");
        intro.setFont(Cute.font(Font.PLAIN, 12));
        intro.setForeground(Theme.BLACK);
        section(home, intro, 6);

        JPanel urlRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        urlRow.setOpaque(false);
        JLabel ul = Pixel.label("Recap webhook URL  ");
        JTextField recapUrl = Pixel.field(settings.getProperty("recap.url", ""), 22, "a webhook or bot recap link");
        Pixel.Button saveUrl = new Pixel.Button("Save", false);
        saveUrl.addActionListener(e -> {
            String u = recapUrl.getText().trim();
            if (!u.isEmpty() && !Recap.isValidUrl(u)) {
                log("*tilts head* that doesn't look like a Discord webhook URL (https://discord.com/api/webhooks/...) or a bot recap link (https://.../recap?key=...).");
                return;
            }
            settings.setProperty("recap.url", u);
            saveSettings();
            log(u.isEmpty() ? "*boop* recap webhook cleared." : Recap.isBotUrl(u) ? "*boop* bot recap link saved! Recaps get arrow buttons." : "*boop* recap webhook saved!");
        });
        urlRow.add(ul);
        urlRow.add(recapUrl);
        urlRow.add(Box.createHorizontalStrut(8));
        urlRow.add(saveUrl);
        section(home, urlRow, 10);

        String[] modes = {"off", "daily", "weekly", "both"};
        int sel = Math.max(0, java.util.Arrays.asList(modes).indexOf(Recap.mode(settings)));
        JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        modeRow.setOpaque(false);
        modeRow.add(Pixel.label("Post  "));
        modeRow.add(new LogTabs.Segments(new String[]{"Off", "Daily", "Weekly", "Daily and weekly"}, sel, i -> {
            settings.setProperty("recap.mode", modes[i]);
            settings.remove("recap.last.daily");
            settings.remove("recap.last.weekly");
            saveSettings();
            log(i == 0 ? "*curls up* no more recaps." : "*wags* recaps on! The first one goes out at the next time it's due.");
        }));
        section(home, modeRow, 8);

        JPanel timeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        timeRow.setOpaque(false);
        String[] days = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};
        JComboBox<String> day = new JComboBox<>(days);
        day.setSelectedIndex(Recap.day(settings) - 1);
        String[] hours = new String[24];
        for (int h = 0; h < 24; h++) hours[h] = (h % 12 == 0 ? 12 : h % 12) + (h < 12 ? " am" : " pm");
        JComboBox<String> hour = new JComboBox<>(hours);
        hour.setSelectedIndex(Recap.hour(settings));
        for (JComboBox<String> c : java.util.Arrays.asList(day, hour)) {
            c.setFont(Cute.font(Font.PLAIN, 13));
            c.addActionListener(e -> {
                settings.setProperty("recap.day", String.valueOf(day.getSelectedIndex() + 1));
                settings.setProperty("recap.hour", String.valueOf(hour.getSelectedIndex()));
                settings.remove("recap.last.daily");
                settings.remove("recap.last.weekly");
                saveSettings();
            });
        }
        timeRow.add(Pixel.label("Weekly on  "));
        timeRow.add(day);
        timeRow.add(Pixel.label("  at  "));
        timeRow.add(hour);
        timeRow.add(Pixel.label("   (daily: same time)"));
        section(home, timeRow, 8);

        // which bags go in the recap (counted and pictured)
        JPanel bagRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        bagRow.setOpaque(false);
        section(home, Pixel.label("Bags in recap"), 8);
        java.util.Set<BagType> recapBags = Recap.bagFilter(settings);
        for (BagType c : Recap.COLOURS) {
            String label = c == BagType.EGG ? "Egg" : c.displayName;
            Pixel.Check box = new Pixel.Check(label, recapBags.contains(c));
            box.addActionListener(e -> {
                java.util.Set<BagType> now = Recap.bagFilter(settings);
                if (box.isSelected()) now.add(c);
                else now.remove(c);
                Recap.setBagFilter(settings, now);
                saveSettings();
            });
            bagRow.add(box);
            bagRow.add(Box.createHorizontalStrut(4));
        }
        section(home, bagRow, 4);

        JPanel nowRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        nowRow.setOpaque(false);
        for (Recap.Period per : Recap.Period.values()) {
            Pixel.Button b = new Pixel.Button("Post " + per.label.toLowerCase(java.util.Locale.ROOT) + " recap now", false);
            b.addActionListener(e -> {
                String u = settings.getProperty("recap.url", "");
                if (!Recap.isValidUrl(u)) {
                    log("*tilts head* paste a recap webhook URL and press Save first.");
                    return;
                }
                for (Account a : accounts) a.me.checkpointPlay();
                int n = Recap.postNow(per, settings, (json, files, what) -> webhook.sendJson(u, json, files, what));
                log(n == 0 ? "*sniffs around* nothing to recap for the last " + (per == Recap.Period.DAILY ? "day" : "week") + " yet."
                        : "*runs off to Discord* sending the " + per.label.toLowerCase(java.util.Locale.ROOT) + " recap" + (n > 1 ? "s (" + n + " accounts)" : "") + "~");
            });
            nowRow.add(b);
            nowRow.add(Box.createHorizontalStrut(8));
        }
        section(home, nowRow, 10);

        // friends leaderboard (through the same bot as the recaps)
        Pixel.Check join = new Pixel.Check("Join the friends leaderboard", Boolean.parseBoolean(settings.getProperty("leaderboard.join", "true")));
        join.addActionListener(e -> {
            String u = settings.getProperty("recap.url", "");
            if (join.isSelected() && !Recap.isBotUrl(u)) {
                // stays ticked: it starts sending once a bot recap link is saved above
                settings.setProperty("leaderboard.join", "true");
                saveSettings();
                log("*tilts head* the friends leaderboard will start once there's a bot recap link (https://.../recap?key=...) above.");
                return;
            }
            settings.setProperty("leaderboard.join", String.valueOf(join.isSelected()));
            saveSettings();
            if (join.isSelected()) {
                log("*bounces* joined the friends leaderboard! Your in-game name and totals go to the bot every few minutes.");
                new Thread(() -> sendLeaderboard(true), "leaderboard").start();
            } else {
                log("*boop* left the friends leaderboard. (Your last totals stay on the board until the week ends.)");
            }
        });
        section(home, join, 12);
        JLabel joinNote = Pixel.note("Needs a bot recap link. Shares your in-game name and totals with the bot.");
        joinNote.setForeground(Theme.BLACK);
        section(home, joinNote, 2);
    }

    /** The Badges section of the Webhooks tab: its own webhook (or the bag one), on/off, and a test post. */
    private void buildBadgeHook(JPanel hooks) {
        section(hooks, new Pixel.Heading("badges", 2), 26);
        JLabel intro = Pixel.note("New badges are posted here. Leave it empty to post them to the bag webhook.");
        intro.setForeground(Theme.BLACK);
        section(hooks, intro, 6);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        JTextField field = Pixel.field(settings.getProperty("badges.url", ""), 24, "https://discord.com/api/webhooks/...");
        Pixel.Button save = new Pixel.Button("Save", false);
        save.addActionListener(e -> {
            String u = field.getText().trim();
            if (!u.isEmpty() && !DiscordWebhook.isValidUrl(u)) {
                log("*tilts head* that doesn't look like a Discord webhook URL (https://discord.com/api/webhooks/...).");
                return;
            }
            settings.setProperty("badges.url", u);
            saveSettings();
            log(u.isEmpty() ? "*boop* badge webhook cleared: badges go to the bag webhook." : "*boop* badge webhook saved!");
        });
        Pixel.Button test = new Pixel.Button("Send test post", false);
        test.addActionListener(e -> {
            String u = field.getText().trim();
            if (!u.isEmpty() && !DiscordWebhook.isValidUrl(u)) {
                log("*tilts head* that doesn't look like a Discord webhook URL (https://discord.com/api/webhooks/...).");
                return;
            }
            settings.setProperty("badges.url", u);
            saveSettings();
            String url = badgeUrl();
            if (!DiscordWebhook.isValidUrl(url)) {
                log("*tilts head* paste a badge webhook (or a bag webhook) first.");
                return;
            }
            log("*runs off to Discord with a test badge*");
            String who = selected != null && selected.key() != null ? History.titledNameOf(selected.key()) : "";
            postBadge(url, Badges.ALL.get(0), who, true);
        });
        row.add(Pixel.label("Badge webhook URL  "));
        row.add(field);
        section(hooks, row, 10);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.setOpaque(false);
        buttons.add(save);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(test);
        section(hooks, buttons, 8);
        Pixel.Check post = new Pixel.Check("Post new badges to Discord", BadgesPage.posting.getAsBoolean());
        post.addActionListener(e -> BadgesPage.setPosting.accept(post.isSelected()));
        section(hooks, post, 8);
    }

    /** The Guild deaths section: post guildmates who die near you, live, and where to. */
    private void buildGuildDeathHook(JPanel hooks) {
        section(hooks, new Pixel.Heading("guild deaths", 2), 26);
        JLabel intro = Pixel.note("When someone in your guild dies (the game's gravestone message at the top left), it's posted straight away "
                + "with what killed them, and their class, fame and gear when they were near you. Leave the URL empty to post to the bag webhook "
                + "(with a Biscuit link, Biscuit posts them in its death channel). Your own deaths can be posted too.");
        intro.setForeground(Theme.BLACK);
        section(hooks, intro, 6);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        JTextField field = Pixel.field(settings.getProperty("deaths.url", ""), 24, "https://discord.com/api/webhooks/...");
        Pixel.Button save = new Pixel.Button("Save", false);
        save.addActionListener(e -> {
            String u = field.getText().trim();
            if (!u.isEmpty() && !DiscordWebhook.isValidUrl(u)) {
                log("*tilts head* that doesn't look like a Discord webhook URL (https://discord.com/api/webhooks/...).");
                return;
            }
            settings.setProperty("deaths.url", u);
            saveSettings();
            log(u.isEmpty() ? "*boop* guild death webhook cleared: deaths go to the bag webhook." : "*boop* guild death webhook saved!");
        });
        Pixel.Button test = new Pixel.Button("Send test post", false);
        test.addActionListener(e -> {
            String u = field.getText().trim();
            if (!u.isEmpty() && !DiscordWebhook.isValidUrl(u)) {
                log("*tilts head* that doesn't look like a Discord webhook URL (https://discord.com/api/webhooks/...).");
                return;
            }
            settings.setProperty("deaths.url", u);
            saveSettings();
            if (!DiscordWebhook.isValidUrl(deathUrl())) {
                log("*tilts head* paste a guild death webhook (or a bag webhook) first.");
                return;
            }
            log("*runs off to Discord with a test death*");
            postDeath(deathUrl(), "Test Pup", 782, 20, 1677, 2695, "MV Pattern Helper 3", "3/8", "MV Pattern Helper 3 120, 95  \u00b7  something unseen 30  \u00b7  MV Pattern Helper 3 210", new int[]{3075, 5356, 3122, 2978},
                    new String[]{"AAIE_____w==", "", "AAIE_____w==", "AAIE__8="}, true); // a Wizard in Staff of Extreme Prejudice, Wandering Souls, Mad Scientist robe, Ring of the Pyramid
        });
        row.add(Pixel.label("Guild death webhook URL  "));
        row.add(field);
        section(hooks, row, 10);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.setOpaque(false);
        buttons.add(save);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(test);
        section(hooks, buttons, 8);
        // 2.30.46: both start ticked, also for people who had them off before (once; unticking afterwards sticks)
        if (settings.getProperty("deaths.defaults46") == null) {
            settings.setProperty("deaths.defaults46", "1");
            settings.setProperty("deaths.on", "true");
            settings.setProperty("deaths.mine", "true");
            saveSettings();
        }
        Pixel.Check post = new Pixel.Check("Post guildmates' deaths", Boolean.parseBoolean(settings.getProperty("deaths.on", "true")));
        post.addActionListener(e -> {
            settings.setProperty("deaths.on", String.valueOf(post.isSelected()));
            saveSettings();
        });
        section(hooks, post, 8);
        Pixel.Check mine = new Pixel.Check("Post my own deaths (with what killed me)", Boolean.parseBoolean(settings.getProperty("deaths.mine", "true")));
        mine.addActionListener(e -> {
            settings.setProperty("deaths.mine", String.valueOf(mine.isSelected()));
            saveSettings();
        });
        section(hooks, mine, 8);
        Pixel.Check online = new Pixel.Check("Tell my guild's Biscuit when I'm online (for /online)",
                Boolean.parseBoolean(settings.getProperty("online.on", "true")));
        online.addActionListener(e -> {
            settings.setProperty("online.on", String.valueOf(online.isSelected()));
            saveSettings();
            if (!online.isSelected()) new Thread(this::sendOffline, "online").start();
        });
        section(hooks, online, 8);
        JLabel onlineNote = Pixel.note("Needs a Biscuit link. Shares your name, class, where you are and guildmates near you.");
        onlineNote.setForeground(Theme.BLACK);
        section(hooks, onlineNote, 2);
    }

    /** The Biscuit link to tell who's on (deaths, bags or badges, whichever is a Biscuit link), or null. */
    private String onlineUrl() {
        for (String u : new String[]{settings.getProperty("deaths.url", ""), settings.getProperty("webhook.url", ""), settings.getProperty("badges.url", "")}) {
            String o = GuildOnline.urlFor(u);
            if (o != null) return o;
        }
        return null;
    }

    /** Names this pup said were online (account -> name), so it can say when they stop. */
    private final java.util.Map<Account, String> onlineSaid = new java.util.concurrent.ConcurrentHashMap<>();

    /** About once a minute: tells Biscuit who's playing on each account, and who's stopped. */
    private void sendOnline() {
        if (!Boolean.parseBoolean(settings.getProperty("online.on", "true"))) return;
        String url = onlineUrl();
        if (url == null) return;
        for (Account a : accounts) {
            String report = GuildOnline.report(a);
            String said = onlineSaid.get(a);
            if (report != null) {
                if (GuildOnline.send(url, report) / 100 == 2) onlineSaid.put(a, a.me.myName());
                if (said != null && !said.equalsIgnoreCase(a.me.myName())) GuildOnline.send(url, GuildOnline.offline(said));
            } else if (said != null) {
                GuildOnline.send(url, GuildOnline.offline(said));
                onlineSaid.remove(a);
            }
        }
    }

    /** Says everyone's gone (when the box is unticked or the pup closes). */
    private void sendOffline() {
        String url = onlineUrl();
        if (url != null) for (String name : onlineSaid.values()) GuildOnline.send(url, GuildOnline.offline(name));
        onlineSaid.clear();
    }

    /** Where guild deaths go: their own webhook, or the bag one. */
    private String deathUrl() {
        String u = settings.getProperty("deaths.url", "").trim();
        return DiscordWebhook.forKind(DiscordWebhook.isValidUrl(u) ? u : settings.getProperty("webhook.url", ""), "deaths");
    }

    /** A guildmate died near you: the diary, and a live post. */
    /** Deaths already posted from this pup (name -> when), so the notice and the gravestone don't both post. */
    private final java.util.Map<String, Long> deathsPosted = new java.util.HashMap<>();

    /** True the first time this death is seen (in the last few minutes). */
    private boolean firstDeath(String name) {
        synchronized (deathsPosted) {
            long now = System.currentTimeMillis();
            deathsPosted.values().removeIf(t -> now - t > 90_000); // the same person can die again soon after, on a new character
            return deathsPosted.putIfAbsent(name.toLowerCase(java.util.Locale.ROOT), now) == null;
        }
    }

    /** The game's death notice: the main way guild deaths are found (it comes from anywhere, with the killer). */
    private void onDeathNotice(Account a, DeathNotice n) {
        MeTracker.Thing t = a.players.find(n.name);
        // in view: if they're not in your guild, it's not a guild death
        String mine = a.me.myGuild();
        if (t != null && !mine.isEmpty() && !mine.equalsIgnoreCase(t.s(packets.data.enums.StatType.GUILD_NAME_STAT).trim())) return;
        if (!firstDeath(n.name)) return;
        String cls = t == null ? "" : AboutMe.objectName(t.objectType, "");
        int fame = t == null ? -1 : t.i(packets.data.enums.StatType.CURR_FAME_STAT, -1);
        String map = t == null ? "" : a.visitMap();
        String where = map == null || map.isEmpty() ? "" : RunLog.label(map);
        log("*howls softly* " + n.name + (cls.isEmpty() ? "" : " (" + cls + ")") + " from your guild died" + (n.level > 0 ? " at level " + n.level : "")
                + (n.killer.isEmpty() ? "" : ", killed by " + n.killer) + (where.isEmpty() ? "" : " in " + where) + ".");
        if (!Boolean.parseBoolean(settings.getProperty("deaths.on", "true"))) return;
        String url = deathUrl();
        if (!DiscordWebhook.isValidUrl(url)) return;
        int[] gear = {-1, -1, -1, -1};
        String[] data = null;
        if (t != null) {
            gear = gearOf(t);
            String raw = t.s(packets.data.enums.StatType.UNIQUE_DATA_STRING);
            if (!raw.isEmpty()) data = raw.split(",", -1);
        }
        int level = n.level > 0 ? n.level : t == null ? -1 : t.i(packets.data.enums.StatType.LEVEL_STAT, -1);
        postDeath(url, n.name, t == null ? -1 : t.objectType, level, fame, -1, n.killer, "", "", gear, data, false);
    }

    private static int[] gearOf(MeTracker.Thing t) {
        packets.data.enums.StatType[] slots = {packets.data.enums.StatType.INVENTORY_0_STAT, packets.data.enums.StatType.INVENTORY_1_STAT, packets.data.enums.StatType.INVENTORY_2_STAT, packets.data.enums.StatType.INVENTORY_3_STAT};
        int[] gear = new int[4];
        for (int k = 0; k < 4; k++) gear[k] = t.i(slots[k], -1);
        return gear;
    }

    /** A gravestone where a guildmate stood: only used if the game's notice doesn't come (it usually comes first). */
    private void onGuildDeath(Account a, PlayersTracker.Gone g, String map) {
        // give the notice a few seconds: it has the killer
        javax.swing.Timer later = new javax.swing.Timer(5000, e -> {
            if (firstDeath(g.name)) guildDeathFromGrave(a, g, map);
        });
        later.setRepeats(false);
        later.start();
    }

    private void guildDeathFromGrave(Account a, PlayersTracker.Gone g, String map) {
        MeTracker.Thing t = g.thing;
        String cls = AboutMe.objectName(t.objectType, "");
        int level = t.i(packets.data.enums.StatType.LEVEL_STAT, -1), fame = t.i(packets.data.enums.StatType.CURR_FAME_STAT, -1);
        String where = map.isEmpty() ? "" : RunLog.label(map);
        log("*howls softly* " + g.name + (cls.isEmpty() ? "" : " (" + cls + ")") + " from your guild died" + (where.isEmpty() ? "" : " in " + where) + ".");
        if (!Boolean.parseBoolean(settings.getProperty("deaths.on", "true"))) return;
        String url = deathUrl();
        if (!DiscordWebhook.isValidUrl(url)) return;
        int[] gear = new int[4];
        packets.data.enums.StatType[] slots = {packets.data.enums.StatType.INVENTORY_0_STAT, packets.data.enums.StatType.INVENTORY_1_STAT, packets.data.enums.StatType.INVENTORY_2_STAT, packets.data.enums.StatType.INVENTORY_3_STAT};
        for (int k = 0; k < 4; k++) gear[k] = t.i(slots[k], -1);
        String[] data = null;
        String raw = t.s(packets.data.enums.StatType.UNIQUE_DATA_STRING);
        if (!raw.isEmpty()) data = raw.split(",", -1);
        postDeath(url, g.name, t.objectType, level, fame, -1, "", "", "", gear, data, false);
    }

    /** One of your own characters died: posted with what killed it and the fame it got. */
    private void onMyDeath(Account a, DeathLog.Death d, String gearData) {
        if (!Boolean.parseBoolean(settings.getProperty("deaths.mine", "true"))) return;
        String url = deathUrl();
        if (!DiscordWebhook.isValidUrl(url)) return;
        int[] gear = new int[4];
        for (int k = 0; k < 4; k++) gear[k] = d.gear != null && k < d.gear.length ? d.gear[k] : -1;
        String[] data = gearData == null || gearData.isEmpty() ? null : gearData.split(",", -1);
        String name = d.name == null || d.name.isEmpty() ? a.me.myName() : d.name;
        int[] max = ClassMax.of(d.classType);
        String stats = "";
        if (max != null && d.stats != null && d.stats.length == 8 && d.stats[0] >= 0) {
            int maxed = 0;
            for (int k = 0; k < 8; k++) if (max[k] > 0 && d.stats[k] >= max[k]) maxed++;
            stats = maxed + "/8";
        }
        postDeath(url, name.isEmpty() ? "Your character" : name, d.classType, d.level, d.baseFame, d.totalFame, d.killedBy, stats,
                d.lastHits == null ? "" : d.lastHits, gear, data, false);
    }

    /** Biscuit's comfort notes (the same ones its graveyard posts use when its AI line isn't available). */
    private static final String[] COMFORT = {"*puts head on your lap* It's okay... you'll get 'em next time \uD83E\uDD7A",
            "*lies down next to you and sighs* \uD83D\uDC3E", "*brings you your favourite toy* \uD83E\uDDF8 Feel better?",
            "*gentle boop* You did your best \uD83D\uDC3D", "*curls up against you and stays very still* \uD83D\uDC9B",
            "*sits beside you quietly* I'm here \uD83D\uDC15", "*tiny whine* ...pats? \uD83D\uDC3E",
            "*licks your hand* Rest in pieces, little hero... \uD83E\uDD79", "*wags tail very gently* Next run will be better!! \uD83D\uDCAA",
            "*pushes a stick toward you* Wanna play something else? \uD83C\uDF33"};
    private static final String[] COMFORT_BIG = {"*sits very still and salutes* That was a REALLY good run... \uD83E\uDD79",
            "*howls softly* Awoooo... what a legend \uD83D\uDC3A", "*puts a flower on the grave* \uD83C\uDF3C What a run...",
            "*lowers head respectfully* So much fame... \uD83E\uDD7A\uD83D\uDC9B"};

    /** A death post laid out like Biscuit's graveyard posts: the text, then the character and its gear on a card. */
    private void postDeath(String url, String name, int classType, int level, int baseFame, int totalFame, String killer, String stats,
                           String lastHits, int[] gear, String[] data, boolean test) {
        java.util.List<ItemInfo> items = new java.util.ArrayList<>();
        for (int k = 0; k < gear.length; k++) {
            if (gear[k] <= 0) continue;
            java.util.List<Integer> slots = Enchant.slots(data != null && k < data.length ? data[k] : null);
            Enchant en = slots != null && !slots.isEmpty() && slots.size() <= 4 ? Enchant.values()[slots.size()] : Enchant.NONE;
            items.add(ItemInfo.of(gear[k]).withEnchant(en));
        }
        StringBuilder c = new StringBuilder();
        if (test) c.append("-# A test post: deaths will look like this.\n");
        c.append("**Oh no!!** **").append(name).append("** died <t:").append(System.currentTimeMillis() / 1000).append(":R>");
        if (killer != null && !killer.isEmpty()) c.append("\n**Murdered** by: ").append(killer);
        if (baseFame >= 0 || totalFame > 0) {
            c.append("\n");
            if (baseFame >= 0) c.append("**Base Fame**: ").append(baseFame);
            if (totalFame > 0) c.append(baseFame >= 0 ? " " : "").append("**Total Fame**: ").append(totalFame);
        }
        if (stats != null && !stats.isEmpty()) c.append("\n**Stats**: ").append(stats);
        if (lastHits != null && !lastHits.isEmpty()) c.append("\n**Last hits**: ").append(lastHits);
        String[] lines = totalFame >= 5000 ? COMFORT_BIG : COMFORT;
        c.append("\n-# ").append(lines[new java.util.Random().nextInt(lines.length)]);
        byte[] pic = LootImage.deathPng(classType, level, items); // the same cards as bag posts, with the character first
        String json = "{\"username\":\"White Bag Puppy\",\"allowed_mentions\":{\"parse\":[]},\"content\":" + DiscordWebhook.str(c.toString())
                + (pic != null ? ",\"attachments\":[{\"id\":0,\"filename\":\"death.png\"}]" : "") + "}";
        // several pups can see the same death: Biscuit posts it once
        // and keeps the details for /graveyard (what killed them, fame, class)
        String target = !DiscordWebhook.isBiscuitLink(url) ? url : url + "&key=" + urlEncode(name.toLowerCase(java.util.Locale.ROOT))
                + "&name=" + urlEncode(name) + (killer == null || killer.isEmpty() ? "" : "&killer=" + urlEncode(killer))
                + (baseFame >= 0 ? "&fame=" + baseFame : "") + (totalFame > 0 ? "&total=" + totalFame : "")
                + (classType > 0 ? "&cls=" + urlEncode(AboutMe.objectName(classType, "")) : "") + (level > 0 ? "&lvl=" + level : "")
                + (test ? "&test=1" : "");
        webhook.sendJson(target, json, pic != null ? java.util.Collections.singletonMap("death.png", pic) : java.util.Collections.emptyMap(), "death");
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** Where badges go: their own webhook, or the bag one. */
    private String badgeUrl() {
        String u = settings.getProperty("badges.url", "").trim();
        return DiscordWebhook.forKind(DiscordWebhook.isValidUrl(u) ? u : settings.getProperty("webhook.url", ""), "badges");
    }

    private void postBadge(String url, Badges.Badge b, String who, boolean test) {
        byte[] medal = BadgesPage.medalPng(b);
        String json = "{\"username\":\"White Bag Puppy\",\"allowed_mentions\":{\"parse\":[]},\"embeds\":[{\"title\":"
                + DiscordWebhook.str("\uD83C\uDFC5 Badge unlocked: " + b.name + (test ? " (test)" : ""))
                + ",\"description\":" + DiscordWebhook.str((test ? "A test post: new badges will look like this.\n" : "") + b.desc
                + (Wardrobe.unlockedBy(b.id) != null ? "\nUnlocks the pup's " + Wardrobe.unlockedBy(b.id) + "!" : "")
                + (AppIcon.unlockedBy(b.id) != null ? "\nUnlocks " + AppIcon.unlockedBy(b.id) + "!" : "")
                + (who.isEmpty() ? "" : "\n*good pup, " + who + "!* \uD83D\uDC3E"))
                + ",\"color\":" + (b.tier.color.getRGB() & 0xFFFFFF)
                + (medal != null ? ",\"thumbnail\":{\"url\":\"attachment://medal.png\"}" : "") + "}]}";
        webhook.sendJson(url, json, medal != null ? java.util.Collections.singletonMap("medal.png", medal) : java.util.Collections.emptyMap(), "badge");
    }

    /** Several badges in one post: the best one's medal, and the list. */
    private void postBadges(String url, java.util.List<Badges.Badge> list, String who) {
        Badges.Badge best = list.get(0);
        for (Badges.Badge b : list) if (b.tier.ordinal() > best.tier.ordinal()) best = b;
        StringBuilder d = new StringBuilder();
        int n = 0;
        for (Badges.Badge b : list) {
            if (n++ == 15) {
                d.append("...and ").append(list.size() - 15).append(" more\n");
                break;
            }
            d.append("\uD83C\uDFC5 **").append(b.name).append("**: ").append(b.desc);
            if (Wardrobe.unlockedBy(b.id) != null) d.append(" (unlocks the pup's ").append(Wardrobe.unlockedBy(b.id)).append("!)");
            if (AppIcon.unlockedBy(b.id) != null) d.append(" (unlocks ").append(AppIcon.unlockedBy(b.id)).append("!)");
            d.append("\n");
        }
        if (!who.isEmpty()) d.append("*good pup, ").append(who).append("!* \uD83D\uDC3E");
        byte[] medal = BadgesPage.medalPng(best);
        String json = "{\"username\":\"White Bag Puppy\",\"allowed_mentions\":{\"parse\":[]},\"embeds\":[{\"title\":"
                + DiscordWebhook.str("\uD83C\uDFC5 " + list.size() + " badges unlocked!")
                + ",\"description\":" + DiscordWebhook.str(d.toString())
                + ",\"color\":" + (best.tier.color.getRGB() & 0xFFFFFF)
                + (medal != null ? ",\"thumbnail\":{\"url\":\"attachment://medal.png\"}" : "") + "}]}";
        webhook.sendJson(url, json, medal != null ? java.util.Collections.singletonMap("medal.png", medal) : java.util.Collections.emptyMap(), "badge");
    }

    /** Unlocks any badges this account has just earned: a note in the log, a Discord post, and the Badges tab redrawn. */
    private void checkBadges(Account a) {
        String key = a.key();
        if (key == null) return;
        java.util.List<Badges.Badge> fresh;
        try {
            fresh = Badges.check(key, a::isMine);
        } catch (Throwable e) {
            e.printStackTrace();
            return;
        }
        if (fresh.isEmpty()) return;
        String who = History.titledNameOf(key);
        String url = badgeUrl();
        // lots at once (a first run somewhere often unlocks a few): one Discord post for the lot instead of a flood
        boolean together = fresh.size() > 3;
        for (Badges.Badge b : fresh) {
            log((accounts.size() > 1 ? a.prefix() : "") + "*happy howl* badge unlocked: " + b.name + "! (" + b.desc + ")");
            String gift = Wardrobe.unlockedBy(b.id);
            if (gift != null) log("*spins around* new in the Wardrobe (Me tab): the " + gift + "!");
            String icon = AppIcon.unlockedBy(b.id);
            if (icon != null) log("*sparkly eyes* unlocked " + icon + " (Home, under App icon)!");
            if (!together && BadgesPage.posting.getAsBoolean() && DiscordWebhook.isValidUrl(url)) postBadge(url, b, who, false);
        }
        if (together && BadgesPage.posting.getAsBoolean() && DiscordWebhook.isValidUrl(url)) postBadges(url, fresh, who);
        SwingUtilities.invokeLater(() -> {
            if (header != null) header.celebrate();
            if (a.badgesPage != null) a.badgesPage.changed();
        });
    }

    /** Settings > Accounts: move old data into the account you're playing (when the game gives it a new ID). */
    private void buildAccountMerge(JPanel page) {
        section(page, new Pixel.Heading("accounts", 2), 26);
        JLabel intro = Pixel.note("<html><div style='width:560px'>Badges, bags or stats gone after a name change or a fresh start? The pup keeps everything "
                + "under the game's account ID, and sometimes it starts seeing your account under a new one. This finds the old data "
                + "and moves it into the account you're playing now (a backup is made first).</div></html>");
        section(page, intro, 6);
        Pixel.Button find = new Pixel.Button("Find missing badges and data...", false);
        find.addActionListener(e -> mergeAccounts());
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.add(find);
        section(page, row, 10);
    }

    private void mergeAccounts() {
        // the account you're playing, or else the one the pup saw most recently (no need to be in game)
        String now = selected == null ? null : selected.key();
        for (Account a : accounts) if (now == null && a.key() != null) now = a.key();
        java.util.List<AccountMerge.Found> all = AccountMerge.accounts();
        if (now != null) {
            boolean listed = false;
            for (AccountMerge.Found f : all) if (f.key.equals(now)) listed = true;
            if (!listed) {
                AccountMerge.Found f = new AccountMerge.Found(now);
                f.name = selected != null ? selected.label() : "";
                all.add(0, f);
            }
        }
        if (all.size() < 2) {
            JOptionPane.showMessageDialog(frame, all.isEmpty() ? "The pup hasn't saved any account data yet."
                            : "There's only one account in the saved data:\n" + all.get(0).label()
                            + "\n\nIf the badges are under a new ID, play for a minute with the pup watching (so it sees the new one), then try again.",
                    "Accounts", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String[] labels = new String[all.size()];
        int into = 0, from = -1;
        for (int i = 0; i < labels.length; i++) {
            AccountMerge.Found f = all.get(i);
            labels[i] = f.label() + (f.key.equals(now) ? "  [playing now]" : "") + "  #" + Integer.toHexString(f.key.hashCode());
            if (f.key.equals(now)) into = i;
        }
        if (selected == null || selected.key() == null) { // not in game: the new account is the one with the fewest badges
            for (int i = 0; i < all.size(); i++) if (all.get(i).badges < all.get(into).badges) into = i;
        }
        for (int i = 0; i < all.size(); i++) // the old data: the account with the most badges that isn't the target
            if (i != into && (from < 0 || all.get(i).badges > all.get(from).badges)) from = i;
        JComboBox<String> fromBox = new JComboBox<>(labels), intoBox = new JComboBox<>(labels);
        fromBox.setSelectedIndex(from);
        intoBox.setSelectedIndex(into);
        JPanel box = new JPanel(new GridLayout(0, 1, 0, 6));
        box.add(new JLabel("Move the data from (the old one, with your badges):"));
        box.add(fromBox);
        box.add(new JLabel("Into (the account you play now):"));
        box.add(intoBox);
        box.add(new JLabel("<html><div style='width:460px'>Only do this if both are really yours (the same game account). "
                + "A backup is made first. Afterwards close and reopen the pup.</div></html>"));
        int ok = JOptionPane.showConfirmDialog(frame, box, "Move account data", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        AccountMerge.Found f = all.get(fromBox.getSelectedIndex()), to = all.get(intoBox.getSelectedIndex());
        if (f == to) {
            JOptionPane.showMessageDialog(frame, "Pick two different accounts.", "Accounts", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            int n = AccountMerge.move(f.key, to.key, settings);
            saveSettings();
            log("*happy wiggle* moved " + n + " saved lines (" + f.label() + ") into " + to.label() + ". Close and reopen the pup to see them.");
            JOptionPane.showMessageDialog(frame, "Done! Moved " + n + " saved lines.\nClose and reopen the pup to see the badges and stats.", "Accounts", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            log("*whimper* couldn't move the account data: " + ex.getMessage() + ". Nothing is lost: there's a backup in the backups folder.");
        }
    }


    /** Discord status (Rich Presence): on/off, what it shows, and the application ID. */
    private void buildDiscordStatus(JPanel page) {
        section(page, new Pixel.Heading("discord status", 2), 26);
        JLabel intro = Pixel.note("Shows \"Playing White Bag Puppy\" on your Discord profile with what you're doing. Needs the Discord app open on this PC.");
        intro.setForeground(Theme.BLACK);
        section(page, intro, 6);
        Pixel.Check on = new Pixel.Check("Show my status on Discord", Boolean.parseBoolean(settings.getProperty("presence.on", "false")));
        JLabel state = Pixel.note("");
        JPanel parts = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        parts.setOpaque(false);
        String[][] what = {{"name", "Name"}, {"class", "Class"}, {"fame", "Fame"}, {"place", "Dungeon"}, {"time", "Time there"},
                {"party", "Party"}};
        for (String[] w : what) {
            Pixel.Check c = new Pixel.Check(w[1], !"false".equals(settings.getProperty("presence." + w[0], "true")));
            c.addActionListener(e -> {
                settings.setProperty("presence." + w[0], String.valueOf(c.isSelected()));
                saveSettings();
                DiscordPresence.poke();
            });
            parts.add(c);
            parts.add(Box.createHorizontalStrut(6));
        }
        on.addActionListener(e -> {
            settings.setProperty("presence.on", String.valueOf(on.isSelected()));
            saveSettings();
            DiscordPresence.poke();
            log(on.isSelected() ? "*wags* showing what you're up to on Discord." : "*nods* not showing anything on Discord any more.");
        });
        on.setAlignmentX(Component.LEFT_ALIGNMENT);
        section(page, on, 6);
        section(page, parts, 4);
        // the application ID: built in once there is one, but changeable (Discord shows that application's name)
        JPanel idRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        idRow.setOpaque(false);
        JTextField id = Pixel.field(settings.getProperty("presence.appId", ""), 22,
                DiscordPresence.DEFAULT_APP_ID.isEmpty() ? "Discord application ID" : "Discord application ID (optional)");
        Pixel.Button saveId = new Pixel.Button("Save", false);
        saveId.addActionListener(e -> {
            settings.setProperty("presence.appId", id.getText().replaceAll("[^0-9]", ""));
            id.setText(settings.getProperty("presence.appId"));
            saveSettings();
            DiscordPresence.poke();
        });
        idRow.add(id);
        idRow.add(Box.createHorizontalStrut(8));
        idRow.add(saveId);
        section(page, idRow, 6);
        section(page, state, 4);
        new javax.swing.Timer(2000, e -> state.setText("Status: " + DiscordPresence.status())).start();
        DiscordPresence.onStatus = st -> SwingUtilities.invokeLater(() -> log("*ears up* Discord status: " + st));
        DiscordPresence.start(() -> {
            // the account that's playing right now (the one ticking most recently), else the one you're looking at
            Account a = selected;
            try {
                for (Account x : accounts) if (a == null || x.lastTick > a.lastTick) a = x;
            } catch (Exception ignored) {
            }
            DiscordPresence.Info in = new DiscordPresence.Info();
            if (a == null) return in;
            long quiet = System.currentTimeMillis() - a.lastTick;
            in.inGame = quiet < 30_000 && !a.me.myName().isEmpty();
            in.why = a.lastTick == 0 ? "no game ticks seen yet"
                    : quiet >= 30_000 ? "no game ticks for " + quiet / 1000 + "s" : a.me.myName().isEmpty() ? "no character name yet" : "";
            in.name = a.me.myName();
            int cls = a.me.myClassType();
            in.cls = cls > 0 ? AboutMe.objectName(cls, "") : "";
            in.fame = a.me.fameNow();
            String map = a.visitMap();
            in.map = map == null ? "" : RunLog.label(map);
            in.since = a.visitStart();
            in.partySize = a.players.partySize();
            in.partyMax = a.players.partyMax();
            in.partyName = a.players.partyName();
            return in;
        }, (k, f) -> settings.getProperty(k, f));
    }

    /** The Backups section on Home: back up now, restore, open the folder, and the weekly backup switch. */
    private void buildBackups(JPanel home) {
        section(home, new Pixel.Heading("backups", 2), 26);
        JLabel intro = Pixel.note("Your logs, badges, settings and sounds, zipped into the backups folder (the last " + Backup.KEEP + " are kept).");
        intro.setForeground(Theme.BLACK);
        section(home, intro, 6);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        Pixel.Button now = new Pixel.Button("Back up now", false);
        now.addActionListener(e -> {
            saveSettings();
            try {
                java.io.File b = Backup.backupNow("");
                log("*buries a bone* backup saved: backups\\" + b.getName());
            } catch (Exception ex) {
                log("*whimper* couldn't make a backup: " + ex.getMessage());
            }
        });
        Pixel.Button restore = new Pixel.Button("Restore...", false);
        restore.addActionListener(e -> restoreBackup());
        Pixel.Button open = new Pixel.Button("Open folder", false);
        open.addActionListener(e -> {
            try {
                if (!Backup.DIR.isDirectory()) Backup.DIR.mkdirs();
                Desktop.getDesktop().open(Backup.DIR.getAbsoluteFile());
            } catch (Exception ex) {
                log("*tilts head* couldn't open the folder. It's at " + Backup.DIR.getAbsolutePath());
            }
        });
        row.add(now);
        row.add(Box.createHorizontalStrut(8));
        row.add(restore);
        row.add(Box.createHorizontalStrut(8));
        row.add(open);
        section(home, row, 10);
        Pixel.Check weekly = new Pixel.Check("Back up every week by itself", !"false".equals(settings.getProperty("backup.weekly")));
        weekly.addActionListener(e -> {
            settings.setProperty("backup.weekly", String.valueOf(weekly.isSelected()));
            saveSettings();
        });
        section(home, weekly, 6);
        Pixel.Check autoUpdate = new Pixel.Check("Install updates by itself (when the pup closes)", !"false".equals(settings.getProperty("update.auto")));
        autoUpdate.addActionListener(e -> {
            settings.setProperty("update.auto", String.valueOf(autoUpdate.isSelected()));
            saveSettings();
        });
        section(home, autoUpdate, 2);
        Pixel.Button checkNow = new Pixel.Button("Check for updates", false);
        checkNow.addActionListener(e -> {
            log("*sniffs around GitHub* checking for a new version...");
            checkForUpdate(true);
        });
        JPanel checkRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        checkRow.setOpaque(false);
        checkRow.add(checkNow);
        checkRow.add(Box.createHorizontalStrut(8));
        Pixel.Button reread = new Pixel.Button("Re-read game files", false);
        reread.setToolTipText("If items show as a ? or Item #12345 after a game update, this reads the game's files again");
        reread.addActionListener(e -> rereadGameFiles(true, ""));
        checkRow.add(reread);
        section(home, checkRow, 6);
        Pixel.Check dark = new Pixel.Check("Dark mode (the pup wears pyjamas)", Theme.DARK);
        dark.addActionListener(e -> {
            settings.setProperty("theme.dark", String.valueOf(dark.isSelected()));
            saveSettings();
            if (dark.isSelected() == Theme.DARK) return;
            int ok = JOptionPane.showConfirmDialog(frame, "The pup needs to restart to switch to " + (dark.isSelected() ? "dark" : "light")
                    + " mode.\nRestart now?", "Dark mode", JOptionPane.YES_NO_OPTION);
            if (ok == JOptionPane.YES_OPTION) restartPup();
            else log("*nods* " + (dark.isSelected() ? "dark" : "light") + " mode starts next time you open the pup.");
        });
        section(home, dark, 2);
        // the mini overlay: a small always-on-top window over the game
        overlay = new MiniOverlay(() -> accounts, new MiniOverlay.Settings() {
            @Override public String get(String key, String fallback) { return settings.getProperty(key, fallback); }
            @Override public void set(String key, String value) { settings.setProperty(key, value); saveSettings(); }
        });
        Pixel.Check overlayBox = new Pixel.Check("Mini overlay (a small window on top of the game: run timer, last white, fame per hour)", overlay.isOn());
        Pixel.Check overlayDungeons = new Pixel.Check("Only show the overlay in dungeons", overlay.onlyDungeons());
        overlay.watching = () -> processor != null;
        overlayBox.addActionListener(e -> overlay.setOn(overlayBox.isSelected()));
        overlayDungeons.addActionListener(e -> overlay.setOnlyDungeons(overlayDungeons.isSelected()));
        overlay.onClosed = () -> SwingUtilities.invokeLater(() -> {
            overlayBox.setSelected(false);
            log("*curls up* overlay hidden. Turn it back on under Home > Mini overlay.");
        });
        section(home, overlayBox, 2);
        Pixel.Check widgetsBox = new Pixel.Check("Widget bar under the header (gold, fame, rank, dust, forge; right-click it to choose)",
                Boolean.parseBoolean(settings.getProperty("widgets.on", "true")));
        widgetsBox.addActionListener(e -> {
            settings.setProperty("widgets.on", String.valueOf(widgetsBox.isSelected()));
            saveSettings();
            if (widgetBar != null) widgetBar.refresh();
        });
        section(home, overlayDungeons, 2);
        Pixel.Check overlayDps = new Pixel.Check("Show live DPS (top 3) on the overlay", overlay.showing("dps"));
        overlayDps.addActionListener(e -> overlay.show("dps", overlayDps.isSelected()));
        overlay.onRowChanged = (id, on) -> { if (id.equals("dps") && overlayDps.isSelected() != on) overlayDps.setSelected(on); };
        section(home, overlayDps, 2);
        Pixel.Check overlayFoldedDps = new Pixel.Check("Show my name, damage and DPS on the folded overlay", overlay.foldedDps());
        overlayFoldedDps.addActionListener(e -> overlay.setFoldedDps(overlayFoldedDps.isSelected()));
        overlay.onFoldedDpsChanged = on -> { if (overlayFoldedDps.isSelected() != on) overlayFoldedDps.setSelected(on); };
        section(home, overlayFoldedDps, 2);
        Pixel.Check dangerBox = new Pixel.Check("Danger warnings (a warning sound and the overlay flashes red when something that killed you before shows up)",
                !"false".equals(settings.getProperty("danger.on", "true")));
        dangerBox.addActionListener(e -> {
            settings.setProperty("danger.on", String.valueOf(dangerBox.isSelected()));
            saveSettings();
        });
        section(home, dangerBox, 2);
        Pixel.Check shinyBox = new Pixel.Check("Sparkle sound when a shiny item drops", !"false".equals(settings.getProperty("shiny.sound", "true")));
        shinyBox.addActionListener(e -> {
            settings.setProperty("shiny.sound", String.valueOf(shinyBox.isSelected()));
            saveSettings();
            if (shinyBox.isSelected()) Squeak.playShiny();
        });
        section(home, shinyBox, 2);
        section(home, widgetsBox, 2);
        // accent colour (restarts, like dark mode)
        section(home, Pixel.label("Accent colour"), 12);
        JPanel accents = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        accents.setOpaque(false);
        Theme.Accent[] picked = {Theme.Accent.of(settings.getProperty("theme.accent"))};
        for (Theme.Accent ac : Theme.Accent.values()) {
            JComponent sw = new JComponent() {
                {
                    setPreferredSize(new Dimension(46, 46));
                    setToolTipText(ac.label);
                    setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                }

                @Override
                protected void paintComponent(Graphics g0) {
                    Graphics2D g = (Graphics2D) g0.create();
                    boolean on = picked[0] == ac;
                    Pixel.frame(g, 0, 0, getWidth(), getHeight(), Pixel.SHEET, on ? Pixel.INK : Pixel.LINE, on ? 3 : 2);
                    Cute.smooth(g);
                    g.setColor(new Color(ac.swatch));
                    g.fillOval(9, 9, getWidth() - 18, getHeight() - 18);
                    if (on) {
                        g.setColor(Color.WHITE);
                        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g.drawLine(16, 23, 21, 28);
                        g.drawLine(21, 28, 30, 18);
                    }
                    g.dispose();
                }
            };
            sw.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (picked[0] == ac) return;
                    picked[0] = ac;
                    accents.repaint();
                    settings.setProperty("theme.accent", ac.name());
                    saveSettings();
                    if (ac == Theme.ACCENT) return;
                    int ok = JOptionPane.showConfirmDialog(frame, "The pup needs to restart to turn " + ac.label.toLowerCase(java.util.Locale.ROOT)
                            + ".\nRestart now?", "Accent colour", JOptionPane.YES_NO_OPTION);
                    if (ok == JOptionPane.YES_OPTION) restartPup();
                    else log("*nods* the pup turns " + ac.label.toLowerCase(java.util.Locale.ROOT) + " next time you open it.");
                }
            });
            accents.add(sw);
        }
        section(home, accents, 6);

        // sound pack
        section(home, Pixel.label("Bag sounds"), 12);
        Squeak.Pack[] packs = Squeak.Pack.values();
        String[] packNames = new String[packs.length];
        for (int i = 0; i < packs.length; i++) packNames[i] = packs[i].label;
        JPanel packRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        packRow.setOpaque(false);
        LogTabs.Segments packChips = new LogTabs.Segments(packNames, Squeak.pack.ordinal(), i -> {
            Squeak.pack = packs[i];
            settings.setProperty("sound.pack", packs[i].name());
            saveSettings();
            Squeak.playDefault(BagType.WHITE); // a preview
        });
        packChips.setLayout(new GridLayout(2, 0, 4, 4)); // two neat rows
        packRow.add(packChips);
        section(home, packRow, 6);
        JPanel volRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        volRow.setOpaque(false);
        JLabel volLabel = Pixel.label("Volume  ");
        volRow.add(volLabel);
        volRow.add(new VolumeSlider(Squeak.volume, v -> Squeak.volume = v, v -> {
            settings.setProperty("sound.volume", String.valueOf(v));
            saveSettings();
            Squeak.playDefault(BagType.WHITE);
        }));
        section(home, volRow, 8);
        JLabel packNote = Pixel.note("Each bag colour has its own sound in every pack (press one to hear the white bag). "
                + "Your own sound files in the sounds folder still play instead, at this volume too.");
        section(home, packNote, 4);

        section(home, Pixel.label("Background"), 12);
        section(home, Backgrounds.picker(backgroundPreset, backgroundPicture != null, backgroundPicture, p -> {
            backgroundPreset = p;
            settings.setProperty("background.preset", p.name());
            saveSettings();
            if (frame != null) frame.repaint();
        }), 6);
        if (backgroundPicture == null) {
            section(home, Pixel.note("Put a picture called background.png next to the app (and restart) to use your own."), 4);
        }

        JLabel iconLabel = Pixel.label("App icon (window and taskbar)");
        section(home, iconLabel, 12);
        section(home, AppIcon.picker(AppIcon.of(settings.getProperty("app.icon")), p -> {
            settings.setProperty("app.icon", p.name());
            saveSettings();
            if (frame != null) frame.setIconImages(AppIcon.images(p));
            log("*wags* new app icon: " + p.label.toLowerCase(java.util.Locale.ROOT) + "!");
        }), 6);
    }

    /** Closes and opens the pup again (onto a waiting update, if there is one). */
    private void restartPup() {
        java.io.File jar = Updater.runningJar();
        if (jar == null) {
            log("*tilts head* couldn't find the pup's jar to restart it. Close and open the pup to finish.");
            return;
        }
        try {
            saveSettings();
            handedOver = true;
            boolean update = pendingUpdate != null && pendingUpdate.isFile();
            Updater.handOver(jar, update ? pendingUpdate : null);
            log("*stretches* restarting the pup...");
            stopSniffer();
            new Timer(800, ev -> System.exit(0)).start();
        } catch (Exception ex) {
            handedOver = false;
            log("*whimper* couldn't restart: " + ex.getMessage() + ". Close and open the pup to finish.");
        }
    }

    /** Picks a backup and puts it back (after backing up what's there now), then closes so it opens fresh. */
    private void restoreBackup() {
        JFileChooser pick = new JFileChooser(Backup.DIR.isDirectory() ? Backup.DIR.getAbsoluteFile() : new java.io.File(".").getAbsoluteFile());
        pick.setDialogTitle("Pick a backup to restore");
        pick.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("White Bag Puppy backups (.zip)", "zip"));
        if (pick.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        java.io.File zip = pick.getSelectedFile();
        int ok = JOptionPane.showConfirmDialog(frame,
                "Restore " + zip.getName() + "?\n\nWhat's there now is backed up first, so you can go back.\n"
                        + "The pup closes afterwards: open it again to load the restored files.",
                "Restore backup", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        stopSniffer();
        try {
            int n = Backup.restore(zip);
            JOptionPane.showMessageDialog(frame, "Restored " + n + " files. The pup will close now: open it again.",
                    "Restore backup", JOptionPane.INFORMATION_MESSAGE);
            Runtime.getRuntime().halt(0); // skip the save-on-exit, so the restored logs stay as they were
        } catch (Exception ex) {
            log("*whimper* couldn't restore that backup: " + ex.getMessage() + ". Your files were backed up first (see the backups folder).");
        }
    }

    /** Posts the Collection tab's picture to the bag webhook. */
    private void shareCollection(byte[] png, String who) {
        String url = settings.getProperty("webhook.url", "");
        if (!DiscordWebhook.isValidUrl(url)) {
            log("*tilts head* paste your webhook URL on Home > Settings first, then I can share your collection.");
            return;
        }
        String title = "\uD83D\uDCD6 " + (who.isEmpty() ? "My" : who + "'s") + " collection";
        String json = "{\"username\":\"White Bag Puppy\",\"allowed_mentions\":{\"parse\":[]},\"embeds\":[{\"title\":"
                + DiscordWebhook.str(title) + ",\"color\":" + 0xFF9EBB + ",\"image\":{\"url\":\"attachment://collection.png\"}}]}";
        webhook.sendJson(url, json, java.util.Collections.singletonMap("collection.png", png), "collection");
        log("*proudly trots off* sharing your collection on Discord~");
    }

    private int leaderboardTick = 0;
    private volatile String leaderboardProblem = null;

    /** Sends each account's totals to the bot for the friends leaderboard (when joined and the recap link is a bot link). */
    private void sendLeaderboard(boolean force) {
        if (!Boolean.parseBoolean(settings.getProperty("leaderboard.join", "true"))) return;
        String u = settings.getProperty("recap.url", "");
        if (!force && !Recap.isBotUrl(u)) return; // ticked from the start, but nothing to send to until there's a bot recap link
        for (Account a : accounts) {
            if (a.key() == null) continue;
            String problem = Leaderboard.send(u, a.key(), a::isMine, force);
            if (problem != null && !problem.equals(leaderboardProblem)) log("*tilts head* friends leaderboard: " + problem + ".");
            if (problem == null && force) log("*wags* sent your totals to the friends leaderboard!");
            leaderboardProblem = problem;
        }
    }

    /** Checks every minute whether a recap is due. */
    private void startRecapTimer() {
        java.util.concurrent.ScheduledExecutorService t = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread th = new Thread(r, "recaps");
            th.setDaemon(true);
            return th;
        });
        t.scheduleWithFixedDelay(() -> {
            try {
                String u = settings.getProperty("recap.url", "");
                for (Account a : accounts) checkBadges(a);
                if (Recap.isDue(settings)) for (Account a : accounts) a.me.checkpointPlay();
                boolean changed = Recap.tick(settings, (json, files, what) -> webhook.sendJson(u, json, files, what), this::log);
                if (leaderboardTick++ % 10 == 0) sendLeaderboard(false);
                sendOnline();
                for (Account a : accounts) a.noteCharacterItems();
                if (leaderboardTick % 360 == 0) SwingUtilities.invokeLater(this::checkForUpdate); // every 6 hours
                if (Backup.weeklyDue(settings)) {
                    try {
                        java.io.File b = Backup.backupNow("weekly");
                        log("*buries a bone* weekly backup saved: backups\\" + b.getName());
                    } catch (Exception e) {
                        // nothing to back up yet: try again next week
                    }
                    settings.setProperty("backup.last", String.valueOf(System.currentTimeMillis()));
                    changed = true;
                }
                if (changed) SwingUtilities.invokeLater(this::saveSettings);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }, 20, 60, java.util.concurrent.TimeUnit.SECONDS);
        // save fame and DPS in progress when the app closes, so recaps include them
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Account a : accounts) {
                try {
                    a.me.flush();
                    a.dps.flush();
                } catch (Throwable ignored) {
                }
            }
            installPendingOnExit(); // a downloaded update goes in now, ready for next time
            if (!onlineSaid.isEmpty()) {
                // tell Biscuit you've gone (it would notice in 5 minutes anyway), without holding up closing for long
                Thread bye = new Thread(this::sendOffline, "online-bye");
                bye.setDaemon(true);
                bye.start();
                try {
                    bye.join(3000);
                } catch (InterruptedException ignored) {
                }
            }
        }, "save-on-exit"));
    }

    // ---------------------------------------------------------------- startup

    /**
     * Updates by itself: backs up, downloads the new version in the background, and puts it in place when the pup
     * closes (so it opens on the new version next time). The button at the top of Home restarts onto it straight away.
     */
    private void downloadInBackground(String tag, String url) {
        if (tag.equals(pendingTag) || tag.equals(downloadingTag)) return;
        java.io.File jar = Updater.runningJar();
        if (jar == null) return;
        downloadingTag = tag;
        Thread t = new Thread(() -> {
            try {
                try {
                    Backup.backupNow("before-update");
                } catch (java.io.IOException ignored) {
                    // nothing to back up yet
                }
                java.io.File got = Updater.download(url, jar);
                pendingUpdate = got;
                pendingTag = tag;
                SwingUtilities.invokeLater(() -> {
                    log("*wags* White Bag Puppy " + tag + " is downloaded! It installs by itself next time you close the pup "
                            + "(or press the button at the top of Home to restart onto it now).");
                    if (updateButton != null) {
                        updateButton.setText("White Bag Puppy " + tag + " is ready! Restart to update");
                        updateButton.setEnabled(true);
                        updateButton.setVisible(true);
                        updateButton.revalidate();
                    }
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    log("*whimper* couldn't download White Bag Puppy " + tag + " by itself: " + ex.getMessage() + ". Press the button at the top of Home to try again.");
                    if (updateButton != null) {
                        updateButton.setText("White Bag Puppy " + tag + " is out! Update now");
                        updateButton.setVisible(true);
                        updateButton.revalidate();
                    }
                });
            } finally {
                downloadingTag = null;
            }
        }, "update-download");
        t.setDaemon(true);
        t.start();
    }

    /** When the pup closes with an update waiting, put it in place for next time. */
    private void installPendingOnExit() {
        java.io.File jar = Updater.runningJar();
        if (pendingUpdate == null || handedOver || jar == null || !pendingUpdate.isFile()) return;
        try {
            handedOver = true;
            Updater.handOver(jar, pendingUpdate, false);
        } catch (Exception e) {
            System.out.println("Couldn't install the update: " + e.getMessage());
        }
    }

    /** Backs up, downloads the new jar, and hands over to the script that swaps it in and restarts the pup. */
    private void installUpdate() {
        java.io.File jar = Updater.runningJar();
        if (pendingUpdate != null && pendingUpdate.isFile() && jar != null) {
            // already downloaded: just restart onto it
            try {
                saveSettings();
                handedOver = true;
                Updater.handOver(jar, pendingUpdate);
                log("*wags* restarting on White Bag Puppy " + pendingTag + "...");
                stopSniffer();
                new Timer(800, ev -> System.exit(0)).start();
            } catch (Exception ex) {
                handedOver = false;
                log("*whimper* couldn't restart onto the update: " + ex.getMessage());
            }
            return;
        }
        int ok = JOptionPane.showConfirmDialog(frame,
                "Update to White Bag Puppy " + updateTag + "?\n\nThe pup backs up your files, downloads the update from GitHub,\n"
                        + "then closes and opens again on the new version (your old one is kept as " + jar.getName() + ".old).",
                "Update", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        updateButton.setEnabled(false);
        updateButton.setText("Updating...");
        log("*grabs the update* backing up and downloading " + updateTag + "...");
        String url = updateJar;
        Thread t = new Thread(() -> {
            try {
                saveSettingsLater();
                try {
                    Backup.backupNow("before-update");
                } catch (java.io.IOException ignored) {
                    // nothing to back up yet
                }
                java.io.File got = Updater.download(url, jar);
                Updater.handOver(jar, got);
                SwingUtilities.invokeLater(() -> {
                    log("*wags* got it! Restarting on the new version...");
                    stopSniffer();
                    new Timer(800, ev -> System.exit(0)).start();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    log("*whimper* the update didn't work: " + ex.getMessage() + ". Opening the download page instead.");
                    updateButton.setEnabled(true);
                    updateButton.setText("A new version is out! Get the update");
                    updateJar = null;
                    try {
                        Desktop.getDesktop().browse(new java.net.URI(updateLink));
                    } catch (Exception ignored) {
                    }
                });
            }
        }, "update");
        t.setDaemon(true);
        t.start();
    }

    private void saveSettingsLater() throws Exception {
        SwingUtilities.invokeAndWait(this::saveSettings);
    }

    /** Asks GitHub (once) whether there's a newer White Bag Puppy, and shows a button if so. */
    private void checkForUpdate() {
        checkForUpdate(false);
    }

    /** {@code byHand}: the "Check for updates" button, which always says what it found. */
    private void checkForUpdate(boolean byHand) {
        String repo = UpdateCheck.repo(settings.getProperty("update.repo"));
        if (!byHand && Boolean.parseBoolean(settings.getProperty("update.off", "false"))) return;
        UpdateCheck.Found found = (tag, page, jar) -> SwingUtilities.invokeLater(() -> {
            if (tag.equals(pendingTag)) {
                if (byHand) log("*wags* White Bag Puppy " + tag + " is already downloaded: it installs when you close the pup (or press the button at the top of Home).");
                return;
            }
            if (tag.equals(updateTag) && !byHand) return; // already told (the check repeats every 6 hours)
            updateLink = page;
            updateJar = jar;
            updateTag = tag;
            boolean auto = jar != null && Updater.runningJar() != null;
            if (auto && !"false".equals(settings.getProperty("update.auto"))) {
                downloadInBackground(tag, jar);
                return;
            }
            log("*ears perk up* White Bag Puppy " + tag + " is out! Press the button at the top of Home to "
                    + (auto ? "update (the pup backs up your files and restarts by itself)." : "get it."));
            if (updateButton != null) {
                updateButton.setText(auto ? "White Bag Puppy " + tag + " is out! Update now" : "A new version is out! Get the update");
                updateButton.setVisible(true);
                updateButton.revalidate();
            }
        });
        String fake = System.getProperty("whitebag.fakeUpdate"); // for testing the notice
        if (fake != null) found.found(fake, "https://github.com/", System.getProperty("whitebag.fakeUpdateJar"));
        else UpdateCheck.check(repo, found,
                () -> { if (byHand) SwingUtilities.invokeLater(() -> log("*wags tail* you're on the newest White Bag Puppy (" + UpdateCheck.VERSION + ").")); },
                problem -> {
                    System.out.println("Update check: " + problem);
                    // say so in the diary, so a stuck update isn't a mystery
                    SwingUtilities.invokeLater(() -> log("*tilts head* couldn't check GitHub for updates: " + problem
                            + (byHand ? "" : ". I'll try again later, or press Check for updates on Home > Settings.")));
                });
    }

    /**
     * Says in the diary which shinies the game files list more than once (merged into one entry on the Collection
     * page), each with its dungeons. Only when that list changes, so it doesn't repeat every time the pup opens.
     */
    private void reportShinyMerges() {
        try {
            java.util.List<String> merged = LootBook.shinyMerges();
            String sig = String.valueOf(String.join("|", merged).hashCode());
            if (merged.isEmpty() || sig.equals(settings.getProperty("shiny.mergesLogged"))) return;
            settings.setProperty("shiny.mergesLogged", sig);
            saveSettings();
            log("*sniffs* the game files list " + merged.size() + (merged.size() == 1 ? " shiny" : " shinies")
                    + " more than once, so each is one entry in Loot > Collection now: " + String.join("; ", merged) + ".");
        } catch (Throwable ignored) {
        }
    }

    private volatile boolean rereading;
    private volatile long unknownCheckAt;

    /**
     * Reads the game files again in the background, if the game has updated (or always, by hand), then refreshes every
     * page so new items get their names and pictures. Also checked every few minutes, and when an unknown item turns up.
     */
    private void rereadGameFiles(boolean byHand, String why) {
        if (rereading) return;
        rereading = true;
        Thread t = new Thread(() -> {
            try {
                if (!byHand && !AssetExtractor.needsUpdate(Version.VERSION)) return;
                java.util.Set<Integer> itemsBefore = NewItems.snapshot();
                log(byHand ? "*sniff sniff* reading the game files again..." : "*perks up* " + why + ", reading the game's new files...");
                if (!AssetExtractor.updateQuietly(Version.VERSION, byHand)) {
                    log("*tilts head* couldn't find the game's files to read. If the game is somewhere unusual, start the pup with --path.");
                    return;
                }
                IdToAsset.reloadAssets();
                LootImage.gameSpritesReady = new java.io.File("assets/flatbuffer/spritesheetf").exists();
                if (LootImage.gameSpritesReady) LootImage.reloadSprites();
                ItemXml.reset();
                DungeonMods.reload();
                LootBook.reset();
                Titles.reset();
                SwingUtilities.invokeLater(() -> { for (Account a : accounts) a.refreshPages(); });
                log("*happy wiggle* got the new game files: new items have their names and pictures now.");
                String news = NewItems.report(NewItems.since(itemsBefore));
                if (news != null) log(news);
                SwingUtilities.invokeLater(this::reportShinyMerges); // after the settings are loaded
            } catch (Throwable e) {
                log("*whimper* couldn't read the new game files (" + e.getClass().getSimpleName() + ": " + e.getMessage() + "). Try Re-read game files on Home > Settings after closing the game.");
            } finally {
                rereading = false;
            }
        }, "reread-game-files");
        t.setDaemon(true);
        t.start();
    }

    private void loadAssets() {
        // the game files are saved next to the app; check Windows lets us write here first
        java.io.File here = new java.io.File(".").getAbsoluteFile().getParentFile();
        try {
            java.io.File probe = new java.io.File("assets");
            if (!probe.isDirectory() && !probe.mkdirs()) throw new java.io.IOException("can't create the assets folder");
            java.io.File t = java.io.File.createTempFile("write-test", ".tmp", probe);
            if (!t.delete()) t.deleteOnExit();
        } catch (Exception e) {
            log("*whimper* Windows won't let me save files in " + here + " (" + e.getMessage() + "). "
                    + "Item names and pictures need that. Fix: move the app to a plain folder like C:\\WhiteBagPuppy "
                    + "(not inside a zip file, OneDrive, Program Files or a protected Desktop/Documents folder), "
                    + "or allow Java under Windows Security > Virus & threat protection > Ransomware protection > Controlled folder access.");
        }
        try {
            // the game updated: read its files again by itself (asking only when the game can't be found)
            boolean quiet = false;
            java.util.Set<Integer> itemsBefore = AssetExtractor.needsUpdate(Version.VERSION) ? NewItems.snapshot() : null;
            try {
                if (AssetExtractor.needsUpdate(Version.VERSION)) {
                    log("*sniff sniff* the game has updated, reading its new files (new items get their names and pictures)...");
                    quiet = AssetExtractor.updateQuietly(Version.VERSION, false);
                }
            } catch (Throwable e) {
                System.out.println("Quiet asset update failed: " + e);
            }
            if (!quiet) AssetExtractor.checkForExtraction(Version.VERSION);
            IdToAsset.reloadAssets();
            LootImage.gameSpritesReady = new java.io.File("assets/flatbuffer/spritesheetf").exists();
            // pages drawn before the game files were read showed item numbers: read again and redraw
            ItemXml.reset();
            DungeonMods.reload();
            LootBook.reset();
            Titles.reset();
            for (Account a : accounts) a.refreshPages();
            Badges.ready = true;
            if (!LootImage.gameSpritesReady) log("*tilts head* found item names but no sprite sheets, so posts will be text only.");
            log("*sniffs the game files* ...got all the item names and pictures!");
            if (itemsBefore != null) {
                String news = NewItems.report(NewItems.since(itemsBefore));
                if (news != null) log(news);
            }
            SwingUtilities.invokeLater(this::reportShinyMerges); // after the settings are loaded
            // the game can update while the pup is open: look again every 5 minutes, and soon after an unknown item turns up
            ItemInfo.onUnknown = id -> {
                long now = System.currentTimeMillis();
                if (now - unknownCheckAt < 120_000) return;
                unknownCheckAt = now;
                rereadGameFiles(false, "found an item I don't know (#" + id + ") and the game has updated");
            };
            javax.swing.Timer watchGame = new javax.swing.Timer(5 * 60_000, e -> rereadGameFiles(false, "the game has updated"));
            watchGame.start();
        } catch (Throwable e) {
            Badges.ready = true;
            log("*whimper* couldn't read the game files (" + e.getClass().getSimpleName() + "), so posts will show item numbers instead of names and pictures. "
                    + "If the game is installed somewhere unusual, start with: java -jar WhiteBagPuppy.jar --path \"<path to resources.assets>\"");
        }
    }

    private void buildGui() {
        loadSettings();
        upeMini.start();
        GroupPage.prefs = settings; // the pages remember where you were
        GroupPage.save = this::saveSettings;
        Squeak.prepareSoundFolder();
        // Each game client gets its own connection; the router sends its packets to the right account.
        router = new AccountRouter(accounts, this::addAccount);
        AccountRouter.log = this::log;
        BadgesPage.diagnose = () -> {
            StringBuilder sb = new StringBuilder("*sniffs around* accounts: ");
            for (Account x : accounts) sb.append("page ").append(x.index + 1).append(x == selected ? " (showing)" : "")
                    .append(x.claimed() ? " known #" + Integer.toHexString(x.key().hashCode()) : " unknown").append(", ");
            java.util.Set<String> saved = AccountRouter.knownKeys.get();
            sb.append(saved.size()).append(" saved (");
            int i = 0;
            for (String k : saved) sb.append(i++ > 0 ? " " : "").append("#").append(Integer.toHexString(k.hashCode()));
            sb.append("). One account mode ").append(AccountRouter.singleAccount ? "on" : "off").append(". ").append(router == null ? "not watching" : router.describe()).append(".");
            log(sb.toString());
        };
        AccountRouter.knownKeys = () -> {
            java.util.Set<String> keys = new java.util.HashSet<>();
            for (Badges.Unlock u : Badges.unlocks()) if (!u.account.isEmpty()) keys.add(u.account);
            return keys;
        };
        packets.packetcapture.ConnectionRouter.setHandler(router);
        packets.packetcapture.ConnectionRouter.setClosedHandler(router);
        // realm events on every server from RealmStock (only if turned on in the Events tab)
        RealmStockFeed.onPing = e -> {
            if (RealmStockFeed.sound()) Squeak.playEvent(e.name, RealmStockFeed.soundNames(e)[1]);
            String d = RealmStockFeed.details(e);
            log("*ears up* [" + e.source + "] " + RealmStockFeed.headline(e) + " in " + RealmStockFeed.where(e) + (d.isEmpty() ? "" : " (" + d.replace("  ·  ", ", ") + ")"));
        };
        RealmStockFeed.start();
        // realm bosses, invasion wormholes and closing realms on every server from atti.network (ticked in the Realms tab)
        AttiFeed.onPing = s -> {
            if (AttiFeed.sound()) Squeak.playEvent(s.name, s.kind == AttiFeed.Kind.CLOSING ? "realm-closed" : "event");
            log("*ears up* [atti] " + s.name + " in " + s.where() + (s.detail.isEmpty() ? "" : " (" + s.detail.replace("  ·  ", ", ") + ")")
                    + (s.players >= 0 ? ", " + s.players + " players" : ""));
        };
        TcpStreamErrorHandler.INSTANCE.setErrorMessageHandler((error, dump) -> log("*whimper* sniffer error: " + error));
        TcpStreamErrorHandler.INSTANCE.setErrorStopHandler(() -> SwingUtilities.invokeLater(this::stopSniffer));

        frame = new JFrame("White Bag Puppy");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setIconImages(AppIcon.images(AppIcon.of(settings.getProperty("app.icon"))));

        // Background picture: put "background.png" next to the jar. Falls back to the plain colour if it's missing.
        final java.awt.image.BufferedImage bg = loadBackground();
        if (bg != null) {
            // How solid the white card is over the picture: 0 = fully see-through, 100 = solid white.
            int opacity = 45;
            try {
                opacity = Integer.parseInt(settings.getProperty("card.opacity", "45").trim());
            } catch (NumberFormatException ignored) {
            }
            settings.setProperty("card.opacity", String.valueOf(opacity));
            Pixel.setCardOpacity(opacity);
        }
        // How the picture fits the window (whitebag-notifier.properties):
        //   background.fit      = fill (cover the window, trim the overflow) | fit (show the whole picture) | stretch
        //   background.position = center | top | bottom | left | right   (which part stays in view)
        final String fit = bg == null ? "" : settings.getProperty("background.fit", "fill").trim().toLowerCase(java.util.Locale.ROOT);
        final String pos = bg == null ? "" : settings.getProperty("background.position", "center").trim().toLowerCase(java.util.Locale.ROOT);
        if (bg != null) {
            settings.setProperty("background.fit", fit);
            settings.setProperty("background.position", pos);
        }
        // The backdrop paints the picture (or a built-in background) and stays still; the window's contents scroll over it.
        backgroundPreset = Backgrounds.Preset.of(settings.getProperty("background.preset"), bg != null);
        JPanel backdrop = new JPanel(new BorderLayout()) {
            private java.awt.image.BufferedImage scaled; // picture already resized for the current window size

            @Override
            protected void paintComponent(Graphics g0) {
                super.paintComponent(g0); // paints Pixel.PAGE first (shows around the picture in "fit" mode)
                if (backgroundPreset != Backgrounds.Preset.PICTURE) {
                    Backgrounds.paint((Graphics2D) g0, backgroundPreset, getWidth(), getHeight());
                    return;
                }
                if (bg == null) return;
                int W = getWidth(), H = getHeight();
                if (W <= 0 || H <= 0) return;
                int w, h;
                if (fit.equals("stretch")) {
                    w = W;
                    h = H;
                } else {
                    double sx = W / (double) bg.getWidth(), sy = H / (double) bg.getHeight();
                    double s = fit.equals("fit") ? Math.min(sx, sy) : Math.max(sx, sy);
                    w = (int) Math.ceil(bg.getWidth() * s);
                    h = (int) Math.ceil(bg.getHeight() * s);
                }
                if (scaled == null || scaled.getWidth() != w || scaled.getHeight() != h) scaled = scalePicture(bg, w, h);
                int x = (W - w) / 2, y = (H - h) / 2;
                if (pos.equals("top")) y = 0;
                else if (pos.equals("bottom")) y = H - h;
                else if (pos.equals("left")) x = 0;
                else if (pos.equals("right")) x = W - w;
                g0.drawImage(scaled, x, y, null);
                if (Theme.DARK) { // dim the picture so light text reads over it
                    g0.setColor(new Color(0x16, 0x12, 0x1D, 150));
                    g0.fillRect(0, 0, W, H);
                }
            }
        };
        backdrop.setBackground(Pixel.PAGE);
        backdropPanel = backdrop;
        backgroundPicture = bg;
        JPanel root = new FitPanel();
        root.setOpaque(false);
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(new EmptyBorder(12, 20, 20, 20));

        // what the pup is wearing (Wardrobe page)
        Wardrobe.load(settings);
        Wardrobe.save = (k, v) -> {
            settings.setProperty(k, v);
            saveSettings();
            if (header != null) header.repaint();
            if (frame != null && AppIcon.of(settings.getProperty("app.icon")) == AppIcon.Preset.PUP) // the pup icon wears it too
                frame.setIconImages(AppIcon.images(AppIcon.Preset.PUP));
        };
        header = new Cute.Header();
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.setOnPet(() -> {
            Squeak.boop();
            // pets count towards the puppy badges
            Account a = selected;
            Badges.note(a == null ? null : a.key(), "pet");
            long pets = Badges.count(a == null ? null : a.key(), "pet");
            if (a != null && (pets == 25 || pets == 500)) checkBadges(a); // only when a pet badge could unlock
        });
        header.setLastWhite(lastDrop("white"));
        root.add(header);
        root.add(Box.createVerticalStrut(6));
        widgetBar = new WidgetBar(() -> selected == null ? null : selected.me, () -> selected == null ? null : selected.mods, new WidgetBar.Settings() {
            @Override public String get(String key, String fallback) { return settings.getProperty(key, fallback); }
            @Override public void set(String key, String value) { settings.setProperty(key, value); saveSettings(); }
        });
        widgetBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        // the chips, with the search box at the end of the row
        JPanel chipRow = new JPanel(new BorderLayout(10, 0)) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        chipRow.setOpaque(false);
        chipRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        chipRow.add(widgetBar, BorderLayout.CENTER);
        JTextField find = Pixel.field("", 18, "Find an item, dungeon or player");
        find.setToolTipText("Press Enter: bags you've had with it, the collection, or the player");
        find.addActionListener(e -> findThing(find.getText()));
        JPanel findBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        findBox.setOpaque(false);
        findBox.add(find);
        chipRow.add(findBox, BorderLayout.EAST);
        root.add(chipRow);

        // Folder tabs on top of one white sheet; each tab shows a page on the sheet.
        pages = new CardLayout();
        sheet = new Pixel.Sheet(pages);
        sheet.setAlignmentX(Component.LEFT_ALIGNMENT);
        tabNames = new String[]{"home", "loot", "fights", "me", "chat", "UPE"};
        tabs = new Pixel.Tabs(tabNames, i -> showPage());
        // account switcher (only shown once a second game account turns up)
        accountBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        accountBar.setOpaque(false);
        accountBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        accountBar.setVisible(false);
        root.add(accountBar);
        sheet.tabs = tabs;
        tabs.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.add(tabs);
        root.add(sheet);

        JPanel home = new JPanel();
        home.setLayout(new BoxLayout(home, BoxLayout.Y_AXIS));
        home.setOpaque(false);
        // Settings: every Discord webhook (bag posts, recaps and badges), then backups, updates and how the pup looks
        JPanel hooks = new JPanel();
        hooks.setLayout(new BoxLayout(hooks, BoxLayout.Y_AXIS));
        hooks.setOpaque(false);
        sheet.add(new GroupPage(new String[]{"Watch", "Settings"}, home, hooks), "home");
        selected = addAccount(); // the first account's pages, ready before any game is seen

        // ---- update notice (hidden until GitHub has a newer release)
        updateButton = new Pixel.Button("", true);
        updateButton.setVisible(false);
        updateButton.addActionListener(e -> {
            if (updateJar != null && Updater.runningJar() != null) {
                installUpdate();
                return;
            }
            try {
                Desktop.getDesktop().browse(new java.net.URI(updateLink));
            } catch (Exception ex) {
                log("*tilts head* couldn't open the browser. The new version is at " + updateLink);
            }
        });
        section(home, updateButton, 0);

        // ---- pointer to the Webhooks tab
        JLabel hookNote = Pixel.note("Discord webhooks (bag posts, recaps and badges), backups, the overlay and how the pup looks are on Settings, just above.");
        hookNote.setForeground(Theme.BLACK);
        section(home, hookNote, 0);

        // ---- Discord
        section(hooks, new Pixel.Heading("bag posts", 2), 0);
        section(hooks, Pixel.note("Watched bags (and collections you share) are posted to this webhook."), 6);
        JPanel discord = new JPanel(new GridBagLayout());
        discord.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        urlField = Pixel.field(settings.getProperty("webhook.url", ""), 34, "a Discord webhook, or your Biscuit link from /pup link");
        pingField = Pixel.field(settings.getProperty("ping.userId", ""), 16, "e.g. 1234567890");
        pingField.setToolTipText("Discord: Settings > Advanced > Developer Mode, then right-click your name > Copy User ID");
        c.gridx = 0; c.gridy = 0; c.insets = new Insets(0, 0, 8, 14); discord.add(Pixel.label("Webhook URL"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1; c.insets = new Insets(0, 0, 8, 0);
        discord.add(urlField, c);
        c.gridx = 0; c.gridy = 1; c.fill = GridBagConstraints.NONE; c.weightx = 0; c.insets = new Insets(0, 0, 8, 14);
        discord.add(Pixel.label("Ping me"), c);
        JPanel pingRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        pingRow.setOpaque(false);
        pingRow.add(pingField);
        pingRow.add(Box.createHorizontalStrut(10));
        pingRow.add(Pixel.note("Your Discord user ID (optional)."));
        c.gridx = 1; c.insets = new Insets(0, 0, 8, 0); discord.add(pingRow, c);
        JPanel discordButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        discordButtons.setOpaque(false);
        Pixel.Button save = new Pixel.Button("Save", false);
        save.addActionListener(e -> { if (saveSettings()) log("*boop* saved!"); });
        Pixel.Button test = new Pixel.Button("Send test post", false);
        test.addActionListener(e -> sendTest());
        // on/off button for adding a picture of the game to bag posts (pink when on)
        boolean picsOn = Boolean.parseBoolean(settings.getProperty("screenshot", "false"));
        shotBox = new Pixel.Check("", picsOn);
        Pixel.Button pics = new Pixel.Button(picsOn ? "Game pictures: On" : "Game pictures: Off", picsOn);
        pics.setToolTipText("When a bag you're watching drops, add a picture of the RotMG window to the Discord post.");
        pics.addActionListener(e -> {
            boolean on = !shotBox.isSelected();
            shotBox.setSelected(on);
            pics.setText(on ? "Game pictures: On" : "Game pictures: Off");
            pics.setPrimary(on);
            saveSettings();
            log(on ? "*grabs the camera* bag posts will include a picture of the game. Send a test post to try it."
                    : "*puts the camera away* no more game pictures in bag posts.");
        });
        discordButtons.add(save);
        discordButtons.add(Box.createHorizontalStrut(8));
        discordButtons.add(test);
        discordButtons.add(Box.createHorizontalStrut(8));
        discordButtons.add(pics);
        c.gridx = 1; c.gridy = 2; c.insets = new Insets(4, 0, 0, 0); discord.add(discordButtons, c);
        section(hooks, discord, 12);
        buildRecaps(hooks);
        buildBadgeHook(hooks);

        // ---- Bags to watch: an inventory grid of the real bag sprites
        section(home, new Pixel.Heading("bags to watch", 2), 26);
        JPanel grid = new JPanel(new GridLayout(2, 7, 8, 8));
        grid.setOpaque(false);
        BagType[] order = {BagType.WHITE, BagType.RED, BagType.ORANGE, BagType.GOLD, BagType.EGG, BagType.CYAN, BagType.BLUE,
                BagType.BOOSTED_WHITE, BagType.BOOSTED_RED, BagType.BOOSTED_ORANGE, BagType.BOOSTED_GOLD, BagType.BOOSTED_EGG, BagType.BOOSTED_CYAN, BagType.BOOSTED_BLUE};
        for (BagType type : order) {
            boolean on = Boolean.parseBoolean(settings.getProperty(type.settingKey(), String.valueOf(type.defaultOn)));
            Pixel.BagSlot slot = new Pixel.BagSlot(type, on);
            slot.addActionListener(e -> {
                applyWatched();
                saveSettings();
                if (slot.isSelected()) Squeak.play(type); // preview this bag's sound
            });
            bagBoxes.put(type, slot);
            // a box under each bag to turn its built-in sound off
            boolean soundOn = Boolean.parseBoolean(settings.getProperty(type.soundKey(), "true"));
            if (!soundOn) Squeak.muted.add(type);
            Pixel.Check sound = new Pixel.Check("Sound", soundOn);
            sound.setFont(Cute.font(Font.PLAIN, 11));
            sound.setBorder(new EmptyBorder(2, 28, 2, 2));
            sound.setToolTipText("Play the pup's " + type.displayName + " bag sound (your own sound file still plays if it's off)");
            sound.addActionListener(e -> {
                if (sound.isSelected()) Squeak.muted.remove(type); else Squeak.muted.add(type);
                settings.setProperty(type.soundKey(), String.valueOf(sound.isSelected()));
                saveSettings();
                if (sound.isSelected()) Squeak.playDefault(type);
            });
            JPanel cell = new JPanel(new BorderLayout(0, 2));
            cell.setOpaque(false);
            JPanel slotWrap = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
            slotWrap.setOpaque(false);
            slotWrap.add(slot);
            cell.add(slotWrap, BorderLayout.CENTER);
            cell.add(sound, BorderLayout.SOUTH);
            grid.add(cell);
        }
        JPanel gridWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        gridWrap.setOpaque(false);
        gridWrap.add(grid);
        section(home, gridWrap, 12);
        watchingLabel = Pixel.note("");
        watchingLabel.setForeground(Theme.BLACK); // black so it stands out over the background picture
        section(home, watchingLabel, 8);
        applyWatched();
        // only post bags to Discord with an item of at least this rarity (enchantment slots)
        JLabel rarityLabel = Pixel.label("Post to Discord only when an item is at least");
        rarityLabel.setForeground(Theme.BLACK);
        section(home, rarityLabel, 12);
        JPanel rarityRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        rarityRow.setOpaque(false);
        int minR = minRarity();
        BagTracker.shinyAnyBag = minR == SHINY_ONLY;
        rarityRow.add(new LogTabs.Segments(new String[]{"Any", "Uncommon", "Rare", "Legendary", "Divine", "Shiny only"}, minR, i -> {
            settings.setProperty("discord.minRarity", String.valueOf(i));
            BagTracker.shinyAnyBag = i == SHINY_ONLY;
            saveSettings();
            log(i == 0 ? "*wags* every watched bag goes to Discord again."
                    : i == SHINY_ONLY ? "*sparkly eyes* only bags with a shiny item go to Discord now, from every bag colour (the rest still show here)."
                    : "*sniffs carefully* only bags with a " + Enchant.values()[i].label + " or better item go to Discord now (the rest still show here).");
        }));
        section(home, rarityRow, 4);

        // ---- Start / stop
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        controls.setOpaque(false);
        startButton = new Pixel.Button("Start watching", true);
        startButton.setFont(Cute.font(Font.BOLD, 14));
        startButton.setBorder(new EmptyBorder(10, 22, 13, 22));
        startButton.addActionListener(e -> { if (processor == null) startSniffer(); else stopSniffer(); });
        autoStartBox = new Pixel.Check("Start watching when the app opens", Boolean.parseBoolean(settings.getProperty("autostart", "false")));
        autoStartBox.addActionListener(e -> saveSettings());
        status = new Pixel.Status();
        status.set("Asleep", false);
        Squeak.enabled = Boolean.parseBoolean(settings.getProperty("sounds", "true"));
        Squeak.pack = Squeak.Pack.of(settings.getProperty("sound.pack"));
        ChatPings.words = ChatPings.parse(settings.getProperty("chat.pings", ""));
        ChatPings.name = Boolean.parseBoolean(settings.getProperty("chat.pingName", "true"));
        ChatPings.whispers = Boolean.parseBoolean(settings.getProperty("chat.pingWhispers", "true"));
        ChatPings.saver = () -> {
            settings.setProperty("chat.pings", ChatPings.wordsText());
            settings.setProperty("chat.pingName", String.valueOf(ChatPings.name));
            settings.setProperty("chat.pingWhispers", String.valueOf(ChatPings.whispers));
            saveSettings();
        };
        try {
            Squeak.volume = Math.max(0, Math.min(100, Integer.parseInt(settings.getProperty("sound.volume", "100").trim())));
        } catch (NumberFormatException e) {
            Squeak.volume = 100;
        }
        soundBox = new Pixel.Check("Squeaks and boops", Squeak.enabled);
        soundBox.addActionListener(e -> { Squeak.enabled = soundBox.isSelected(); saveSettings(); if (Squeak.enabled) Squeak.boop(); });
        JPanel options = new JPanel();
        options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
        options.setOpaque(false);
        autoStartBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        soundBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        options.add(autoStartBox);
        if (WinStartup.supported()) {
            WinStartup.refresh(); // keeps it pointing at this folder if the pup was moved
            Pixel.Check withWindows = new Pixel.Check("Open White Bag Puppy when Windows starts", WinStartup.isOn());
            withWindows.setToolTipText("Tick \"Start watching when the app opens\" too, and it starts watching by itself");
            withWindows.addActionListener(e -> {
                String err = WinStartup.set(withWindows.isSelected());
                if (err != null) {
                    withWindows.setSelected(WinStartup.isOn());
                    log("*whimper* couldn't change starting with Windows: " + err);
                } else {
                    log(withWindows.isSelected() ? "*wags* I'll open by myself when Windows starts." : "*nods* I won't open by myself when Windows starts any more.");
                }
            });
            withWindows.setAlignmentX(Component.LEFT_ALIGNMENT);
            options.add(withWindows);
        }
        options.add(soundBox);
        AccountRouter.singleAccount = "true".equals(settings.getProperty("accounts.single"));
        Pixel.Check single = new Pixel.Check("I only play one account", AccountRouter.singleAccount);
        single.setToolTipText("Everything goes to one account and the account buttons above the tabs go away");
        single.addActionListener(e -> {
            AccountRouter.singleAccount = single.isSelected();
            settings.setProperty("accounts.single", String.valueOf(single.isSelected()));
            saveSettings();
            if (single.isSelected() && !accounts.isEmpty() && selected != accounts.get(0)) {
                selected = accounts.get(0);
                showPage();
            }
            refreshAccountBar();
            log(single.isSelected() ? "*boop* one account it is! The account buttons are gone." : "*boop* the pup will keep accounts apart again.");
        });
        single.setAlignmentX(Component.LEFT_ALIGNMENT);
        options.add(single);
        controls.add(startButton);
        controls.add(Box.createHorizontalStrut(18));
        controls.add(status);
        controls.add(Box.createHorizontalStrut(24));
        controls.add(options);
        section(home, controls, 26);

        buildGuildDeathHook(hooks);
        buildDiscordStatus(hooks);
        buildBackups(hooks);
        buildAccountMerge(hooks);

        // ---- Diary
        section(home, new Pixel.Heading("pup diary", 2), 26);
        logArea = new JTextArea(7, 50);
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setFont(Cute.font(Font.PLAIN, 12));
        logArea.setForeground(Pixel.INK);
        logArea.setBackground(Pixel.SHEET);
        logArea.setBorder(new EmptyBorder(8, 10, 8, 10));
        JScrollPane scroll = Pixel.scroll(logArea);
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        home.add(Box.createVerticalStrut(12));
        home.add(scroll);

        // Scroll the whole window when it's smaller than its contents (small screens, display scaling).
        JScrollPane windowScroll = new JScrollPane(root);
        windowScroll.setBorder(null);
        windowScroll.setOpaque(false);
        windowScroll.getViewport().setOpaque(false);
        windowScroll.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE); // keeps the still background clean while scrolling
        windowScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        windowScroll.getVerticalScrollBar().setUnitIncrement(24);
        windowScroll.getHorizontalScrollBar().setUnitIncrement(24);
        windowScroll.getVerticalScrollBar().setUI(new Pixel.ThinBar());
        windowScroll.getHorizontalScrollBar().setUI(new Pixel.ThinBar());
        windowScroll.getVerticalScrollBar().setOpaque(false);
        windowScroll.getHorizontalScrollBar().setOpaque(false);
        windowScroll.getVerticalScrollBar().setPreferredSize(new Dimension(12, 0));
        windowScroll.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 12));
        backdrop.add(windowScroll, BorderLayout.CENTER);

        frame.setContentPane(backdrop);
        frame.pack();
        // Never open bigger than the screen; the contents scroll instead of getting squashed.
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        boolean tooTall = frame.getHeight() > screen.height;
        int extra = tooTall ? windowScroll.getVerticalScrollBar().getPreferredSize().width : 0; // room for the scrollbar
        frame.setSize(Math.min(frame.getWidth() + extra, screen.width), Math.min(frame.getHeight(), screen.height));
        frame.setMinimumSize(new Dimension(Math.min(frame.getWidth(), 640), 280));
        frame.setLocationRelativeTo(null);
        root.setFocusable(true);
        int lastTab = java.util.Arrays.asList(tabNames).indexOf(settings.getProperty("ui.tab", "home"));
        if (lastTab > 0) {
            tabs.select(lastTab);
            showPage();
        }
        frame.setVisible(true);
        if (overlay != null) overlay.start();
        root.requestFocusInWindow();

        flushEarlyLog();
        if (bg != null) {
            log("*sniff* found background.png in " + new File("").getAbsolutePath() + " (fit: " + settings.getProperty("background.fit") + ", position: " + settings.getProperty("background.position") + ", card opacity " + settings.getProperty("card.opacity") + "%)");
        } else {
            log("*tilts head* no background.png in " + new File("").getAbsolutePath() + ", so using the plain colour.");
        }
        log("*wags tail* White Bag Puppy " + UpdateCheck.VERSION + " is awake!");
        log("*wags tail* hi! paste your webhook URL on Home > Settings, send a test post, then press Start watching.");
        packets.packetcapture.PacketProcessor.onBadDeath = () -> log("*tilts head* a death message came in that I couldn't fully read (the game may have changed it). Checking it anyway...");
        int fix = contest.loadFix();
        if (fix > 0) {
            log("*happy wiggle* fixed your UPE total with the updated scoring rules: " + fix + (fix == 1 ? " missing point" : " missing points") + " added back. New total: " + contest.total() + " points.");
            saveSettings();
        }
        int joinFix = contest.repairJoinedRun();
        if (joinFix != 0) {
            log("*tilts head* UPE: I checked your put-back run again and " + (joinFix > 0 ? "gave back " + joinFix : "took off " + (-joinFix))
                    + (Math.abs(joinFix) == 1 ? " point" : " points") + ". New total: " + contest.total() + " points.");
            saveSettings();
        }
        int reskinFix = contest.loadReskinFix();
        if (reskinFix > 0) {
            log("*tilts head* UPE: reskins now count as their original item, and reskins of tiered gear don't count, so I took "
                    + reskinFix + (reskinFix == 1 ? " point" : " points") + " off your total. New total: " + contest.total() + " points (same as Biscuit's).");
            saveSettings();
        }
        if (autoStartBox.isSelected()) startSniffer();
    }

    /** Holds the window's contents: fills the window when there's room, scrolls when there isn't. */
    private static class FitPanel extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 24; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return o == SwingConstants.VERTICAL ? r.height - 40 : r.width - 40; }
        // Always fill the window's width (no sideways scrolling). Switching between the two made the
        // scrollbars appear and disappear in a loop, which flashed.
        @Override public boolean getScrollableTracksViewportWidth() {
            return true;
        }
        @Override public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport && getParent().getHeight() >= getPreferredSize().height;
        }
    }

    /** Smoothly resizes the picture (in steps when shrinking a lot, so it stays sharp instead of grainy). */
    private static java.awt.image.BufferedImage scalePicture(java.awt.image.BufferedImage src, int w, int h) {
        java.awt.image.BufferedImage cur = src;
        int cw = src.getWidth(), ch = src.getHeight();
        do {
            cw = Math.max(w, cw / 2);
            ch = Math.max(h, ch / 2);
            if (cw < w) cw = w;
            if (ch < h) ch = h;
            if (cur.getWidth() <= w * 2 && cur.getHeight() <= h * 2) { cw = w; ch = h; }
            java.awt.image.BufferedImage next = new java.awt.image.BufferedImage(cw, ch, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(cur, 0, 0, cw, ch, null);
            g.dispose();
            cur = next;
        } while (cw != w || ch != h);
        return cur;
    }

    /** Loads background.png from the folder the app runs in, or returns null if there isn't one. */
    private static java.awt.image.BufferedImage loadBackground() {
        try {
            File f = new File("background.png");
            if (f.exists()) return javax.imageio.ImageIO.read(f);
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void section(JPanel page, JComponent c, int gapAbove) {
        if (gapAbove > 0) page.add(Box.createVerticalStrut(gapAbove));
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        page.add(c);
    }

    // ---------------------------------------------------------------- actions

    private void applyWatched() {
        Set<BagType> on = EnumSet.noneOf(BagType.class);
        for (Map.Entry<BagType, AbstractButton> e : bagBoxes.entrySet()) if (e.getValue().isSelected()) on.add(e.getKey());
        watchedBags = on;
        for (Account a : accounts) a.bags.setWatched(on);
        if (watchingLabel != null) {
            List<String> names = new java.util.ArrayList<>();
            for (BagType t : BagType.values()) if (on.contains(t)) names.add(t.displayName);
            String list = names.size() <= 1 ? String.join("", names)
                    : String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
            watchingLabel.setText(names.isEmpty() ? "Not watching any bags. Pick at least one." : "Watching for " + list + " bags.");
        }
    }

    private void sendTest() {
        String url = urlField.getText().trim();
        if (!checkUrl(url)) return;
        saveSettings();
        log("*runs off to Discord with a test post*");
        byte[] shot = null;
        if (shotBox != null && shotBox.isSelected()) {
            shot = GameShot.capture();
            if (shot == null) log("*tilts head* no game picture in the test post: " + GameShot.lastProblem + ". Open the game and try again.");
        }
        webhook.sendTest(url, pingField.getText().trim(), shot);
    }

    private boolean checkUrl(String url) {
        if (DiscordWebhook.isValidUrl(url)) return true;
        JOptionPane.showMessageDialog(frame,
                "That doesn't look like a Discord webhook URL.\n\n"
                        + "In Discord: Server Settings > Integrations > Webhooks > New Webhook > Copy Webhook URL.\n"
                        + "It should start with https://discord.com/api/webhooks/\n\n"
                        + "Or type /pup link in Discord and paste the Biscuit link it gives you.",
                "Webhook URL", JOptionPane.WARNING_MESSAGE);
        return false;
    }

    private void startSniffer() {
        if (processor != null) return;
        saveSettings();
        if (!DiscordWebhook.isValidUrl(urlField.getText().trim())) {
            log("*tilts head* no webhook URL yet, so i'll only bark in here, not in Discord.");
        }
        router.reset();
        processor = new PacketProcessor();
        processor.setDaemon(true);
        processor.start();
        startButton.setText("Stop watching");
        header.setWatching(true);
        status.set(bagsToday == 0 ? "Watching" : foundText(), true);
        log("*sniff sniff* watching for bags. If you're already in game, change area once (the Nexus works) so I can catch up.");
        // can't see the game after a while? work out why
        final PacketProcessor watching = processor;
        final long since = System.currentTimeMillis();
        Thread check = new Thread(() -> {
            try {
                Thread.sleep(45_000);
            } catch (InterruptedException e) {
                return;
            }
            if (processor != watching || PacketProcessor.lastPacketAt >= since) return;
            log("*tilts head* I've been watching for 45 seconds and haven't seen the game yet. Checking why...");
            for (String l : NetCheck.run()) log("*sniff* " + l);
        }, "net check");
        check.setDaemon(true);
        check.start();
    }

    private void stopSniffer() {
        if (processor == null) return;
        try {
            processor.stopSniffer();
        } catch (Exception e) {
            log("*whimper* trouble stopping: " + e);
        }
        processor = null;
        startButton.setText("Start watching");
        header.setWatching(false);
        status.set("Asleep", false);
        log("*curls up* nap time~ zzz");
    }

    private String foundText() {
        return "Watching, " + bagsToday + (bagsToday == 1 ? " bag" : " bags") + " found";
    }

    private void onBag(Account account, BagDrop drop) {
        drop.note = droughtNote(drop);
        if (overlay != null) overlay.noteBag(drop);
        SwingUtilities.invokeLater(() -> header.setLastWhite(lastDrop("white")));
        String items = String.join(", ", drop.itemNames());
        log((accounts.size() > 1 ? account.prefix() : "") + "*BARK BARK!* " + drop.bagName + " bag in " + (drop.mapName.isEmpty() ? "?" : drop.mapName) + "!!" + (items.isEmpty() ? "" : " " + items)
                + (drop.note == null ? "" : "\n    " + drop.note)
                + (drop.newToCollection.isEmpty() ? "" : "\n    *gasp* new for your collection: " + String.join(", ", drop.newToCollection) + "!"));
        SwingUtilities.invokeLater(() -> {
            bagsToday++;
            if (processor != null) status.set(foundText(), true);
            if (header != null) header.celebrate();
            if (account.bagLog != null) {
                account.bagLog.add(drop);
                bumpTab(account, java.util.Arrays.asList(tabNames).indexOf("loot"));
            }
        });
        String url = settings.getProperty("webhook.url", "");
        // UPE "Contest only": while the contest runs, the normal bag posts take a break (the contest posts carry on)
        if (contest.on() && contest.charId() >= 0 && "true".equals(settings.getProperty("contest.only"))) url = "";
        int min = minRarity();
        boolean rareEnough = min == 0;
        for (ItemInfo it : drop.items()) {
            if (min == SHINY_ONLY ? LootBook.isShiny(it.id) : it.enchant.ordinal() >= min && it.enchant != Enchant.NONE) rareEnough = true;
        }
        if (!rareEnough && DiscordWebhook.isValidUrl(url)) {
            log(min == SHINY_ONLY ? "*sniffs* no shiny item in it, so it stays off Discord."
                    : "*sniffs* nothing " + Enchant.values()[min].label + " or better in it, so it stays off Discord.");
        }
        if (rareEnough && DiscordWebhook.isValidUrl(url)) {
            if (shotBox != null && shotBox.isSelected()) {
                drop.gameShot = GameShot.capture();
                if (drop.gameShot == null && GameShot.lastProblem != null) {
                    log("*tilts head* no game picture this time: " + GameShot.lastProblem + ".");
                }
            }
            webhook.sendBag(url, settings.getProperty("ping.userId", ""), drop);
        }
        Squeak.play(drop.type);
        checkBadges(account);
    }

    /** The rarity filter's last choice: only bags with a shiny item. */
    private static final int SHINY_ONLY = 5;

    private int minRarity() {
        try {
            int v = Integer.parseInt(settings.getProperty("discord.minRarity", "0"));
            return v >= 0 && v <= SHINY_ONLY ? v : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void onChat(Account account, ChatLog.Line line) {
        String why = ChatPings.reason(line, account.chat.myName());
        if (why != null && !account.foreign()) {
            ChatPings.chime();
            String where = line.kind == ChatLog.Kind.GUILD ? " in guild chat" : line.kind == ChatLog.Kind.PARTY ? " in party chat" : "";
            String who = line.kind == ChatLog.Kind.WHISPER ? line.other : line.name;
            log((accounts.size() > 1 ? account.prefix() : "") + "*ears perk* " + (who.isEmpty() ? "Someone" : who)
                    + (why.equals("a whisper") ? " whispered you" : " said " + why + where));
        }
        SwingUtilities.invokeLater(() -> {
            if (account.chatPanel == null) return;
            account.chatPanel.added(line);
            if (line.kind == ChatLog.Kind.WHISPER || line.kind == ChatLog.Kind.GUILD || line.kind == ChatLog.Kind.PARTY) bumpTab(account, java.util.Arrays.asList(tabNames).indexOf("chat"));
        });
    }

    /** A realm event: listed on the Events tab; the ones you ticked also squeak and go in the diary. */
    private void onEvent(Account account, EventTracker.Entry e) {
        if (!EventTracker.pings(e.name)) return;
        e.pinged = true;
        Squeak.playEvent(e.name);
        log((accounts.size() > 1 ? account.prefix() : "") + "*ears up* " + e.name + "!" + (e.map.isEmpty() ? "" : " in " + e.map)
                + " (" + String.join(", ", e.how) + ")");
        SwingUtilities.invokeLater(() -> bumpTab(account, java.util.Arrays.asList(tabNames).indexOf("chat")));
    }

    // ---------------------------------------------------------------- accounts

    /** Makes another account with its own pages (from any thread). */
    private Account addAccount() {
        Account a = new Account(accounts.size(), host);
        Runnable ui = () -> {
            a.buildPanels();
            for (JComponent page : new JComponent[]{a.bagLog, a.chatPanel, a.dpsPanel, a.aboutPanel, a.questsPanel, a.vaultPage, a.lootPage, a.bookPage, a.dungeonPage, a.killsPage, a.puzzlePage, a.badgesPage, a.wardrobePage, a.titlesPage, a.eventsPage, a.famePage, a.tradesPage, a.chestPage, a.holidayPage, a.fameBonusPage, a.playersPage, a.treasuryPage, a.dpsCalcPage, a.charactersPage, a.lootHistoryPage, a.shinyPage, a.contestPage, a.realmsPage}) {
                page.putClientProperty("pixel.ownScroll", Boolean.TRUE); // these scroll inside themselves
            }
            // related pages share a tab, with a switch along the top
            // related pages share a tab, with a switch along the top (and a second switch for close relatives)
            GroupPage bags = a.bagsGroup = new GroupPage(new String[]{"This session", "All time"}, a.bagLog, a.lootHistoryPage);
            GroupPage collection = a.collectionGroup = new GroupPage(new String[]{"Items", "Shinies", "Dungeons", "Holidays", "Quest Chests"},
                    a.bookPage, a.shinyPage, a.dungeonPage, a.holidayPage, a.chestPage);
            a.lootGroup = new GroupPage(new String[]{"Bags", "Stats", "Collection"}, bags, a.lootPage, collection);
            a.fightsGroup = new GroupPage(new String[]{"DPS", "History", "Boss Kills", "DPS Calc", "Puzzle"}, a.dpsPanel, a.fightHistoryPage, a.killsPage, a.dpsCalcPage, a.puzzlePage);
            a.badgesPage.checkNow = () -> checkBadges(a); // opening Badges unlocks anything newly earned
            GroupPage chars = new GroupPage(new String[]{"This character", "All characters"}, a.aboutPanel, a.charactersPage);
            GroupPage fame = new GroupPage(new String[]{"Fame", "Fame Bonuses"}, a.famePage, a.fameBonusPage);
            GroupPage badges = new GroupPage(new String[]{"Badges", "Titles", "Wardrobe"}, a.badgesPage, a.titlesPage, a.wardrobePage);
            a.meGroup = new GroupPage(new String[]{"Characters", "Quests", "Fame", "Vault", "Badges"}, chars, a.questsPanel, fame, a.treasuryPage, badges);
            a.chatGroup = new GroupPage(new String[]{"Chat", "Trades", "Players", "Events", "Realms"}, a.chatPanel, a.tradesPage, a.playersPage, a.eventsPage, a.realmsPage);
            for (JComponent page : new JComponent[]{bags, collection, chars, fame, badges, a.lootGroup, a.fightsGroup, a.meGroup, a.chatGroup}) {
                page.putClientProperty("pixel.ownScroll", Boolean.TRUE);
            }
            sheet.add(a.lootGroup, "loot@" + a.index);
            sheet.add(a.fightsGroup, "fights@" + a.index);
            sheet.add(a.meGroup, "me@" + a.index);
            sheet.add(a.chatGroup, "chat@" + a.index);
            sheet.add(a.contestPage, "UPE@" + a.index);
        };
        if (SwingUtilities.isEventDispatchThread()) ui.run();
        else {
            try {
                SwingUtilities.invokeAndWait(ui);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        accounts.add(a);
        if (a.index > 0) log("*ears perk up* another game account! You can switch between them above the tabs.");
        SwingUtilities.invokeLater(this::refreshAccountBar);
        return a;
    }

    /** The header search: bags you've had with it (Loot > Bags > All time), else the collection, else the player. */
    private void findThing(String text) {
        String q = text == null ? "" : text.trim();
        if (q.isEmpty() || selected == null) return;
        Account a = selected;
        String lower = q.toLowerCase(java.util.Locale.ROOT);
        for (PlayersTracker.Player p : a.players.players()) {
            if (p.name().toLowerCase(java.util.Locale.ROOT).contains(lower)) {
                tabs.select(java.util.Arrays.asList(tabNames).indexOf("chat"));
                a.chatGroup.jump(2);
                return;
            }
        }
        tabs.select(java.util.Arrays.asList(tabNames).indexOf("loot"));
        if (a.lootHistoryPage.has(q)) {
            a.lootGroup.jump(0);
            a.bagsGroup.jump(1);
            a.lootHistoryPage.search(q);
        } else {
            a.lootGroup.jump(2);
            a.collectionGroup.jump(0);
            a.bookPage.search(q);
        }
    }

    /** Counts something new on a tab, on the account's own badges if another account is showing (Swing thread). */
    private void bumpTab(Account account, int tab) {
        if (account == selected) {
            tabs.bump(tab);
        } else {
            account.unread[tab] = Math.min(99, account.unread[tab] + 1);
            refreshAccountBar();
        }
    }

    /** Shows the current tab for the selected account. */
    private void showPage() {
        String name = tabNames[tabs.selected()];
        pages.show(sheet, name.equals("home") ? name : name + "@" + selected.index);
        if (!name.equals(settings.getProperty("ui.tab", "home"))) {
            settings.setProperty("ui.tab", name); // opens here next time
            saveSettings();
        }
        sheet.revalidate(); // the sheet is as tall as the page on show
        sheet.repaint();
    }

    /** The row of account buttons above the tabs (hidden while there's only one account). */
    private void refreshAccountBar() {
        if (accountBar == null) return;
        accountBar.removeAll();
        boolean several = accounts.size() > 1 && !AccountRouter.singleAccount;
        accountBar.setVisible(several);
        if (several) {
            String[] labels = new String[accounts.size()];
            for (int i = 0; i < labels.length; i++) {
                Account a = accounts.get(i);
                int news = 0;
                if (a != selected) for (int n : a.unread) news += n;
                labels[i] = a.label() + (news > 0 ? " (" + news + ")" : "");
            }
            JLabel l = Pixel.label("Account ");
            accountBar.add(l);
            accountBar.add(Box.createHorizontalStrut(6));
            accountBar.add(new LogTabs.Segments(labels, selected.index, i -> {
                int[] mine = tabs.unread();
                System.arraycopy(mine, 0, selected.unread, 0, Math.min(mine.length, selected.unread.length));
                selected = accounts.get(i);
                tabs.setUnread(selected.unread);
                java.util.Arrays.fill(selected.unread, 0);
                showPage();
                refreshAccountBar();
            }));
            accountBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, accountBar.getPreferredSize().height));
            accountBar.setBorder(new EmptyBorder(0, 0, 8, 0));
        }
        accountBar.revalidate();
        accountBar.repaint();
    }

    /**
     * Drought tracker: remembers when each bag colour last dropped (saved in the settings file, so it
     * survives restarts) and returns a celebration line after a long gap, or for the very first one.
     */
    private long lastDrop(String colour) {
        try {
            return Long.parseLong(settings.getProperty("lastDrop." + colour, "0").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String droughtNote(BagDrop drop) {
        String colour = drop.bagName.replace("Boosted ", "").toLowerCase(java.util.Locale.ROOT);
        String key = "lastDrop." + colour.replace(' ', '_');
        String prev = settings.getProperty(key);
        settings.setProperty(key, String.valueOf(drop.droppedAt));
        SwingUtilities.invokeLater(this::saveSettings);
        if (prev == null) return "*happy howl* your very first " + colour + " bag with me!! \uD83C\uDF89";
        long gap;
        try {
            gap = drop.droppedAt - Long.parseLong(prev.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return droughtLine(colour, gap);
    }

    static String droughtLine(String colour, long gapSeconds) {
        long day = 86_400;
        if (gapSeconds >= 2 * day) return "*happy howl* first " + colour + " bag in " + (gapSeconds / day) + " days!!";
        if (gapSeconds >= day) return "*happy howl* first " + colour + " bag in " + (gapSeconds / 3600) + " hours!!";
        return null;
    }


    // ---------------------------------------------------------------- settings

    private void loadSettings() {
        File f = new File(SETTINGS_FILE);
        if (!f.exists()) return;
        try {
            // Notepad can save with a byte-order mark or as UTF-16 ("Unicode"): read it either way
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            String text;
            if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) text = new String(b, 2, b.length - 2, java.nio.charset.StandardCharsets.UTF_16LE);
            else if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) text = new String(b, 2, b.length - 2, java.nio.charset.StandardCharsets.UTF_16BE);
            else if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) text = new String(b, 3, b.length - 3, java.nio.charset.StandardCharsets.UTF_8);
            else text = null;
            if (text != null) settings.load(new java.io.StringReader(text));
            else try (InputStream in = new FileInputStream(f)) {
                settings.load(in);
            }
        } catch (Exception e) {
            log("*whimper* couldn't read " + SETTINGS_FILE + ": " + e);
        }
        moveBiscuitLinks();
    }

    /**
     * Biscuit's old homes: any Railway address (it had more than one) when the link goes to Biscuit's own paths
     * (/pup/... or /recap), which no Discord webhook or other site uses. Its links keep their code, so only the address changes.
     */
    static final java.util.regex.Pattern BISCUIT_OLD_HOST =
            java.util.regex.Pattern.compile("(?i)https?://[a-z0-9-]+\\.up\\.railway\\.app(?=/(?:pup/|recap(?:[/?#]|$)))");
    static final String BISCUIT_HOME = "https://femcore.gay";

    /** One saved value with any old Biscuit address swapped for the new one. */
    static String movedBiscuitLink(String value) {
        return value == null ? null : BISCUIT_OLD_HOST.matcher(value).replaceAll(BISCUIT_HOME);
    }

    /** Biscuit moved: point every saved link (bags, badges, deaths, recaps, contest) at the new address, once. */
    private void moveBiscuitLinks() {
        int moved = 0;
        for (String key : settings.stringPropertyNames()) {
            String value = settings.getProperty(key, "");
            String updated = movedBiscuitLink(value);
            if (!updated.equals(value)) {
                settings.setProperty(key, updated);
                moved++;
            }
        }
        if (moved == 0) return;
        try (OutputStream out = new FileOutputStream(SETTINGS_FILE)) {   // the window isn't built yet, so save directly
            settings.store(out, "White Bag Notifier settings (keep your webhook URL private)");
        } catch (Exception e) {
            log("*whimper* couldn't save the new Biscuit address: " + e);
        }
        log("*wags tail* Biscuit moved to femcore.gay, so i updated your " + moved + " Biscuit link" + (moved == 1 ? "" : "s") + " to match.");
    }

    private synchronized boolean saveSettings() {
        String url = urlField.getText().trim();
        String ping = pingField.getText().trim();
        if (!ping.isEmpty() && !DiscordWebhook.isValidDiscordId(ping)) {
            log("*tilts head* the ping user ID should be a number like 123456789012345678. i won't use it until it's fixed.");
        }
        settings.setProperty("webhook.url", url);
        settings.setProperty("ping.userId", ping);
        settings.setProperty("autostart", String.valueOf(autoStartBox != null && autoStartBox.isSelected()));
        if (soundBox != null) settings.setProperty("sounds", String.valueOf(soundBox.isSelected()));
        if (shotBox != null) settings.setProperty("screenshot", String.valueOf(shotBox.isSelected()));
        for (Map.Entry<BagType, AbstractButton> e : bagBoxes.entrySet()) {
            settings.setProperty(e.getKey().settingKey(), String.valueOf(e.getValue().isSelected()));
        }
        try (OutputStream out = new FileOutputStream(SETTINGS_FILE)) {
            settings.store(out, "White Bag Notifier settings (keep your webhook URL private)");
            return true;
        } catch (Exception e) {
            log("*whimper* couldn't save settings: " + e);
            return false;
        }
    }

    // ---------------------------------------------------------------- logging

    private final StringBuilder earlyLog = new StringBuilder();

    private void log(String msg) {
        String line = "[" + new SimpleDateFormat("HH:mm:ss").format(new Date()) + "] " + LogTabs.noEmoji(msg) + "\n";
        System.out.print(line);
        SwingUtilities.invokeLater(() -> {
            if (logArea == null) {
                earlyLog.append(line);
                return;
            }
            logArea.append(line);
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void flushEarlyLog() {
        if (earlyLog.length() > 0) {
            logArea.append(earlyLog.toString());
            earlyLog.setLength(0);
        }
    }
}
