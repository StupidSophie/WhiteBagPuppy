package whitebag;

import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * The graveyard: every character that died while the puppy was watching, with what killed it, where, its fame and
 * the gear it had on. Kept in deaths.txt, one JSON line per death.
 */
final class DeathLog {
    private DeathLog() {
    }

    private static final String FILE = "deaths.txt";

    static final class Death {
        String account = "";
        int charId = -1, classType = -1, level = -1;
        String name = "";
        boolean seasonal;
        long at; // Unix seconds
        String killedBy = "";
        String where = "";
        /** Fame on the character when it died, and the total with death bonuses (from the game's death message). */
        int baseFame = -1, totalFame = -1;
        int[] gear = new int[0], bags = new int[0], stats = new int[0];
        /** The death bonuses the game gave, like "Well Equipped +120". */
        List<String> bonuses = new ArrayList<>();
        /** The last few hits before it died, e.g. "Abyssal Kraken 140, 140 \u00b7 Jellyfish 85" (from 2.30.45; null before). */
        String lastHits;
    }

    private static List<Death> deaths;

    private static List<Death> deaths() {
        if (deaths != null) return deaths;
        deaths = new ArrayList<>();
        try {
            Gson g = new Gson();
            for (String l : Files.readAllLines(Paths.get(FILE), StandardCharsets.UTF_8)) {
                if (l.trim().isEmpty()) continue;
                try {
                    Death d = g.fromJson(l, Death.class);
                    if (d != null) deaths.add(d);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return deaths;
    }

    /** Adds a death; false if it was already there. */
    static synchronized boolean add(Death d) {
        // the game can send the death message twice; keep one
        for (Death x : deaths()) if (x.account.equals(d.account) && x.charId == d.charId && Math.abs(x.at - d.at) < 120) return false;
        deaths.add(d);
        try {
            Files.write(Paths.get(FILE), Collections.singletonList(new Gson().toJson(d)), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
        return true;
    }

    /** Takes one death off the list. */
    static synchronized void remove(Death d) {
        if (!deaths().remove(d)) return;
        List<String> lines = new ArrayList<>();
        Gson g = new Gson();
        for (Death x : deaths) lines.add(g.toJson(x));
        try {
            Files.write(Paths.get(FILE), lines, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** These accounts' deaths, newest first. */
    static synchronized List<Death> of(Predicate<String> mine) {
        List<Death> out = new ArrayList<>();
        for (Death d : deaths()) if (mine == null || mine.test(d.account)) out.add(d);
        out.sort((a, b) -> Long.compare(b.at, a.at));
        return out;
    }

    /** How many times each enemy has killed one of this account's characters (lower-case name -> count). */
    static synchronized java.util.Map<String, Integer> killers(String account) {
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        for (Death d : deaths()) {
            if (!d.account.equals(account) || d.killedBy == null || d.killedBy.trim().isEmpty()) continue;
            out.merge(key(d.killedBy), 1, Integer::sum);
        }
        return out;
    }

    /** An enemy's name made comparable: "Septavius the Ghost God" -> "septavius the ghost god". */
    static String key(String name) {
        String n = DpsTracker.cleanName(name);
        return n == null ? "" : n.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** "Well Equipped +120" for each bonus in the game's death message (FameData's fields aren't public). */
    static List<String> bonuses(packets.data.FameData[] data) {
        List<String> out = new ArrayList<>();
        if (data == null) return out;
        for (packets.data.FameData f : data) {
            if (f == null) continue;
            try {
                java.lang.reflect.Field a = f.getClass().getDeclaredField("achievement");
                java.lang.reflect.Field n = f.getClass().getDeclaredField("fameAdded");
                a.setAccessible(true);
                n.setAccessible(true);
                String name = String.valueOf(a.get(f));
                int add = n.getInt(f);
                if (!name.isEmpty() && !name.equals("null")) out.add(name + (add != 0 ? " +" + add : ""));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }
}
