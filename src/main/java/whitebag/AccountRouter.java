package whitebag;

import packets.Packet;
import packets.data.ObjectData;
import packets.data.StatData;
import packets.incoming.CreateSuccessPacket;
import packets.incoming.UpdatePacket;
import packets.outgoing.HelloPacket;
import packets.packetcapture.ConnectionRouter;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sends each game connection's packets to the right account.
 * <ul>
 *   <li>A connection is identified by the account id on your own character, which the server sends when you
 *       load into an area (or straight away by its login token, if that account has been seen before).</li>
 *   <li>Until then its packets go to the first account that isn't taken yet, so with one game open nothing waits.
 *       If every account is taken, the packets are held and replayed once the connection is identified.</li>
 * </ul>
 */
final class AccountRouter implements ConnectionRouter.Handler, ConnectionRouter.Closed {
    private static final int ACCOUNT_ID_STAT = 38, NAME_STAT = 31, MAX_HELD = 4000;

    private static final class Conn {
        Account account;
        boolean confirmed;
        int myId = -1;
        String token;
        /** The game's account id for this connection, once known. */
        String key;
        final ArrayDeque<Packet> held = new ArrayDeque<>();
        /** Times your own character turned up without an account id, and its name. */
        int misses, packets;
        String name;
        boolean warned, usedFallback;
    }

    private final List<Account> accounts;
    private final Supplier<Account> newAccount;
    private final Map<Integer, Conn> conns = new HashMap<>();
    private final Map<String, Account> byKey = new HashMap<>();
    private final Map<String, Account> byToken = new HashMap<>();
    private final Map<String, String> keyByToken = new HashMap<>();
    /** "I only play one account" (Home): every connection goes to the first account and no others are made. */
    static volatile boolean singleAccount = false;
    /** Where to say what happened (the pup diary); set by the app. */
    static volatile java.util.function.Consumer<String> log = s -> System.out.println(s);
    /** The account ids the pup has saved data for; set by the app (for when the game doesn't send yours). */
    static volatile Supplier<java.util.Set<String>> knownKeys = java.util.HashSet::new;

    /**
     * @param accounts   the app's account list (the first one exists from the start, unclaimed)
     * @param newAccount makes, registers and shows another account
     */
    AccountRouter(List<Account> accounts, Supplier<Account> newAccount) {
        this.accounts = accounts;
        this.newAccount = newAccount;
    }

    /** Forget connections (the sniffer restarts its connection numbers when it restarts). */
    synchronized void reset() {
        conns.clear();
    }

    @Override
    public synchronized void packet(int connectionId, Packet p) {
        Conn c = conns.computeIfAbsent(connectionId, k -> new Conn());

        if (p instanceof HelloPacket) {
            c.token = ((HelloPacket) p).accessToken;
            if (c.token != null) c.key = keyByToken.get(c.token);
            Account a = c.token == null ? null : byToken.get(c.token);
            if (a != null) bind(c, a, true);
        } else if (p instanceof CreateSuccessPacket) {
            c.myId = ((CreateSuccessPacket) p).objectId;
        } else if (p instanceof UpdatePacket && !c.confirmed) {
            UpdatePacket u = (UpdatePacket) p;
            if (c.myId != -1) identify(c, u);
        } else if (p instanceof packets.incoming.NewTickPacket && c.myId != -1 && !c.confirmed) {
            packets.incoming.NewTickPacket nt = (packets.incoming.NewTickPacket) p;
            if (nt.status != null) for (packets.data.ObjectStatusData s : nt.status)
                if (s != null && s.objectId == c.myId) found(c, s.stats);
        }

        if (!c.confirmed && ++c.packets == 3000 && !c.usedFallback) {
            c.usedFallback = true;
            java.util.Set<String> known = knownKeys.get();
            String acc = singleAccount && accounts.get(0).claimed() ? accounts.get(0).key() : known.size() == 1 ? known.iterator().next() : null;
            if (acc != null) {
                log.accept("*sniffs* couldn't spot your account in the game's messages, so I'm using the account I already know. Your badges and stats carry on as normal.");
                c.key = acc;
                Account a = accountFor(acc, c);
                if (c.name != null) a.setLabel(c.name);
                bind(c, a, true);
            } else {
                log.accept("*tilts head* I can see your game but can't tell which account it is (" + (c.myId == -1 ? "no character id" : "no account id")
                        + ", " + known.size() + " saved accounts). Ticking \"I only play one account\" on Home may help.");
            }
        }
        if (c.account == null) {
            Account free = freeAccount(c);
            if (free != null) bind(c, free, false); // with one game open, go straight to the first account
        }
        if (c.account != null) {
            if (c.key != null) c.account.playing = c.key;
            c.account.bus.emit(p);
        } else {
            c.held.add(p);
            while (c.held.size() > MAX_HELD) c.held.poll();
        }
    }

    /** What each connection knows (for the diary, when an account can't be recognised). */
    synchronized String describe() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Conn> e : conns.entrySet()) {
            Conn c = e.getValue();
            sb.append(sb.length() > 0 ? "; " : "").append("connection ").append(e.getKey()).append(": ")
                    .append(c.myId == -1 ? "no character id" : "character id seen")
                    .append(c.token == null ? ", no login" : ", login seen")
                    .append(c.key == null ? ", no account id" : ", account id seen")
                    .append(c.confirmed ? ", linked" : ", not linked")
                    .append(c.account == null ? ", no page" : ", page " + (c.account.index + 1) + (c.account.claimed() ? " (known)" : " (unknown)"))
                    .append(", ").append(c.packets).append(" messages, ").append(c.misses).append(" without id");
        }
        return sb.length() == 0 ? "no game connections" : sb.toString();
    }

    @Override
    public synchronized void closed(int connectionId) {
        conns.remove(connectionId);
    }

    /** Finds your own character in the update and reads its account id. */
    private void identify(Conn c, UpdatePacket u) {
        if (u.newObjects == null) return;
        for (ObjectData o : u.newObjects) {
            if (o.status == null || o.status.objectId != c.myId || o.status.stats == null) continue;
            found(c, o.status.stats);
            return;
        }
    }

    /** Your own character's stats: its account id ties the connection to an account. */
    private void found(Conn c, StatData[] stats) {
        if (stats == null || c.confirmed) return;
        String acc = null, name = null;
        for (StatData s : stats) {
            if (s.statTypeNum == ACCOUNT_ID_STAT && s.stringStatValue != null && !s.stringStatValue.isEmpty()) acc = s.stringStatValue;
            if (s.statTypeNum == NAME_STAT && s.stringStatValue != null && !s.stringStatValue.isEmpty()) name = DpsTracker.cleanName(s.stringStatValue);
        }
        if (name != null) c.name = name;
        if (acc == null) {
            // the game didn't say whose account this is: after a few tries, use the account the pup already knows
            if (++c.misses < 3) return;
            java.util.Set<String> known = knownKeys.get();
            if (singleAccount && accounts.get(0).claimed()) acc = accounts.get(0).key();
            else if (known.size() == 1) acc = known.iterator().next();
            if (acc == null) {
                if (!c.warned) {
                    c.warned = true;
                    StringBuilder nums = new StringBuilder();
                    for (StatData s : stats) if (s.stringStatValue != null && !s.stringStatValue.isEmpty()) nums.append(nums.length() > 0 ? "," : "").append(s.statTypeNum);
                    log.accept("*tilts head* the game didn't send your account ID" + (c.name == null ? "" : " for " + c.name)
                            + ", so I can't tell which account this is (text stats: " + nums + "). Turn on \"I only play one account\" on Home if it's just you.");
                }
                return;
            }
            if (!c.warned) {
                c.warned = true;
                log.accept("*sniffs* the game didn't send your account ID" + (c.name == null ? "" : " for " + c.name)
                        + ", so I'm using the account I already know. Your badges and stats carry on as normal.");
            }
        }
        c.key = acc;
        if (c.token != null) keyByToken.put(c.token, acc);
        if (c.account != null) c.account.playing = acc;
        Account a = accountFor(acc, c);
        if (c.name != null) a.setLabel(c.name);
        if (c.token != null) byToken.put(c.token, a);
        bind(c, a, true);
    }

    private Account accountFor(String key, Conn asking) {
        if (singleAccount) {
            Account first = accounts.get(0);
            if (!first.claimed()) first.claim(key);
            byKey.put(key, first);
            return first;
        }
        Account a = byKey.get(key);
        if (a != null) return a;
        // an account nobody has claimed, and no other connection is using
        for (Account x : accounts) {
            if (!x.claimed() && !usedByOther(x, asking)) {
                a = x;
                break;
            }
        }
        if (a == null) a = newAccount.get();
        a.claim(key);
        byKey.put(key, a);
        return a;
    }

    /** An unclaimed account no other connection is using yet. */
    private Account freeAccount(Conn asking) {
        if (singleAccount) return accounts.get(0);
        for (Account x : accounts) if (!x.claimed() && !usedByOther(x, asking)) return x;
        return null;
    }

    private boolean usedByOther(Account a, Conn asking) {
        for (Conn o : conns.values()) if (o != asking && o.account == a) return true;
        return false;
    }

    private void bind(Conn c, Account a, boolean confirmed) {
        c.confirmed = c.confirmed || confirmed;
        if (c.account == a) return;
        c.account = a;
        while (!c.held.isEmpty()) a.bus.emit(c.held.poll()); // replay what arrived before we knew the account
    }
}
