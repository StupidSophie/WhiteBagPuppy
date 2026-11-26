package whitebag;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import packets.data.enums.StatType;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Who's on, for Biscuit's /online: about once a minute while you're playing, the pup tells the guild's Biscuit your
 * name, class, server, where you are and your party, plus the guildmates it can see near you (so people without the pup show
 * up too). When you stop playing it says so once. Only with a Biscuit link, and only when ticked in Settings.
 */
final class GuildOnline {
    private GuildOnline() {
    }

    /** The /pup/online address for a Biscuit link (https://.../pup/post?code=X), or null. */
    static String urlFor(String biscuitLink) {
        if (!DiscordWebhook.isBiscuitLink(biscuitLink)) return null;
        return biscuitLink.trim().replaceFirst("&kind=\\w+$", "").replaceFirst("/pup/post\\?", "/pup/online?");
    }

    /** What this account is doing, or null when it isn't in game. */
    static String report(Account a) {
        String name = a.me.myName();
        if (name.isEmpty() || System.currentTimeMillis() - a.lastTick > 30_000) return null;
        JsonObject me = new JsonObject();
        me.addProperty("name", name);
        int cls = a.me.myClassType();
        if (cls > 0) me.addProperty("cls", AboutMe.objectName(cls, ""));
        int fame = a.me.fameNow();
        if (fame >= 0) me.addProperty("fame", fame);
        String map = a.visitMap();
        String where = map == null ? "" : RunLog.label(map);
        if (!where.isEmpty()) me.addProperty("map", where);
        String server = a.server;
        if (!server.isEmpty()) me.addProperty("server", server);
        if (a.players.partySize() > 0) {
            me.addProperty("party", a.players.partySize());
            me.addProperty("pmax", a.players.partyMax());
        }
        JsonObject o = new JsonObject();
        o.add("me", me);
        // guildmates in view (same guild as you)
        String guild = a.me.myGuild();
        JsonArray seen = new JsonArray();
        if (!guild.isEmpty()) {
            for (PlayersTracker.Player p : a.players.players()) {
                String n = p.name();
                if (n.isEmpty() || n.equalsIgnoreCase(name) || !guild.equals(p.thing.s(StatType.GUILD_NAME_STAT).trim())) continue;
                JsonObject s = new JsonObject();
                s.addProperty("name", n);
                int c = p.thing.objectType;
                if (c > 0) s.addProperty("cls", AboutMe.objectName(c, ""));
                if (!where.isEmpty()) s.addProperty("map", where);
                if (!server.isEmpty()) s.addProperty("server", server);
                seen.add(s);
                if (seen.size() >= 40) break;
            }
        }
        if (seen.size() > 0) o.add("seen", seen);
        return o.toString();
    }

    static String offline(String name) {
        JsonObject me = new JsonObject();
        me.addProperty("name", name);
        me.addProperty("offline", true);
        JsonObject o = new JsonObject();
        o.add("me", me);
        return o.toString();
    }

    /** Sends it; the HTTP status, or -1 when Biscuit couldn't be reached. */
    static int send(String url, String json) {
        HttpURLConnection con = null;
        try {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(10_000);
            con.setReadTimeout(15_000);
            con.setDoOutput(true);
            con.setFixedLengthStreamingMode(body.length);
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            con.setRequestProperty("User-Agent", "WhiteBagPuppy/" + UpdateCheck.VERSION);
            try (OutputStream os = con.getOutputStream()) {
                os.write(body);
            }
            return con.getResponseCode();
        } catch (Exception e) {
            return -1;
        } finally {
            if (con != null) con.disconnect();
        }
    }
}
