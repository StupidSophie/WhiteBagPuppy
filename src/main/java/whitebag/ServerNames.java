package whitebag;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which server you're on (USEast2, EUWest...). The game doesn't say: it only connects to an address. So the pup
 * learns each address: in a realm it matches the realm's name ("Djinn") against atti.network's list of open realms
 * and their servers; a dungeon is always on the server you came from, and so is the Nexus you were just in.
 * What it learns is kept in server-ips.txt (addresses only, nothing about you).
 */
final class ServerNames {
    private ServerNames() {
    }

    private static final Path FILE = Paths.get("server-ips.txt");
    private static Map<String, String> known;
    /** Servers a not-yet-known address could still be (narrowed with each realm seen through it). */
    private static final Map<String, Set<String>> maybe = new HashMap<>();

    private static synchronized Map<String, String> known() {
        if (known == null) {
            known = new HashMap<>();
            try {
                if (Files.exists(FILE)) {
                    for (String l : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
                        int eq = l.indexOf('=');
                        if (eq > 0) known.put(l.substring(0, eq).trim(), l.substring(eq + 1).trim());
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return known;
    }

    private static synchronized void learn(String ip, String server) {
        if (ip == null || ip.isEmpty() || server == null || server.isEmpty()) return;
        if (server.equals(known().get(ip))) return;
        known.put(ip, server);
        maybe.remove(ip);
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> e : known.entrySet()) lines.add(e.getKey() + "=" + e.getValue());
        java.util.Collections.sort(lines);
        try {
            Files.write(FILE, lines, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static String letters(String s) {
        return s == null ? "" : s.replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);
    }

    /** The servers atti.network says have an open realm with this name. */
    static Set<String> serversWithRealm(String realmName) {
        Set<String> out = new LinkedHashSet<>();
        String want = letters(realmName);
        if (want.isEmpty()) return out;
        for (AttiFeed.Realm r : AttiFeed.realms()) if (!r.server.isEmpty() && letters(r.name).equals(want)) out.add(r.server);
        return out;
    }

    /**
     * You've just arrived somewhere: works out (and remembers) the server.
     *
     * @param ip        the game server's address now
     * @param prevIp    the address of the place before ("" if none)
     * @param prevServer the server of the place before ("" if not known)
     * @param recent    true if you came straight from there (not after a long gap)
     * @param nexus     true for the Nexus (where you land after changing servers)
     * @param realmName the realm's name ("Djinn") when this is a realm, else "" or null
     * @return the server, or "" while it isn't known yet
     */
    static synchronized String arrived(String ip, String prevIp, String prevServer, boolean recent, boolean nexus, String realmName) {
        if (ip == null || ip.isEmpty()) return "";
        String server = known().get(ip);
        if (realmName != null) realmName = DeathNotice.clean(realmName); // "{s.ogre}" -> "Ogre"
        boolean realm = realmName != null && !letters(realmName).isEmpty();
        if (realm) {
            AttiFeed.start();
            Set<String> could = serversWithRealm(realmName);
            if (!could.isEmpty()) {
                if (server != null && !could.contains(server)) {
                    known.remove(ip); // the address moved to another server
                    server = null;
                }
                if (server == null) {
                    Set<String> was = maybe.get(ip);
                    if (was != null) {
                        Set<String> both = new LinkedHashSet<>(was);
                        both.retainAll(could);
                        if (!both.isEmpty()) could = both;
                    }
                    if (recent && prevServer != null && could.contains(prevServer)) could = java.util.Collections.singleton(prevServer);
                    if (could.size() == 1) {
                        server = could.iterator().next();
                        learn(ip, server);
                    } else {
                        maybe.put(ip, could);
                    }
                }
            }
            // the Nexus you walked in from is on the same server
            if (server != null && recent && prevIp != null && !prevIp.isEmpty() && !known.containsKey(prevIp)) learn(prevIp, server);
        } else if (server == null && !nexus && recent && prevServer != null && !prevServer.isEmpty()) {
            // a dungeon (or the vault, guild hall...) is on the server you came from
            server = prevServer;
            learn(ip, server);
        }
        return server == null ? "" : server;
    }
}
