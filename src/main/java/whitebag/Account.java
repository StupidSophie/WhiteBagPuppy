package whitebag;

import packets.data.enums.StatType;

import javax.swing.*;
import java.util.Set;

/**
 * One game account: its own packet dispatcher, trackers (bags, chat, DPS, character/pet/quests, vault)
 * and the pages that show them. With two game clients open there are two of these.
 */
final class Account {
    interface Host {
        void bag(Account a, BagDrop drop);

        /** Checks this account's badges now (something that might unlock one just happened). */
        void badgesMaybe(Account a);

        void chat(Account a, ChatLog.Line line);

        /** A realm event this account hasn't seen yet. */
        void event(Account a, EventTracker.Entry e);

        void log(String text);

        /** Something about the account list changed (a new account, or a new name). */
        void accountsChanged();

        /** The account that owns files saved before accounts were tracked (assigned to the first one seen). */
        String legacyOwner(String claimingKey);

        Set<BagType> watched();

        /** Something that has killed this account's characters before just showed up. */
        default void danger(Account a, String enemy, int deaths, int nearly) {
        }

        /** Alien invasion wormhole generators seen so far in this realm: kind -> how many; {@code fresh} is the kind that just showed up. */
        default void wormholes(Account a, String where, java.util.Map<String, Integer> seen, String fresh) {
        }

        /** You nearly died but didn't. */
        /** The game's death notice for someone (a guildmate) who died anywhere. */
        default void deathNotice(Account a, DeathNotice n) {
        }

        /** One of your characters died. */
        default void myDeath(Account a, DeathLog.Death d, String gearData) {
        }

        /** A guildmate died near you (a gravestone appeared where they stood). */
        default void guildDeath(Account a, PlayersTracker.Gone g, String map) {
        }

        default void closeCall(Account a, CloseCalls.Call c) {
        }

        /** The UPE contest's controls (null: no contest page). */
        default ContestPage.Ctl contestCtl() {
            return null;
        }

        /** Any bag of this account's, for the UPE contest. */
        default void contestBag(Account a, BagDrop drop) {
        }

        /** An item in this character's slots went up in rarity where it sits, for the UPE contest. */
        default void contestUpgrade(Account a, UpgradeWatch.Upgrade u) {
        }

        /** An item forged at the Forge (with or without Concentrated Soul Fire), for the UPE contest. */
        default void contestCraft(Account a, int itemId, boolean soulFire, Enchant enchant) {
        }

        /** One of this account's characters died, for the UPE contest. */
        default void contestDied(Account a, int charId, String killedBy) {
        }

        /** A bag with a shiny item in it (any bag colour). */
        default void shiny(Account a, BagDrop drop, java.util.List<String> shinies) {
        }
    }

    final int index;
    private final Host host;
    private volatile String key;   // the game's account id; null until this account has been identified
    private volatile String label = "";
    final Bus bus = new Bus();
    /** New-thing counts on the tabs while another account is shown. */
    final int[] unread = new int[16];

    final BagTracker bags;
    final ChatLog chat;
    final DpsTracker dps;
    final MeTracker me;
    final VaultStore vault;
    final EventTracker events;
    final TradeLog trades;
    final PlayersTracker players;
    PlayersPage playersPage;
    TreasuryPage treasuryPage;
    CharactersPage charactersPage;
    LootHistoryPage lootHistoryPage;
    ShinyPage shinyPage;
    ContestPage contestPage;
    DpsCalcPage dpsCalcPage;
    private String visitMap;
    private long visitStart;
    /** What's killed this account's characters (lower-case name -> times), and who's been warned about on this map. */
    private volatile java.util.Map<String, Integer> killers, nearly;
    final CloseCalls closeCalls;
    /** The game server's address (set by AccountRouter), and the server name once the pup has worked it out. */
    volatile String serverIp = "";
    volatile String server = "";
    private String lastMapIp = "";
    private long lastMapAt;

    /** The last few hits you took (for a death). */
    private String lastHits() {
        return closeCalls.lastHits();
    }
    /** The last Forge request: did it use Concentrated Soul Fire, and what was it making. */
    private volatile boolean forgeWithSoulFire;
    private volatile int forgeWants;
    private final java.util.Set<String> warned = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    /** Wormhole generators seen in this realm (object id -> kind), and the kinds already reported. */
    private final java.util.Map<Integer, String> wormholeSeen = new java.util.HashMap<>();
    private final java.util.Set<String> wormholeKinds = new java.util.HashSet<>();
    /** The dungeon modifiers where you are now (empty outside dungeons), for the widget bar and the overlay. */
    volatile DungeonMods.Here mods;
    /** When the game last ticked (ms), 0 if never. */
    volatile long lastTick;


    LogTabs.BagLogPanel bagLog;
    LogTabs.ChatPanel chatPanel;
    LogTabs.DpsPanel dpsPanel;
    AboutMe.Panel aboutPanel;
    /** Me > Tinker Quests. */
    AboutMe.Panel questsPanel;
    MoreTabs.VaultPage vaultPage;
    MoreTabs.LootPage lootPage;
    BookPage bookPage;
    DungeonPage dungeonPage;
    KillsPage killsPage;
    FightHistoryPage fightHistoryPage;
    PuzzlePage puzzlePage;
    BadgesPage badgesPage;
    WardrobePage wardrobePage;
    TitlesPage titlesPage;
    /** The grouped tabs (Loot, Fights, Me, Chat), made by the main window. */
    GroupPage lootGroup, fightsGroup, meGroup, chatGroup, bagsGroup, collectionGroup;
    final ShattersPuzzle puzzle;
    MoreTabs.EventsPage eventsPage;
    RealmsPage realmsPage;
    MoreTabs.FamePage famePage;
    MoreTabs.TradesPage tradesPage;
    QuestChestPage chestPage;
    FameBonusPage fameBonusPage;
    HolidayPage holidayPage;
    final ChestLog chests;

    Account(int index, Host host) {
        this.index = index;
        this.host = host;
        bags = new BagTracker(d -> host.bag(this, d));
        chat = new ChatLog(l -> host.chat(this, l));
        dps = new DpsTracker(() -> {
            if (dpsPanel != null) dpsPanel.changed();
        });
        me = new MeTracker(this::meChanged);
        players = new PlayersTracker(() -> { if (playersPage != null) playersPage.changed(); });
        players.log = t -> host.log(prefix() + t);
        players.onGone = g -> {
            dps.noteGone(g.name, g.died, g.at);
            // a guildmate's death: posted live to Discord
            String mine = me.myGuild();
            if (g.died && !mine.isEmpty() && mine.equalsIgnoreCase(g.thing.s(packets.data.enums.StatType.GUILD_NAME_STAT).trim())) {
                String map = visitMap();
                host.guildDeath(this, g, map == null ? "" : map);
            }
        };
        vault = new VaultStore(() -> {
            if (vaultPage != null) vaultPage.changed();
            if (treasuryPage != null) treasuryPage.changed();
        }, t -> host.log(prefix() + t));
        // everything in the vault, gift chest and seasonal spoils counts for the Collection; a seasonal character has its
        // own vault. A 4th (null) entry means it's the saved copy from last time, so it isn't known if it was seasonal.
        vault.onRead = v -> {
            if (key == null || (v.length < 4 && foreign())) return;
            boolean seasonal = v.length < 4 && Owned.seasonal(me.character());
            String where = seasonal ? Owned.SEASONAL_VAULT : Owned.VAULT;
            int n = Owned.note(key, where, v[0]) + Owned.note(key, Owned.GIFT_CHEST, v[1])
                    + (v.length > 2 ? Owned.note(key, Owned.SEASONAL_SPOILS, v[2]) : 0);
            if (n > 0) {
                if (bookPage != null) bookPage.changed();
                if (dungeonPage != null) dungeonPage.changed();
            }
        };
        bags.onAnyBag = d -> {
            // anything you'd never looted before? (checked before this bag is logged)
            try {
                java.util.List<LootStats.Entry> mineBefore = new java.util.ArrayList<>();
                for (LootStats.Entry e : LootStats.all()) if (isMine(e.account)) mineBefore.add(e);
                d.newToCollection = LootBook.newItems(d.items(), mineBefore);
            } catch (Throwable ignored) {
            }
            // who looted it and which enemy dropped it, for Loot History's filters
            try {
                d.enemy = dps.enemyNear(d.x, d.y);
                d.charClass = me.myClassType();
                d.charId = me.charId();
                MeTracker.Thing looter = me.character();
                d.seasonal = looter == null ? -1 : Owned.seasonal(looter) ? 1 : 0;
            } catch (Throwable ignored) {
            }
            LootStats.add(d, key);
            java.util.List<String> shinies = new java.util.ArrayList<>();
            for (ItemInfo it : d.items()) if (LootBook.isShiny(it.id)) shinies.add(it.name);
            if (!shinies.isEmpty() && !foreign()) host.shiny(this, d, shinies);
            host.contestBag(this, d); // the contest goes by character number, not by which account the pup thinks this is
            if (lootPage != null) lootPage.changed();
            if (lootHistoryPage != null) lootHistoryPage.changed();
            if (shinyPage != null && !shinies.isEmpty()) shinyPage.changed();
            if (bookPage != null) bookPage.changed();
            if (dungeonPage != null) dungeonPage.changed();
        };
        events = new EventTracker(e -> host.event(this, e), () -> {
            if (eventsPage != null) eventsPage.changed();
        });
        trades = new TradeLog(() -> key, () -> {
            if (tradesPage != null) tradesPage.changed();
        });
        trades.register(bus);
        chests = new ChestLog(() -> key, o -> {
            host.log(prefix() + "*sniff sniff* opened a " + o.chestName().toLowerCase(java.util.Locale.ROOT) + ": " + AboutMe.names(o.items) + "!");
            if (!foreign()) Owned.note(key, Owned.VAULT, o.items.stream().mapToInt(Integer::intValue).toArray()); // they're yours now
            if (chestPage != null) chestPage.changed();
            if (bookPage != null) bookPage.changed();
            if (holidayPage != null) holidayPage.changed();
        });
        chests.register(bus);
        // each visit to a dungeon is a run, for drop rates
        // the game ticks several times a second while you're in: how the Discord status knows you're playing
        bus.register(packets.PacketType.NEWTICK, p -> lastTick = System.currentTimeMillis());
        // a guildmate's death notice (the gravestone message at the top left), from anywhere
        bus.register(packets.PacketType.NOTIFICATION, p -> {
            DeathNotice n = DeathNotice.of((packets.incoming.NotificationPacket) p);
            if (n != null && !n.name.equalsIgnoreCase(me.myName())) host.deathNotice(this, n);
            String bad = DeathNotice.unreadable;
            if (n == null && bad != null) {
                DeathNotice.unreadable = null;
                host.log(prefix() + "a guild death message came that I couldn't read (it's in death-notices-debug.txt)");
            }
        });
        bus.register(packets.PacketType.MAPINFO, p -> {
            packets.incoming.MapInfoPacket m = (packets.incoming.MapInfoPacket) p;
            long now = System.currentTimeMillis();
            warned.clear();
            synchronized (wormholeSeen) {
                wormholeSeen.clear();
                wormholeKinds.clear();
            }
            AttiFeed.myRealm = null;
            String ip = serverIp;
            boolean recent = now - lastMapAt < 10 * 60_000L;
            String was = server;
            server = ServerNames.arrived(ip, lastMapIp, was, recent, "Nexus".equalsIgnoreCase(m.name), m.realmName);
            lastMapIp = ip;
            lastMapAt = now;
            if (!server.isEmpty() && !server.equals(was)) host.log(prefix() + "*sniffs the air* you're on " + server + ".");
            synchronized (this) {
                if (visitMap != null) RunLog.add(visitMap, key, visitStart, now);
                visitMap = BagTracker.cleanName(m.name, m.displayName);
                visitStart = now;
            }
            // the dungeon's modifiers and the bonuses they give
            try {
                DungeonMods.Here here = DungeonMods.of(m, BagTracker.cleanName(m.name, m.displayName));
                mods = here;
                if (!here.isEmpty()) host.log(prefix() + "*sniff sniff* " + here.map + " mods: " + here.diary());
            } catch (Throwable e) {
                mods = null;
            }
            if (lootPage != null) lootPage.changed();
            if (lootHistoryPage != null) lootHistoryPage.changed();
            if (bookPage != null) bookPage.changed();
            if (dungeonPage != null) dungeonPage.changed();
        });
        // alien invasion wormhole generators in this realm (only the ones that come into view: the game doesn't send the rest)
        bus.register(packets.PacketType.UPDATE, p -> {
            packets.incoming.UpdatePacket u = (packets.incoming.UpdatePacket) p;
            if (u.newObjects == null || foreign()) return;
            String fresh = null;
            java.util.Map<String, Integer> counts = null;
            synchronized (wormholeSeen) {
                for (packets.data.ObjectData o : u.newObjects) {
                    if (o == null || o.status == null) continue;
                    String kind = AttiFeed.wormhole(DpsTracker.enemyName(o.objectType));
                    if (kind == null || wormholeSeen.containsKey(o.status.objectId)) continue;
                    wormholeSeen.put(o.status.objectId, kind);
                    if (wormholeKinds.add(kind)) fresh = fresh == null ? kind : fresh + ", " + kind;
                }
                if (fresh != null) {
                    counts = new java.util.TreeMap<>();
                    for (String k : wormholeSeen.values()) counts.merge(k, 1, Integer::sum);
                }
            }
            if (counts != null) host.wormholes(this, visitMap, counts, fresh);
        });
        // something that's killed you before has come into view
        bus.register(packets.PacketType.UPDATE, p -> {
            packets.incoming.UpdatePacket u = (packets.incoming.UpdatePacket) p;
            if (u.newObjects == null || key == null || foreign()) return;
            java.util.Map<String, Integer> k = killers, n = nearly;
            if (k == null) killers = k = DeathLog.killers(key);
            if (n == null) nearly = n = CloseCalls.nearly(key);
            if (k.isEmpty() && n.isEmpty()) return;
            for (packets.data.ObjectData o : u.newObjects) {
                if (o == null || !DpsTracker.isMonster(o.objectType)) continue; // not a pet named after a boss, say
                String name = DpsTracker.enemyName(o.objectType), nk = DeathLog.key(name);
                int deaths = k.getOrDefault(nk, 0), near = n.getOrDefault(nk, 0);
                if (deaths + near == 0 || !warned.add(nk)) continue;
                host.danger(this, DpsTracker.cleanName(name), deaths, near);
            }
        });
        // the Forge: a craft that uses Concentrated Soul Fire scores in the UPE contest
        bus.register(packets.PacketType.FORGE_REQUEST, p -> {
            packets.outgoing.ForgeRequestPacket fr = (packets.outgoing.ForgeRequestPacket) p;
            forgeWithSoulFire = Contest.usesSoulFire(fr);
            forgeWants = fr.resultItemType;
        });
        bus.register(packets.PacketType.FORGE_RESULT, p -> {
            packets.incoming.ForgeResultPacket r = (packets.incoming.ForgeResultPacket) p;
            boolean soulFire = forgeWithSoulFire;
            int item = Contest.forgedItem(forgeWants, r.results);
            forgeWithSoulFire = false;
            forgeWants = 0;
            if (!r.success || item <= 0) return; // failed, or dismantling
            int slot = Contest.forgedSlot(item, r.results);
            // give the game a moment to send the new item's details (its modification), then score it
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                }
                MeTracker.Thing ch = me.character();
                Enchant en = Contest.enchantIn(ch == null ? null : ch.s(packets.data.enums.StatType.UNIQUE_DATA_STRING), slot);
                host.contestCraft(this, item, soulFire, en);
            }, "forge score");
            t.setDaemon(true);
            t.start();
        });
        // one of your characters died: into the graveyard, and off the Characters list
        bus.register(packets.PacketType.DEATH, p -> {
            packets.incoming.DeathPacket dp = (packets.incoming.DeathPacket) p;
            dps.noteGone(DpsTracker.YOU, true, System.currentTimeMillis()); // your line on the DPS boards
            // the game only sends this to the player who died, so the contest takes it whatever the account checks say
            host.contestDied(this, dp.charId >= 0 ? dp.charId : me.charId(), dp.killedBy == null ? "" : DpsTracker.cleanName(dp.killedBy));
            if (key == null || foreign()) return;
            if (dp.accountId != null && !dp.accountId.isEmpty() && !dp.accountId.equals(key)) return;
            DeathLog.Death d = new DeathLog.Death();
            d.account = key;
            d.charId = dp.charId >= 0 ? dp.charId : me.charId();
            d.at = System.currentTimeMillis() / 1000;
            d.killedBy = dp.killedBy == null ? "" : DpsTracker.cleanName(dp.killedBy);
            d.where = me.mapTitle();
            d.totalFame = dp.totalFame;
            d.bonuses = DeathLog.bonuses(dp.fameData);
            CharacterBags.Char c = CharacterBags.get(key, d.charId);
            MeTracker.Thing live = me.character();
            if (c != null) {
                d.classType = c.classType;
                d.name = c.name;
                d.seasonal = c.seasonal;
                d.level = c.level;
                d.baseFame = c.fame;
                d.gear = c.gear;
                d.bags = c.bags;
                d.stats = c.stats;
            }
            if (live != null && d.charId == me.charId()) {
                if (d.classType < 0) d.classType = live.objectType;
                if (d.name.isEmpty()) d.name = DpsTracker.cleanName(live.s(packets.data.enums.StatType.NAME_STAT));
                int lv = live.i(packets.data.enums.StatType.LEVEL_STAT, -1), fm = live.i(packets.data.enums.StatType.CURR_FAME_STAT, -1);
                if (lv > 0) d.level = lv;
                if (fm >= 0) d.baseFame = fm;
                d.seasonal = d.seasonal || Owned.seasonal(live);
                // equipment straight from the character, when the saved copy doesn't have it (a new character, say)
                boolean noGear = d.gear == null || d.gear.length < 4;
                if (!noGear) {
                    noGear = true;
                    for (int k = 0; k < 4; k++) if (d.gear[k] > 0) noGear = false;
                }
                if (noGear) {
                    packets.data.enums.StatType[] slots = {packets.data.enums.StatType.INVENTORY_0_STAT, packets.data.enums.StatType.INVENTORY_1_STAT,
                            packets.data.enums.StatType.INVENTORY_2_STAT, packets.data.enums.StatType.INVENTORY_3_STAT};
                    int[] gear = new int[4];
                    for (int k = 0; k < 4; k++) gear[k] = live.i(slots[k], -1);
                    d.gear = gear;
                }
            }
            if (d.name == null) d.name = "";
            String hitsBefore = lastHits();
            if (!hitsBefore.isEmpty()) d.lastHits = hitsBefore;
            if (!DeathLog.add(d)) return;
            // its enchantments, while the character is still loaded
            String gearData = live != null && d.charId == me.charId() ? live.s(packets.data.enums.StatType.UNIQUE_DATA_STRING) : "";
            host.myDeath(this, d, gearData);
            killers = null; // worked out again with this one
            CharacterBags.forget(key, d.charId);
            String cls = AboutMe.objectName(d.classType, "character");
            host.log(prefix() + "*sad whimper* your " + cls + " died" + (d.killedBy.isEmpty() ? "" : " to " + d.killedBy)
                    + (d.where.isEmpty() ? "" : " in " + d.where) + (d.totalFame > 0 ? String.format(java.util.Locale.ROOT, " (%,d fame)", d.totalFame) : "")
                    + "." + (d.lastHits == null ? "" : " Last hits: " + d.lastHits + ".") + " It's in Me > Characters > All characters > Graveyard.");
            if (charactersPage != null) charactersPage.changed();
        });
        puzzle = new ShattersPuzzle(green -> {
            if (puzzlePage != null) puzzlePage.boardSeen(green);
        });
        puzzle.register(bus);
        dps.onRunEnd = (run, boards) -> {
            History.addRun(run, boards, key);
            FightLog.add(run, boards, key);
            if (fightHistoryPage != null) fightHistoryPage.changed();
            // a dungeon's boss died on this character: tick it for the fame bonuses
            boolean bossDied = false;
            for (DpsTracker.Board b : boards) if (b.killed && b.kind == DpsTracker.Kind.BOSS) bossDied = true;
            int cid = me.charId();
            if (bossDied && key != null && cid >= 0 && !foreign()) {
                MeTracker.Thing ch = me.character();
                if (ch != null) FameDungeons.name(key, cid, AboutMe.objectName(ch.objectType, "Character"));
                String first = FameDungeons.completed(key, cid, run.mapName);
                if (first != null) host.log(prefix() + "*proud wag* first " + first + " on this character! (Me > Fame > Fame Bonuses)");
                if (fameBonusPage != null) fameBonusPage.changed();
            }
            if (killsPage != null) killsPage.changed();
        };
        // realm bosses and events count as soon as they die
        dps.onKill = (run, board) -> {
            History.addRun(run, java.util.Collections.singletonList(board), key);
            FightLog.add(run, java.util.Collections.singletonList(board), key);
            if (fightHistoryPage != null) fightHistoryPage.changed();
            if (killsPage != null) killsPage.changed();
        };
        closeCalls = new CloseCalls(new CloseCalls.Me() {
            public String account() { return foreign() ? null : key; }
            public MeTracker tracker() { return me; }
            public String map() { String m = me.mapTitle(); return m == null ? "" : m; }
        }, c -> {
            nearly = null; // counted for danger warnings from now on
            host.closeCall(this, c);
            if (charactersPage != null) charactersPage.changed();
        });
        bags.setWatched(host.watched());
        bags.register(bus);
        chat.register(bus);
        dps.register(bus);
        me.register(bus);
        closeCalls.register(bus);
        closeCalls.onZero = () -> host.contestDied(this, me.charId(), "");
        me.onUpgrade = u -> host.contestUpgrade(this, u);
        players.register(bus);
        vault.register(bus);
        // realm events now come from the RealmStock feed (Events tab), not from this client
    }

    /** Builds this account's pages (call on the Swing thread). */
    void buildPanels() {
        bagLog = new LogTabs.BagLogPanel();
        chatPanel = new LogTabs.ChatPanel(chat);
        dpsPanel = new LogTabs.DpsPanel(dps);
        fightHistoryPage = new FightHistoryPage(this::isMine);
        aboutPanel = new AboutMe.Panel(me);
        questsPanel = new AboutMe.Panel(me, true);
        vaultPage = new MoreTabs.VaultPage(vault);
        eventsPage = new MoreTabs.EventsPage(events);
        realmsPage = new RealmsPage();
        famePage = new MoreTabs.FamePage(me);
        tradesPage = new MoreTabs.TradesPage(t -> isMine(t.account));
        playersPage = new PlayersPage(players);
        treasuryPage = new TreasuryPage(vault, this::isMine);
        charactersPage = new CharactersPage(this::isMine);
        shinyPage = new ShinyPage(this::isMine);
        if (host.contestCtl() != null) contestPage = new ContestPage(this, host.contestCtl());
        lootHistoryPage = new LootHistoryPage(this::isMine);
        dpsCalcPage = new DpsCalcPage(me, vault, this::isMine);
        chestPage = new QuestChestPage(this::isMine);
        fameBonusPage = new FameBonusPage(() -> key, () -> me.charId(), this::isMine);
        // the game sends a character's dungeon completions when you load in: use them for the Fame Bonuses page
        me.onCharacterStats = (cid, pcStats) -> {
            if (key == null || foreign()) return;
            boolean firstTime = FameDungeons.get(key, cid) == null || !FameDungeons.get(key, cid).fromGame();
            int n = FameDungeons.fromGame(key, cid, pcStats);
            if (firstTime && n > 0) host.log(prefix() + "*sniff sniff* read this character's dungeon completions from the game: " + n + " dungeons so far (Me > Fame > Fame Bonuses).");
            if (fameBonusPage != null) fameBonusPage.changed();
        };
        holidayPage = new HolidayPage(e -> isMine(e.account));
        puzzlePage = new PuzzlePage();
        puzzlePage.account = () -> key;
        puzzlePage.onEvent = ev -> {
            Badges.note(key, ev);
            host.badgesMaybe(this);
        };
        badgesPage = new BadgesPage(() -> key, this::isMine);
        wardrobePage = new WardrobePage();
        titlesPage = new TitlesPage(() -> key, this::isMine);
        killsPage = new KillsPage(d -> isMine(d.account));
        bookPage = new BookPage(e -> isMine(e.account),
                r -> isMine(r.account));
        bookPage.who = () -> key == null ? "" : History.nameOf(key);
        bookPage.account = () -> key;
        dungeonPage = new DungeonPage(e -> isMine(e.account));
        lootPage = new MoreTabs.LootPage(e -> isMine(e.account),
                r -> isMine(r.account));
    }

    /** True for a log line that belongs to this account (lines from before accounts were tracked go to the first one). */
    boolean isMine(String account) {
        // before the game has said which account this is, the first one shows everything you've logged
        if (key == null) return index == 0;
        return key.equals(account) || (account.isEmpty() && key.equals(host.legacyOwner(null)));
    }

    /** Redraws the pages that show items (after the game files are read). */
    void refreshPages() {
        for (MoreTabs.Page p : new MoreTabs.Page[]{lootPage, bookPage, killsPage, tradesPage, vaultPage, badgesPage, chestPage, holidayPage,
                titlesPage, wardrobePage, famePage, fameBonusPage, charactersPage, lootHistoryPage, shinyPage, contestPage, treasuryPage, eventsPage}) if (p != null) p.changed();
        if (dungeonPage != null) dungeonPage.changed();
        if (puzzlePage != null) puzzlePage.changed();
        if (aboutPanel != null) aboutPanel.changed();
        if (questsPanel != null) questsPanel.changed();
    }

    /** The game account being played on this tab right now (with "I only play one account", it can be another). */
    volatile String playing;
    private volatile boolean warnedForeign;

    /**
     * True when "I only play one account" is on and someone else's account is logged in: its vault and characters
     * don't go in your Collection.
     */
    boolean foreign() {
        String p = playing;
        boolean f = AccountRouter.singleAccount && key != null && p != null && !p.equals(key);
        if (f && !warnedForeign) {
            warnedForeign = true;
            host.log(prefix() + "*sniff sniff* that's not your usual account, so its vault and characters won't go in your Collection.");
        }
        if (!f) warnedForeign = false;
        return f;
    }

    /** Notes the current character's gear, inventory and backpack for the Collection. */
    void noteCharacterItems() {
        if (key == null || foreign()) return;
        MeTracker.Thing c = me.character();
        if (c == null) return;
        if (me.charId() >= 0) FameDungeons.name(key, me.charId(), AboutMe.objectName(c.objectType, "Character")); // for the Fame Bonuses list
        if (CharacterBags.note(key, me.charId(), c)) {
            if (treasuryPage != null) treasuryPage.changed();
            if (charactersPage != null) charactersPage.changed();
        }
        int n = Owned.note(key, Owned.seasonal(c) ? Owned.SEASONAL_CHARACTER : Owned.CHARACTER, Owned.onCharacter(c));
        if (n > 0) {
            if (bookPage != null) bookPage.changed();
            if (dungeonPage != null) dungeonPage.changed();
        }
    }

    /** The place you're in now (null before the first map), and when you went in (ms; 0 before). */
    synchronized String visitMap() {
        return visitMap;
    }

    synchronized long visitStart() {
        return visitMap == null ? 0 : visitStart;
    }

    String key() {
        return key;
    }

    boolean claimed() {
        return key != null;
    }

    /** Ties this account to the game's account id, and loads its saved vault. */
    synchronized void claim(String accountKey) {
        if (key != null) return;
        key = accountKey;
        String owner = host.legacyOwner(accountKey);
        vault.useFile(accountKey.equals(owner) ? "vault.txt" : "vault-" + Integer.toHexString(accountKey.hashCode()) + ".txt");
        // every page that shows this account's things draws again now that it knows whose they are (one may already
        // be on screen: the pup reopens on the page you were last on, before the game has said who you are)
        SwingUtilities.invokeLater(this::refreshPages);
        host.accountsChanged();
    }

    String label() {
        return label.isEmpty() ? "Account " + (index + 1) : label;
    }

    void setLabel(String name) {
        if (name == null || name.isEmpty() || name.equals(label)) return;
        label = name;
        host.accountsChanged();
    }

    /** "[flushed] " in the diary when more than one account is open. */
    String prefix() {
        return label.isEmpty() ? "" : "[" + label + "] ";
    }

    private void meChanged() {
        if (aboutPanel != null) aboutPanel.changed();
        if (questsPanel != null) questsPanel.changed();
        MeTracker.Thing t = me.character();
        if (t != null) {
            String n = DpsTracker.cleanName(t.s(StatType.NAME_STAT));
            if (n != null && !n.isEmpty()) SwingUtilities.invokeLater(() -> setLabel(n));
        }
    }
}
