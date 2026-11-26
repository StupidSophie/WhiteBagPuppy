package whitebag;

import assets.IdToAsset;
import packets.Packet;
import packets.PacketType;
import packets.data.ObjectData;
import packets.data.ObjectStatusData;
import packets.data.StatData;
import packets.incoming.CreateSuccessPacket;
import packets.incoming.DamagePacket;
import packets.incoming.MapInfoPacket;
import packets.incoming.NewTickPacket;
import packets.incoming.ServerPlayerShootPacket;
import packets.incoming.UpdatePacket;
import packets.outgoing.EnemyHitPacket;
import packets.packetcapture.register.Register;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a per-boss damage leaderboard for each dungeon run.
 * <p>
 * How damage is worked out:
 * <ul>
 *   <li>Other players' (and their pets'/minions') damage comes straight from the server's DamagePacket.</li>
 *   <li>Your own damage is worked out shot by shot, the way the game does it (and like RealmShark):
 *       each shot's damage is rolled from your weapon's range with the map's random seed, times your
 *       Attack (and Weak / Damaging / exaltation bonus), and each hit (your EnemyHit packets) takes off
 *       the enemy's Defense (Armor Broken, Armored, Exposed and Curse included). Shots the server makes
 *       for you (abilities) use the damage it sends.</li>
 *   <li>Only if that isn't possible (no game files to look weapons up in) does your damage fall back to the
 *       enemy's health loss nobody else's damage explains.</li>
 * </ul>
 */
public class DpsTracker {
    /** Enemies with at least this much max health get their own leaderboard. */
    static final int BOSS_HP = 10_000;
    private static final int MAX_RUNS = 30;
    private static final int MAX_HP_STAT = 0, HP_STAT = 1, NAME_STAT = 31, ACCOUNT_ID_STAT = 38, ANIMATION_STAT = 125;
    /** Oryx 3 and his "guarded" animations (from Puppy): hits during these are guarded hits. */
    private static final int ORYX_3 = 45363, ORYX_GUARD = -935464302, ORYX_GUARD_EXALTED = -918686683;
    static final String YOU = "\u0000you";
    static final String UNKNOWN = "Other / unknown";

    public interface Listener {
        void changed();
    }

    // ------------------------------------------------------------------ results (read by the UI)

    /** Damage dealt to one enemy. */
    public static final class Fight {
        public final int enemyId;
        public final int enemyType;
        public final String enemyName;
        public int maxHp;
        long hp = -1;
        long hpLost;
        long firstHitMs, lastHitMs;
        int myHits;
        boolean killed;
        /** Saved to the kill log as soon as it died (realm bosses and events), so not again when the run ends. */
        boolean savedEarly;
        /** Damage reported by the server's DamagePackets, by player. */
        final Map<String, Long> fromPackets = new LinkedHashMap<>();
        /** Guarded hits by player: {hits, damage}. */
        final Map<String, long[]> guarded = new LinkedHashMap<>();
        long guardHpLost;
        int myGuardHits;
        /** Your damage worked out hit by hit, and how many hits that covers. */
        long myCalc, myGuardCalc;
        int myCalcHits;

        /** Guarded hits per player, with your own damage worked out like the rest of yours. */
        Map<String, long[]> guardedByName() {
            Map<String, long[]> out = new LinkedHashMap<>();
            long reported = 0;
            for (Map.Entry<String, long[]> e : guarded.entrySet()) {
                out.put(e.getKey(), e.getValue().clone());
                reported += e.getValue()[1];
            }
            if (myCalcHits > 0) {
                if (myGuardCalc > 0 || myGuardHits > 0) {
                    long[] y = out.computeIfAbsent(YOU, k -> new long[2]);
                    y[0] += myGuardHits;
                    y[1] += myGuardCalc;
                }
            } else if (myGuardHits > 0) {
                long mine = Math.max(0, guardHpLost - reported);
                long[] y = out.computeIfAbsent(YOU, k -> new long[2]);
                y[0] += myGuardHits;
                y[1] += mine;
            }
            return out;
        }

        Fight(int enemyId, int enemyType, String enemyName, int maxHp) {
            this.enemyId = enemyId;
            this.enemyType = enemyType;
            this.enemyName = enemyName;
            this.maxHp = maxHp;
        }

        void add(String who, long dmg, long now) {
            fromPackets.merge(who, dmg, Long::sum);
            touch(now);
        }

        void touch(long now) {
            if (firstHitMs == 0) firstHitMs = now;
            lastHitMs = Math.max(lastHitMs, now);
        }

        /**
         * Damage by player: server-reported damage, plus any health loss nothing explains, which goes
         * to you if you hit this enemy (otherwise to "unknown").
         */
        Map<String, Long> byName() {
            Map<String, Long> out = new LinkedHashMap<>(fromPackets);
            long reported = 0;
            for (long v : fromPackets.values()) reported += v;
            if (myCalcHits > 0) {
                // your hits are worked out shot by shot; any hits that couldn't be (a shot the app didn't see)
                // count as your average hit. Health loss nobody's damage explains isn't shown.
                long mine = myHits > myCalcHits ? Math.round((double) myCalc * myHits / myCalcHits) : myCalc;
                out.merge(YOU, mine, Long::sum);
                return out;
            }
            // no game files to work your shots out from: your damage is the health loss nobody else's explains
            long residual = hpLost - reported;
            if (residual > 0 && myHits > 0) out.merge(YOU, residual, Long::sum);
            return out;
        }

        /** Seconds from first to last hit (at least 1). */
        public double seconds() {
            return Math.max(1, (lastHitMs - firstHitMs) / 1000.0);
        }

        public long total() {
            long sum = 0;
            for (long v : byName().values()) sum += v;
            return sum;
        }

        public boolean killed() {
            return killed;
        }
    }

    /** One player's line on a leaderboard. */
    public static final class Row {
        public final String name;
        public final boolean you;
        public final long damage;
        public final double percent;
        public final double dps;
        /** Guarded hits and their damage (Oryx 3), 0 if none. */
        public long guardHits, guardDmg;
        /** Damage this player took during the fight, and how many hits (-1 if unknown). */
        public long takenDmg = -1, takenHits = -1;
        /** Weapon, ability, armour and ring (item ids, -1 empty), or null if never seen. */
        public int[] gear;
        /** When they died or left the map during this fight (0: they didn't), and which. */
        public long goneMs;
        public boolean died;
        /** The class they're playing (object type), or -1. */
        public int classType = -1;
        /** The game's item data for each gear slot (enchantments), or null. */
        public String[] gearData;

        Row(String name, boolean you, long damage, double percent, double dps) {
            this.name = name;
            this.you = you;
            this.damage = damage;
            this.percent = percent;
            this.dps = dps;
        }
    }

    /** A finished (or in-progress) boss leaderboard. */
    /** What kind of enemy a leaderboard is for, from the game's own labels (like Puppy). */
    public enum Kind { BOSS, MINIBOSS, OTHER, WHOLE_RUN }

    public static final class Board {
        public Kind kind = Kind.OTHER;
        /** Damage you took and hits you took during this fight (-1 if unknown). */
        public long takenDmg = -1, takenHits = -1;
        /** True if anyone landed guarded hits. */
        public boolean hasGuard;
        public final String title;
        public final int maxHp;
        public final double seconds;
        public final boolean killed;
        public final long total;
        public final List<Row> rows;
        /** Already saved to the kill log when the boss died. */
        public boolean savedEarly;
        /** When it died (its last hit), for kills saved straight away; 0 otherwise. */
        public long endMs;

        Board(String title, int maxHp, double seconds, boolean killed, long total, List<Row> rows) {
            this.title = title;
            this.maxHp = maxHp;
            this.seconds = seconds;
            this.killed = killed;
            this.total = total;
            this.rows = rows;
        }
    }

    /** Everything from one visit to a map. */
    public static final class Run {
        public final String mapName;
        public final long startMs;
        final List<Fight> fights = new ArrayList<>();
        String youName = "You";
        /** Your health drops {time, amount} and the times you were hit. */
        final List<long[]> taken = new ArrayList<>();
        final List<Long> hitTimes = new ArrayList<>();
        /** Other players' damage taken {time, amount}, from the damage numbers the server shows over them. */
        final Map<String, List<long[]>> takenBy = new HashMap<>();
        /** What each player had equipped (last seen), by name ({@link #YOU} for you). */
        final Map<String, int[]> gear = new HashMap<>();
        /** Who died or left the map: name -> {time, 1 if died}. */
        final Map<String, long[]> gone = new HashMap<>();
        /** Each player's class (object type), by name. */
        final Map<String, Integer> classes = new HashMap<>();
        /** Each player's item data (enchantments) per gear slot, by name. */
        final Map<String, String[]> gearData = new HashMap<>();

        long[] takenBy(String who, long from, long to) {
            if (YOU.equals(who)) return takenBetween(from, to);
            List<long[]> l = takenBy.get(who);
            if (l == null) return new long[]{0, 0};
            long dmg = 0, hits = 0;
            for (long[] t : l) if (t[0] >= from && t[0] <= to) { dmg += t[1]; hits++; }
            return new long[]{dmg, hits};
        }

        long[] takenBetween(long from, long to) {
            long dmg = 0, hits = 0;
            for (long[] t : taken) if (t[0] >= from && t[0] <= to) dmg += t[1];
            for (long t : hitTimes) if (t >= from && t <= to) hits++;
            return new long[]{dmg, hits};
        }

        Run(String mapName, long startMs) {
            this.mapName = mapName;
            this.startMs = startMs;
        }

        public boolean hasDamage() {
            for (Fight f : fights) if (f.total() > 0) return true;
            return false;
        }

        /** One fight's leaderboard, or null if it isn't a boss (or nobody hit it). */
        Board boardOf(Fight f) {
            return boardOf(f, false);
        }

        /** {@code any}: any enemy at all, not just bosses (for the overlay when there's no boss around). */
        Board boardOf(Fight f, boolean any) {
            long t = f.total();
            if (t <= 0) return null;
            Kind kind = kindOf(f.enemyType);
            if (!any && kind == Kind.OTHER && f.maxHp < BOSS_HP) return null;
            Map<String, Long> by = f.byName();
            Map<String, long[]> guard = f.guardedByName();
            Board b = new Board(f.enemyName, f.maxHp, f.seconds(), f.killed, t, rows(by, t, f.seconds(), guard, f.firstHitMs, f.lastHitMs));
            b.kind = kind;
            b.hasGuard = !guard.isEmpty();
            long[] tk = takenBetween(f.firstHitMs, f.lastHitMs);
            b.takenDmg = tk[0];
            b.takenHits = tk[1];
            b.savedEarly = f.savedEarly;
            return b;
        }

        /** Boss leaderboards (big enemies) in the order they were fought, then the whole-run total. */
        public List<Board> boards() {
            List<Board> out = new ArrayList<>();
            Map<String, Long> all = new LinkedHashMap<>();
            Map<String, long[]> allGuard = new LinkedHashMap<>();
            long allTotal = 0;
            long first = Long.MAX_VALUE, last = 0;
            List<Fight> sorted = new ArrayList<>(fights);
            sorted.sort((a, b) -> Long.compare(a.firstHitMs, b.firstHitMs));
            for (Fight f : sorted) {
                long t = f.total();
                if (t <= 0) continue;
                Map<String, Long> by = f.byName();
                for (Map.Entry<String, Long> e : by.entrySet()) all.merge(e.getKey(), e.getValue(), Long::sum);
                Map<String, long[]> guard = f.guardedByName();
                for (Map.Entry<String, long[]> e : guard.entrySet()) {
                    long[] a = allGuard.computeIfAbsent(e.getKey(), k -> new long[2]);
                    a[0] += e.getValue()[0];
                    a[1] += e.getValue()[1];
                }
                allTotal += t;
                first = Math.min(first, f.firstHitMs);
                last = Math.max(last, f.lastHitMs);
                Board b = boardOf(f);
                if (b != null) out.add(b);
            }
            if (allTotal > 0) {
                double secs = Math.max(1, (last - first) / 1000.0);
                Board whole = new Board("Whole run (every enemy)", 0, secs, false, allTotal, rows(all, allTotal, secs, allGuard, Long.MIN_VALUE, Long.MAX_VALUE));
                whole.kind = Kind.WHOLE_RUN;
                whole.hasGuard = !allGuard.isEmpty();
                long[] tk = takenBetween(Long.MIN_VALUE, Long.MAX_VALUE);
                whole.takenDmg = tk[0];
                whole.takenHits = tk[1];
                out.add(whole);
            }
            return out;
        }

        private List<Row> rows(Map<String, Long> byName, long total, double secs, Map<String, long[]> guard, long from, long to) {
            List<Row> rows = new ArrayList<>();
            for (Map.Entry<String, Long> e : byName.entrySet()) {
                if (e.getValue() <= 0) continue;
                boolean you = YOU.equals(e.getKey());
                String name = you ? youName + " (you)" : e.getKey();
                Row r = new Row(name, you, e.getValue(), 100.0 * e.getValue() / total, e.getValue() / secs);
                long[] gd = guard.get(e.getKey());
                if (gd != null) {
                    r.guardHits = gd[0];
                    r.guardDmg = gd[1];
                }
                if (!UNKNOWN.equals(e.getKey())) {
                    long[] tk = takenBy(e.getKey(), from, to);
                    r.takenDmg = tk[0];
                    r.takenHits = tk[1];
                    Integer ct = classes.get(e.getKey());
                    if (ct != null) r.classType = ct;
                    int[] gr = gear.get(e.getKey());
                    if (gr != null) r.gear = gr.clone();
                    String[] gdat = gearData.get(e.getKey());
                    if (gdat != null) r.gearData = gdat.clone();
                    long[] gn = gone.get(e.getKey());
                    // during the fight (or just after its last hit)
                    if (gn != null && gn[0] >= from && (to == Long.MAX_VALUE || gn[0] <= to + 15_000)) {
                        r.goneMs = gn[0];
                        r.died = gn[1] == 1;
                    }
                }
                rows.add(r);
            }
            rows.sort((a, b) -> Long.compare(b.damage, a.damage));
            return rows;
        }
    }

    // ------------------------------------------------------------------ state

    private final Listener listener;
    private final List<Run> runs = new ArrayList<>();
    private Run current;
    private int myId = -1;
    private String myName = "You";
    private final Map<Integer, String> playerNames = new HashMap<>();
    private final Map<Integer, Integer> minionOwner = new HashMap<>();
    private final Map<Integer, int[]> enemies = new HashMap<>();       // id -> {type, maxHp, hp}
    private final Map<Integer, Fight> fights = new HashMap<>();
    private final Map<Integer, Integer> animation = new HashMap<>();   // enemy id -> animation stat
    private int myHp = -1;

    // ---- working out your own damage (like RealmShark)
    private static final int ATTACK_STAT = 20, DEFENSE_STAT = 21, CONDITION_STAT = 29, NEW_CON_STAT = 96, EXALT_DMG_STAT = 113;
    private static final int WEAK = 0x4, DAMAGING = 0x40000;
    /** The game's damage dice, seeded per map. */
    static final class Rng {
        private long seed;

        Rng(long seed) {
            this.seed = seed;
        }

        long next() {
            long right16 = (seed >> 16) * 0x41A7;
            long left = (seed & 0xFFFF) * 0x41A7;
            long right15 = right16 >> 15;
            long rightAnd = (right16 & 0x7FFF) << 16;
            long sum = left + rightAnd + right15;
            long v = Integer.toUnsignedLong((int) sum - 0x7FFFFFFF);
            if (sum <= 0x7FFFFFFF) v = sum;
            seed = v;
            return v;
        }
    }

    private Rng rng;
    private int myAttack = -1, myCond = 0, myExalt = 0;
    /** Enemy id -> {defense, condition, new condition}. */
    private final Map<Integer, int[]> enemyDef = new HashMap<>();
    /** Your shots by bullet id -> {damage before defense, armor piercing}. */
    private final Map<Integer, int[]> shots = new LinkedHashMap<Integer, int[]>(1024, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, int[]> e) {
            return size() > 2048;
        }
    };
    /** Clock, swappable for tests. */
    static java.util.function.LongSupplier clock = System::currentTimeMillis;

    public DpsTracker(Listener listener) {
        this.listener = listener;
    }

    public void register(Bus bus) {
        bus.register(PacketType.MAPINFO, this::onMapInfo);
        bus.register(PacketType.CREATE_SUCCESS, this::onCreateSuccess);
        bus.register(PacketType.UPDATE, this::onUpdate);
        bus.register(PacketType.NEWTICK, this::onNewTick);
        bus.register(PacketType.DAMAGE, this::onDamage);
        bus.register(PacketType.ENEMYHIT, this::onEnemyHit);
        bus.register(PacketType.SERVERPLAYERSHOOT, this::onServerPlayerShoot);
        bus.register(PacketType.PLAYERSHOOT, this::onPlayerShoot);
        bus.register(PacketType.PLAYERHIT, p -> gotHit());
        bus.register(PacketType.GROUNDDAMAGE, p -> gotHit());
    }

    private synchronized void gotHit() {
        if (current != null) current.hitTimes.add(clock.getAsLong());
    }

    private boolean guarded(int enemyId) {
        int[] e = enemies.get(enemyId);
        if (e == null || e[0] != ORYX_3) return false;
        Integer a = animation.get(enemyId);
        return a != null && (a == ORYX_GUARD || a == ORYX_GUARD_EXALTED);
    }

    /** The toughest enemy that died in the last {@code withinMs} (the boss whose bag just dropped), or null. */
    public synchronized String lastKill(long withinMs) {
        if (current == null) return null;
        long now = clock.getAsLong();
        Fight best = null;
        for (Fight f : current.fights)
            if (f.killed && now - f.lastHitMs <= withinMs && (best == null || f.maxHp > best.maxHp)) best = f;
        return best == null ? null : best.enemyName;
    }

    /** Newest first; only runs where damage was done. */
    public synchronized List<Run> runs() {
        List<Run> out = new ArrayList<>();
        if (current != null && current.hasDamage()) out.add(current);
        for (int i = runs.size() - 1; i >= 0; i--) out.add(runs.get(i));
        return out;
    }

    public synchronized List<Board> boards(Run run) {
        return run.boards();
    }

    /** For the mini overlay: the fight you hit most recently in this run, and the whole run so far. */
    static final class Live {
        Board fight, run;
        /** True while the fight is still going (someone hit it in the last few seconds and it isn't dead). */
        boolean active;
    }

    synchronized Live live() {
        if (current == null || !current.hasDamage()) return null;
        Live l = new Live();
        // the boss hit most recently; if no boss has been hit, the toughest enemy hit in the last half minute
        Fight latest = null, tough = null;
        long now = clock.getAsLong();
        for (Fight f : current.fights) {
            if (f.total() <= 0) continue;
            if (current.boardOf(f) != null) {
                if (latest == null || f.lastHitMs > latest.lastHitMs) latest = f;
            } else if (now - f.lastHitMs < 30_000 && (tough == null || f.maxHp > tough.maxHp)) {
                tough = f;
            }
        }
        Fight show = latest != null ? latest : tough;
        if (show != null) {
            l.fight = current.boardOf(show, true);
            l.active = !show.killed && now - show.lastHitMs < 8000;
        }
        List<Board> all = current.boards();
        for (Board b : all) if (b.kind == Kind.WHOLE_RUN) l.run = b;
        return l;
    }

    // ------------------------------------------------------------------ packets

    synchronized void onMapInfo(Packet packet) {
        MapInfoPacket p = (MapInfoPacket) packet;
        endRun();
        current = new Run(BagTracker.cleanName(p.name, p.displayName), clock.getAsLong());
        current.youName = myName;
        myId = -1;
        playerNames.clear();
        minionOwner.clear();
        enemies.clear();
        enemyPos.clear();
        deaths.clear();
        fights.clear();
        animation.clear();
        myHp = -1;
        rng = new Rng(p.seed);
        shots.clear();
        enemyDef.clear();
        notifyListener();
    }

    private static final packets.data.enums.StatType[] GEAR = {packets.data.enums.StatType.INVENTORY_0_STAT,
            packets.data.enums.StatType.INVENTORY_1_STAT, packets.data.enums.StatType.INVENTORY_2_STAT, packets.data.enums.StatType.INVENTORY_3_STAT};

    /** Notes what a player has equipped, for the leaderboards. */
    private void readGear(int id, StatData[] stats) {
        if (current == null || stats == null) return;
        String who = id == myId && myId != -1 ? YOU : playerNames.get(id);
        if (who == null) return;
        int[] g = null;
        for (StatData d : stats) {
            if (d.statTypeNum == 80 && d.stringStatValue != null) { // item data: enchantments, one entry per slot
                String[] parts = d.stringStatValue.split(",", -1);
                String[] gd = new String[4];
                for (int k = 0; k < 4; k++) gd[k] = k < parts.length ? parts[k] : "";
                current.gearData.put(who, gd);
            }
            for (int k = 0; k < 4; k++) {
                if (d.statType != GEAR[k]) continue;
                if (g == null) g = current.gear.computeIfAbsent(who, x -> new int[]{-1, -1, -1, -1});
                g[k] = d.statValue;
            }
        }
    }

    /** Where each enemy is, for {@link #enemyNear}. */
    private final Map<Integer, float[]> enemyPos = new HashMap<>();
    /** Enemies that just went: {time, x bits, y bits, object type}. */
    private final java.util.ArrayDeque<long[]> deaths = new java.util.ArrayDeque<>();

    /**
     * The enemy that died where a bag just appeared (within a few tiles, in the last few seconds), or "" if none: who
     * dropped it.
     */
    synchronized String enemyNear(double x, double y) {
        if (Double.isNaN(x) || Double.isNaN(y)) return "";
        long now = clock.getAsLong();
        long[] best = null;
        double bestD = 4.0;
        for (long[] d : deaths) {
            if (now - d[0] > 8000) continue;
            double dx = Float.intBitsToFloat((int) d[1]) - x, dy = Float.intBitsToFloat((int) d[2]) - y;
            double dist = Math.sqrt(dx * dx + dy * dy);
            if (dist <= bestD) {
                bestD = dist;
                best = d;
            }
        }
        if (best == null) return "";
        String n = AboutMe.objectName((int) best[3], "");
        return n == null ? "" : n.trim();
    }

    /** Someone died or left the map (from the Players tracker), for the leaderboards. */
    synchronized void noteGone(String name, boolean died, long at) {
        if (current == null || name == null || name.isEmpty()) return;
        current.gone.put(name, new long[]{at, died ? 1 : 0});
        notifyListener();
    }

    synchronized void onCreateSuccess(Packet packet) {
        myId = ((CreateSuccessPacket) packet).objectId;
    }

    synchronized void onUpdate(Packet packet) {
        UpdatePacket p = (UpdatePacket) packet;
        long now = clock.getAsLong();
        if (p.newObjects != null) {
            for (ObjectData o : p.newObjects) {
                if (o.status == null) continue;
                int id = o.status.objectId;
                if (isPlayer(o.objectType, o.status.stats)) {
                    String n = cleanName(stringStat(o.status.stats, NAME_STAT));
                    if (n != null) {
                        playerNames.put(id, n);
                        if (id == myId) setMyName(n);
                    }
                    readGear(id, o.status.stats);
                    if (current != null) {
                        String who = id == myId && myId != -1 ? YOU : playerNames.get(id);
                        if (who != null) current.classes.put(who, o.objectType);
                    }
                    if (id == myId) {
                        myHp = intStat(o.status.stats, HP_STAT, myHp);
                        readMyStats(o.status.stats);
                    }
                    continue;
                }
                int an = intStat(o.status.stats, ANIMATION_STAT, Integer.MIN_VALUE);
                if (an != Integer.MIN_VALUE) animation.put(id, an);
                int maxHp = intStat(o.status.stats, MAX_HP_STAT, -1);
                if (maxHp <= 0) continue;
                int hp = intStat(o.status.stats, HP_STAT, maxHp);
                enemies.put(id, new int[]{o.objectType, maxHp, hp});
                if (o.status.pos != null) enemyPos.put(id, new float[]{o.status.pos.x, o.status.pos.y});
                readEnemyStats(id, o.status.stats);
            }
        }
        if (p.drops != null) {
            boolean changed = false;
            for (int id : p.drops) {
                Fight f = fights.remove(id);
                int[] e = enemies.remove(id);
                float[] where = enemyPos.remove(id);
                if (e != null && where != null) {
                    // where and when it went, so a bag that appears there can say who dropped it
                    deaths.addLast(new long[]{now, Float.floatToIntBits(where[0]), Float.floatToIntBits(where[1]), e[0]});
                    while (deaths.size() > 60) deaths.removeFirst();
                }
                if (f != null) {
                    // Gone from view: treat as a kill if it was nearly dead or we landed the killing blow.
                    if (f.killed || (e != null && f.hp >= 0 && f.hp <= Math.max(1, f.maxHp * 0.03))) {
                        f.killed = true;
                        if (f.hp > 0) f.hpLost += f.hp;
                        f.hp = 0;
                        f.touch(now);
                        saveKillNow(f);
                    }
                    changed = true;
                }
            }
            if (changed) notifyListener();
        }
    }

    synchronized void onNewTick(Packet packet) {
        NewTickPacket p = (NewTickPacket) packet;
        if (p.status == null) return;
        long now = clock.getAsLong();
        boolean changed = false;
        for (ObjectStatusData s : p.status) {
            String n = playerNames.containsKey(s.objectId) ? cleanName(stringStat(s.stats, NAME_STAT)) : null;
            if (n != null) {
                playerNames.put(s.objectId, n);
                if (s.objectId == myId) setMyName(n);
            }
            if (playerNames.containsKey(s.objectId) || s.objectId == myId) readGear(s.objectId, s.stats);
            if (s.objectId == myId && myId != -1) {
                int hpNow = intStat(s.stats, HP_STAT, Integer.MIN_VALUE);
                if (hpNow != Integer.MIN_VALUE) {
                    if (myHp >= 0 && hpNow < myHp && current != null) current.taken.add(new long[]{now, myHp - hpNow});
                    myHp = hpNow;
                }
                readMyStats(s.stats);
                continue;
            }
            int[] e = enemies.get(s.objectId);
            if (e == null) continue;
            if (s.pos != null) enemyPos.put(s.objectId, new float[]{s.pos.x, s.pos.y});
            readEnemyStats(s.objectId, s.stats);
            int an = intStat(s.stats, ANIMATION_STAT, Integer.MIN_VALUE);
            if (an != Integer.MIN_VALUE) animation.put(s.objectId, an);
            int maxHp = intStat(s.stats, MAX_HP_STAT, e[1]);
            int hp = intStat(s.stats, HP_STAT, Integer.MIN_VALUE);
            e[1] = maxHp;
            Fight f = fights.get(s.objectId);
            if (f != null) f.maxHp = maxHp;
            if (hp == Integer.MIN_VALUE) continue;
            if (f != null) {
                if (f.hp >= 0 && hp < f.hp) {
                    f.hpLost += f.hp - hp;
                    if (guarded(s.objectId)) f.guardHpLost += f.hp - hp;
                    f.touch(now);
                    changed = true;
                }
                f.hp = hp;
            }
            e[2] = hp;
        }
        if (changed) notifyListener();
    }

    synchronized void onDamage(Packet packet) {
        DamagePacket p = (DamagePacket) packet;
        if (p.damageAmount <= 0) return;
        if (p.targetId != myId && playerNames.containsKey(p.targetId)) {
            // another player got hit: the server sends their damage number so everyone can see it (like Puppy)
            if (current != null) current.takenBy.computeIfAbsent(playerNames.get(p.targetId), k -> new ArrayList<>())
                    .add(new long[]{clock.getAsLong(), p.damageAmount});
            return;
        }
        Fight f = fight(p.targetId);
        if (f == null) return;
        long now = clock.getAsLong();
        int attacker = minionOwner.getOrDefault(p.objectId, p.objectId);
        String who;
        if (attacker == myId && myId != -1) who = YOU;
        else if (playerNames.containsKey(attacker)) who = playerNames.get(attacker);
        else return; // damage from something that isn't a player (or a pet/minion of one we know): not on the board
        f.add(who, p.damageAmount, now);
        if (guarded(p.targetId)) {
            long[] g = f.guarded.computeIfAbsent(who, k -> new long[2]);
            g[0]++;
            g[1] += p.damageAmount;
        }
        notifyListener();
    }

    synchronized void onEnemyHit(Packet packet) {
        EnemyHitPacket p = (EnemyHitPacket) packet;
        Fight f = fight(p.targetId);
        if (f == null) return;
        f.myHits++;
        boolean guard = guarded(p.targetId);
        if (guard) f.myGuardHits++;
        int[] shot = p.shooterID == myId || myId == -1 ? findShot(p.bulletId) : null;
        if (shot != null) {
            int[] d = enemyDef.getOrDefault(p.targetId, new int[3]);
            int dmg = damageWithDefense(shot[0], shot[1] != 0, d[0], d[1], d[2]);
            f.myCalc += dmg;
            f.myCalcHits++;
            if (guard) f.myGuardCalc += dmg;
            notifyListener();
        }
        f.touch(clock.getAsLong());
        if (p.kill) f.killed = true;
    }

    synchronized void onServerPlayerShoot(Packet packet) {
        ServerPlayerShootPacket p = (ServerPlayerShootPacket) packet;
        if (p.summonerId != 0 && p.ownerId != 0 && p.ownerId != p.summonerId) minionOwner.put(p.ownerId, p.summonerId);
        // shots the server makes for you (abilities and the like) come with their damage
        if (p.ownerId == myId && myId != -1 && p.damage > 0) {
            boolean ap = false;
            try {
                ap = IdToAsset.getIdProjectileArmorPierces(p.containerType, p.bulletType);
            } catch (Throwable ignored) {
            }
            int n = Math.max(1, p.bulletCount);
            for (int j = 0; j < n; j++) {
                int b = (p.bulletId + j) & 0xFFFF;
                shots.put(b, new int[]{p.damage, ap ? 1 : 0});
                shots.put((b % 256) + 256, new int[]{p.damage, ap ? 1 : 0});
            }
        }
    }

    /** One of your weapon's shots: roll its damage the way the game does. */
    synchronized void onPlayerShoot(Packet packet) {
        packets.outgoing.PlayerShootPacket p = (packets.outgoing.PlayerShootPacket) packet;
        if (rng == null) return;
        int proj = Math.max(0, p.projectileId);
        int min, max, slot;
        boolean ap;
        try {
            min = IdToAsset.getIdProjectileMinDmg(p.weaponId, proj);
            max = IdToAsset.getIdProjectileMaxDmg(p.weaponId, proj);
            ap = IdToAsset.getIdProjectileArmorPierces(p.weaponId, proj);
            slot = IdToAsset.getIdProjectileSlotType(p.weaponId);
        } catch (Throwable e) {
            return; // weapon not in the game files: fall back to health loss
        }
        if (min < 0 || max < 0) return;
        int dmg = min != max ? (int) (min + (rng.next() % (max - min))) : min;
        if (isMainWeapon(slot)) dmg = (int) (dmg * attackMultiplier());
        shots.put(p.bulletId & 0xFFFF, new int[]{dmg, ap ? 1 : 0});
    }

    private int[] findShot(int bulletId) {
        int b = bulletId & 0xFFFF;
        int[] s = shots.get(b);
        if (s == null) s = shots.get((b % 256) + 256);
        return s;
    }

    private static boolean isMainWeapon(int slot) {
        switch (slot) {
            case 1: case 2: case 3: case 8: case 17: case 24:
                return true;
            default:
                return false;
        }
    }

    /** (Attack + 25) x 0.02, x1.25 when Damaging, x0.5 when Weak, times the exaltation damage bonus. */
    private float attackMultiplier() {
        if ((myCond & WEAK) != 0) return 0.5f;
        float m = (Math.max(0, myAttack) + 25) * 0.02f;
        if ((myCond & DAMAGING) != 0) m *= 1.25f;
        if (myExalt > 0) m *= myExalt / 1000f;
        return m;
    }

    /** The game's defence rule: at least 10% of the damage gets through. */
    static int damageWithDefense(int damage, boolean armorPiercing, int defence, int cond, int cond2) {
        if (damage == 0) return 0;
        if (armorPiercing || (cond & 0x4000000) != 0) defence = 0;          // armor broken
        else if ((cond & 0x2000000) != 0) defence = (int) (defence * 1.5);  // armored
        if ((cond2 & 0x20000) != 0) defence -= 20;                          // exposed
        int dmg = Math.max((damage * 2) / 20, damage - defence);
        if ((cond & 0x1000000) != 0) dmg = 0;                               // invulnerable
        if ((cond2 & 0x8) != 0) dmg = (int) (dmg * 0.9);
        if ((cond2 & 0x40) != 0) dmg = (int) (dmg * 1.25);                 // cursed
        return dmg;
    }

    private void readMyStats(StatData[] stats) {
        myAttack = intStat(stats, ATTACK_STAT, myAttack);
        myCond = intStat(stats, CONDITION_STAT, myCond);
        myExalt = intStat(stats, EXALT_DMG_STAT, myExalt);
    }

    private void readEnemyStats(int id, StatData[] stats) {
        int[] d = enemyDef.computeIfAbsent(id, k -> new int[3]);
        d[0] = intStat(stats, DEFENSE_STAT, d[0]);
        d[1] = intStat(stats, CONDITION_STAT, d[1]);
        d[2] = intStat(stats, NEW_CON_STAT, d[2]);
    }

    // ------------------------------------------------------------------ helpers

    private Fight fight(int enemyId) {
        Fight f = fights.get(enemyId);
        if (f != null) return f;
        int[] e = enemies.get(enemyId);
        if (e == null || current == null) return null;
        f = new Fight(enemyId, e[0], enemyName(e[0]), e[1]);
        f.hp = e[2];
        fights.put(enemyId, f);
        current.fights.add(f);
        return f;
    }

    /** Gets each finished run that had damage (to save it for the recaps). */
    volatile java.util.function.BiConsumer<Run, List<Board>> onRunEnd;
    /**
     * A boss died in the realm: its board, saved straight away. A realm visit can last hours (and the game may be
     * closed without leaving), so realm kills don't wait for the run to end.
     */
    volatile java.util.function.BiConsumer<Run, Board> onKill;

    private void saveKillNow(Fight f) {
        java.util.function.BiConsumer<Run, Board> c = onKill;
        if (c == null || current == null || f.savedEarly || !RunLog.isRealm(current.mapName)) return;
        Board b = current.boardOf(f);
        if (b == null) return;
        f.savedEarly = true;
        b.endMs = f.lastHitMs;
        try {
            c.accept(current, b);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Ends the run in progress (when the app closes). */
    public synchronized void flush() {
        endRun();
    }

    private void endRun() {
        if (current != null) {
            if (current.hasDamage()) {
                java.util.function.BiConsumer<Run, List<Board>> c = onRunEnd;
                if (c != null) {
                    try {
                        c.accept(current, current.boards());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                runs.add(current);
                while (runs.size() > MAX_RUNS) runs.remove(0);
            }
        }
        current = null;
    }

    private void setMyName(String n) {
        myName = n;
        if (current != null) current.youName = n;
    }

    private void notifyListener() {
        try {
            listener.changed();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static boolean isPlayer(int type, StatData[] stats) {
        try {
            if ("Player".equals(IdToAsset.getClazz(type))) return true;
        } catch (Throwable ignored) {
        }
        return stringStat(stats, ACCOUNT_ID_STAT) != null;
    }

    /** BOSS / MINIBOSS from the enemy's labels in the game files. */
    static Kind kindOf(int type) {
        try {
            String label = IdToAsset.getIdLabel(type);
            if (label != null) {
                for (String s : label.split("[,;\\s]+")) {
                    if (s.equalsIgnoreCase("BOSS")) return Kind.BOSS;
                    if (s.equalsIgnoreCase("MINIBOSS")) return Kind.MINIBOSS;
                }
            }
        } catch (Throwable ignored) {
        }
        return Kind.OTHER;
    }

    /** Player names come as "Name,title code" (e.g. "Venussy,9a2a"); keep just the name. */
    static String cleanName(String n) {
        if (n == null) return null;
        int c = n.indexOf(',');
        String out = (c >= 0 ? n.substring(0, c) : n).trim();
        return out.isEmpty() ? n.trim() : out;
    }

    /**
     * True for monsters (and anything the game files don't say): false for pets, players, walls, portals and other
     * objects, which can share a boss's name (a "Killer Bee Queen" pet skin, say).
     */
    static boolean isMonster(int type) {
        String c = null;
        try {
            c = IdToAsset.getClazz(type);
        } catch (Throwable ignored) {
        }
        return c == null || c.isEmpty() || c.equalsIgnoreCase("Character");
    }

    static String enemyName(int type) {
        String n = null;
        try {
            n = IdToAsset.objectName(type);
        } catch (Throwable ignored) {
        }
        return n == null || n.isEmpty() ? "Enemy #" + type : n;
    }

    private static int intStat(StatData[] stats, int type, int fallback) {
        if (stats != null) for (StatData s : stats) if (s.statTypeNum == type) return s.statValue;
        return fallback;
    }

    private static String stringStat(StatData[] stats, int type) {
        if (stats != null) for (StatData s : stats) {
            if (s.statTypeNum == type && s.stringStatValue != null && !s.stringStatValue.isEmpty()) return s.stringStatValue;
        }
        return null;
    }
}
