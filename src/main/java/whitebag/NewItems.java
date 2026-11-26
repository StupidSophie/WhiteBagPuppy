package whitebag;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * New items after a game update: the items in the game files now that weren't there before the pup re-read them.
 * Each update's list goes in the diary and is added to new-items.txt.
 */
final class NewItems {
    private NewItems() {
    }

    private static final String FILE = "new-items.txt";
    private static final java.util.regex.Pattern INTERNAL =
            java.util.regex.Pattern.compile("(?i)\\b(proc[a-z0-9]*|proj(ectile)?|effect|test|dummy|placeholder)\\b|proc\\d|test$");

    /** The game files' item ids right now (call before re-reading them). */
    static Set<Integer> snapshot() {
        try {
            return new HashSet<>(ItemXml.ids());
        } catch (Throwable e) {
            return new HashSet<>();
        }
    }

    /**
     * The items that weren't in {@code before} (call after the files were re-read and reloaded): "Coral Bow (UT)",
     * "Doom Bow ✨"... Empty the first time (when there was nothing before to compare with).
     */
    static List<String> since(Set<Integer> before) {
        List<String> out = new ArrayList<>();
        if (before == null || before.isEmpty()) return out;
        List<Integer> fresh = new ArrayList<>();
        for (int id : snapshot()) if (!before.contains(id)) fresh.add(id);
        java.util.Collections.sort(fresh);
        Set<String> seen = new HashSet<>();
        for (int id : fresh) {
            ItemInfo i = ItemInfo.of(id);
            if (i.name.startsWith("Item #") || INTERNAL.matcher(i.name).find() || !seen.add(i.name)) continue;
            boolean shiny = LootBook.isShiny(id);
            out.add(i.name + (i.rarity.isEmpty() ? "" : " (" + i.rarity + ")") + (shiny ? " ✨" : ""));
        }
        return out;
    }

    /** "12 new items: A, B, C ... and 4 more", or null if there are none. Also saves the full list. */
    static String report(List<String> items) {
        if (items.isEmpty()) return null;
        try {
            List<String> lines = new ArrayList<>();
            lines.add("== Game update read " + new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ROOT).format(new Date())
                    + ": " + items.size() + " new items ==");
            lines.addAll(items);
            lines.add("");
            Files.write(Paths.get(FILE), lines, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
        int show = Math.min(25, items.size());
        StringBuilder sb = new StringBuilder();
        sb.append("*ears up* the game update added ").append(items.size()).append(items.size() == 1 ? " new item: " : " new items: ");
        sb.append(String.join(", ", items.subList(0, show)));
        if (items.size() > show) sb.append(" and ").append(items.size() - show).append(" more (the full list is in new-items.txt)");
        return sb.append(".").toString();
    }
}
