package whitebag;

import packets.data.enums.StatType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * What each of your characters had on them the last time the pup saw them (gear, inventory, backpack), for the
 * Treasury. Kept in character-items.txt: account|charId|class type|name|seasonal|seen at|gear ids|bag ids.
 */
final class CharacterBags {
    private CharacterBags() {
    }

    private static final String FILE = "character-items.txt";

    static final class Char {
        final String account;
        final int charId, classType;
        final String name;
        final boolean seasonal;
        final long at; // Unix seconds
        final int[] gear, bags;
        /** Level, current fame, and the 8 base stats (life, mana, att, def, spd, dex, vit, wis) without gear. Empty when unknown. */
        int level = -1, fame = -1;
        int[] stats = new int[0];

        Char(String account, int charId, int classType, String name, boolean seasonal, long at, int[] gear, int[] bags) {
            this.account = account;
            this.charId = charId;
            this.classType = classType;
            this.name = name;
            this.seasonal = seasonal;
            this.at = at;
            this.gear = gear;
            this.bags = bags;
        }

        String line() {
            return account.replace('|', '/') + "|" + charId + "|" + classType + "|" + name.replace('|', '/') + "|" + (seasonal ? 1 : 0)
                    + "|" + at + "|" + join(gear) + "|" + join(bags) + "|" + level + "|" + fame + "|" + join(stats);
        }
    }

    private static Map<String, Char> chars; // account|charId -> latest

    private static Map<String, Char> chars() {
        if (chars != null) return chars;
        chars = new LinkedHashMap<>();
        try {
            for (String l : Files.readAllLines(Paths.get(FILE), StandardCharsets.UTF_8)) {
                String[] f = l.split("\\|", -1);
                if (f.length < 8) continue;
                try {
                    Char c = new Char(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]), f[3], f[4].equals("1"),
                            Long.parseLong(f[5]), split(f[6]), split(f[7]));
                    if (f.length >= 11) {
                        c.level = Integer.parseInt(f[8]);
                        c.fame = Integer.parseInt(f[9]);
                        c.stats = split(f[10]);
                    }
                    chars.put(c.account + "|" + c.charId, c);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return chars;
    }

    /** Each stat and its gear boost, in the game's order: life, mana, attack, defense, speed, dexterity, vitality, wisdom. */
    static final StatType[][] STATS = {
            {StatType.MAX_HP_STAT, StatType.MAX_HP_BOOST_STAT}, {StatType.MAX_MP_STAT, StatType.MAX_MP_BOOST_STAT},
            {StatType.ATTACK_STAT, StatType.ATTACK_BOOST_STAT}, {StatType.DEFENSE_STAT, StatType.DEFENSE_BOOST_STAT},
            {StatType.SPEED_STAT, StatType.SPEED_BOOST_STAT}, {StatType.DEXTERITY_STAT, StatType.DEXTERITY_BOOST_STAT},
            {StatType.VITALITY_STAT, StatType.VITALITY_BOOST_STAT}, {StatType.WISDOM_STAT, StatType.WISDOM_BOOST_STAT}};

    private static final StatType[] GEAR = {StatType.INVENTORY_0_STAT, StatType.INVENTORY_1_STAT, StatType.INVENTORY_2_STAT, StatType.INVENTORY_3_STAT};

    /** Remembers what this character has on them now. Only writes (and returns true) when something changed. */
    static synchronized boolean note(String account, int charId, MeTracker.Thing c) {
        if (account == null || account.isEmpty() || charId < 0 || c == null) return false;
        int[] gear = new int[4];
        for (int k = 0; k < 4; k++) gear[k] = c.i(GEAR[k], -1);
        List<Integer> bag = new ArrayList<>();
        for (StatType t : StatType.values()) {
            String n = t.name();
            boolean inv = n.startsWith("INVENTORY_") && !n.equals("INVENTORY_0_STAT") && !n.equals("INVENTORY_1_STAT")
                    && !n.equals("INVENTORY_2_STAT") && !n.equals("INVENTORY_3_STAT");
            boolean pack = n.startsWith("BACKPACK_") && !n.equals("BACKPACK_SLOTS_STAT");
            if (!inv && !pack) continue;
            int v = c.i(t, -1);
            if (v > 0) bag.add(v);
        }
        int[] bags = new int[bag.size()];
        for (int i = 0; i < bags.length; i++) bags[i] = bag.get(i);
        String name = DpsTracker.cleanName(c.s(StatType.NAME_STAT));
        String key = account + "|" + charId;
        int level = c.i(StatType.LEVEL_STAT, -1), fame = c.i(StatType.CURR_FAME_STAT, -1);
        int[] stats = new int[8];
        for (int k = 0; k < 8; k++) {
            int total = c.i(STATS[k][0], -1);
            stats[k] = total < 0 ? -1 : total - c.i(STATS[k][1], 0);
        }
        Char old = chars().get(key);
        if (old != null && old.classType == c.objectType && java.util.Arrays.equals(old.gear, gear) && java.util.Arrays.equals(old.bags, bags)
                && old.level == level && old.fame == fame && java.util.Arrays.equals(old.stats, stats)) return false;
        Char fresh = new Char(account, charId, c.objectType, name, Owned.seasonal(c), System.currentTimeMillis() / 1000, gear, bags);
        fresh.level = level;
        fresh.fame = fame;
        fresh.stats = stats;
        chars.put(key, fresh);
        List<String> lines = new ArrayList<>();
        for (Char x : chars.values()) lines.add(x.line());
        try {
            Files.write(Paths.get(FILE), lines, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
        return true;
    }

    /** What the puppy last saw on this character, or null. */
    static synchronized Char get(String account, int charId) {
        return chars().get(account + "|" + charId);
    }

    /** Takes a character off the list (it died, or was deleted). */
    static synchronized void forget(String account, int charId) {
        if (chars().remove(account + "|" + charId) == null) return;
        List<String> lines = new ArrayList<>();
        for (Char x : chars.values()) lines.add(x.line());
        try {
            Files.write(Paths.get(FILE), lines, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** These accounts' characters, most recently seen first. */
    static synchronized List<Char> of(Predicate<String> mine) {
        List<Char> out = new ArrayList<>();
        for (Char c : chars().values()) if (mine == null || mine.test(c.account)) out.add(c);
        out.sort((a, b) -> Long.compare(b.at, a.at));
        return Collections.unmodifiableList(out);
    }

    private static String join(int[] a) {
        StringBuilder sb = new StringBuilder();
        for (int v : a) {
            if (sb.length() > 0) sb.append(',');
            sb.append(v);
        }
        return sb.toString();
    }

    private static int[] split(String s) {
        if (s == null || s.isEmpty()) return new int[0];
        String[] p = s.split(",");
        int[] a = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            try {
                a[i] = Integer.parseInt(p[i].trim());
            } catch (NumberFormatException e) {
                a[i] = -1;
            }
        }
        return a;
    }
}
