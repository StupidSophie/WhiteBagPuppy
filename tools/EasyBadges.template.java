package whitebag;

import whitebag.Badges.Badge;
import whitebag.Badges.Counter;
import whitebag.Badges.Tier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The easy badges (2.12): small steps on the way to the bigger badges, plus three for every dungeon on the Dungeons
 * tab (finish a run there, finish 5, loot a bag there). Made by easy/gen.py; the dungeon ones come from dungeon-atlas.txt.
 */
final class EasyBadges {
    private EasyBadges() {
    }

    static List<Badge> all() {
        List<Badge> out = new ArrayList<>();
        //LADDERS
        dungeons(out);
        return out;
    }

    private static void add(List<Badge> out, String id, String name, String desc, String glyph, Tier tier, long need, Counter c) {
        out.add(new Badge(id, name, desc, glyph, tier, need, c));
    }

    /** "The Shatters" -> "shatters": how dungeon names are matched between the runs log and the dungeon list. */
    static String key(String name) {
        String k = name == null ? "" : name.toLowerCase(Locale.ROOT).replace("’", "'");
        if (k.startsWith("the ")) k = k.substring(4);
        return k.replaceAll("[^a-z0-9]+", "");
    }

    private static void dungeons(List<Badge> out) {
        Set<String> done = new LinkedHashSet<>();
        for (DungeonPage.Dungeon d : DungeonPage.dungeons()) {
            String k = key(d.name);
            if (k.isEmpty() || !done.add(k)) continue;
            add(out, "e-visit-" + k, "Hello, " + d.name, "Finish a run in " + d.name, "paw", Tier.BRONZE, 1, s -> s.runsIn(k));
            add(out, "e-bag-" + k, "Treats from " + d.name, "Loot a bag in " + d.name, "bone", Tier.BRONZE, 1, s -> s.bagsIn(k));
            add(out, "e-run5-" + k, d.name + " Regular", "Finish 5 runs in " + d.name, "paw", Tier.SILVER, 5, s -> s.runsIn(k));
        }
    }
}
