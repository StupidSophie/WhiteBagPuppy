package whitebag;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The game's own death notice for a guildmate (the gravestone message at the top left: "Name died at level 20,
 * killed by Inferno"), which arrives wherever they are, not only when they're in view. A NOTIFICATION packet with the
 * PlayerDeath effect; its text is read either as plain text or as the game's {"k":..,"t":{..}} message.
 */
final class DeathNotice {
    final String name, killer;
    final int level;
    /** The gravestone picture's object type, or -1. */
    final int grave;

    private DeathNotice(String name, int level, String killer, int grave) {
        this.name = name;
        this.level = level;
        this.killer = killer;
        this.grave = grave;
    }

    private static final Pattern TEXT = Pattern.compile("^\\s*(\\S+)(?:\\s+.*?)?\\s+died at level\\s+(\\d+)\\s*,?\\s*killed by\\s+(.+?)\\.?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** The notice, or null when this notification isn't a death (or can't be read; see the debug file). */
    static DeathNotice of(packets.incoming.NotificationPacket p) {
        if (p == null || p.effect != packets.data.enums.NotificationEffectType.PlayerDeath || p.message == null) return null;
        debug(p.message);
        String msg = p.message.trim();
        if (msg.startsWith("{")) {
            try {
                // the game leaves a comma before the closing brace, which a strict reader refuses
                JsonObject o = JsonParser.parseString(msg.replaceAll(",\\s*([}\\]])", "$1")).getAsJsonObject();
                JsonObject t = o.has("t") && o.get("t").isJsonObject() ? o.getAsJsonObject("t") : o;
                String name = "", killer = "";
                int level = -1;
                for (Map.Entry<String, JsonElement> e : t.entrySet()) {
                    if (!e.getValue().isJsonPrimitive()) continue;
                    String k = e.getKey().toLowerCase(Locale.ROOT), v = e.getValue().getAsString().trim();
                    if (k.contains("level") || k.equals("lvl")) {
                        try {
                            level = Integer.parseInt(v.replaceAll("[^0-9]", ""));
                        } catch (NumberFormatException ignored) {
                        }
                    } else if (k.contains("kill") || k.contains("enemy") || k.equals("by") || k.contains("source")) {
                        killer = clean(v);
                    } else if (k.contains("name") || k.contains("player")) {
                        name = v;
                    }
                }
                // "Sophie,a19d,9a12": the name, then ids. The level is always sent as 1, so it isn't used.
                name = name.split("[,\\s]+")[0];
                if (level <= 1) level = -1;
                if (!name.isEmpty()) return new DeathNotice(DpsTracker.cleanName(name), level, killer, p.pictureType);
            } catch (Exception ignored) {
                // not JSON after all: try it as text
            }
        }
        Matcher m = TEXT.matcher(msg.replaceAll("<[^>]*>", ""));
        if (m.matches()) {
            return new DeathNotice(DpsTracker.cleanName(m.group(1)), Integer.parseInt(m.group(2)), clean(m.group(3)), p.pictureType);
        }
        unreadable = msg;
        return null;
    }

    /** The last death notice that couldn't be read, for the diary. */
    static volatile String unreadable;

    /** "{s.inferno}" / "Inferno" -> "Inferno" */
    static String clean(String s) {
        String t = s.replaceAll("[{}]", "").trim();
        if (t.matches("(?i)[a-z]+\\.[A-Za-z0-9_.]+")) {
            t = t.substring(t.lastIndexOf('.') + 1).replace('_', ' ');
            if (!t.isEmpty()) t = Character.toUpperCase(t.charAt(0)) + t.substring(1);
        }
        return t;
    }

    private static int logged;

    /** death-notices-debug.txt: the first few death notices as the game sent them (game text only, no chat). */
    private static synchronized void debug(String msg) {
        if (logged++ >= 30) return;
        try (java.io.FileWriter w = new java.io.FileWriter("death-notices-debug.txt", true)) {
            w.write(new java.text.SimpleDateFormat("d MMM HH:mm:ss").format(new java.util.Date()) + "  " + msg.replace('\n', ' ') + "\r\n");
        } catch (Exception ignored) {
        }
    }
}
