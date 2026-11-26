package whitebag;

import com.google.gson.Gson;
import packets.Packet;
import packets.PacketType;
import packets.data.ObjectData;
import packets.data.ObjectStatusData;
import packets.data.StatData;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Close calls: your HP dropped really low (20% or less) but you lived, either healing back up or escaping (Nexus,
 * or leaving the map). Remembers who hit you last, so danger warnings know what nearly killed you too.
 * Kept in close-calls.txt, one JSON line each.
 */
final class CloseCalls {
    private static final String FILE = "close-calls.txt";
    /** Low enough to count, and healed enough to call it survived. */
    static final double LOW = 0.20, SAFE = 0.50;
    private static final int MAX_HP_STAT = 0, HP_STAT = 1;

    static final class Call {
        String account = "";
        int charId = -1, classType = -1;
        String name = "";
        long at; // Unix seconds
        String where = "";
        /** The lowest you got, and your max HP then. */
        int hp = -1, maxHp = -1;
        /** What hit you last before the low point ("" if not known). */
        String by = "";
        /** You got away by leaving the map (the Nexus, mostly) rather than healing up. */
        boolean escaped;

        int percent() {
            return maxHp > 0 ? (int) Math.max(1, Math.round(hp * 100.0 / maxHp)) : 0;
        }
    }

    // ---------------------------------------------------------------- the tracker (one per account)

    /** Where to look up who you are and where you are. */
    interface Me {
        String account();

        MeTracker tracker();

        String map();
    }

    private final Me me;
    private final Consumer<Call> onCall;
    /** Your HP went to 0 (a death, even if the game's death message is missed). */
    volatile Runnable onZero = () -> { };
    private int myId = -1, hp = -1, maxHp = -1;
    /** Each enemy in view's object type, so a hit's shooter can be named. */
    private final Map<Integer, Integer> types = new HashMap<>();
    private String lastBy = "";
    private long lastByAt;

    /** One drop in your HP and what did it (the last few seconds before a death are its "last hits"). */
    static final class Hit {
        final long at;
        final String by;
        final int dmg;

        Hit(long at, String by, int dmg) {
            this.at = at;
            this.by = by;
            this.dmg = dmg;
        }
    }

    /** What hit you since your HP was last read; the next drop in HP is put down to them. */
    private final List<String> hitters = new ArrayList<>();
    private final List<Long> hittersAt = new ArrayList<>();
    private final java.util.ArrayDeque<Hit> hits = new java.util.ArrayDeque<>();
    /** The low point while you're in danger, or null. */
    private Call low;
    private long lastCallAt;

    CloseCalls(Me me, Consumer<Call> onCall) {
        this.me = me;
        this.onCall = onCall;
    }

    void register(Bus bus) {
        bus.register(PacketType.CREATE_SUCCESS, p -> {
            synchronized (this) {
                myId = ((packets.incoming.CreateSuccessPacket) p).objectId;
                hp = maxHp = -1;
                hits.clear();
                hitters.clear();
                hittersAt.clear();
            }
        });
        bus.register(PacketType.UPDATE, this::onUpdate);
        bus.register(PacketType.NEWTICK, this::onNewTick);
        bus.register(PacketType.PLAYERHIT, p -> hitBy(((packets.outgoing.PlayerHitPacket) p).objectId));
        bus.register(PacketType.AOE, p -> {
            int t = ((packets.incoming.AoePacket) p).origType;
            if (t > 0) named(DpsTracker.enemyName(t));
        });
        bus.register(PacketType.GROUNDDAMAGE, p -> hitter("the ground"));
        bus.register(PacketType.MAPINFO, p -> {
            Call c;
            synchronized (this) {
                types.clear();
                hits.clear();
                hitters.clear();
                hittersAt.clear();
                c = low;
                low = null;
                hp = maxHp = -1;
            }
            // left the map while nearly dead: that's an escape
            if (c != null) {
                c.escaped = true;
                record(c);
            }
        });
        // died: not a close call after all
        bus.register(PacketType.DEATH, p -> {
            synchronized (this) {
                low = null;
            }
        });
    }

    private synchronized void hitBy(int objectId) {
        Integer t = types.get(objectId);
        if (t != null) named(DpsTracker.enemyName(t));
    }

    private synchronized void named(String n) {
        if (n == null || n.startsWith("Enemy #")) return;
        lastBy = DpsTracker.cleanName(n);
        lastByAt = System.currentTimeMillis();
        hitter(lastBy);
    }

    private synchronized void hitter(String n) {
        if (hitters.size() >= 12) {
            hitters.remove(0);
            hittersAt.remove(0);
        }
        hitters.add(n);
        hittersAt.add(System.currentTimeMillis());
    }

    /** The hits in the last few seconds, oldest first, e.g. "Abyssal Kraken 140, 140 · Hadopelagic Jellyfish 85" ("" if none). */
    synchronized String lastHits() {
        long now = System.currentTimeMillis();
        List<Hit> recent = new ArrayList<>();
        for (Hit h : hits) if (now - h.at <= 8000) recent.add(h);
        if (recent.size() > 6) recent = recent.subList(recent.size() - 6, recent.size());
        StringBuilder sb = new StringBuilder();
        String prev = null;
        for (Hit h : recent) {
            if (h.by.equals(prev)) {
                sb.append(", ").append(h.dmg);
            } else {
                if (sb.length() > 0) sb.append("  \u00b7  ");
                sb.append(h.by).append(' ').append(h.dmg);
            }
            prev = h.by;
        }
        return sb.toString();
    }

    private void onUpdate(Packet packet) {
        packets.incoming.UpdatePacket p = (packets.incoming.UpdatePacket) packet;
        synchronized (this) {
            if (p.newObjects != null) {
                for (ObjectData o : p.newObjects) {
                    if (o == null || o.status == null) continue;
                    if (o.status.objectId == myId) {
                        stats(o.status.stats);
                        continue;
                    }
                    if (stat(o.status.stats, MAX_HP_STAT) > 0) types.put(o.status.objectId, o.objectType);
                }
            }
            if (p.drops != null) for (int id : p.drops) types.remove(id);
        }
        check();
    }

    private void onNewTick(Packet packet) {
        packets.incoming.NewTickPacket p = (packets.incoming.NewTickPacket) packet;
        if (p.status == null) return;
        synchronized (this) {
            for (ObjectStatusData s : p.status) if (s != null && s.objectId == myId && myId != -1) stats(s.stats);
        }
        check();
    }

    private void stats(StatData[] stats) {
        int m = stat(stats, MAX_HP_STAT), h = stat(stats, HP_STAT);
        boolean sameMax = m <= 0 || m == maxHp;
        if (m > 0) maxHp = m;
        if (h != Integer.MIN_VALUE) {
            // a drop in HP (not from changing gear): put it down to whatever hit you since the last reading
            long now = System.currentTimeMillis();
            // hits more than 1.5 seconds old have already shown up in your HP (or missed)
            while (!hittersAt.isEmpty() && now - hittersAt.get(0) > 1500) {
                hitters.remove(0);
                hittersAt.remove(0);
            }
            if (hp > 0 && h < hp && sameMax) {
                java.util.LinkedHashSet<String> who = new java.util.LinkedHashSet<>(hitters);
                String by = who.isEmpty() ? "something unseen" : String.join(" + ", who);
                hits.addLast(new Hit(now, by, hp - Math.max(h, 0)));
                while (hits.size() > 20) hits.removeFirst();
                hitters.clear();
                hittersAt.clear();
            }
            hp = h;
        }
    }

    private static int stat(StatData[] stats, int type) {
        if (stats != null) for (StatData s : stats) if (s != null && s.statTypeNum == type) return s.statValue;
        return Integer.MIN_VALUE;
    }

    private int lastHp = -1;

    private void check() {
        Call done = null;
        boolean zero;
        synchronized (this) {
            zero = lastHp > 0 && hp <= 0 && maxHp > 0 && hp != -1;
            lastHp = hp;
        }
        if (zero) {
            synchronized (this) {
                low = null;
            }
            onZero.run();
            return;
        }
        synchronized (this) {
            if (hp <= 0 || maxHp <= 0) return;
            double f = hp / (double) maxHp;
            if (f <= LOW) {
                if (low == null) {
                    low = new Call();
                    low.at = System.currentTimeMillis() / 1000;
                    low.where = me.map();
                }
                if (low.hp < 0 || hp < low.hp) {
                    low.hp = hp;
                    low.maxHp = maxHp;
                    if (System.currentTimeMillis() - lastByAt < 5000) low.by = lastBy;
                }
            } else if (low != null && f >= SAFE) {
                done = low;
                low = null;
            }
        }
        if (done != null) record(done);
    }

    private void record(Call c) {
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (now - lastCallAt < 30_000) return; // one at a time, not every wobble
            lastCallAt = now;
        }
        String acc = me.account();
        if (acc == null || acc.isEmpty()) return;
        c.account = acc;
        MeTracker t = me.tracker();
        c.charId = t.charId();
        MeTracker.Thing ch = t.character();
        if (ch != null) {
            c.classType = ch.objectType;
            String n = DpsTracker.cleanName(ch.s(packets.data.enums.StatType.NAME_STAT));
            c.name = n == null ? "" : n;
        }
        add(c);
        onCall.accept(c);
    }

    // ---------------------------------------------------------------- the saved list

    private static List<Call> calls;

    private static List<Call> calls() {
        if (calls != null) return calls;
        calls = new ArrayList<>();
        try {
            Gson g = new Gson();
            for (String l : Files.readAllLines(Paths.get(FILE), StandardCharsets.UTF_8)) {
                if (l.trim().isEmpty()) continue;
                try {
                    Call c = g.fromJson(l, Call.class);
                    if (c != null) calls.add(c);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return calls;
    }

    static synchronized void add(Call c) {
        calls().add(c);
        try {
            Files.write(Paths.get(FILE), Collections.singletonList(new Gson().toJson(c)), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    static synchronized void remove(Call c) {
        if (!calls().remove(c)) return;
        List<String> lines = new ArrayList<>();
        Gson g = new Gson();
        for (Call x : calls) lines.add(g.toJson(x));
        try {
            Files.write(Paths.get(FILE), lines, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** These accounts' close calls, newest first. */
    static synchronized List<Call> of(Predicate<String> mine) {
        List<Call> out = new ArrayList<>();
        for (Call c : calls()) if (mine == null || mine.test(c.account)) out.add(c);
        out.sort((a, b) -> Long.compare(b.at, a.at));
        return out;
    }

    /** How many times each enemy nearly killed this account (lower-case name -> count). */
    static synchronized Map<String, Integer> nearly(String account) {
        Map<String, Integer> out = new HashMap<>();
        for (Call c : calls()) if (c.account.equals(account) && c.by != null && !c.by.isEmpty()) out.merge(DeathLog.key(c.by), 1, Integer::sum);
        return out;
    }
}
