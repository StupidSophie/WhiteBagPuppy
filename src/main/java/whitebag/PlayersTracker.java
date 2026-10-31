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
        bus.register(PacketType.MAPINFO, p -> mapName = ((packets.incoming.MapInfoPacket) p).displayName);
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

    private void onCreateSuccess(Packet packet) {
        synchronized (this) {
            myId = ((CreateSuccessPacket) packet).objectId;
            players.clear();
            seen.clear();
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
                    if (o == null || o.status == null || o.status.objectId == myId || !isPlayer(o.objectType)) continue;
                    MeTracker.Thing t = new MeTracker.Thing();
                    t.objectType = o.objectType;
                    MeTracker.apply(t, o.status);
                    players.put(o.status.objectId, t);
                    seen.put(o.status.objectId, System.currentTimeMillis());
                    changed = true;
                }
            }
            if (p.drops != null) {
                for (int id : p.drops) {
                    if (players.remove(id) != null) changed = true;
                    seen.remove(id);
                }
            }
        }
        if (changed) fire();
    }

    private void onNewTick(Packet packet) {
        NewTickPacket p = (NewTickPacket) packet;
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
