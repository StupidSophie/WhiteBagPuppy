package whitebag;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which dungeons each character has completed, for the dungeon fame bonuses: ticked by itself when you kill a
 * dungeon's boss on that character, and by hand on the Fame Bonuses page. Kept in fame-dungeons.txt
 * (account|character id|dungeon|time|done, undone or a character's name).
 */
final class FameDungeons {
    private FameDungeons() {
    }

    private static final String FILE = "fame-dungeons.txt";

    static final class Char {
        final String account;
        final int id;
        String label = "";
        long last;
        /** Dungeon -> when the pup saw you complete it. */
        final Map<String, java.util.List<Long>> done = new HashMap<>();
        /** Dungeon -> completions the game said this character has, and when it said so. */
        final Map<String, Integer> game = new HashMap<>();
        long gameAt;
        /** Dungeon -> ticked (true) or unticked (false) by hand, overriding what the pup saw. */
        final Map<String, Boolean> manual = new HashMap<>();

        Char(String account, int id) {
            this.account = account;
            this.id = id;
        }

        boolean has(String dungeon) {
            Boolean m = manual.get(dungeon);
            return m != null ? m : times(dungeon) > 0;
        }

        /** The game's count when you last loaded in, plus completions the pup saw since then. */
        int times(String dungeon) {
            int n = game.getOrDefault(dungeon, 0);
            for (long at : done.getOrDefault(dungeon, java.util.Collections.emptyList())) if (at > gameAt) n++;
            return n;
        }

        /** True once the game has told the pup this character's completions. */
        boolean fromGame() {
            return gameAt > 0;
        }
    }

    private static Map<String, Char> chars;

    private static String key(String account, int id) {
        return account + ":" + id;
    }

    private static synchronized Map<String, Char> chars() {
        if (chars != null) return chars;
        chars = new LinkedHashMap<>();
        try {
            for (String l : Files.readAllLines(Paths.get(FILE), StandardCharsets.UTF_8)) apply(l.split("\\|", -1));
        } catch (Exception ignored) {
        }
        return chars;
    }

    private static void apply(String[] f) {
        if (f.length < 5) return;
        int id;
        long at;
        try {
            id = Integer.parseInt(f[1]);
            at = Long.parseLong(f[3]);
        } catch (NumberFormatException e) {
            return;
        }
        Char c = chars.computeIfAbsent(key(f[0], id), k -> new Char(f[0], id));
        c.last = Math.max(c.last, at);
        if (f[2].equals("#game")) {
            c.gameAt = Math.max(c.gameAt, at);
            return;
        }
        if (f[2].equals("#name")) {
            c.label = f[4];
            return;
        }
        if (f[4].startsWith("game=")) {
            try {
                int n = Integer.parseInt(f[4].substring(5));
                if (n > 0) c.game.put(f[2], n);
                else c.game.remove(f[2]);
                c.gameAt = Math.max(c.gameAt, at);
            } catch (NumberFormatException ignored) {
            }
            return;
        }
        switch (f[4]) {
            case "done": c.done.computeIfAbsent(f[2], k -> new java.util.ArrayList<>()).add(at); break;
            case "on": c.manual.put(f[2], true); break;
            case "off": c.manual.put(f[2], false); break;
            case "auto": c.manual.remove(f[2]); break;
            default:
        }
    }

    private static synchronized void write(String account, int id, String dungeon, String what) {
        if (account == null || account.isEmpty() || id < 0) return;
        chars();
        String[] f = {account.replace('|', '/'), String.valueOf(id), dungeon.replace('|', '/'), String.valueOf(System.currentTimeMillis() / 1000), what.replace('|', '/')};
        apply(f);
        try {
            Files.write(Paths.get(FILE), Collections.singletonList(String.join("|", f)), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    /** The name a game map ("The Shatters") goes by in the fame bonuses (or the dungeon list), or null if it isn't a dungeon. */
    static String dungeonOf(String map) {
        if (map == null || !RunLog.isDungeon(map)) return null;
        String m = map.trim().replace('\u2019', '\'');
        for (String d : Bonuses.dungeons()) if (LootBook.samePlace(m, d) || d.equalsIgnoreCase(m)) return d;
        for (DungeonPage.Dungeon d : DungeonPage.dungeons()) if (LootBook.samePlace(m, d.name) || d.name.equalsIgnoreCase(m)) return d.name;
        return null;
    }

    /** The game's dungeon fame bonuses (fame-bonuses.txt, from the RealmEye wiki). */
    static final class Bonuses {
        static final class Set {
            final String name, what;
            final double percent;
            final int fame;
            final java.util.List<String> dungeons = new java.util.ArrayList<>();

            Set(String name, String what, double percent, int fame) {
                this.name = name;
                this.what = what;
                this.percent = percent;
                this.fame = fame;
            }
        }

        static final class Tiers {
            final String dungeon;
            final int[] times, fame;

            Tiers(String dungeon, int[] times, int[] fame) {
                this.dungeon = dungeon;
                this.times = times;
                this.fame = fame;
            }
        }

        static final String[] TIER_NAMES = {"Scout", "Explorer", "Adventurer", "Conqueror", "Master"};
        private static java.util.List<Set> sets;
        private static java.util.Map<String, Tiers> tiers;

        static synchronized java.util.List<Set> sets() {
            load();
            return sets;
        }

        static synchronized java.util.Map<String, Tiers> tiers() {
            load();
            return tiers;
        }

        /** Every dungeon any bonus mentions. */
        static synchronized java.util.Set<String> dungeons() {
            load();
            java.util.Set<String> out = new java.util.LinkedHashSet<>();
            for (Set s : sets) out.addAll(s.dungeons);
            out.addAll(tiers.keySet());
            return out;
        }

        private static int[] ints(String csv) {
            String[] p = csv.split(",");
            int[] out = new int[p.length];
            for (int i = 0; i < p.length; i++) out[i] = Integer.parseInt(p[i].trim());
            return out;
        }

        private static void load() {
            if (sets != null) return;
            sets = new java.util.ArrayList<>();
            tiers = new LinkedHashMap<>();
            try (java.io.InputStream in = FameDungeons.class.getResourceAsStream("/whitebag/fame-bonuses.txt")) {
                if (in == null) return;
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
                String l;
                while ((l = r.readLine()) != null) {
                    if (l.startsWith("#") || l.trim().isEmpty()) continue;
                    String[] f = l.split("\\|", -1);
                    try {
                        if (f[0].equals("C") && f.length >= 6) {
                            Set s = new Set(f[1], f[2], Double.parseDouble(f[3]), Integer.parseInt(f[4]));
                            for (String d : f[5].split(";")) if (!d.trim().isEmpty()) s.dungeons.add(d.trim());
                            sets.add(s);
                        } else if (f[0].equals("T") && f.length >= 4) {
                            tiers.put(f[1], new Tiers(f[1], ints(f[2]), ints(f[3])));
                        }
                    } catch (RuntimeException ignored) {
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /** You finished this dungeon (its boss died) on this character. Returns the dungeon's name if it was its first time. */
    static String completed(String account, int charId, String map) {
        String d = dungeonOf(map);
        if (d == null) return null;
        Char before = get(account, charId);
        boolean first = before == null || !before.has(d);
        write(account, charId, d, "done");
        return first ? d : null;
    }

    private static String plain(String s) {
        return s.toLowerCase(java.util.Locale.ROOT).replace("\u2019", "").replace("'", "").replaceAll("[^a-z0-9]+", " ").trim();
    }

    /**
     * The game's own completion counts for a character (sent each time you load in), so dungeons you did before the
     * pup was watching count too. Only changes are written down. Returns how many dungeons it knew about.
     */
    static synchronized int fromGame(String account, int charId, String pcStats) {
        if (account == null || account.isEmpty() || charId < 0 || pcStats == null) return 0;
        int[] counts;
        try {
            tomato.realmshark.RealmCharacterStats s = new tomato.realmshark.RealmCharacterStats();
            s.decode(pcStats);
            counts = s.completionCounts();
        } catch (Throwable e) {
            return 0;
        }
        if (counts == null) return 0; // couldn't read all of it: don't trust part of it
        java.util.List<String> names = tomato.realmshark.enums.CharacterStatistics.DUNGEON_NAMES;
        // the game's names to ours ("Santas Workshop" -> "Santa's Workshop")
        Map<String, String> ours = new HashMap<>();
        for (String d : Bonuses.dungeons()) ours.put(plain(d), d);
        for (DungeonPage.Dungeon d : DungeonPage.dungeons()) ours.putIfAbsent(plain(d.name), d.name);
        Map<String, Integer> now = new HashMap<>();
        for (int i = 0; i < counts.length && i < names.size(); i++) {
            String d = ours.getOrDefault(plain(names.get(i)), names.get(i));
            if (counts[i] > 0) now.merge(d, counts[i], Math::max);
        }
        Char c = chars().computeIfAbsent(key(account, charId), k -> new Char(account, charId));
        boolean changed = c.gameAt == 0;
        for (Map.Entry<String, Integer> e : now.entrySet()) if (!e.getValue().equals(c.game.get(e.getKey()))) changed = true;
        for (String d : c.game.keySet()) if (!now.containsKey(d)) changed = true;
        if (!changed) return now.size();
        java.util.Set<String> all = new java.util.HashSet<>(c.game.keySet());
        all.addAll(now.keySet());
        for (String d : all) {
            int n = now.getOrDefault(d, 0);
            if (n != c.game.getOrDefault(d, 0)) write(account, charId, d, "game=" + n);
        }
        write(account, charId, "#game", "seen"); // the game's counts are as of now
        return now.size();
    }

    /** Ticks or unticks a dungeon by hand. */
    static void set(String account, int charId, String dungeon, boolean on) {
        write(account, charId, dungeon, on ? "on" : "off");
    }

    /** Remembers what a character is called ("Wizard"), for the list of characters. */
    static synchronized void name(String account, int charId, String label) {
        Char c = get(account, charId);
        if (label == null || label.isEmpty() || (c != null && label.equals(c.label))) return;
        write(account, charId, "#name", label);
    }

    static synchronized Char get(String account, int charId) {
        return chars().get(key(account, charId));
    }

    /** Every character of these accounts the pup has noted, most recently played first. */
    static synchronized java.util.List<Char> of(java.util.function.Predicate<String> mine) {
        java.util.List<Char> out = new java.util.ArrayList<>();
        for (Char c : chars().values()) if (mine.test(c.account)) out.add(c);
        out.sort((a, b) -> Long.compare(b.last, a.last));
        return out;
    }
}
