package whitebag;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Discord "Playing White Bag Puppy" status (Rich Presence): your character, class and fame, where you are, how long
 * you've been there, and your party. Talks to the Discord app on this PC through its local pipe
 * (\\.\pipe\discord-ipc-N), the same way games do; nothing goes over the internet from the pup and no token is used.
 * Each part can be switched off in Settings.
 */
final class DiscordPresence {
    private DiscordPresence() {
    }

    /** The White Bag Puppy application on Discord's developer portal (its name is what shows after "Playing"). */
    static final String DEFAULT_APP_ID = "1443716608194379846";

    /** What to show, read fresh each time (from the account you're looking at). */
    static final class Info {
        String name = "", cls = "", map = "";
        int fame = -1, partySize, partyMax;
        String partyName = "";
        long since;
        boolean inGame;
        /** Why it thinks you're not in game, for the debug file. */
        String why = "";
    }

    interface Settings {
        String get(String key, String fallback);
    }

    private static volatile Supplier<Info> source;
    private static volatile Settings settings;
    private static Thread thread;
    private static volatile String status = "Off";

    static String status() {
        return status;
    }

    /** Gets each new status line, for the pup diary. */
    static volatile java.util.function.Consumer<String> onStatus;

    private static void setStatus(String s) {
        if (s.equals(status)) return;
        status = s;
        java.util.function.Consumer<String> c = onStatus;
        if (c != null) c.accept(s);
    }

    /** Was discord-status-debug.txt while the status was being fixed; it works now, so nothing is written. */
    static void debug(String line) {
    }

    static synchronized void start(Supplier<Info> src, Settings s) {
        source = src;
        settings = s;
        if (thread != null) return;
        thread = new Thread(DiscordPresence::loop, "discord-status");
        thread.setDaemon(true);
        thread.start();
    }

    static boolean on() {
        Settings s = settings;
        return s != null && Boolean.parseBoolean(s.get("presence.on", "false")) && !appId().isEmpty();
    }

    static String appId() {
        Settings s = settings;
        String id = s == null ? "" : s.get("presence.appId", "").trim();
        return id.isEmpty() ? DEFAULT_APP_ID : id;
    }

    private static boolean show(String part) {
        return Boolean.parseBoolean(settings.get("presence." + part, "true"));
    }

    /** Wakes the loop so a change in Settings shows straight away. */
    static void poke() {
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    // ---------------------------------------------------------------- the connection

    private static RandomAccessFile pipe;
    private static String connectedId = "";
    private static String lastSent = "";
    private static long lastSentAt;
    private static String lastWhy = "";
    /** How many updates have been written to the debug file since connecting. */
    private static int logged;

    private static void loop() {
        while (true) {
            try {
                if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                    setStatus("Only works on Windows");
                    sleep(60_000);
                    continue;
                }
                if (!on()) {
                    if (pipe != null) {
                        clear();
                        close();
                    }
                    setStatus(appId().isEmpty() ? "Needs the Discord application ID" : "Off");
                    sleep(5_000);
                    continue;
                }
                if (pipe == null || !connectedId.equals(appId())) {
                    close();
                    if (!connect()) {
                        setStatus("Discord isn't open (trying again shortly)");
                        sleep(20_000);
                        continue;
                    }
                }
                String activity = activity();
                long now = System.currentTimeMillis();
                if (activity == null) {
                    // the game's closed: take the status down (once)
                    if (!lastSent.isEmpty()) {
                        clear();
                        lastSent = "";
                        lastSentAt = now;
                    }
                    Supplier<Info> src = source;
                    Info in = src == null ? null : src.get();
                    String why = in == null ? "no account yet" : in.why;
                    if (!why.equals(lastWhy)) debug("not in game: " + why);
                    lastWhy = why;
                    setStatus("Connected, waiting for the game");
                    sleep(5_000);
                    continue;
                }
                // Discord allows about 5 updates every 20 seconds: send only changes, at most every 5 seconds
                if (!activity.equals(lastSent) && now - lastSentAt >= 5_000) {
                    // one pipe handle can't read and write at the same time, so each update waits for Discord's answer
                    send(1, "{\"cmd\":\"SET_ACTIVITY\",\"args\":{\"pid\":" + pid() + ",\"activity\":" + activity + "},\"nonce\":\"" + now + "\"}");
                    int[] op = new int[1];
                    String reply = read(op);
                    if (op[0] == 2) throw new java.io.IOException("Discord closed the connection");
                    lastSent = activity;
                    lastSentAt = now;
                    if (reply.contains("\"evt\":\"ERROR\"")) {
                        setStatus("Discord said no: " + reply.replaceAll(".*\"message\":\"([^\"]*)\".*", "$1"));
                        sleep(30_000);
                        continue;
                    }
                }
                setStatus("Showing on Discord");
                sleep(5_000);
            } catch (Exception e) {
                close();
                setStatus("Lost Discord (" + e.getClass().getSimpleName() + "), trying again shortly");
                sleep(15_000);
            }
        }
    }

    /** One line, at most {@code max} characters. */
    private static String shorten(String s, int max) {
        String one = s == null ? "" : s.replaceAll("\\s+", " ");
        return one.length() > max ? one.substring(0, max) + "..." : one;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            // poked: go round again now
        }
    }

    private static boolean connect() {
        String firstError = null;
        for (int i = 0; i < 20; i++) {
            // \\?\pipe\... (Java tidies the \\.\ form into a network path that doesn't exist); also the plain form
            String name = (i < 10 ? "\\\\?\\pipe\\discord-ipc-" : "\\\\.\\pipe\\discord-ipc-") + (i % 10);
            try {
                RandomAccessFile p = new RandomAccessFile(name, "rw");
                pipe = p;
                send(0, "{\"v\":1,\"client_id\":\"" + appId().replaceAll("[^0-9]", "") + "\"}");
                int[] op = new int[1];
                String reply = read(op);
                if (op[0] != 1 || !reply.contains("READY")) {
                    debug("pipe " + name + " answered op " + op[0] + ": " + shorten(reply, 200));
                    close();
                    continue;
                }
                debug("connected through " + name);
                logged = 0;
                connectedId = appId();
                lastSent = "";
                return true;
            } catch (Exception e) {
                if (firstError == null && !(e instanceof java.io.FileNotFoundException)) firstError = name + ": " + e;
                close();
            }
        }
        debug("no Discord pipe found" + (firstError == null ? " (is the Discord desktop app open?)" : ", first error " + firstError));
        return false;
    }

    private static void send(int op, String json) throws Exception {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(op).putInt(body.length).put(body);
        pipe.write(b.array());
    }

    private static String read(int[] op) throws Exception {
        RandomAccessFile p = pipe;
        if (p == null) throw new java.io.EOFException();
        byte[] head = new byte[8];
        p.readFully(head);
        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        op[0] = h.getInt();
        int len = h.getInt();
        if (len < 0 || len > 1 << 20) throw new java.io.IOException("bad frame");
        byte[] body = new byte[len];
        p.readFully(body);
        return new String(body, StandardCharsets.UTF_8);
    }

    private static void clear() {
        try {
            send(1, "{\"cmd\":\"SET_ACTIVITY\",\"args\":{\"pid\":" + pid() + "},\"nonce\":\"" + System.currentTimeMillis() + "\"}");
            read(new int[1]);
        } catch (Exception ignored) {
        }
    }

    private static void close() {
        RandomAccessFile p = pipe;
        pipe = null;
        connectedId = "";
        lastSent = "";
        if (p != null) {
            try {
                p.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static long pid() {
        try {
            String n = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            return Long.parseLong(n.substring(0, n.indexOf('@')));
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- what it shows

    /** The activity as JSON, from the current game state and the switches in Settings, or null when you're not playing. */
    static String activity() {
        Supplier<Info> src = source;
        Info in = src == null ? null : src.get();
        JsonObject a = new JsonObject();
        JsonObject assets = new JsonObject();
        assets.addProperty("large_image", "pup");
        assets.addProperty("large_text", "White Bag Puppy " + UpdateCheck.VERSION);
        if (in == null || !in.inGame) return null; // not playing: no status at all
        // first line: who you are
        StringBuilder who = new StringBuilder();
        if (show("name") && !in.name.isEmpty()) who.append(in.name);
        if (show("class") && !in.cls.isEmpty()) who.append(who.length() > 0 ? " the " : "").append(in.cls);
        if (show("fame") && in.fame >= 0) who.append(who.length() > 0 ? "  ·  " : "").append(String.format(Locale.ROOT, "%,d fame", in.fame));
        if (who.length() > 0) a.addProperty("details", cut(who.toString()));
        // second line: where you are, and your party
        StringBuilder where = new StringBuilder();
        if (show("place") && !in.map.isEmpty()) where.append("In ").append(in.map);
        boolean party = show("party") && in.partySize > 0;
        if (party) where.append(where.length() > 0 ? "  ·  " : "").append(in.partyName.isEmpty() ? "In a party" : "Party: " + in.partyName);
        if (where.length() > 0) a.addProperty("state", cut(where.toString()));
        if (party && in.partyMax >= in.partySize && in.partySize > 0) {
            JsonObject p = new JsonObject();
            p.addProperty("id", "wbp-party");
            JsonArray size = new JsonArray();
            size.add(in.partySize);
            size.add(in.partyMax);
            p.add("size", size);
            a.add("party", p); // Discord adds "(2 of 8)" after the second line
        }
        if (show("time") && in.since > 0) {
            JsonObject t = new JsonObject();
            t.addProperty("start", in.since / 1000);
            a.add("timestamps", t); // Discord counts up "12:34 elapsed" by itself
        }
        a.add("assets", assets);
        return a.toString();
    }

    /** Discord's lines take 2 to 128 characters. */
    private static String cut(String s) {
        if (s.length() > 128) s = s.substring(0, 127) + "…";
        return s.length() < 2 ? s + " " : s;
    }
}
