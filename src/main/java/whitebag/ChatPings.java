package whitebag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Chat pings: a little chime when someone in chat says your name, whispers you, or says one of your words
 * ("chat.pings", "chat.pingName", "chat.pingWhispers" in whitebag-notifier.properties). Your own lines and NPCs never ping.
 */
final class ChatPings {
    private ChatPings() {
    }

    static volatile List<String> words = Collections.emptyList();
    static volatile boolean name = true;
    static volatile boolean whispers = true;
    /** Saves the three settings above (set by the main window). */
    static volatile Runnable saver = () -> { };

    private static volatile long lastSound;

    /** "white bag, oryx , lf trickster" -> [white bag, oryx, lf trickster]. */
    static List<String> parse(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String w : text.split(",")) {
            String t = w.trim().toLowerCase(Locale.ROOT);
            if (t.length() >= 2 && !out.contains(t)) out.add(t);
        }
        return out;
    }

    static String wordsText() {
        return String.join(", ", words);
    }

    /** Why this line pings ("your name", "a whisper", "\"oryx\""), or null when it doesn't. */
    static String reason(ChatLog.Line l, String myName) {
        if (l == null || l.fromMe || l.kind == ChatLog.Kind.SYSTEM) return null;
        if (serverNotice(l)) return null;
        if (whispers && l.kind == ChatLog.Kind.WHISPER && l.toMe) return "a whisper";
        String text = l.text == null ? "" : l.text.toLowerCase(Locale.ROOT);
        if (name && myName != null && myName.length() >= 2 && containsWord(text, myName.toLowerCase(Locale.ROOT))) return "your name";
        for (String w : words) if (containsWord(text, w)) return "\"" + w + "\"";
        return null;
    }

    private static final java.util.regex.Pattern NOTICE = java.util.regex.Pattern.compile(
            "(?i)\\bhas (come online|gone offline|joined|left)( the (guild|party))?\\b|\\bis now (online|offline)\\b");

    /**
     * The game's own notices ("Sophie has come online." in guild chat when you log in): they have no sender, or say
     * someone came online or left. They never ping, even though they have your name in them.
     */
    static boolean serverNotice(ChatLog.Line l) {
        String n = l.name == null ? "" : l.name.trim();
        if (n.isEmpty() || n.startsWith("*") || n.startsWith("#")) return true;
        return NOTICE.matcher(l.text == null ? "" : l.text).find();
    }

    /** The word on its own, not inside another one ("oryx" matches "oryx 3?" but not "oryxsanctuary"). */
    static boolean containsWord(String text, String word) {
        if (word.isEmpty()) return false;
        int from = 0;
        while (true) {
            int i = text.indexOf(word, from);
            if (i < 0) return false;
            int j = i + word.length();
            boolean before = i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1));
            boolean after = j >= text.length() || !Character.isLetterOrDigit(text.charAt(j));
            if (before && after) return true;
            from = i + 1;
        }
    }

    /** The chime, at most once every couple of seconds so a busy chat doesn't turn into a song. */
    static void chime() {
        long now = System.currentTimeMillis();
        if (now - lastSound < 2500) return;
        lastSound = now;
        Squeak.playPing();
    }
}
