package whitebag;

import packets.Packet;
import packets.PacketType;
import packets.data.ObjectData;
import packets.data.ObjectStatusData;
import packets.data.enums.StatType;
import packets.incoming.CreateSuccessPacket;
import packets.incoming.NewTickPacket;
import packets.incoming.UpdatePacket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The other players in view on your map, with what they're wearing (the Players page under Chat). Your party
 * members (from the game's party messages) are marked so they can go first. Only what the game already shows you.
 * <p>
 * When someone disappears close to you it works out why: a gravestone appearing where they stood means they died;
 * no gravestone means they left the map (nexused, went to the vault or logged off). Someone who reappears at once
 * just teleported, and someone far away just walked out of view; neither is counted.
 */
final class PlayersTracker {
    interface Listener {
        void changed();
    }

    static final class Player {
        final int objectId;
        final MeTracker.Thing thing;
        final boolean party;
        final long seenAt;

        Player(int objectId, MeTracker.Thing thing, boolean party, long seenAt) {
            this.objectId = objectId;
            this.thing = thing;
            this.party = party;
            this.seenAt = seenAt;
        }

        String name() {
            return DpsTracker.cleanName(thing.s(StatType.NAME_STAT));
        }
    }

    /** Someone who left the map or died, with what they were wearing. */
    static final class Gone {
        final String name;
        final MeTracker.Thing thing;
        final boolean party;
        final boolean died;
        final long at;
        /** Their HP in percent just before (-1: unknown). */
        final int hpPct;

        Gone(String name, MeTracker.Thing thing, boolean party, boolean died, long at, int hpPct) {
            this.name = name;
            this.thing = thing;
            this.party = party;
            this.died = died;
            this.at = at;
            this.hpPct = hpPct;
        }
    }

    /** A player who just disappeared, waiting to see if a gravestone shows up or they come back. */
    private static final class Pending {
        final String name;
        final MeTracker.Thing thing;
        final boolean party;
        final long at;
        final float dist;
        boolean died, crowd;

        Pending(String name, MeTracker.Thing thing, boolean party, long at, float dist) {
            this.name = name;
            this.thing = thing;
            this.party = party;
            this.at = at;
            this.dist = dist;
        }
    }

    /** How long to wait for a gravestone (or the player coming back) before deciding. */
    private static final long DECIDE_MS = 2500;
    /** Closer than this (tiles) and vanishing means they left; further and they may have just walked out of view. */
    private static final float NEAR_TILES = 12f;
    private final List<Pending> pending = new ArrayList<>();
    private final java.util.ArrayDeque<float[]> graves = new java.util.ArrayDeque<>(); // x, y, time (ms since start)
    private final java.util.LinkedList<Gone> gone = new java.util.LinkedList<>();
    private float myX = Float.NaN, myY = Float.NaN;
    private long myJumpAt;
    private static final long T0 = System.currentTimeMillis();
    /** Diary lines (party members only, so a busy realm doesn't flood it). */
    volatile java.util.function.Consumer<String> log;
    /** Everyone who died or left near you (for the DPS boards). */
    volatile java.util.function.Consumer<Gone> onGone;

    private final Listener listener;
    private int myId = -1;
    private final Map<Integer, MeTracker.Thing> players = new HashMap<>();
    private final Map<Integer, Long> seen = new HashMap<>();
    /** Lower-case names of the people in your party. */
    private final Set<String> party = new LinkedHashSet<>();
    private volatile String mapName = "";

    PlayersTracker(Listener listener) {
        this.listener = listener;
    }

    void register(Bus bus) {
        bus.register(PacketType.CREATE_SUCCESS, this::onCreateSuccess);
        bus.register(PacketType.UPDATE, this::onUpdate);
        bus.register(PacketType.NEWTICK, this::onNewTick);
        bus.register(PacketType.MOVE, p -> {
            packets.outgoing.MovePacket m = (packets.outgoing.MovePacket) p;
            if (m.records == null || m.records.length == 0) return;
            packets.data.MoveRecord last = m.records[m.records.length - 1];
            if (last == null || last.pos == null) return;
            synchronized (this) {
                // a big jump is you teleporting: whoever vanished around then was left behind, not gone
                if (!Float.isNaN(myX) && Math.hypot(last.pos.x - myX, last.pos.y - myY) > 6) myJumpAt = System.currentTimeMillis();
                myX = last.pos.x;
                myY = last.pos.y;
            }
        });
        bus.register(PacketType.MAPINFO, p -> {
            packets.incoming.MapInfoPacket m = (packets.incoming.MapInfoPacket) p;
            mapName = BagTracker.cleanName(m.name, m.displayName); // "{s.nexus}" -> "Nexus"
        });
        bus.register(PacketType.PARTY_MEMBER_ADDED, p -> {
            packets.incoming.PartyMemberAddedPacket m = (packets.incoming.PartyMemberAddedPacket) p;
            if (m.name == null || m.name.isEmpty()) return;
            synchronized (this) {
                party.add(m.name.trim().toLowerCase(Locale.ROOT));
            }
            fire();
        });
        bus.register(PacketType.INCOMING_PARTY_MEMBER_INFO, p -> {
            packets.incoming.IncomingPartyMemberInfoPacket m = (packets.incoming.IncomingPartyMemberInfoPacket) p;
            if (m.partyPlayers == null) return;
            synchronized (this) {
                partyName = m.description == null ? "" : m.description.trim();
                partyMax = m.maxSize & 0xFF;
                party.clear(); // the whole party, as it is now
                for (packets.data.PartyPlayerData d : m.partyPlayers) {
                    if (d != null && d.name != null && !d.name.isEmpty()) party.add(d.name.trim().toLowerCase(Locale.ROOT));
                }
            }
            fire();
        });
    }

    String mapName() {
        return mapName;
    }

    private String partyName = "";
    private int partyMax;

    /** The party's name (its description) and how many it holds, from the game's party message. */
    synchronized String partyName() {
        return partyName;
    }

    synchronized int partyMax() {
        return partyMax;
    }

    synchronized int partySize() {
        return party.size();
    }

    /** Everyone in view: party first, then by name. */
    synchronized List<Player> players() {
        List<Player> out = new ArrayList<>();
        for (Map.Entry<Integer, MeTracker.Thing> e : players.entrySet()) {
            MeTracker.Thing t = e.getValue();
            String n = DpsTracker.cleanName(t.s(StatType.NAME_STAT));
            if (n.isEmpty()) continue;
            Long at = seen.get(e.getKey());
            out.add(new Player(e.getKey(), t.copy(), party.contains(n.toLowerCase(Locale.ROOT)), at == null ? 0 : at));
        }
        out.sort((a, b) -> a.party != b.party ? (a.party ? -1 : 1) : a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    /** Someone by name: in view now, or gone from view in the last minute. Null if not seen. */
    synchronized MeTracker.Thing find(String name) {
        for (MeTracker.Thing t : players.values()) if (DpsTracker.cleanName(t.s(StatType.NAME_STAT)).equalsIgnoreCase(name)) return t;
        for (Pending d : pending) if (d.name.equalsIgnoreCase(name)) return d.thing;
        long now = System.currentTimeMillis();
        for (Gone g : gone) if (g.name.equalsIgnoreCase(name) && now - g.at < 60_000) return g.thing;
        return null;
    }

    /** The people who left or died on this map, newest first (up to 40). */
    synchronized List<Gone> gone() {
        return new ArrayList<>(gone);
    }

    private void onCreateSuccess(Packet packet) {
        synchronized (this) {
            myId = ((CreateSuccessPacket) packet).objectId;
            players.clear();
            seen.clear();
            pending.clear();
            graves.clear();
            gone.clear(); // a new map: who left the last one doesn't matter here
            myX = myY = Float.NaN;
        }
        fire();
    }

    /** The classes' object types, for when the game's object list hasn't loaded. */
    private static final java.util.Set<Integer> CLASSES = new java.util.HashSet<>(java.util.Arrays.asList(
            768, 775, 782, 784, 785, 796, 797, 798, 799, 800, 801, 802, 803, 804, 805, 806, 817, 818));

    static boolean isPlayer(int type) {
        String clazz = null;
        try {
            clazz = assets.IdToAsset.getClazz(type);
        } catch (Throwable ignored) {
        }
        if (clazz != null && !clazz.isEmpty()) return "Player".equalsIgnoreCase(clazz);
        return CLASSES.contains(type) || ClassMax.of(type) != null;
    }

    private void onUpdate(Packet packet) {
        UpdatePacket p = (UpdatePacket) packet;
        boolean changed = false;
        synchronized (this) {
            if (p.newObjects != null) {
                for (ObjectData o : p.newObjects) {
                    if (o == null || o.status == null || o.status.objectId == myId) continue;
                    if (!isPlayer(o.objectType)) {
                        if (o.status.pos != null && isGrave(o.objectType)) {
                            float[] g = {o.status.pos.x, o.status.pos.y, System.currentTimeMillis() - T0};
                            graves.addLast(g);
                            while (graves.size() > 30) graves.removeFirst();
                            for (Pending d : pending) if (near(d.thing, g)) d.died = true;
                        }
                        continue;
                    }
                    MeTracker.Thing t = new MeTracker.Thing();
                    t.objectType = o.objectType;
                    MeTracker.apply(t, o.status);
                    players.put(o.status.objectId, t);
                    seen.put(o.status.objectId, System.currentTimeMillis());
                    // back straight away: they only teleported or walked back into view
                    String n = DpsTracker.cleanName(t.s(StatType.NAME_STAT));
                    if (!n.isEmpty()) pending.removeIf(d -> d.name.equalsIgnoreCase(n));
                    changed = true;
                }
            }
            if (p.drops != null) {
                int playerDrops = 0;
                for (int id : p.drops) if (players.containsKey(id)) playerDrops++;
                for (int id : p.drops) {
                    MeTracker.Thing t = players.remove(id);
                    seen.remove(id);
                    if (t == null) continue;
                    changed = true;
                    String n = DpsTracker.cleanName(t.s(StatType.NAME_STAT));
                    if (n.isEmpty()) continue;
                    float dist = Float.isNaN(myX) || Float.isNaN(t.x) ? -1f : (float) Math.hypot(t.x - myX, t.y - myY);
                    Pending d = new Pending(n, t, party.contains(n.toLowerCase(Locale.ROOT)), System.currentTimeMillis(), dist);
                    long now = System.currentTimeMillis() - T0;
                    for (float[] g : graves) if (now - g[2] < DECIDE_MS && near(t, g)) d.died = true;
                    // several people vanishing at once is you moving away from them (unless one left a gravestone)
                    if (playerDrops >= 3) d.crowd = true;
                    pending.add(d);
                }
            }
        }
        if (changed) fire();
    }

    private static boolean near(MeTracker.Thing t, float[] g) {
        return !Float.isNaN(t.x) && Math.abs(t.x - g[0]) <= 2f && Math.abs(t.y - g[1]) <= 2f;
    }

    private static final Map<Integer, Boolean> GRAVE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Gravestones (and the like) left where a player died. */
    static boolean isGrave(int type) {
        return GRAVE.computeIfAbsent(type, k -> {
            String n = AboutMe.objectName(k, "").toLowerCase(Locale.ROOT);
            String id = "";
            try {
                String x = assets.IdToAsset.getObjectIdName(k);
                if (x != null) id = x.toLowerCase(Locale.ROOT);
            } catch (Throwable ignored) {
            }
            return n.contains("grave") || id.contains("grave") || n.contains("tombstone") || id.contains("tombstone");
        });
    }

    /** Decides the people who vanished a moment ago: died, left, or (far away) just out of view. */
    private boolean decide() {
        List<String> lines = new ArrayList<>();
        List<Gone> newGone = new ArrayList<>();
        boolean changed = false;
        synchronized (this) {
            long now = System.currentTimeMillis();
            java.util.Iterator<Pending> it = pending.iterator();
            while (it.hasNext()) {
                Pending d = it.next();
                if (now - d.at < DECIDE_MS) continue;
                it.remove();
                boolean close = d.dist >= 0 && d.dist <= NEAR_TILES && !d.crowd
                        && !(myJumpAt > d.at - 1500 && myJumpAt < d.at + DECIDE_MS);
                if (!d.died && !close) continue; // walked out of view (or we don't know where you are)
                int hp = d.thing.i(StatType.HP_STAT, -1), max = d.thing.i(StatType.MAX_HP_STAT, -1);
                int pct = hp >= 0 && max > 0 ? (int) Math.round(100.0 * Math.max(0, hp) / max) : -1;
                Gone g = new Gone(d.name, d.thing, d.party, d.died, d.at, pct);
                gone.addFirst(g);
                newGone.add(g);
                while (gone.size() > 40) gone.removeLast();
                changed = true;
                if (d.party) {
                    String cls = AboutMe.objectName(d.thing.objectType, "");
                    lines.add(d.died
                            ? "*whimper* " + d.name + (cls.isEmpty() ? "" : " (" + cls + ")") + " died" + (mapName.isEmpty() ? "" : " in " + mapName) + "."
                            : "*head tilt* " + d.name + " left" + (mapName.isEmpty() ? "" : " " + mapName) + (pct >= 0 && pct < 50 ? " at " + pct + "% HP (nexused?)" : "") + ".");
                }
            }
        }
        java.util.function.Consumer<String> l = log;
        if (l != null) for (String s : lines) l.accept(s);
        java.util.function.Consumer<Gone> og = onGone;
        if (og != null) for (Gone g : newGone) og.accept(g);
        return changed;
    }

    private void onNewTick(Packet packet) {
        NewTickPacket p = (NewTickPacket) packet;
        boolean decided = decide();
        if (decided) fire();
        if (p.status == null) return;
        boolean changed = false;
        synchronized (this) {
            for (ObjectStatusData s : p.status) {
                if (s == null || s.stats == null || s.stats.length == 0) continue;
                MeTracker.Thing t = players.get(s.objectId);
                if (t != null && MeTracker.apply(t, s)) changed = true;
            }
        }
        if (changed) fire();
    }

    private void fire() {
        try {
            listener.changed();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
