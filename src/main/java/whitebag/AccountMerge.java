package whitebag;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Moves saved data (badges, bags, runs, characters, fame...) from one game account ID to another. For when the
 * pup starts seeing your account under a new ID (after a name change, say) and your badges and stats look empty.
 * Every log next to the app is rewritten field by field; a backup is made first.
 */
final class AccountMerge {
    private AccountMerge() {
    }

    /** Files that aren't the pup's own logs, so they're left alone. */
    private static boolean skip(String name) {
        return !name.endsWith(".txt") || name.equals("new-items.txt") || name.startsWith("README") || name.startsWith("LICENSE");
    }

    private static final Pattern JSON_ACCOUNT = Pattern.compile("\"account\"\\s*:\\s*\"([^\"]*)\"");

    /** An account ID the pup has data for. */
    static final class Found {
        final String key;
        String name = "";
        int badges, bags, lines;
        long last;

        Found(String key) {
            this.key = key;
        }

        String label() {
            String n = name.isEmpty() ? "an account" : name;
            return n + "  (" + badges + (badges == 1 ? " badge, " : " badges, ") + bags + (bags == 1 ? " bag" : " bags")
                    + (last > 0 ? ", last seen " + new SimpleDateFormat("d MMM yyyy", Locale.ROOT).format(new Date(last * 1000L)) : "") + ")";
        }
    }

    /** Every account ID with data, most data first. */
    static List<Found> accounts() {
        Map<String, Found> by = new LinkedHashMap<>();
        // the IDs to look for: whoever has badges, bags or characters
        for (Badges.Unlock u : Badges.unlocks()) {
            if (u.account.isEmpty()) continue;
            Found f = by.computeIfAbsent(u.account, Found::new);
            if (!u.id.equals("-") && !u.id.startsWith("~") && Badges.byId(u.id) != null) f.badges++;
            f.last = Math.max(f.last, u.at);
        }
        for (LootStats.Entry e : LootStats.all()) {
            if (e.account == null || e.account.isEmpty()) continue;
            Found f = by.computeIfAbsent(e.account, Found::new);
            f.bags++;
            f.last = Math.max(f.last, e.at);
        }
        for (CharacterBags.Char c : CharacterBags.of(null)) {
            Found f = by.computeIfAbsent(c.account, Found::new);
            f.last = Math.max(f.last, c.at);
            if (f.name.isEmpty() && !c.name.isEmpty()) f.name = c.name;
        }
        for (Found f : by.values()) {
            try {
                String n = History.nameOf(f.key);
                if (n != null && !n.isEmpty()) f.name = n;
            } catch (Throwable ignored) {
            }
        }
        List<Found> out = new ArrayList<>(by.values());
        out.sort((a, b) -> Long.compare(b.last, a.last));
        return out;
    }

    /**
     * Rewrites every saved log so {@code from}'s data belongs to {@code to}. Returns how many lines changed.
     * The pup needs a restart afterwards (everything it has loaded still has the old ID).
     */
    static int move(String from, String to, java.util.Properties settings) throws Exception {
        if (from == null || to == null || from.isEmpty() || from.equals(to)) return 0;
        Backup.backupNow("before moving account data");
        int changed = 0;
        File[] files = new File(".").listFiles();
        if (files == null) return 0;
        for (File file : files) {
            if (!file.isFile() || skip(file.getName())) continue;
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            boolean any = false;
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i), fixed = fix(l, from, to);
                if (!fixed.equals(l)) {
                    lines.set(i, fixed);
                    any = true;
                    changed++;
                }
            }
            if (any) Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        }
        // settings that name an account
        for (String k : settings.stringPropertyNames()) {
            String v = settings.getProperty(k);
            if (from.equals(v)) settings.setProperty(k, to);
        }
        return changed;
    }

    /** One line with {@code from} swapped for {@code to}: JSON "account" fields, and |-separated fields. */
    static String fix(String line, String from, String to) {
        if (line.trim().startsWith("{")) {
            Matcher m = JSON_ACCOUNT.matcher(line);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String v = m.group(1);
                m.appendReplacement(sb, Matcher.quoteReplacement(v.equals(from) ? "\"account\":\"" + to + "\"" : m.group()));
            }
            m.appendTail(sb);
            return sb.toString();
        }
        if (!line.contains(from)) return line;
        String[] f = line.split("\\|", -1);
        boolean any = false;
        for (int i = 0; i < f.length; i++) {
            if (f[i].equals(from)) {
                f[i] = to;
                any = true;
            } else if (f[i].startsWith(from + "/")) { // ticks like "<account>/<quest>#pin"
                f[i] = to + f[i].substring(from.length());
                any = true;
            }
        }
        return any ? String.join("|", f) : line;
    }
}
