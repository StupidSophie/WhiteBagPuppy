package whitebag;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * atti.network's realm tracker (https://atti.network/realm), used with atti's permission: every open realm, the realm
 * bosses with their HP, and alien invasions with their wormhole generators. Checks every 10 seconds while the Realms tab
 * is open, and every 30 seconds with it closed when wormhole pings are on.
 */
final class AttiFeed {
    private AttiFeed() {
    }

    static final String PAGE_URL = "https://atti.network/realm";
    private static final String REALMS_URL = System.getProperty("atti.realms", "https://atti.network/realm.json");
    private static final String DATA_URL = System.getProperty("atti.data", "https://atti.network/events_data.php");
    /** What Chrome on Windows calls itself (atti's choice; settable with -Datti.ua=...). */
    private static final String CHROME_UA = System.getProperty("atti.ua",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36");
    /** How often it checks while the Realms tab is open, and while it's closed with wormhole pings on. */
    private static final long POLL_MS = Long.getLong("atti.pollMs", 10_000L);
    private static final long BACKGROUND_POLL_MS = Long.getLong("atti.backgroundPollMs", 30_000L);

    static final class Realm {
        String server = "", name = "";
        int players = -1, percent = -1;
        boolean closed;
        long updatedMs;

        String key() {
            return server + "|" + name;
        }
    }

    static final class Boss {
        String name;
        int hp, maxHp;
        long id;

        int percent() {
            return maxHp > 0 ? Math.max(0, Math.min(100, Math.round(100f * hp / maxHp))) : -1;
        }
    }

    /** One realm with an invasion (or another current event) going on. */
    static final class Invasion {
        String server = "", realm = "", event = "";
        int players = -1, stage = -1, value = -1, max = -1, percent = -1;
        boolean complete;
        /** "Forax" -> 5 */
        final Map<String, Integer> wormholes = new LinkedHashMap<>();

        String key() {
            return server + "|" + realm;
        }

        /** "Forax ×5, Katalund ×2, Malogia" */
        String wormholeText() {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> w : wormholes.entrySet()) parts.add(w.getKey() + (w.getValue() > 1 ? " ×" + w.getValue() : ""));
            return String.join(", ", parts);
        }

        /** "stage 2  ·  40/100  ·  40%", or "" when the tracker doesn't know. */
        String progress() {
            if (complete) return "done";
            List<String> p = new ArrayList<>();
            if (stage >= 0) p.add("stage " + stage);
            if (value >= 0 && max > 0) p.add(value + "/" + max);
            if (percent >= 0) p.add(percent + "%");
            return String.join("  ·  ", p);
        }
    }

    private static volatile List<Realm> realms = new ArrayList<>();
    private static volatile Map<String, List<Boss>> bosses = new LinkedHashMap<>();
    private static volatile List<Invasion> invasions = new ArrayList<>();
    private static volatile long lastOk, lastTry;
    private static volatile String problem;
    private static volatile boolean busy;
    /** Someone is looking at a Realms tab (set by the page each second it's showing). */
    static volatile long lookedAt;
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    enum Kind { BOSS, WORMHOLE, CLOSING }

    /** Something that just showed up: a realm boss, an invasion's wormholes, or a realm about to close. */
    static final class Sighting {
        Kind kind;
        /** What the tick box is called: the boss, "Forax Wormhole", or {@link #CLOSING}. */
        String name;
        String server = "", realm = "", detail = "";
        int players = -1;
        long atMs;

        String where() {
            return server + " " + realm;
        }
    }

    static final String CLOSING = "Realm closing (90%+)";
    static final String[] WORMHOLES = {"Forax Wormhole", "Katalund Wormhole", "Malogia Wormhole", "Untaris Wormhole"};
    private static final int MAX_SIGHTINGS = 60;
    private static final List<Sighting> sightings = new CopyOnWriteArrayList<>();
    /** What was there last check: boss object ids, "server|realm|Forax Wormhole", realms at 90%+. */
    private static Set<String> lastSeen;
    /** Told about each new sighting that's ticked and in your region (when pings are on). */
    static volatile Consumer<Sighting> onPing;
    /** Wormholes the pup itself has seen in the realm you're in ("Horizon: Forax ×5, Untaris"), or null. */
    static volatile String myRealm;
    private static Thread poller;

    static List<Realm> realms() {
        return realms;
    }

    static List<Boss> bosses(String realmKey) {
        List<Boss> b = bosses.get(realmKey);
        return b == null ? new ArrayList<>() : b;
    }

    static List<Invasion> invasions() {
        return invasions;
    }

    static long lastOk() {
        return lastOk;
    }

    static String problem() {
        return problem;
    }

    static void addListener(Runnable r) {
        listeners.add(r);
    }

    private static void changed() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (Exception ignored) {
            }
        }
    }

    /** Pings (sound + diary) for ticked things; also keeps checking with the tab closed. */
    static boolean pings() {
        return Boolean.parseBoolean(EventTracker.setting("atti.ping", "false"));
    }

    static void setPings(boolean on) {
        EventTracker.setSetting("atti.ping", String.valueOf(on));
        if (on) start();
        changed();
    }

    static boolean sound() {
        return Boolean.parseBoolean(EventTracker.setting("atti.sound", "true"));
    }

    static void setSound(boolean on) {
        EventTracker.setSetting("atti.sound", String.valueOf(on));
        changed();
    }

    /** "Only what I'm watching" (false) or "Everything" (true) in the Latest list. */
    static boolean showAll() {
        return Boolean.parseBoolean(EventTracker.setting("atti.showAll", "true"));
    }

    static void setShowAll(boolean on) {
        EventTracker.setSetting("atti.showAll", String.valueOf(on));
        changed();
    }

    private static String tickKey(String name) {
        return "atti.tick." + Squeak.slug(name);
    }

    /** Wormholes and closing realms start ticked, bosses unticked. */
    static boolean ticked(String name) {
        boolean def = name.endsWith("Wormhole") || CLOSING.equals(name);
        return Boolean.parseBoolean(EventTracker.setting(tickKey(name), String.valueOf(def)));
    }

    static void setTicked(String name, boolean on) {
        EventTracker.setSetting(tickKey(name), String.valueOf(on));
        changed();
    }

    static void setAllBosses(boolean on) {
        for (String b : bossNames()) EventTracker.setSetting(tickKey(b), String.valueOf(on));
        changed();
    }

    /** Every realm event: the known list (the same one as the Events tab) plus any atti.network has shown, A to Z. */
    static List<String> bossNames() {
        java.util.TreeSet<String> s = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        RealmStockFeed.ensureEventNames();
        s.addAll(RealmStockFeed.eventNames());
        for (String n : EventTracker.setting("atti.known", "").split("\\|")) if (!n.trim().isEmpty()) s.add(n.trim());
        return new ArrayList<>(s);
    }

    private static void remember(Set<String> names) {
        List<String> known = bossNames();
        boolean added = false;
        for (String n : names) {
            if (n.isEmpty() || n.contains("|")) continue;
            boolean has = false;
            for (String k : known) if (k.equalsIgnoreCase(n)) has = true;
            if (!has) {
                known.add(n);
                added = true;
            }
        }
        if (added) EventTracker.setSetting("atti.known", String.join("|", known));
    }

    /** What showed up lately, newest first. */
    static List<Sighting> sightings() {
        List<Sighting> out = new ArrayList<>(sightings);
        out.sort((a, b) -> Long.compare(b.atMs, a.atMs));
        return out;
    }

    static String region() {
        return EventTracker.setting("realms.region", "ALL");
    }

    static void setRegion(String r) {
        EventTracker.setSetting("realms.region", r);
        changed();
    }

    static boolean inRegion(String server) {
        String r = region();
        return "ALL".equals(r) || r.equals(RealmStockFeed.regionOf(server));
    }

    /** Starts the background checker (safe to call more than once). */
    static synchronized void start() {
        if (poller != null && poller.isAlive()) return;
        poller = new Thread(AttiFeed::run, "atti-feed");
        poller.setDaemon(true);
        poller.start();
    }

    private static void run() {
        while (true) {
            boolean looking = System.currentTimeMillis() - lookedAt < 3000;
            long every = looking ? POLL_MS : BACKGROUND_POLL_MS;
            if ((looking || pings()) && !busy && System.currentTimeMillis() - lastTry >= every) fetchNow();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Checks right away (the Refresh button), in the background. */
    static void refresh() {
        if (busy) return;
        Thread t = new Thread(AttiFeed::fetchNow, "atti-refresh");
        t.setDaemon(true);
        t.start();
    }

    private static synchronized void fetchNow() {
        busy = true;
        lastTry = System.currentTimeMillis();
        try {
            realms = parseRealms(get(REALMS_URL, false));
            try {
                accept(get(DATA_URL, true));
                problem = null;
            } catch (Exception e) {
                problem = "Realms are fine, but the bosses and invasions couldn't load: "
                        + (e.getClass() == Exception.class ? e.getMessage() : why(e)) + ".";
            }
            diff();
            lastOk = System.currentTimeMillis();
        } catch (Exception e) {
            problem = "Can't reach atti.network right now (" + why(e) + "). Trying again shortly.";
        } finally {
            busy = false;
            changed();
        }
    }

    private static String why(Exception e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }

    // ---------------------------------------------------------------- parsing

    static List<Realm> parseRealms(String body) {
        JsonObject o = JsonParser.parseString(body).getAsJsonObject();
        List<Realm> out = new ArrayList<>();
        JsonArray a = o.has("realms") && o.get("realms").isJsonArray() ? o.getAsJsonArray("realms") : new JsonArray();
        for (JsonElement el : a) {
            if (!el.isJsonObject()) continue;
            JsonObject r = el.getAsJsonObject();
            Realm x = new Realm();
            x.server = str(r, "server");
            x.name = str(r, "realm");
            x.players = num(r, "players");
            x.percent = num(r, "percent");
            x.closed = r.has("closed") && !r.get("closed").isJsonNull() && r.get("closed").getAsBoolean();
            long unix = num(r, "last_updated_unix");
            x.updatedMs = unix > 0 ? unix * 1000L : 0;
            out.add(x);
        }
        out.sort((p, q) -> p.closed != q.closed ? (p.closed ? 1 : -1) : Integer.compare(q.percent, p.percent));
        return out;
    }

    /** Takes events_data.php: bosses from "map", invasions from "current_event". */
    static void accept(String body) throws Exception {
        if (body.trim().startsWith("<"))
            throw new Exception("atti.network sent its web page instead of the data, so it isn't letting the pup in. Send Sophie this line");
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) throw new Exception("not the data it expected");
        JsonObject o = root.getAsJsonObject();
        if (!o.has("map") && !o.has("current_event")) throw new Exception("atti.network sent a page instead of its data");
        Map<String, List<Boss>> b = new LinkedHashMap<>();
        if (o.has("map") && o.get("map").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("map")) {
                if (!el.isJsonObject()) continue;
                JsonObject m = el.getAsJsonObject();
                // bosses and other realm events (an altar, a nest...); the UFO ones are the invasion's
                String icon = str(m, "icon");
                if ("UFO".equalsIgnoreCase(icon) || AttiFeed.wormhole(str(m, "name")) != null) continue;
                Boss x = new Boss();
                x.name = str(m, "name");
                x.hp = num(m, "hp");
                x.maxHp = num(m, "maxhp");
                x.id = num(m, "objectid");
                b.computeIfAbsent(str(m, "server") + "|" + str(m, "realm"), k -> new ArrayList<>()).add(x);
            }
        }
        List<Invasion> inv = new ArrayList<>();
        if (o.has("current_event") && o.get("current_event").isJsonObject()) {
            JsonObject ce = o.getAsJsonObject("current_event");
            String event = str(ce, "event");
            if (ce.has("realms") && ce.get("realms").isJsonArray()) {
                for (JsonElement el : ce.getAsJsonArray("realms")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject r = el.getAsJsonObject();
                    Invasion x = new Invasion();
                    x.event = event.isEmpty() ? "Event" : event;
                    x.server = str(r, "server");
                    x.realm = str(r, "realm");
                    x.players = num(r, "players");
                    x.stage = num(r, "stage");
                    x.value = num(r, "value");
                    x.max = num(r, "max");
                    x.percent = num(r, "percent");
                    x.complete = r.has("complete") && !r.get("complete").isJsonNull() && r.get("complete").getAsBoolean();
                    if (r.has("objects") && r.get("objects").isJsonArray()) {
                        for (JsonElement ob : r.getAsJsonArray("objects")) {
                            if (!ob.isJsonObject()) continue;
                            String name = str(ob.getAsJsonObject(), "name");
                            String w = wormhole(name);
                            if (w != null) x.wormholes.merge(w, 1, Integer::sum);
                        }
                    }
                    inv.add(x);
                }
            }
        }
        bosses = b;
        invasions = inv;
    }

    /**
     * Compares this check with the last one: new bosses, new wormhole kinds in a realm, realms reaching 90%. They go in
     * the Latest list, and ticked ones in your region ping. The first check only fills the list (no pings for what was
     * already there when the pup started).
     */
    private static void diff() {
        long now = System.currentTimeMillis();
        Map<String, Realm> byKey = new LinkedHashMap<>();
        for (Realm r : realms) byKey.put(r.key(), r);
        Set<String> seen = new HashSet<>();
        List<Sighting> fresh = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Map.Entry<String, List<Boss>> e : bosses.entrySet()) {
            String[] sr = e.getKey().split("\\|", 2);
            for (Boss x : e.getValue()) {
                names.add(x.name);
                String k = "boss|" + e.getKey() + "|" + (x.id > 0 ? String.valueOf(x.id) : x.name);
                seen.add(k);
                if (lastSeen != null && lastSeen.contains(k)) continue;
                Sighting s = sighting(Kind.BOSS, x.name, sr[0], sr.length > 1 ? sr[1] : "", byKey.get(e.getKey()), now);
                int pc = x.percent();
                if (pc >= 0 && pc < 100) s.detail = pc + "% HP";
                fresh.add(s);
            }
        }
        for (Invasion x : invasions) {
            if (x.complete) continue;
            for (Map.Entry<String, Integer> w : x.wormholes.entrySet()) {
                String name = w.getKey() + " Wormhole";
                String k = "worm|" + x.key() + "|" + name;
                seen.add(k);
                if (lastSeen != null && lastSeen.contains(k)) continue;
                Sighting s = sighting(Kind.WORMHOLE, name, x.server, x.realm, byKey.get(x.key()), now);
                if (x.players >= 0) s.players = x.players;
                s.detail = (w.getValue() > 1 ? "×" + w.getValue() + "  ·  " : "") + x.event;
                fresh.add(s);
            }
        }
        for (Realm r : realms) {
            if (r.closed || r.percent < 90) continue;
            String k = "close|" + r.key();
            seen.add(k);
            if (lastSeen != null && lastSeen.contains(k)) continue;
            Sighting s = sighting(Kind.CLOSING, CLOSING, r.server, r.name, r, now);
            s.detail = r.percent + "%";
            fresh.add(s);
        }
        remember(names);
        boolean firstCheck = lastSeen == null;
        lastSeen = seen;
        sightings.addAll(0, fresh);
        while (sightings.size() > MAX_SIGHTINGS) sightings.remove(sightings.size() - 1);
        if (firstCheck) return;
        Consumer<Sighting> ping = onPing;
        if (ping == null || !pings()) return;
        for (Sighting s : fresh) if (inRegion(s.server) && ticked(s.name)) ping.accept(s);
    }

    private static Sighting sighting(Kind kind, String name, String server, String realm, Realm r, long now) {
        Sighting s = new Sighting();
        s.kind = kind;
        s.name = name;
        s.server = server;
        s.realm = realm;
        s.atMs = now;
        if (r != null) s.players = r.players;
        return s;
    }

    /** "AI Forax Wormhole Generator" -> "Forax"; null if it isn't a wormhole generator. */
    static String wormhole(String name) {
        if (name == null || !name.toLowerCase().contains("wormhole")) return null;
        String s = name.replaceFirst("(?i)^AI\\s+", "").replaceFirst("(?i)\\s*Wormhole Generator\\s*$", "").replaceFirst("(?i)\\s*Wormhole\\s*$", "").trim();
        return s.isEmpty() ? name : s;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static int num(JsonObject o, String k) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? (int) Math.min(Integer.MAX_VALUE, Math.round(o.get(k).getAsDouble())) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- fetching

    /** A stable, random id for this pup (the tracker counts viewers by it). */
    private static String viewerId() {
        String id = EventTracker.setting("atti.viewer", "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            EventTracker.setSetting("atti.viewer", id);
        }
        return id;
    }

    static String get(String url, boolean data) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Accept", "application/json, */*");
        if (data) {
            // atti asked us to send what Chrome sends on their Aliens page (simpler for them than allowing the pup by name)
            c.setRequestProperty("User-Agent", CHROME_UA);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            c.setRequestProperty("Referer", "https://atti.network/aliens");
            c.setRequestProperty("x-viewer-id", viewerId());
            c.setRequestProperty("Cache-Control", "no-cache");
            c.setRequestProperty("Pragma", "no-cache");
            c.setRequestProperty("sec-ch-ua", "\"Chromium\";v=\"142\", \"Google Chrome\";v=\"142\", \"Not_A Brand\";v=\"99\"");
            c.setRequestProperty("sec-ch-ua-mobile", "?0");
            c.setRequestProperty("sec-ch-ua-platform", "\"Windows\"");
            c.setRequestProperty("sec-fetch-dest", "empty");
            c.setRequestProperty("sec-fetch-mode", "cors");
            c.setRequestProperty("sec-fetch-site", "same-origin");
        } else {
            c.setRequestProperty("User-Agent", "WhiteBagPuppy/" + UpdateCheck.VERSION + " (realms tab; with atti's permission)");
        }
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
