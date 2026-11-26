package whitebag;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Properties;

/**
 * A UPE contest for Loot Puppy: one character (until it dies), only UT and ST items count, scored by how much they
 * dismantle for plus their modification and shiny bonuses (added together). Points count when an item drops in your bag.
 *
 * <p>Default points (the contest sheet): dismantling tier 5 = 15, 4 = 10, 3 = 7, 2 = 5, 1 = 1, none = 0; then
 * uncommon +1, rare +2, legendary +3, divine +5, shiny +4. ST items are a flat 7 instead of their tier (9 from the Shatters). All of them can be changed in loot-puppy.properties.
 *
 * <p>The dismantling tier comes from the item's power tier in the game files (POWERTIER_S = tier 5, A = 4, B = 3,
 * C = 2, D = 1), which is how the Forge works out what an item dismantles for. Oryx gear (SS) is counted as tier 5.
 * Every scored item is kept in loot-puppy-contest.txt.
 */
final class Contest {
    /** Where every scored item is written (loot-puppy-contest.txt for Loot Puppy, upe-contest.txt for White Bag Puppy). */
    private final String FILE;
    private final Properties p;

    Contest(Properties settings) {
        this(settings, "loot-puppy-contest.txt");
    }

    Contest(Properties settings, String file) {
        this.p = settings;
        this.FILE = file;
        loadTierFixes(settings);
    }

    /**
     * Items whose dismantling tier isn't what the game's label says (item key -> tier). Built in, plus
     * contest.tiers in the settings, like "Esben's Wedding Ring=5; Some Item=4".
     */
    private static volatile Map<String, Integer> tierFixes = new HashMap<>();
    static final String TIER_FIXES = "Esben's Wedding Ring=5; Skullish Remains of Esben=5; Amulet of Restoration=5; Ring of Decades=5; The Forgotten Ring=5; "
            + "Yellow Beehemoth Armor=2; Red Beehemoth Armor=2; Blue Beehemoth Armor=2; Green Beehemoth Armor=2; "
            // all of Agents of Oryx (weapons, abilities, armors, rings): tier 4 (30 rare + 40 common), UT or ST
            + "Bounty Hunter's Dagger=4; Predator Bow=4; Oppressor's Staff=4; Tyrant's Wand=4; Henchman's Claymore=4; Blade of the Assailant=4; "
            + "Cloak of the Darkened Sun=4; Mad Javelin=4; Cursed Spire Spell=4; Tome of the Tarnished Gods=4; Helm of Exalted Might=4; Brutal Buckler=4; "
            + "Seal of the Battle God=4; Tyrant's Toxin=4; Demon Lord's Skull=4; Blacklight Artifice=4; Orb of Conquest=4; Prism of Shattered Light=4; "
            + "Siege Scepter=4; Rage Claws=4; Amaterasu=4; Chief's War Horn=4; Mechanical Mace=4; Blitz Sheath=4; "
            + "Squadron Sheathing=4; Judge's Robe=4; Aegis Armor=4; Mayhem Medallion=4; Ring of Wrath=4; Autarch Amulet=4; Hawkmaster Sigil=4; Elder Heartwood Staff=4; Wandering Souls Spell=3"
            // RealmEye Dismantling Values for UTs the game gives no tier (15/40 = 5, 30/40 = 4, 15/30 = 3, 15 = 1)
            + "; Overclocking Amulet=5; Kogbold Cower Shield=5; Laser Pointer=5; Scepter of Rust=5; Solar Flare Igniter=5; Steam Pipe=5; Beekeeper's Flamethrower=5; Swarmlord's Sigil=5; Crystalline Sigil=5; Crystallized Worm Spellblade=5; Sinister Syringes=5; Icebreaker Maul=5; Rusted Mechanism=5; Draconic Insignia=4; Adamantine Helm=4; Spirit's Bane=4; Morning Square=4; Sunken Fire Opal Ring=4; Sweetener Ring=4; Seismic Flail=4; Shard Launcher=4; Occult Bow=3; Silken Sigil=3; Spider Silk Bow=3; Sigil of the Rubber Duck=1";

    /** An ST item's points: its fixed dismantling tier if it has one (at least the Shatters' 9 there), else a flat 7 (9 in the Shatters). */
    int stPoints(String name, boolean shatters) {
        return stPoints(-1, name, shatters);
    }

    int stPoints(int id, String name, boolean shatters) {
        if (tierFixes.isEmpty()) loadTierFixes(p);
        Integer fix = fixFor(id, name);
        int flat = shatters ? num("contest.points.stShatters", 9) : num("contest.points.st", 7);
        return fix == null ? flat : Math.max(tierPoints(fix), shatters ? flat : 0);
    }

    /** The item's fixed dismantling tier, by its name or the name the game shows (some items are named like "AoO Sigil"); null if none. */
    static Integer fixFor(int id, String name) {
        Integer fix = name == null ? null : tierFixes.get(itemKey(name));
        if (fix == null && id > 0) {
            try {
                ItemXml.Info x = ItemXml.of(id);
                if (x != null && x.display != null && !x.display.isEmpty()) fix = tierFixes.get(itemKey(x.display));
            } catch (Throwable ignored) {
            }
        }
        return fix;
    }

    static void loadTierFixes(Properties settings) {
        Map<String, Integer> m = new HashMap<>();
        String extra = settings == null ? "" : settings.getProperty("contest.tiers", "");
        for (String part : (TIER_FIXES + ";" + extra).split(";")) {
            int eq = part.lastIndexOf('=');
            if (eq <= 0) continue;
            try {
                m.put(itemKey(part.substring(0, eq).trim()), Math.max(0, Math.min(5, Integer.parseInt(part.substring(eq + 1).trim()))));
            } catch (NumberFormatException ignored) {
            }
        }
        tierFixes = m;
    }

    // ---------------------------------------------------------------- the points sheet

    private int num(String key, int fallback) {
        try {
            return Integer.parseInt(p.getProperty(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** A UT item's base points: its dismantling tier's, or the ST flat 7 when the game gives it no tier at all. */
    int utPoints(int tier) {
        return tier > 0 ? tierPoints(tier) : num("contest.points.utNoTier", 7);
    }

    /** How a UT's tier reads in posts: "tier 4 (10)", or "no tier (7)". */
    static String tierText(int tier, int base) {
        return (tier > 0 ? "tier " + tier : "no tier") + " (" + base + ")";
    }

    int tierPoints(int tier) {
        switch (tier) {
            case 5: return num("contest.points.tier5", 15);
            case 4: return num("contest.points.tier4", 10);
            case 3: return num("contest.points.tier3", 7);
            case 2: return num("contest.points.tier2", 5);
            case 1: return num("contest.points.tier1", 1);
            default: return num("contest.points.noTier", 0);
        }
    }

    int bonus(Enchant e) {
        switch (e) {
            case UNCOMMON: return num("contest.points.uncommon", 1);
            case RARE: return num("contest.points.rare", 2);
            case LEGENDARY: return num("contest.points.legendary", 3);
            case DIVINE: return num("contest.points.divine", 5);
            default: return 0;
        }
    }

    /**
     * White bag bonuses by dungeon: "Oryx's Sanctuary=1; Moonlight Village=1; The Shatters=2" by default
     * (contest.points.whiteFrom in the settings). Returns {dungeon, points} for this map, or null.
     */
    String[] placeBonus(String map) {
        String m = norm(map);
        if (m.isEmpty()) return null;
        for (String part : p.getProperty("contest.points.whiteFrom", "Oryx's Sanctuary=1; Moonlight Village=1; The Shatters=2").split(";")) {
            String[] kv = part.split("=");
            if (kv.length != 2) continue;
            String place = kv[0].trim();
            if (place.isEmpty() || !m.contains(norm(place))) continue;
            try {
                return new String[]{place, String.valueOf(Integer.parseInt(kv[1].trim()))};
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /** "The Shatters" -> "shatters", "Oryx's Sanctuary" -> "oryxs sanctuary". */
    private static String norm(String s) {
        if (s == null) return "";
        String n = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").replaceAll("\\s+", " ").trim();
        return n.startsWith("the ") ? n.substring(4) : n;
    }

    /**
     * Extra points for particular items, on top of everything else: "Neo Reality Reactor=2; Neo Laser Rifle=1" by default
     * (contest.points.itemBonus, like "Neo Reality Reactor=2; Some Item=1"). Shiny versions count too.
     */
    int itemBonus(String name) {
        String key = itemKey(name);
        for (String part : p.getProperty("contest.points.itemBonus", "Neo Reality Reactor=2; Neo Laser Rifle=1").split(";")) {
            String[] kv = part.split("=");
            if (kv.length != 2 || kv[0].trim().isEmpty()) continue;
            if (!itemKey(kv[0].trim()).equals(key)) continue;
            try {
                return Integer.parseInt(kv[1].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    /**
     * A Forge craft: worth contest.points.soulFireCraft (2) with Concentrated Soul Fire, or contest.points.forgeCraft (1)
     * without, if your class can wear the item. Once per item: a Soul Fire craft of something you'd forged plainly adds
     * the difference. Returns the line for the diary, with the points added to the total.
     */
    synchronized String craft(ItemInfo item, int classType, boolean soulFire) {
        return craft(item, classType, soulFire, Enchant.NONE);
    }

    /** A Forge craft whose item came out with this modification (forged items can roll one too). */
    synchronized String craft(ItemInfo item, int classType, boolean soulFire, Enchant enchant) {
        if (enchant == null) enchant = Enchant.NONE;
        String how = soulFire ? "forged with Concentrated Soul Fire" : "forged";
        if (!counts(item)) return null; // the UPE is UT and ST only: tiered gear you forge doesn't count
        if (classType > 0 && !canEquip(classType, item.id)) return item.name + ": " + how + ", but can't be equipped by "
                + (AboutMe.objectName(classType, "").isEmpty() ? "your class" : "a " + AboutMe.objectName(classType, "")) + " (0)";
        long at = System.currentTimeMillis() / 1000;
        List<String> rows = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        int pts = 0;
        boolean newItem = false;
        // the craft itself: once per item (a Soul Fire craft after a plain one adds the difference)
        String key = "forged:" + scoreKey(item);
        int worth = soulFire ? num("contest.points.soulFireCraft", 2) : num("contest.points.forgeCraft", 1);
        Have h = have().get(key);
        int had = h == null ? 0 : h.mod;
        if (worth > had) {
            Have now = new Have();
            now.mod = worth;
            now.modName = how;
            have.put(key, now);
            pts += worth - had;
            newItem |= h == null;
            parts.add(how + (h == null ? "" : ", up from a plain craft") + " (" + (worth - had) + ")");
            rows.add(at + "|" + item.id + "|" + item.name.replace('|', '/') + " (forged)|0|" + worth + "|" + (worth - had)
                    + "|The Forge|forge#" + item.id + "#" + at + "|" + key + "|" + how + "|" + worth + "|0");
        }
        // the item: a UT/ST you forge counts like one that dropped (its tier, once per item; no place bonus)
        if (counts(item)) {
            String ik = scoreKey(item);
            boolean shiny = LootBook.isShiny(item.id);
            int shinyPts = shiny ? shinyBonus() : 0;
            int mod = bonus(enchant); // its modification counts, and a shiny adds +4 on top
            String modName = mod > 0 ? enchant.label.toLowerCase(Locale.ROOT) : "";
            Have old = have().get(ik);
            int tier = tier(item.id);
            if (old == null) {
                int base = "ST".equals(item.rarity) ? stPoints(item.id, item.name, false) : utPoints(tier);
                int extra = itemBonus(item.name);
                int got = base + extra + shinyPts + mod;
                Have n = new Have();
                n.shiny = shiny;
                n.mod = mod;
                n.modName = modName;
                have.put(ik, n);
                newItem = true;
                if (got > 0) {
                    pts += got;
                    parts.add(("ST".equals(item.rarity) ? "ST (" + base + ")" : tierText(tier, base))
                            + (mod > 0 ? " + " + modName + " (" + mod + ")" : "") + (shiny ? " + shiny (" + shinyPts + ")" : "")
                            + (extra > 0 ? " + " + extra + " bonus" : ""));
                }
                rows.add(at + "|" + item.id + "|" + item.name.replace('|', '/') + "|" + tier + "|" + (shinyPts + mod) + "|" + got
                        + "|The Forge|forge#" + item.id + "#" + at + "|" + ik + "|" + n.why() + "|" + mod + "|" + (shiny ? 1 : 0));
            } else {
                int add = 0;
                List<String> up = new ArrayList<>();
                if (mod > old.mod) {
                    add += mod - old.mod;
                    up.add((old.modName.isEmpty() ? "no modification" : old.modName) + " to " + modName + " (" + (mod - old.mod) + ")");
                    old.mod = mod;
                    old.modName = modName;
                }
                if (shiny && !old.shiny) {
                    old.shiny = true;
                    add += shinyPts;
                    up.add("first shiny (" + shinyPts + ")");
                }
                if (add > 0) {
                    pts += add;
                    parts.add("upgrade, " + String.join(" + ", up));
                    rows.add(at + "|" + item.id + "|" + item.name.replace('|', '/') + "|" + tier + "|" + old.bonus(shinyBonus()) + "|" + add
                            + "|The Forge|forge#" + item.id + "#" + at + "|" + ik + "|" + old.why() + "|" + old.mod + "|" + (old.shiny ? 1 : 0));
                } else {
                    parts.add("item already counted (0)");
                }
            }
        }
        if (pts <= 0) return item.name + ": " + how + " again, already counted (0)";
        last = item.name + " +" + pts;
        p.setProperty("contest.total", String.valueOf(total() + pts));
        if (newItem) p.setProperty("contest.items", String.valueOf(items() + 1));
        write(rows);
        return item.name + ": " + String.join(" + ", parts) + " = " + pts;
    }

    /**
     * An item you already scored this run went up in rarity where it sits (same item, same slot): adds the
     * difference, like a better copy dropping. Returns the line for the diary, or null if it adds nothing (not
     * scored yet, a shiny, can't be worn, or no better than what's counted).
     */
    synchronized String upgrade(ItemInfo item, int classType, Enchant to) {
        if (to == null || !counts(item)) return null;
        if (classType > 0 && !canEquip(classType, item.id)) return null;
        String ik = scoreKey(item);
        Have h = have().get(ik);
        if (h == null) return null; // not scored this run: only upgrades of counted items add points
        int mod = bonus(to);
        if (mod <= h.mod) return null;
        int add = mod - h.mod;
        String modName = to.label.toLowerCase(Locale.ROOT);
        String line = item.name + ": upgraded, " + (h.modName.isEmpty() ? "no modification" : h.modName) + " to " + modName + " (+" + add + ")";
        h.mod = mod;
        h.modName = modName;
        last = item.name + " +" + add;
        p.setProperty("contest.total", String.valueOf(total() + add));
        long at = System.currentTimeMillis() / 1000;
        write(java.util.Collections.singletonList(at + "|" + item.id + "|" + item.name.replace('|', '/') + "|" + tier(item.id) + "|"
                + h.bonus(shinyBonus()) + "|" + add + "|Upgraded|upgrade#" + item.id + "#" + at + "|" + ik + "|" + h.why() + "|" + h.mod + "|" + (h.shiny ? 1 : 0)));
        return line;
    }

    /**
     * What an item would score on this run, for the UPE tab's item check: a few short lines. {@code classType} is the
     * character's class (-1 if not known).
     */
    synchronized List<String> preview(ItemInfo it, int classType) {
        List<String> out = new ArrayList<>();
        if (!counts(it)) {
            out.add(tieredReskin(it) ? it.name + " is a reskin of tiered gear, so it scores 0 (like the original would)."
                    : it.name + " isn't a UT or ST item, so it scores 0.");
            return out;
        }
        String cls = classType > 0 ? AboutMe.objectName(classType, "") : "";
        if (classType > 0 && !canEquip(classType, it.id)) {
            out.add((cls.isEmpty() ? "Your class" : "A " + cls) + " can't wear " + it.name + ", so it scores 0.");
            return out;
        }
        boolean st = "ST".equals(it.rarity);
        int t = tier(it.id), base = st ? stPoints(it.id, it.name, false) : utPoints(t), extra = itemBonus(it.name), sh = shinyBonus();
        String what = st ? "ST (" + base + (stPoints(it.id, it.name, true) != base ? ", " + stPoints(it.id, it.name, true) + " from the Shatters" : "") + ")"
                : tierText(t, base);
        Have h = have().get(scoreKey(it));
        if (h == null) {
            int b = base + extra;
            out.add("Not counted yet: " + what + (extra > 0 ? " + " + extra + " bonus" : "") + " = " + b + ", plus its rarity:");
            out.add("none " + b + "  ·  uncommon " + (b + bonus(Enchant.UNCOMMON)) + "  ·  rare " + (b + bonus(Enchant.RARE)) + "  ·  legendary "
                    + (b + bonus(Enchant.LEGENDARY)) + "  ·  divine " + (b + bonus(Enchant.DIVINE)) + "  ·  shiny +" + sh + " on top");
            out.add("A white bag from Oryx's Sanctuary or Moonlight Village adds 1 (Umi 2), the Shatters 2.");
        } else {
            out.add("Already counted" + (h.why().isEmpty() ? "" : " (" + h.why() + ")") + ": a copy only adds what's better.");
            List<String> up = new ArrayList<>();
            for (Enchant e : new Enchant[]{Enchant.UNCOMMON, Enchant.RARE, Enchant.LEGENDARY, Enchant.DIVINE})
                if (bonus(e) > h.mod) up.add(e.label.toLowerCase(Locale.ROOT) + " +" + (bonus(e) - h.mod));
            if (!h.shiny) up.add("a shiny +" + sh);
            out.add(up.isEmpty() ? "Nothing more to get from this one." : "Better copies: " + String.join("  ·  ", up));
        }
        Have f = have().get("forged:" + scoreKey(it));
        int plain = num("contest.points.forgeCraft", 1), soul = num("contest.points.soulFireCraft", 2), had = f == null ? 0 : f.mod;
        out.add("Forging it: " + (plain > had ? "+" + (plain - had) : "0") + " (" + (soul > had ? "+" + (soul - had) : "0") + " with Concentrated Soul Fire)"
                + (h == null ? ", plus its points above" : ""));
        return out;
    }

    /** UT and ST items by name (lower case), for the item check. Built once. */
    private static volatile Map<String, ItemInfo> byName;

    static Map<String, ItemInfo> itemsByName() {
        Map<String, ItemInfo> m = byName;
        if (m != null) return m;
        m = new java.util.TreeMap<>();
        for (int id : ItemXml.ids()) {
            try {
                ItemInfo it = ItemInfo.of(id);
                if (!counts(it) || it.name.startsWith("Item #")) continue;
                String k = it.name.replaceAll("(?i)\\s*\\bshiny\\b", "").trim().toLowerCase(Locale.ROOT);
                ItemInfo had = m.get(k);
                if (had == null || (LootBook.isShiny(had.id) && !LootBook.isShiny(id))) m.put(k, it); // the plain one when there's both
            } catch (Throwable ignored) {
            }
        }
        if (!m.isEmpty()) byName = m;
        return m;
    }

    /** The items whose names match what you typed: an exact match first, then ones starting with it, then the rest. */
    static List<ItemInfo> find(String typed, int max) {
        String q = typed == null ? "" : typed.trim().toLowerCase(Locale.ROOT);
        List<ItemInfo> out = new ArrayList<>();
        if (q.length() < 2) return out;
        Map<String, ItemInfo> m = itemsByName();
        ItemInfo exact = m.get(q);
        if (exact != null) out.add(exact);
        for (Map.Entry<String, ItemInfo> e : m.entrySet()) if (out.size() < max && e.getKey().startsWith(q) && e.getValue() != exact) out.add(e.getValue());
        for (Map.Entry<String, ItemInfo> e : m.entrySet()) if (out.size() < max && !e.getKey().startsWith(q) && e.getKey().contains(q)) out.add(e.getValue());
        return out;
    }

    /** The inventory slot a Forge craft put its item in, or -1 if not known. */
    static int forgedSlot(int item, packets.data.SlotObjectData[] results) {
        if (results != null) for (packets.data.SlotObjectData s : results) if (s != null && s.objectType == item) return s.slotId;
        return -1;
    }

    /** The modification of the item in this slot, from a character's unique data (one entry per slot, like a bag's). */
    static Enchant enchantIn(String uniqueData, int slot) {
        if (uniqueData == null || slot < 0) return Enchant.NONE;
        String[] parts = uniqueData.split(",", -1);
        return slot < parts.length ? Enchant.of(parts[slot]) : Enchant.NONE;
    }

    /** The item a Forge craft made: the one you asked for ({@code wants}), or -1 for dismantling (no item asked for). */
    static int forgedItem(int wants, packets.data.SlotObjectData[] results) {
        if (wants <= 0) return -1;
        if (results != null) {
            int only = -1, n = 0;
            for (packets.data.SlotObjectData s : results) {
                if (s == null || s.objectType <= 0) continue;
                if (s.objectType == wants) return wants;
                only = s.objectType;
                n++;
            }
            if (n == 1 && ItemInfo.of(only).name != null && !ItemInfo.of(only).name.startsWith("item#")) return only;
        }
        return wants;
    }

    int shinyBonus() {
        return num("contest.points.shiny", 4);
    }

    /** The item's dismantling tier (5 best ... 1), or 0 if it has none. */
    static int tier(int id) {
        if (tierFixes.isEmpty()) loadTierFixes(null);
        {
            try {
                Integer fix = fixFor(id, ItemInfo.of(id).name);
                if (fix != null) return fix;
            } catch (Throwable ignored) {
            }
        }
        return gameTier(id);
    }

    /** The dismantling tier the game files give the item (no fixes); 0 if none. */
    static int gameTier(int id) {
        ItemXml.Info x = ItemXml.of(id);
        String labels = x == null || x.labels == null ? "" : x.labels;
        if (labels.isEmpty()) {
            try {
                labels = assets.IdToAsset.getIdLabel(id);
            } catch (Throwable ignored) {
            }
        }
        if (labels == null) return 0;
        for (String l : labels.split(",")) {
            switch (l.trim()) {
                case "POWERTIER_SS":
                case "POWERTIER_S": return 5;
                case "POWERTIER_A": return 4;
                case "POWERTIER_B": return 3;
                case "POWERTIER_C": return 2;
                case "POWERTIER_D": return 1;
                default:
            }
        }
        return 0;
    }

    /** True for a UT item the game gives no dismantling tier (it scores the ST flat 7). */
    private static boolean noTierUt(int id) {
        try {
            return id > 0 && "UT".equals(ItemInfo.of(id).rarity) && gameTier(id) == 0;
        } catch (Throwable e) {
            return false;
        }
    }

    /** True if a character of this class can equip the item (its slot type is one of the class's 4). */
    static boolean canEquip(int classType, int itemId) {
        ItemXml.Info x = ItemXml.of(itemId);
        return x == null || ClassMax.canEquip(classType, x.slotType);
    }

    /** True if a Forge request offers up a Concentrated Soul Fire. */
    static boolean usesSoulFire(packets.outgoing.ForgeRequestPacket fr) {
        if (fr == null || fr.dismantledItems == null) return false;
        for (packets.data.SlotObjectData s : fr.dismantledItems) {
            if (s == null || s.objectType <= 0) continue;
            String n = ItemInfo.of(s.objectType).name.toLowerCase(Locale.ROOT);
            if (n.contains("concentrated soul fire")) return true;
        }
        return false;
    }

    /** True for UT and ST items, the only ones a UPE counts. */
    static boolean counts(ItemInfo it) {
        return ("UT".equals(it.rarity) || "ST".equals(it.rarity)) && !tieredReskin(it);
    }

    /** One scored item. */
    static final class Score {
        final ItemInfo item;
        final int tier, base, bonus;
        final String bonusWhy;
        /** What it adds to the total: everything the first time, the bonus going up for an upgrade, else 0. */
        int points;
        /** 0 = first one of this item, 1 = upgrade (better modification or shiny), 2 = had it already, 3 = your class can't use it. */
        final int kind;
        final int oldBonus;
        final String oldWhy;
        /** The item's best modification so far (points and name) and whether a shiny copy has dropped, after this drop. */
        int mod;
        String modName = "";
        boolean shiny;
        /** What this upgrade added ("rare to legendary", "shiny", "legendary + shiny"). */
        String upWhy = "";

        /** Extra points for a white bag from certain dungeons (Oryx's Sanctuary +1...), and where. */
        int place;
        String placeWhy = "";
        /** Extra points for this particular item (Neo Reality Reactor +2...). */
        int extra;

        Score(ItemInfo item, int tier, int base, int bonus, String bonusWhy, int kind, int oldBonus, String oldWhy) {
            this.item = item;
            this.tier = tier;
            this.base = base;
            this.bonus = bonus;
            this.bonusWhy = bonusWhy;
            this.kind = kind;
            this.oldBonus = oldBonus;
            this.oldWhy = oldWhy;
            this.points = kind == 0 ? base + bonus : kind == 1 ? bonus - oldBonus : 0; // 2 and 3 score nothing
        }

        /** "Doom Bow: tier 3 (7) + legendary (3) = 10", "Doom Bow: upgrade, rare to legendary (+1)", "Doom Bow: already counted (0)" */
        String line() {
            if (kind == 1) return item.name + ": upgrade, " + (upWhy.isEmpty() ? (oldWhy.isEmpty() ? "no bonus" : oldWhy) + " to " + bonusWhy : upWhy) + " (+" + points + ")";
            if (kind == 2) return item.name + ": already counted (0)";
            if (kind == 3) return item.name + ": can't be equipped by " + (oldWhy.isEmpty() ? "your class" : "a " + oldWhy) + " (0)";
            return item.name + ": " + (tier == -1 ? "ST (" + base + ")" : tier > 0 ? "tier " + tier + " (" + base + ")" : "no tier (" + base + ")")
                    + (bonus > 0 ? " + " + bonusWhy + " (" + bonus + ")" : "")
                    + (place > 0 ? " + white from " + placeWhy + " (" + place + ")" : "")
                    + (extra > 0 ? " + item bonus (" + extra + ")" : "") + " = " + points;
        }
    }

    /** "Doom Bow Shiny" and "Doom Bow" are the same item for counting once (the shiny is just a bigger bonus). */
    // ---------------------------------------------------------------- reskins

    /** A reskin's original (by key) and whether that original is tiered gear (then the reskin doesn't count). */
    private static final class Reskin {
        final String base;
        final boolean tiered;

        Reskin(String base, boolean tiered) {
            this.base = base;
            this.tiered = tiered;
        }
    }

    private static volatile Map<String, Reskin> reskins;

    /** reskins.txt in the jar: "reskin|tier of the original|original", from RealmEye's "Reskin of" lines. */
    private static Map<String, Reskin> reskins() {
        Map<String, Reskin> r = reskins;
        if (r != null) return r;
        r = new HashMap<>();
        try (java.io.InputStream in = Contest.class.getResourceAsStream("reskins.txt")) {
            if (in != null) {
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                String l;
                while ((l = br.readLine()) != null) {
                    String[] f = l.split("\\|");
                    if (l.startsWith("#") || f.length < 3) continue;
                    String tier = f[1].trim();
                    r.put(itemKey(f[0]), new Reskin(itemKey(f[2]), !tier.equals("UT") && !tier.equals("ST")));
                }
            }
        } catch (Exception ignored) {
        }
        reskins = r;
        return r;
    }

    /** The reskin entry for this item (by its own name or the name the game shows), or null. */
    private static Reskin reskinOf(ItemInfo it) {
        if (it == null) return null;
        Map<String, Reskin> r = reskins();
        Reskin x = it.name == null ? null : r.get(itemKey(it.name));
        if (x == null) {
            try {
                ItemXml.Info info = ItemXml.of(it.id);
                if (info != null && info.display != null && !info.display.isEmpty()) x = r.get(itemKey(info.display));
            } catch (Throwable ignored) {
            }
        }
        return x;
    }

    /** What an item counts as for points: a reskin of a UT/ST item counts as that item (getting both scores once). */
    static String scoreKey(ItemInfo it) {
        Reskin x = reskinOf(it);
        if (x == null || x.tiered) return itemKey(it.name);
        // the original's key the same way its own drops are keyed (some items have code names, like "MysticST0")
        String own = displayKeys().get(x.base);
        return own != null ? own : x.base;
    }

    private static volatile Map<String, String> displayKeys;

    /** The name the game shows -> the key that item's drops are scored under. */
    private static Map<String, String> displayKeys() {
        Map<String, String> m = displayKeys;
        if (m != null && !m.isEmpty()) return m;
        m = new HashMap<>();
        try {
            for (int id : ItemXml.ids()) {
                ItemXml.Info info = ItemXml.of(id);
                ItemInfo ii = ItemInfo.of(id);
                if (ii == null || ii.name == null) continue;
                String shown = info != null && info.display != null && !info.display.isEmpty() ? info.display : ii.name;
                m.putIfAbsent(itemKey(shown), itemKey(ii.name));
            }
        } catch (Throwable ignored) {
        }
        if (!m.isEmpty()) displayKeys = m;
        return m;
    }

    /** A reskin of tiered gear (a T11 sword with a new look): the original wouldn't count, so it doesn't either. */
    static boolean tieredReskin(ItemInfo it) {
        Reskin x = reskinOf(it);
        return x != null && x.tiered;
    }

    static String itemKey(String name) {
        return name.replace('\u2019', '\'').replaceAll("(?i)\\(\\s*shiny\\s*\\)|\\bshiny\\b", " ").replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    /** One item's best so far this run: its best modification and whether a shiny copy has dropped. */
    static final class Have {
        int mod;
        String modName = "";
        boolean shiny;

        int bonus(int shinyPts) {
            return mod + (shiny ? shinyPts : 0);
        }

        String why() {
            return modName.isEmpty() ? (shiny ? "shiny" : "") : shiny ? modName + " + shiny" : modName;
        }

        Have copy() {
            Have h = new Have();
            h.mod = mod;
            h.modName = modName;
            h.shiny = shiny;
            return h;
        }
    }

    /** This run's items so far: item key -> its best modification and shiny. */
    private Map<String, Have> have;

    private synchronized Map<String, Have> have() {
        if (have != null) return have;
        if (tierFixes.isEmpty()) loadTierFixes(p);
        have = new HashMap<>();
        String lastLoaded = "";
        // lines written before the modification and the shiny were counted separately: work out what they missed
        Map<String, int[]> missed = new HashMap<>(); // key -> {points missed, points already fixed}
        Map<String, String[]> lastRow = new HashMap<>();
        // reskins scored before they counted as their original (2.30.19): what each item's rows gave, to take extra off once
        Map<String, List<int[]>> group = new HashMap<>();   // item key -> {points, modification, shiny} per scoring row, in order
        Map<String, java.util.Set<String>> groupKeys = new HashMap<>(); // item key -> the keys its rows were saved under
        Map<String, Integer> tieredOver = new HashMap<>(); // reskins of tiered gear -> points they got
        Map<String, Integer> reskinFixed = new HashMap<>(); // points already taken off per item
        Map<String, String[]> reskinRow = new HashMap<>();
        try {
            for (String l : Files.readAllLines(Paths.get(FILE), StandardCharsets.UTF_8)) {
                if (l.startsWith("==")) {
                    lastLoaded = "";
                    have.clear(); // a new run started
                    missed.clear();
                    lastRow.clear();
                    group.clear();
                    groupKeys.clear();
                    tieredOver.clear();
                    reskinFixed.clear();
                    reskinRow.clear();
                    continue;
                }
                String[] f = l.split("\\|", -1);
                if (f.length < 7) continue;
                String saved = f.length >= 9 && !f[8].isEmpty() ? f[8] : itemKey(f[2]);
                String why = f.length >= 10 ? f[9] : "";
                if (why.equals(MERGE_FIX)) continue; // points only: already taken off the total
                if (why.equals(RESKIN_FIX)) {
                    reskinFixed.merge(saved, Integer.parseInt(f[5]), Integer::sum);
                    continue;
                }
                // a reskin counts as its original: file its old rows under the original's key
                String key = saved;
                ItemInfo rowItem = null;
                int rowId = parseInt(f[1]);
                if (rowId > 0 && !why.equals(FIX) && !why.startsWith("fix: ")) {
                    try {
                        rowItem = ItemInfo.of(rowId);
                        if (rowItem != null && rowItem.name != null) {
                            boolean craft = saved.startsWith("forged:");
                            if (tieredReskin(rowItem)) {
                                tieredOver.merge((craft ? "forged:" : "") + itemKey(rowItem.name), parseInt(f[5]), Integer::sum);
                                reskinRow.put((craft ? "forged:" : "") + itemKey(rowItem.name), f);
                                continue; // doesn't count any more
                            }
                            key = (craft ? "forged:" : "") + scoreKey(rowItem);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (why.equals(FIX) || why.startsWith("fix: ")) { // a make-up line (any version)
                    missed.computeIfAbsent(key, k -> new int[2])[1] += Integer.parseInt(f[5]);
                    continue;
                }
                if (key.startsWith("forged:")) { // a Forge craft: its worth so far
                    group.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[]{parseInt(f[5]), 0, 0});
                    groupKeys.computeIfAbsent(key, k -> new java.util.LinkedHashSet<>()).add(saved);
                    reskinRow.put(key, f);
                    Have fh = have.computeIfAbsent(key, k -> new Have());
                    int w = f.length >= 12 ? Integer.parseInt(f[10]) : num("contest.points.soulFireCraft", 2); // older lines were all Soul Fire crafts
                    if (w > fh.mod) {
                        fh.mod = w;
                        fh.modName = why;
                    }
                    continue;
                }
                int bonus = Integer.parseInt(f[4]), pts = Integer.parseInt(f[5]);
                if (pts > 0) lastLoaded = f[2].replace(" (forged)", "") + " +" + pts;
                boolean shiny;
                int mod;
                String modName;
                if (f.length >= 12) {
                    mod = Integer.parseInt(f[10]);
                    shiny = "1".equals(f[11]);
                    modName = why.replace(" + shiny", "").replace("shiny", "").trim();
                } else { // old line: "legendary + shiny", "shiny", "rare" in the why
                    shiny = why.contains("shiny");
                    mod = shiny ? Math.max(0, bonus - shinyBonus()) : bonus; // the why said "legendary + shiny": both counted
                    modName = why.replace(" + shiny", "").replace("shiny", "").trim();
                }
                group.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[]{pts, mod, shiny ? 1 : 0});
                groupKeys.computeIfAbsent(key, k -> new java.util.LinkedHashSet<>()).add(saved);
                reskinRow.put(key, f);
                Have h = have.get(key);
                Have before = h == null ? null : h.copy();
                if (h == null) have.put(key, h = new Have());
                if (mod > h.mod) {
                    h.mod = mod;
                    h.modName = modName;
                }
                h.shiny |= shiny;
                if (before != null && f.length < 12) {
                    int should = h.bonus(shinyBonus()) - before.bonus(shinyBonus());
                    if (should > pts) missed.computeIfAbsent(key, k -> new int[2])[0] += should - pts;
                }
                // the first copy was scored with a dismantling tier that's since been fixed (Esben's Wedding Ring...)
                if (before == null && !forged(f)) {
                    Integer fixed = fixFor(parseInt(f[1]), f[2].replace(" (forged)", ""));
                    int was = parseInt(f[3]);
                    // a UT with no tier: 2.30.7 on scores it 7, before that 0 (its points without the rarity show which)
                    int wasPts = was == 0 && noTierUt(parseInt(f[1])) ? (pts - bonus >= utPoints(0) ? utPoints(0) : 0) : tierPoints(Math.max(was, 0));
                    if (fixed != null && was >= 0 && fixed > was && tierPoints(fixed) > wasPts)
                        missed.computeIfAbsent(key, k -> new int[2])[0] += tierPoints(fixed) - wasPts;
                    else if (fixed == null && was == 0 && noTierUt(parseInt(f[1])) && wasPts == 0)
                        missed.computeIfAbsent(key, k -> new int[2])[0] += utPoints(0); // from when those scored 0
                    else if (fixed != null && "-1".equals(f[3].trim())) { // an ST item scored at the flat 7 (9 in the Shatters)
                        boolean shatters = norm(f[6]).contains("shatters");
                        int stWas = shatters ? num("contest.points.stShatters", 9) : num("contest.points.st", 7);
                        int now = Math.max(tierPoints(fixed), shatters ? stWas : 0);
                        if (now > stWas) missed.computeIfAbsent(key, k -> new int[2])[0] += now - stWas;
                    }
                }
                lastRow.put(key, f);
            }
        } catch (Exception ignored) {
        }
        if (last.isEmpty()) last = lastLoaded;
        // add anything the old way of counting missed (once)
        List<String> fixes = new ArrayList<>();
        int add = 0;
        long now = System.currentTimeMillis() / 1000;
        for (Map.Entry<String, int[]> e : missed.entrySet()) {
            int owe = e.getValue()[0] - e.getValue()[1];
            String[] f = lastRow.get(e.getKey());
            if (owe <= 0 || f == null) continue;
            Have h = have.get(e.getKey());
            add += owe;
            fixes.add(now + "|" + f[1] + "|" + f[2] + "|" + f[3] + "|" + h.bonus(shinyBonus()) + "|" + owe + "|" + f[6] + "|" + f[7] + "|" + e.getKey() + "|" + FIX
                    + "|" + h.mod + "|" + (h.shiny ? 1 : 0));
        }
        if (add > 0) {
            p.setProperty("contest.total", String.valueOf(total() + add));
            write(fixes);
            fixedPoints = add;
        }
        // reskins: an item and its reskin saved separately only count once; reskins of tiered gear don't count (once)
        List<String> takeRows = new ArrayList<>();
        int take = 0;
        Map<String, Integer> over = new HashMap<>(tieredOver);
        for (Map.Entry<String, List<int[]>> e : group.entrySet()) {
            java.util.Set<String> keys = groupKeys.get(e.getKey());
            if (keys == null || keys.size() < 2) continue; // never split between an item and its reskin
            List<int[]> rows = e.getValue();
            int sum = 0;
            for (int[] r : rows) sum += r[0];
            int ideal;
            if (e.getKey().startsWith("forged:")) {
                ideal = 0;
                for (int[] r : rows) ideal = Math.max(ideal, r[0]); // the craft counts once
            } else {
                Have first = new Have(), all = new Have();
                first.mod = rows.get(0)[1];
                first.shiny = rows.get(0)[2] == 1;
                for (int[] r : rows) {
                    all.mod = Math.max(all.mod, r[1]);
                    all.shiny |= r[2] == 1;
                }
                ideal = rows.get(0)[0] + Math.max(0, all.bonus(shinyBonus()) - first.bonus(shinyBonus()));
            }
            if (sum > ideal) over.merge(e.getKey(), sum - ideal, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : over.entrySet()) {
            int owe = e.getValue() + reskinFixed.getOrDefault(e.getKey(), 0); // fixes already written are negative
            String[] f = reskinRow.get(e.getKey());
            if (owe <= 0 || f == null) continue;
            take += owe;
            takeRows.add(now + "|" + f[1] + "|" + f[2] + "|" + f[3] + "|0|" + (-owe) + "|" + f[6] + "|" + f[7] + "|" + e.getKey() + "|" + RESKIN_FIX + "|0|0");
        }
        if (take > 0) {
            p.setProperty("contest.total", String.valueOf(Math.max(0, total() - take)));
            write(takeRows);
            reskinPoints = take;
        }
        return have;
    }

    private static boolean forged(String[] f) {
        return f.length >= 9 && f[8].startsWith("forged:");
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** Loads this run (making up any points the old counting missed) and returns those points. */
    synchronized int loadFix() {
        have();
        int n = fixedPoints;
        fixedPoints = 0;
        return n;
    }

    /** Marks a line that takes off points a reskin got before it counted as its original (or for tiered gear). */
    static final String RESKIN_FIX = "fix: reskins count as their original";
    /** Points taken off when the run was loaded for reskins (0 if none), so the app can say so. */
    volatile int reskinPoints;

    synchronized int loadReskinFix() {
        have();
        int n = reskinPoints;
        reskinPoints = 0;
        return n;
    }

    /** Marks a line that makes up points the old counting missed. */
    static final String FIX = "fix: points the old counting missed";
    /** Points made up when the run was loaded (0 if none), so the app can say so. */
    volatile int fixedPoints;

    /**
     * Scores the UT/ST items in a bag: each item counts once per run, with its modification and shiny bonuses added
     * together. A shiny adds +4 on top of its modification; a better modification later adds the difference, and the first shiny copy adds +4.
     */
    synchronized List<Score> score(BagDrop drop) {
        return score(drop, null, -1);
    }

    synchronized List<Score> score(BagDrop drop, String boss) {
        return score(drop, boss, -1);
    }

    /** True for Umi (Kitsune Umi, Umi Goddess of Revelry...), whose white bags in Moonlight Village are worth more. */
    static boolean isUmi(String boss) {
        return boss != null && boss.toLowerCase(Locale.ROOT).matches(".*\\bumi\\b.*");
    }

    /** {@code boss}: the boss that died just before the bag appeared (or null if none). */
    /** {@code classType}: the class you're playing; items it can't equip score nothing (-1: don't check). */
    synchronized List<Score> score(BagDrop drop, String boss, int classType) {
        List<Score> out = new ArrayList<>();
        Map<String, Have> seen = new HashMap<>(); // copies in the same bag count against each other too
        for (Map.Entry<String, Have> e : have().entrySet()) seen.put(e.getKey(), e.getValue().copy());
        boolean white = drop.type == BagType.WHITE || drop.type == BagType.BOOSTED_WHITE;
        int placeBonus = 0;
        String placeName = "";
        if (white) {
            String[] pb = placeBonus(drop.mapName);
            if (pb != null) {
                placeName = pb[0];
                placeBonus = Integer.parseInt(pb[1]);
                // Moonlight Village: a white from Umi is worth more than one from the other bosses
                if (norm(pb[0]).contains("moonlight") && isUmi(boss)) {
                    placeName = "Umi";
                    placeBonus = num("contest.points.whiteFromUmi", 2);
                }
            }
            white = placeBonus > 0;
        }
        for (ItemInfo it : drop.items()) {
            if (!counts(it)) continue;
            if (classType > 0 && !canEquip(classType, it.id)) {
                out.add(new Score(it, 0, 0, 0, "", 3, 0, AboutMe.objectName(classType, "")));
                continue;
            }
            int t = tier(it.id), base = utPoints(t);
            // ST items: a flat 7 (9 in the Shatters, which already counts its white bag bonus)
            boolean st = "ST".equals(it.rarity), stShatters = st && norm(drop.mapName).contains("shatters");
            if (st) {
                t = -1;
                base = stPoints(it.id, it.name, stShatters);
            }
            boolean isShiny = LootBook.isShiny(it.id);
            int mod = bonus(it.enchant), shinyPts = shinyBonus(); // the modification counts, and a shiny adds +4 on top
            String modName = mod > 0 ? it.enchant.label.toLowerCase(Locale.ROOT) : "";
            String key = scoreKey(it);
            Have old = seen.get(key);
            // the best modification and the shiny count separately: a shiny copy adds +4 once, a better modification adds the difference
            Have now = old == null ? new Have() : old.copy();
            if (mod > now.mod) {
                now.mod = mod;
                now.modName = modName;
            }
            now.shiny |= isShiny;
            int bonus = now.bonus(shinyPts), oldBonus = old == null ? 0 : old.bonus(shinyPts);
            String why = old == null ? (mod + (isShiny ? shinyPts : 0) == 0 ? "" : isShiny ? (modName.isEmpty() ? "shiny" : modName + " + shiny") : modName) : now.why();
            if (old == null) bonus = mod + (isShiny ? shinyPts : 0);
            int kind = old == null ? 0 : bonus > oldBonus ? 1 : 2;
            String oldWhy = old == null ? "" : old.why();
            Score sc = new Score(it, t, base, bonus, why, kind, oldBonus, oldWhy);
            sc.mod = now.mod;
            sc.modName = now.modName;
            sc.shiny = now.shiny;
            if (kind == 1) {
                boolean betterMod = old.mod < now.mod, newShiny = !old.shiny && now.shiny;
                String m = betterMod ? (old.modName.isEmpty() ? "no modification" : old.modName) + " to " + now.modName : "";
                sc.upWhy = newShiny ? (m.isEmpty() ? "first shiny" : m + " + first shiny") : m;
            }
            if (kind == 0 && white && !stShatters) { // a new item from a white bag in one of the bonus dungeons
                sc.place = placeBonus;
                sc.placeWhy = placeName;
                sc.points += placeBonus;
            }
            int extra = itemBonus(it.name);
            if (kind == 0 && extra > 0) { // some items are worth extra (first copy only)
                sc.extra = extra;
                sc.points += extra;
            }
            out.add(sc);
            if (kind != 2) seen.put(key, now);
        }
        return out;
    }

    // ---------------------------------------------------------------- the contest character and the running total

    boolean on() {
        return "true".equals(p.getProperty("contest.on"));
    }

    /** The character the contest is for, or -1 when none is picked (or it died). */
    int charId() {
        return num("contest.charId", -1);
    }

    String account() {
        return p.getProperty("contest.account", "");
    }

    String who() {
        String name = p.getProperty("contest.name", ""), cls = p.getProperty("contest.class", "");
        return (cls.isEmpty() ? "character" : cls) + (name.isEmpty() ? "" : " " + name) + " #" + charId();
    }

    int total() {
        return num("contest.total", 0);
    }

    int items() {
        return num("contest.items", 0);
    }

    void pick(String account, int charId, String name, String cls) {
        p.setProperty("contest.account", account == null ? "" : account);
        p.setProperty("contest.charId", String.valueOf(charId));
        p.setProperty("contest.name", name == null ? "" : name);
        p.setProperty("contest.class", cls == null ? "" : cls);
        p.setProperty("contest.started", String.valueOf(System.currentTimeMillis() / 1000));
        p.setProperty("contest.total", "0");
        p.setProperty("contest.items", "0");
        p.remove("contest.diedTo");
        have = new HashMap<>();
        last = "";
        write(Collections.singletonList("== " + who() + " started " + new java.util.Date() + " =="));
    }

    static final String MERGE_FIX = "fix: run put back after a reset (drops it already had)";

    private static final java.util.regex.Pattern HEADER = java.util.regex.Pattern.compile("^==\\s*(.+?) started .*==\\s*$");
    private static final java.util.regex.Pattern WHO = java.util.regex.Pattern.compile("^(.*?)(?: (\\S+))? #(-?\\d+)$");

    private static String headerWho(String line) {
        java.util.regex.Matcher m = HEADER.matcher(line);
        return m.matches() ? m.group(1) : "";
    }

    private static String whoChar(String who) {
        java.util.regex.Matcher m = WHO.matcher(who);
        return m.matches() ? m.group(3) : "?";
    }

    /**
     * Rows of runs joined together: the extra points items got when a later run counted them again (only what's new
     * should count: a better rarity, a first shiny), less what earlier joins already took off. Key -> {points to take
     * off now (negative: give back), a row of that item}.
     */
    private Map<String, Object[]> joinFixes(List<String> rows) {
        Map<String, int[]> had = new HashMap<>();
        Map<String, Integer> over = new HashMap<>(), taken = new HashMap<>();
        Map<String, String[]> sample = new HashMap<>();
        for (String l : rows) {
            String[] f = l.split("\\|", -1);
            if (f.length < 7) continue;
            int p0 = parseInt(f[5]);
            String key = f.length >= 9 && !f[8].isEmpty() ? f[8] : itemKey(f[2]);
            String why = f.length >= 10 ? f[9] : "";
            if (why.equals(MERGE_FIX)) {
                taken.merge(key, -p0, Integer::sum);
                sample.putIfAbsent(key, f);
                continue;
            }
            if (why.startsWith("fix: ")) continue;
            sample.putIfAbsent(key, f);
            int mod = f.length >= 12 ? parseInt(f[10]) : parseInt(f[4]);
            boolean shiny = f.length >= 12 && "1".equals(f[11]);
            int[] h = had.get(key);
            if (h == null) {
                had.put(key, new int[]{mod, shiny ? 1 : 0});
                continue;
            }
            // seen before: worth only what it adds
            int now = Math.max(h[0], mod) + ((h[1] == 1 || shiny) ? shinyBonus() : 0);
            int was = h[0] + (h[1] == 1 ? shinyBonus() : 0);
            int due = key.startsWith("forged:") ? 0 : Math.max(0, now - was);
            if (p0 > due) over.merge(key, p0 - due, Integer::sum);
            h[0] = Math.max(h[0], mod);
            if (shiny) h[1] = 1;
        }
        Map<String, Object[]> out = new java.util.LinkedHashMap<>();
        java.util.Set<String> keys = new java.util.LinkedHashSet<>(over.keySet());
        keys.addAll(taken.keySet());
        for (String k : keys) {
            int d = over.getOrDefault(k, 0) - taken.getOrDefault(k, 0);
            if (d != 0) out.put(k, new Object[]{d, sample.get(k)});
        }
        return out;
    }

    private static String fixRow(long now, String key, String[] f, int pts) {
        return now + "|" + (f == null ? "0" : f[1]) + "|" + (f == null ? key : f[2]) + "|" + (f == null ? "0" : f[3]) + "|0|" + pts + "|"
                + (f == null ? "" : f[6]) + "||" + key + "|" + MERGE_FIX + "|0|0";
    }

    /**
     * The run to go back to after "Use the character I'm on now" by mistake (maybe pressed more than once): {who, points}.
     * Runs of the same character are joined into one; a new run on another character can only be undone while it has
     * nothing scored. Null if there's nothing to go back to.
     */
    synchronized String[] previousRun() {
        Object[] plan = mergePlan();
        return plan == null ? null : new String[]{(String) plan[0], String.valueOf(plan[1])};
    }

    /** {who, points after, the file's new lines}, or null. */
    private Object[] mergePlan() {
        try {
            List<String> lines = Files.readAllLines(Paths.get(FILE), StandardCharsets.UTF_8);
            List<Integer> heads = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith("==")) heads.add(i);
            if (heads.size() < 2) return null;
            int last = heads.get(heads.size() - 1);
            boolean lastEmpty = true;
            for (int i = last + 1; i < lines.size(); i++) if (lines.get(i).split("\\|", -1).length >= 7) lastEmpty = false;
            String lastChar = whoChar(headerWho(lines.get(last)));
            // the first run of this character's unbroken line of runs (empty runs of other characters in between don't count)
            int first = heads.size() - 1;
            for (int h = heads.size() - 2; h >= 0; h--) {
                int at = heads.get(h), next = heads.get(h + 1);
                boolean empty = true;
                for (int i = at + 1; i < next; i++) if (lines.get(i).split("\\|", -1).length >= 7) empty = false;
                if (whoChar(headerWho(lines.get(at))).equals(lastChar)) first = h;
                else if (!empty) break;
            }
            int keepHead;
            if (first < heads.size() - 1) keepHead = heads.get(first);
            else if (lastEmpty) keepHead = heads.get(heads.size() - 2); // a different character picked by mistake, nothing scored yet
            else return null;
            List<String> out = new ArrayList<>(lines.subList(0, keepHead + 1));
            List<String> block = new ArrayList<>();
            for (int i = keepHead + 1; i < lines.size(); i++) if (!lines.get(i).startsWith("==")) block.add(lines.get(i));
            long now = System.currentTimeMillis() / 1000;
            for (Map.Entry<String, Object[]> e : joinFixes(block).entrySet())
                block.add(fixRow(now, e.getKey(), (String[]) e.getValue()[1], -(Integer) e.getValue()[0]));
            out.addAll(block);
            int pts = 0;
            for (String l : block) {
                String[] f = l.split("\\|", -1);
                if (f.length >= 7) pts += parseInt(f[5]);
            }
            String who = headerWho(lines.get(keepHead));
            return new Object[]{who.isEmpty() ? "your last character" : who, pts, out};
        } catch (Exception e) {
            return null;
        }
    }

    /** Undoes "Use the character I'm on now": back to the run before it, with its points. False if it can't. */
    synchronized boolean undoNewRun() {
        Object[] plan = mergePlan();
        if (plan == null) return false;
        try {
            @SuppressWarnings("unchecked") List<String> out = (List<String>) plan[2];
            Files.write(Paths.get(FILE), out, StandardCharsets.UTF_8);
            java.util.regex.Matcher m = WHO.matcher((String) plan[0]);
            if (m.matches()) {
                p.setProperty("contest.class", m.group(1).equals("character") ? "" : m.group(1));
                p.setProperty("contest.name", m.group(2) == null ? "" : m.group(2));
                p.setProperty("contest.charId", m.group(3));
            }
            p.setProperty("contest.total", String.valueOf(plan[1]));
            p.remove("contest.diedTo");
            have = null;
            counted = null;
            int items = 0;
            for (String k : have().keySet()) if (!k.startsWith("forged:")) items++;
            p.setProperty("contest.items", String.valueOf(items));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * At start-up: a run that was put back (Undo) gets its numbers checked again, in case the button was pressed more
     * than once in 2.30.28 and took points off twice. Returns the change (0 if none).
     */
    synchronized int repairJoinedRun() {
        try {
            List<String> lines = Files.readAllLines(Paths.get(FILE), StandardCharsets.UTF_8);
            int last = -1;
            for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith("==")) last = i;
            List<String> run = new ArrayList<>(lines.subList(last + 1, lines.size()));
            boolean joined = false;
            for (String l : run) if (l.contains("|" + MERGE_FIX + "|")) joined = true;
            if (!joined) return 0;
            int before = total();
            int change = 0;
            List<String> add = new ArrayList<>();
            long now = System.currentTimeMillis() / 1000;
            for (Map.Entry<String, Object[]> e : joinFixes(run).entrySet()) {
                int d = (Integer) e.getValue()[0];
                change -= d;
                add.add(fixRow(now, e.getKey(), (String[]) e.getValue()[1], -d));
            }
            if (!add.isEmpty()) write(add);
            int pts = 0;
            for (String l : run) {
                String[] f = l.split("\\|", -1);
                if (f.length >= 7) pts += parseInt(f[5]);
            }
            // the total is what the run's lines add up to
            p.setProperty("contest.total", String.valueOf(pts + change));
            have = null;
            return total() - before;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Bags already counted (map | bag's object id | its items), so the same bag isn't counted twice if you
     * reconnect into the same dungeon or restart Loot Puppy mid-run. Bag ids are only reused across different maps.
     */
    private java.util.Set<String> counted;

    private static String bagKey(BagDrop drop) {
        StringBuilder sb = new StringBuilder(drop.mapName.replace('|', '/')).append('#').append(drop.objectId).append('#');
        for (ItemInfo it : drop.items()) sb.append(it.id).append(',');
        return sb.toString();
    }

    /** True if this bag has been counted already (in the last 3 hours). */
    synchronized boolean alreadyCounted(BagDrop drop) {
        if (counted == null) {
            counted = new java.util.HashSet<>();
            long since = System.currentTimeMillis() / 1000 - 3 * 3600;
            try {
                for (String l : Files.readAllLines(Paths.get(FILE), StandardCharsets.UTF_8)) {
                    String[] f = l.split("\\|", -1);
                    if (f.length >= 8 && Long.parseLong(f[0]) >= since) counted.add(f[7]);
                }
            } catch (Exception ignored) {
            }
        }
        return counted.contains(bagKey(drop));
    }

    /** Adds a bag's scores to the total, and writes them down. */
    synchronized void add(List<Score> scores, BagDrop drop) {
        String map = drop.mapName;
        String key = bagKey(drop);
        alreadyCounted(drop);
        counted.add(key);
        int sum = 0, fresh = 0;
        List<String> lines = new ArrayList<>();
        long now = System.currentTimeMillis() / 1000;
        for (Score s : scores) {
            if (s.kind == 3) continue;
            sum += s.points;
            if (s.kind == 0) fresh++;
            String ik = scoreKey(s.item);
            Have h = have().get(ik);
            if (h == null) have.put(ik, h = new Have());
            if (s.mod > h.mod) {
                h.mod = s.mod;
                h.modName = s.modName;
            }
            h.shiny |= s.shiny;
            lines.add(now + "|" + s.item.id + "|" + s.item.name.replace('|', '/') + "|" + s.tier + "|" + s.bonus + "|" + s.points
                    + "|" + map.replace('|', '/') + "|" + key + "|" + ik.replace('|', '/') + "|" + s.bonusWhy + "|" + h.mod + "|" + (h.shiny ? 1 : 0));
        }
        p.setProperty("contest.total", String.valueOf(total() + sum));
        p.setProperty("contest.items", String.valueOf(items() + fresh));
        write(lines);
        for (Score s : scores) if (s.kind != 3 && s.points > 0) last = s.item.name + " +" + s.points;
    }

    /** The last item that scored this run ("Vigor +22"), for the mini UPE window. */
    volatile String last = "";

    /** The character died: the contest run is over. */
    void died(String killedBy) {
        p.setProperty("contest.diedTo", killedBy == null ? "" : killedBy);
        p.setProperty("contest.charId", "-1");
    }

    private void write(List<String> lines) {
        try {
            Files.write(Paths.get(FILE), lines, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    /** This run's items (since the last "started" line), each once: {name, points in all, map}, best first. */
    List<String[]> best() {
        Map<String, String[]> by = new java.util.LinkedHashMap<>();
        try {
            for (String l : Files.readAllLines(Paths.get(FILE), StandardCharsets.UTF_8)) {
                if (l.startsWith("==")) {
                    by.clear();
                    continue;
                }
                String[] f = l.split("\\|", -1);
                if (f.length < 7) continue;
                String key = f.length >= 9 && !f[8].isEmpty() ? f[8] : itemKey(f[2]);
                String[] b = by.get(key);
                int pts = Integer.parseInt(f[5]);
                if (b == null) by.put(key, new String[]{f[2], String.valueOf(pts), f[6]});
                else {
                    b[1] = String.valueOf(Integer.parseInt(b[1]) + pts);
                    if (pts > 0) b[0] = f[2]; // the upgraded copy's name (the shiny, say)
                }
            }
        } catch (Exception ignored) {
        }
        List<String[]> out = new ArrayList<>(by.values());
        out.sort((x, y) -> Integer.parseInt(y[1]) - Integer.parseInt(x[1]));
        return out;
    }

    /** The "Contest total" / "RIP" post: total, items and best drops. */
    String summaryText(boolean died) {
        List<String[]> best = best();
        StringBuilder sb = new StringBuilder();
        String who = (p.getProperty("contest.class", "") + " " + p.getProperty("contest.name", "")).trim();
        String to = p.getProperty("contest.diedTo", "");
        sb.append(died ? "💀 **RIP " + who + "**" + (to.isEmpty() ? "" : " (killed by " + to + ")") + "\n"
                : "🏆 **" + who + "'s UPE so far**\n");
        sb.append("**Total: ").append(total()).append(" points** from ").append(items()).append(items() == 1 ? " item" : " items").append("\n");
        if (!best.isEmpty()) {
            sb.append("\n**Best drops**\n");
            for (int i = 0; i < Math.min(10, best.size()); i++)
                sb.append("• ").append(best.get(i)[0]).append(": ").append(best.get(i)[1]).append(" pts").append(best.get(i)[2].isEmpty() ? "" : " (" + best.get(i)[2] + ")").append("\n");
        }
        return sb.toString();
    }

    String summaryJson(boolean died) {
        return summaryJson(died, false);
    }

    /** The total post; with {@code picture}, it shows the attached game.jpg (the game when the character died). */
    String summaryJson(boolean died, boolean picture) {
        return "{\"username\":" + DiscordWebhook.str(DiscordWebhook.USERNAME) + ",\"embeds\":[{\"title\":" + DiscordWebhook.str(died ? "Contest run over" : "Contest total")
                + ",\"description\":" + DiscordWebhook.str(summaryText(died)) + ",\"color\":" + (died ? 0x8A7F99 : 0xE8B12A)
                + (picture ? ",\"image\":{\"url\":\"attachment://game.jpg\"}" : "") + "}]}";
    }

    /** "+9 points  ·  Total: 245  ·  Wizard Sophie #7" for a bag post. */
    String bagNote(int sum) {
        return "+" + sum + (sum == 1 ? " point" : " points") + "  ·  Total: " + total() + "  ·  " + who();
    }

    /** The forge blueprints in a bag, by the names the game shows ("Divinity Blueprint"). Logged for the UPE, 0 points. */
    static List<String> blueprints(BagDrop drop) {
        List<String> out = new ArrayList<>();
        for (ItemInfo it : drop.items()) {
            String shown = it.name;
            try {
                ItemXml.Info x = ItemXml.of(it.id);
                if (x != null && x.display != null && !x.display.isEmpty()) shown = x.display;
            } catch (Throwable ignored) {
            }
            if (shown != null && (shown.toLowerCase(Locale.ROOT).contains("blueprint") || it.name.startsWith("Blueprint_"))) out.add(shown);
        }
        return out;
    }

    /** The lines a bag post gets for its blueprints. */
    static String blueprintLines(List<String> bps) {
        StringBuilder sb = new StringBuilder();
        for (String b : bps) sb.append(sb.length() > 0 ? "\n" : "").append("\uD83D\uDCDC **").append(b).append("**: blueprint, logged (0 points)");
        return sb.toString();
    }

    /** Each scored item's line, one per line, for a bag post. */
    static String lines(List<Score> scores) {
        StringBuilder why = new StringBuilder();
        for (Score s : scores) why.append(why.length() > 0 ? "\n" : "").append(s.line());
        return why.toString();
    }
}
