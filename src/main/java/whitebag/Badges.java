package whitebag;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Pup badges: achievements worked out from the logs next to the app (bags, runs, kills, fame, play time, the
 * collection, trades and the Shatters puzzle). Unlocked badges are kept in badges.txt; things the logs don't record
 * (solving the puzzle) are noted in badge-events.txt.
 */
final class Badges {
    private Badges() {
    }

    enum Tier {
        BRONZE(0xCD8B5A), SILVER(0x9FA8B8), GOLD(0xE8B630), PINK(0xFF6FA8),
        /** The hardest badges: drawn with a rainbow medal. */
        PRISMATIC(0x9B5CFF);
        final Color color;

        Tier(int rgb) {
            this.color = new Color(rgb);
        }
    }

    /** What the badges are counted from, for one account. */
    static final class Stats {
        long whites, legendaries, divines, runs, mostRunsOneDungeon, brokeStreak, luckyRuns;
        long bossKills, topDamage, soloKills, eventKills;
        long fame, playSeconds, collection, completedDungeons, trades;
        long puzzleSolved, puzzlePerfect, practicePerfect, practiceSolved;
        long pets, bags, shinies, busiestDay, nightRuns, dungeonsVisited, playDays, mostWhitesOneRun;
        long mostWhitesOneDay, topDamageTotal;
        // more badges (2.8)
        long reds, oranges, golds, eggs, blues, cyans, boostedWhites, boosted, colours, mostColoursOneDay;
        long rares, items, mostLegendariesOneBag, mostDivinesOneDay, whiteDungeons, whiteRunsInARow, firstRunWhites, quickWhites;
        long longestRunMinutes, earlyRuns, dungeons10, dungeons25, dayStreak, longestSessionHours;
        long minibosses, differentBosses, quickKills, biggestHit, topBigGroup, mostEventsOneDay, mostFameOneDay, classes;
        long tradePartners, vaultItems, seasonalItems, badges, wardrobe, titles, darkMode, spookyWhites, festiveWhites, newYear;
        long valentineWhites, springWhites, wardrobeAll;
        String mostRunDungeon = "";
        /** Runs and bags in each dungeon, by {@link EasyBadges#key} name. */
        final Map<String, Long> runsBy = new HashMap<>(), bagsBy = new HashMap<>();

        long runsIn(String key) {
            return runsBy.getOrDefault(key, 0L);
        }

        long bagsIn(String key) {
            return bagsBy.getOrDefault(key, 0L);
        }
    }

    interface Counter {
        long of(Stats s);
    }

    static final class Badge {
        final String id, name, desc, glyph;
        final Tier tier;
        final long need;
        final Counter count;

        Badge(String id, String name, String desc, String glyph, Tier tier, long need, Counter count) {
            this.id = id;
            this.name = name;
            this.desc = desc;
            this.glyph = glyph;
            this.tier = tier;
            this.need = need;
            this.count = count;
        }
    }

    static final List<Badge> ALL = Collections.unmodifiableList(withEasy(Arrays.asList(
            new Badge("white1", "First White", "Loot your first white bag", "W", Tier.BRONZE, 1, s -> s.whites),
            new Badge("white10", "White Collar", "Loot 10 white bags", "W", Tier.SILVER, 10, s -> s.whites),
            new Badge("white50", "Snow Pile", "Loot 50 white bags", "W", Tier.GOLD, 50, s -> s.whites),
            new Badge("white100", "Blizzard", "Loot 100 white bags", "W", Tier.PINK, 100, s -> s.whites),
            new Badge("legendary", "Ooh, Shiny", "Loot a Legendary item", "L", Tier.SILVER, 1, s -> s.legendaries),
            new Badge("divine", "Divine Intervention", "Loot a Divine item", "D", Tier.PINK, 1, s -> s.divines),
            new Badge("lucky", "Lucky Pup", "Get two white bags in one run", "2", Tier.GOLD, 1, s -> s.luckyRuns),
            new Badge("runs10", "Getting Started", "Finish 10 dungeon runs", "R", Tier.BRONZE, 10, s -> s.runs),
            new Badge("runs100", "Regular", "Finish 100 dungeon runs", "R", Tier.SILVER, 100, s -> s.runs),
            new Badge("runs500", "Dungeon Dweller", "Finish 500 dungeon runs", "R", Tier.GOLD, 500, s -> s.runs),
            new Badge("devoted", "Devoted", "Run the same dungeon 100 times", "♥", Tier.GOLD, 100, s -> s.mostRunsOneDungeon),
            new Badge("boss50", "Boss Hunter", "Help kill 50 bosses", "B", Tier.BRONZE, 50, s -> s.bossKills),
            new Badge("boss500", "Boss Slayer", "Help kill 500 bosses", "B", Tier.GOLD, 500, s -> s.bossKills),
            new Badge("topdog", "Top Dog", "Do the most damage in a group 25 times", "1", Tier.SILVER, 25, s -> s.topDamage),
            new Badge("solo", "Solo Act", "Kill a boss doing all the damage", "!", Tier.GOLD, 1, s -> s.soloKills),
            new Badge("events25", "Event Chaser", "Help kill 25 realm events", "E", Tier.SILVER, 25, s -> s.eventKills),
            new Badge("fame10k", "Famous", "Gain 10,000 fame", "F", Tier.SILVER, 10_000, s -> s.fame),
            new Badge("fame100k", "Legend", "Gain 100,000 fame", "F", Tier.PINK, 100_000, s -> s.fame),
            new Badge("time10", "Dedicated", "Play for 10 hours", "T", Tier.BRONZE, 10, s -> s.playSeconds / 3600),
            new Badge("time100", "Realm Regular", "Play for 100 hours", "T", Tier.GOLD, 100, s -> s.playSeconds / 3600),
            new Badge("collect25", "Collector", "Find 25 items for your collection", "C", Tier.BRONZE, 25, s -> s.collection),
            new Badge("collect100", "Curator", "Find 100 items for your collection", "C", Tier.GOLD, 100, s -> s.collection),
            new Badge("complete", "Completionist", "Find every item from one dungeon", "★", Tier.PINK, 1, s -> s.completedDungeons),
            new Badge("trades10", "Wheeler Dealer", "Finish 10 trades", "$", Tier.BRONZE, 10, s -> s.trades),
            new Badge("puzzle", "Puzzle Solved", "Solve the Shatters puzzle in game", "P", Tier.SILVER, 1, s -> s.puzzleSolved),
            new Badge("bigbrain", "Big Brain", "Solve the Shatters puzzle in the fewest shots", "P", Tier.PINK, 1, s -> s.puzzlePerfect),
            new Badge("practice", "Puzzle Pup", "Solve a practice puzzle perfectly", "P", Tier.BRONZE, 1, s -> s.practicePerfect),
            // ---- puppy badges ("paw" and "bone" are drawn as little pictures)
            new Badge("goodpup", "Good Pup", "Solve 10 practice puzzles", "paw", Tier.BRONZE, 10, s -> s.practiceSolved),
            new Badge("verygoodpup", "Very Good Pup", "Solve 50 practice puzzles", "paw", Tier.SILVER, 50, s -> s.practiceSolved),
            new Badge("bestinshow", "Best in Show", "Solve 25 practice puzzles perfectly", "paw", Tier.GOLD, 25, s -> s.practicePerfect),
            new Badge("bellyrubs", "Belly Rubs", "Pet the pup 25 times", "♥", Tier.BRONZE, 25, s -> s.pets),
            new Badge("spoiled", "Spoiled Rotten", "Pet the pup 500 times", "♥", Tier.PINK, 500, s -> s.pets),
            new Badge("fetch", "Fetch!", "Loot 100 bags of any colour", "bone", Tier.BRONZE, 100, s -> s.bags),
            new Badge("fetchchamp", "Fetch Champion", "Loot 1,000 bags of any colour", "bone", Tier.GOLD, 1000, s -> s.bags),
            new Badge("sniffer", "Sniffer Dog", "Sniff out a shiny item", "★", Tier.SILVER, 1, s -> s.shinies),
            new Badge("bonecollector", "Bone Collector", "Find 10 shiny items", "bone", Tier.PINK, 10, s -> s.shinies),
            new Badge("zoomies", "Zoomies", "Finish 10 dungeon runs in one day", "Z", Tier.SILVER, 10, s -> s.busiestDay),
            new Badge("midnight", "Midnight Walkies", "Start a dungeon run between midnight and 4 am", "M", Tier.BRONZE, 1, s -> s.nightRuns),
            new Badge("walkies", "Walkies", "Run 10 different dungeons", "paw", Tier.SILVER, 10, s -> s.dungeonsVisited),
            new Badge("longwalkies", "Long Walkies", "Run 30 different dungeons", "paw", Tier.GOLD, 30, s -> s.dungeonsVisited),
            new Badge("packleader", "Pack Leader", "Do the most damage in a group 100 times", "1", Tier.PINK, 100, s -> s.topDamage),
            new Badge("loyal", "Loyal Companion", "Play on 30 different days", "♥", Tier.GOLD, 30, s -> s.playDays),
            // ---- the hard ones
            new Badge("white250", "Avalanche", "Loot 250 white bags", "W", Tier.PINK, 250, s -> s.whites),
            new Badge("white500", "Ice Age", "Loot 500 white bags", "W", Tier.PINK, 500, s -> s.whites),
            new Badge("jackpot", "Jackpot", "Get three white bags in one run", "3", Tier.PINK, 3, s -> s.mostWhitesOneRun),
            new Badge("blessed", "Blessed", "Loot 10 Divine items", "D", Tier.PINK, 10, s -> s.divines),
            new Badge("dungeonmaster", "Dungeon Master", "Finish 1,000 dungeon runs", "R", Tier.PINK, 1000, s -> s.runs),
            new Badge("obsessed", "Obsessed", "Run the same dungeon 500 times", "♥", Tier.PINK, 500, s -> s.mostRunsOneDungeon),
            new Badge("traveller", "World Traveller", "Run 60 different dungeons", "paw", Tier.PINK, 60, s -> s.dungeonsVisited),
            new Badge("boss2000", "Boss Exterminator", "Help kill 2,000 bosses", "B", Tier.PINK, 2000, s -> s.bossKills),
            new Badge("onepuparmy", "One Pup Army", "Kill 10 bosses doing all the damage", "!", Tier.PINK, 10, s -> s.soloKills),
            new Badge("events250", "Storm Chaser", "Help kill 250 realm events", "E", Tier.PINK, 250, s -> s.eventKills),
            new Badge("mythic", "Mythic", "Gain 1,000,000 fame", "F", Tier.PINK, 1_000_000, s -> s.fame),
            new Badge("time500", "Realm Resident", "Play for 500 hours", "T", Tier.PINK, 500, s -> s.playSeconds / 3600),
            new Badge("bestfriend", "Best Friends Forever", "Play on 100 different days", "♥", Tier.PINK, 100, s -> s.playDays),
            new Badge("museum", "Museum", "Find 300 items for your collection", "C", Tier.PINK, 300, s -> s.collection),
            new Badge("completionist5", "Master Completionist", "Find every item from 5 dungeons", "★", Tier.PINK, 5, s -> s.completedDungeons),
            new Badge("shinyhunter", "Shiny Hunter", "Find 25 shiny items", "★", Tier.PINK, 25, s -> s.shinies),
            new Badge("puzzlemaster", "Puzzle Master", "Solve the Shatters puzzle in the fewest shots 10 times", "P", Tier.PINK, 10, s -> s.puzzlePerfect),
            new Badge("puzzleaddict", "Puzzle Addict", "Solve 500 practice puzzles", "paw", Tier.PINK, 500, s -> s.practiceSolved),
            new Badge("turbozoomies", "Turbo Zoomies", "Finish 25 dungeon runs in one day", "Z", Tier.PINK, 25, s -> s.busiestDay),
            new Badge("mostloved", "Most Loved Pup", "Pet the pup 2,000 times", "♥", Tier.PINK, 2000, s -> s.pets),
            // ---- prismatic: the hardest of all
            // ---- bags of every colour
            new Badge("red1", "Seeing Red", "Loot a red bag", "bone", Tier.BRONZE, 1, s -> s.reds),
            new Badge("red25", "Red Alert", "Loot 25 red bags", "bone", Tier.SILVER, 25, s -> s.reds),
            new Badge("red100", "Crimson Tide", "Loot 100 red bags", "bone", Tier.GOLD, 100, s -> s.reds),
            new Badge("orange10", "Orange You Glad", "Loot 10 orange bags", "bone", Tier.BRONZE, 10, s -> s.oranges),
            new Badge("orange100", "Tangerine Dream", "Loot 100 orange bags", "bone", Tier.GOLD, 100, s -> s.oranges),
            new Badge("gold25", "Golden Retriever", "Loot 25 gold bags", "bone", Tier.SILVER, 25, s -> s.golds),
            new Badge("gold250", "Midas Paws", "Loot 250 gold bags", "bone", Tier.PINK, 250, s -> s.golds),
            new Badge("egg10", "Egg Hunt", "Loot 10 egg baskets", "bone", Tier.BRONZE, 10, s -> s.eggs),
            new Badge("egg50", "Easter Pup", "Loot 50 egg baskets", "bone", Tier.GOLD, 50, s -> s.eggs),
            new Badge("blue25", "Feeling Blue", "Loot 25 blue bags", "bone", Tier.SILVER, 25, s -> s.blues),
            new Badge("blue250", "Deep Blue", "Loot 250 blue bags", "bone", Tier.PINK, 250, s -> s.blues),
            new Badge("cyan1", "Cyan Sighting", "Loot a cyan bag", "bone", Tier.SILVER, 1, s -> s.cyans),
            new Badge("cyan10", "Cyan Collector", "Loot 10 cyan bags", "bone", Tier.PINK, 10, s -> s.cyans),
            new Badge("boostwhite", "Boost Mode", "Loot a boosted white bag", "W", Tier.SILVER, 1, s -> s.boostedWhites),
            new Badge("boostwhite10", "Turbo Snow", "Loot 10 boosted white bags", "W", Tier.PINK, 10, s -> s.boostedWhites),
            new Badge("boosted100", "Supercharged", "Loot 100 boosted bags of any colour", "bone", Tier.GOLD, 100, s -> s.boosted),
            new Badge("rainbow", "Taste the Rainbow", "Loot a bag of every colour", "★", Tier.GOLD, 7, s -> s.colours),
            new Badge("doublerainbow", "Double Rainbow", "Loot a bag of every colour in one day", "★", Tier.PINK, 7, s -> s.mostColoursOneDay),
            // ---- items
            new Badge("rare100", "Rare Breed", "Loot 100 Rare items", "L", Tier.SILVER, 100, s -> s.rares),
            new Badge("packrat", "Pack Rat", "Loot 1,000 items from bags", "bone", Tier.GOLD, 1000, s -> s.items),
            new Badge("legendtrio", "Legendary Trio", "Get 3 Legendary items in one bag", "L", Tier.PINK, 3, s -> s.mostLegendariesOneBag),
            new Badge("divinedouble", "Heaven Sent", "Loot 2 Divine items in one day", "D", Tier.PINK, 2, s -> s.mostDivinesOneDay),
            // ---- white bags
            new Badge("snowday", "Snow Day", "Loot 3 white bags in one day", "W", Tier.SILVER, 3, s -> s.mostWhitesOneDay),
            new Badge("snowstorm", "Snowstorm", "Loot 5 white bags in one day", "W", Tier.GOLD, 5, s -> s.mostWhitesOneDay),
            new Badge("whiteout10", "White Out", "Loot white bags in 10 different dungeons", "W", Tier.GOLD, 10, s -> s.whiteDungeons),
            new Badge("whiteout25", "Snow Globe Trotter", "Loot white bags in 25 different dungeons", "W", Tier.PINK, 25, s -> s.whiteDungeons),
            new Badge("backtoback", "Back to Back", "Get a white bag in 2 runs in a row", "2", Tier.GOLD, 2, s -> s.whiteRunsInARow),
            new Badge("beginnersluck", "Beginner's Luck", "Get a white bag on your first run of a dungeon", "!", Tier.GOLD, 1, s -> s.firstRunWhites),
            new Badge("smashgrab", "Smash and Grab", "Get a white bag in a run under 5 minutes", "Z", Tier.PINK, 1, s -> s.quickWhites),
            new Badge("spooky", "Spooky Pup", "Loot a white bag in October", "W", Tier.SILVER, 1, s -> s.spookyWhites),
            new Badge("festive", "Festive Pup", "Loot a white bag in December", "W", Tier.SILVER, 1, s -> s.festiveWhites),
            // ---- runs and play
            new Badge("earlybird", "Early Bird", "Start a dungeon run between 5 and 7 am", "M", Tier.BRONZE, 1, s -> s.earlyRuns),
            new Badge("nightowl", "Night Owl", "Start 50 runs between midnight and 4 am", "M", Tier.GOLD, 50, s -> s.nightRuns),
            new Badge("longhaul", "Long Haul", "Spend an hour in one dungeon", "T", Tier.SILVER, 60, s -> s.longestRunMinutes),
            new Badge("regulars", "Regular Customer", "Run 10 different dungeons 10 times each", "R", Tier.GOLD, 10, s -> s.dungeons10),
            new Badge("dailywalk", "Daily Walkies", "Play 7 days in a row", "♥", Tier.SILVER, 7, s -> s.dayStreak),
            new Badge("goodhabit", "Good Habit", "Play 30 days in a row", "♥", Tier.PINK, 30, s -> s.dayStreak),
            new Badge("session6", "Marathon Pup", "Play for 6 hours in one go", "T", Tier.GOLD, 6, s -> s.longestSessionHours),
            new Badge("session12", "No Sleep Pup", "Play for 12 hours in one go", "T", Tier.PINK, 12, s -> s.longestSessionHours),
            new Badge("time1000", "Realm Lifer", "Play for 1,000 hours", "T", Tier.PINK, 1000, s -> s.playSeconds / 3600),
            new Badge("newyear", "Happy New Year", "Play on the 1st of January", "★", Tier.GOLD, 1, s -> s.newYear),
            // ---- fights
            new Badge("firstbite", "First Bite", "Help kill a boss", "B", Tier.BRONZE, 1, s -> s.bossKills),
            new Badge("mini100", "Mini Menace", "Help kill 100 minibosses", "B", Tier.SILVER, 100, s -> s.minibosses),
            new Badge("mini1000", "Mini Massacre", "Help kill 1,000 minibosses", "B", Tier.PINK, 1000, s -> s.minibosses),
            new Badge("bestiary25", "Monster Manual", "Kill 25 different bosses", "B", Tier.GOLD, 25, s -> s.differentBosses),
            new Badge("quickkill", "Speed Kill", "Kill a boss in under 10 seconds", "!", Tier.GOLD, 1, s -> s.quickKills),
            new Badge("hit100k", "Heavy Hitter", "Do 100,000 damage to one boss", "!", Tier.GOLD, 100_000, s -> s.biggestHit),
            new Badge("showoff", "Show Off", "Do the most damage in a group 5 times", "1", Tier.BRONZE, 5, s -> s.topDamage),
            new Badge("topofpack", "Top of the Pack", "Do the most damage with 10 or more players", "1", Tier.PINK, 1, s -> s.topBigGroup),
            new Badge("eventday", "Event Marathon", "Help kill 20 realm events in one day", "E", Tier.PINK, 20, s -> s.mostEventsOneDay),
            new Badge("events100", "Event Regular", "Help kill 100 realm events", "E", Tier.GOLD, 100, s -> s.eventKills),
            // ---- fame and classes
            new Badge("fame1k", "Rising Star", "Gain 1,000 fame", "F", Tier.BRONZE, 1000, s -> s.fame),
            new Badge("famerush", "Fame Rush", "Gain 2,000 fame in one day", "F", Tier.GOLD, 2000, s -> s.mostFameOneDay),
            new Badge("class5", "Class Act", "Gain fame on 5 different classes", "C", Tier.SILVER, 5, s -> s.classes),
            new Badge("class10", "Jack of All Trades", "Gain fame on 10 different classes", "C", Tier.GOLD, 10, s -> s.classes),
            new Badge("class15", "Master of Many", "Gain fame on 15 different classes", "C", Tier.PINK, 15, s -> s.classes),
            // ---- trades, vaults and collection
            new Badge("trades50", "Haggler", "Finish 50 trades", "$", Tier.SILVER, 50, s -> s.trades),
            new Badge("trades100", "Trader Pup", "Finish 100 trades", "$", Tier.GOLD, 100, s -> s.trades),
            new Badge("partners25", "Social Butterfly", "Trade with 25 different players", "$", Tier.PINK, 25, s -> s.tradePartners),
            new Badge("collect50", "Hobbyist", "Find 50 items for your collection", "C", Tier.SILVER, 50, s -> s.collection),
            new Badge("collect200", "Gallery", "Find 200 items for your collection", "C", Tier.PINK, 200, s -> s.collection),
            new Badge("shiny3", "Sparkle", "Find 3 shiny items", "★", Tier.GOLD, 3, s -> s.shinies),
            new Badge("vault100", "Vault Keeper", "Have 100 different items in your vaults", "V", Tier.GOLD, 100, s -> s.vaultItems),
            new Badge("seasonal25", "Seasonal Stash", "Own 25 different items in seasonal", "S", Tier.SILVER, 25, s -> s.seasonalItems),
            // ---- the pup
            new Badge("pets100", "Head Scratches", "Pet the pup 100 times", "♥", Tier.SILVER, 100, s -> s.pets),
            new Badge("practice100", "Puzzle Champ", "Solve 100 practice puzzles", "paw", Tier.GOLD, 100, s -> s.practiceSolved),
            new Badge("practiceperfect100", "Flawless", "Solve 100 practice puzzles perfectly", "paw", Tier.PINK, 100, s -> s.practicePerfect),
            new Badge("nightpup", "Night Pup", "Turn on dark mode", "M", Tier.BRONZE, 1, s -> s.darkMode),
            new Badge("dressup", "Dress Up", "Unlock 10 hats and outfits for the pup", "paw", Tier.GOLD, 10, s -> s.wardrobe),
            new Badge("titles5", "Title Holder", "Wear 5 different titles", "T", Tier.SILVER, 5, s -> s.titles),
            new Badge("titles25", "Name Dropper", "Wear 25 different titles", "T", Tier.PINK, 25, s -> s.titles),
            new Badge("badges25", "Badge Collector", "Unlock 25 badges", "★", Tier.GOLD, 25, s -> s.badges),
            new Badge("badges75", "Decorated", "Unlock 75 badges", "★", Tier.PINK, 75, s -> s.badges),
            // ---- 2.9.3: more steps on the ladders, seasons, and the wardrobe
            new Badge("white25", "Snowball", "Loot 25 white bags", "W", Tier.SILVER, 25, s -> s.whites),
            new Badge("sweetheart", "Sweetheart", "Loot a white bag in February", "♥", Tier.SILVER, 1, s -> s.valentineWhites),
            new Badge("springpup", "Spring Pup", "Loot a white bag in April", "W", Tier.SILVER, 1, s -> s.springWhites),
            new Badge("egg100", "Egg Hoarder", "Loot 100 egg baskets", "bone", Tier.PINK, 100, s -> s.eggs),
            new Badge("legendary10", "Shiny Things", "Loot 10 Legendary items", "L", Tier.SILVER, 10, s -> s.legendaries),
            new Badge("legendary50", "Treasure Hunter", "Loot 50 Legendary items", "L", Tier.GOLD, 50, s -> s.legendaries),
            new Badge("divine5", "Heavenly", "Loot 5 Divine items", "D", Tier.GOLD, 5, s -> s.divines),
            new Badge("rare500", "Rare Collector", "Loot 500 Rare items", "L", Tier.GOLD, 500, s -> s.rares),
            new Badge("items5000", "Overflowing Backpack", "Loot 5,000 items from bags", "bone", Tier.PINK, 5000, s -> s.items),
            new Badge("runs50", "Getting Comfy", "Finish 50 dungeon runs", "R", Tier.BRONZE, 50, s -> s.runs),
            new Badge("runs250", "Seasoned Explorer", "Finish 250 dungeon runs", "R", Tier.GOLD, 250, s -> s.runs),
            new Badge("runs2500", "Dungeon Royalty", "Finish 2,500 dungeon runs", "R", Tier.PINK, 2500, s -> s.runs),
            new Badge("favourite", "Favourite Spot", "Run the same dungeon 25 times", "♥", Tier.SILVER, 25, s -> s.mostRunsOneDungeon),
            new Badge("explorer20", "Explorer", "Run 20 different dungeons", "paw", Tier.GOLD, 20, s -> s.dungeonsVisited),
            new Badge("earlybird25", "Morning Pup", "Start 25 runs between 5 and 7 am", "M", Tier.GOLD, 25, s -> s.earlyRuns),
            new Badge("nightowl200", "Nocturnal", "Start 200 runs between midnight and 4 am", "M", Tier.PINK, 200, s -> s.nightRuns),
            new Badge("boss100", "Boss Botherer", "Help kill 100 bosses", "B", Tier.SILVER, 100, s -> s.bossKills),
            new Badge("boss1000", "Boss Bane", "Help kill 1,000 bosses", "B", Tier.PINK, 1000, s -> s.bossKills),
            new Badge("events10", "Event Rookie", "Help kill 10 realm events", "E", Tier.BRONZE, 10, s -> s.eventKills),
            new Badge("events500", "Tempest", "Help kill 500 realm events", "E", Tier.PINK, 500, s -> s.eventKills),
            new Badge("quickkill10", "Speed Demon", "Kill 10 bosses in under 10 seconds", "!", Tier.PINK, 10, s -> s.quickKills),
            new Badge("fame50k", "Celebrity", "Gain 50,000 fame", "F", Tier.GOLD, 50_000, s -> s.fame),
            new Badge("fame500k", "Superstar", "Gain 500,000 fame", "F", Tier.PINK, 500_000, s -> s.fame),
            new Badge("session3", "Long Walk", "Play for 3 hours in one go", "T", Tier.BRONZE, 3, s -> s.longestSessionHours),
            new Badge("time50", "Comfy", "Play for 50 hours", "T", Tier.SILVER, 50, s -> s.playSeconds / 3600),
            new Badge("time250", "Homebody", "Play for 250 hours", "T", Tier.GOLD, 250, s -> s.playSeconds / 3600),
            new Badge("days7", "First Week", "Play on 7 different days", "♥", Tier.BRONZE, 7, s -> s.playDays),
            new Badge("days60", "Two Months Strong", "Play on 60 different days", "♥", Tier.GOLD, 60, s -> s.playDays),
            new Badge("days200", "Old Friends", "Play on 200 different days", "♥", Tier.PINK, 200, s -> s.playDays),
            new Badge("fortnight", "Fortnight", "Play 14 days in a row", "♥", Tier.GOLD, 14, s -> s.dayStreak),
            new Badge("collect10", "Starter Set", "Find 10 items for your collection", "C", Tier.BRONZE, 10, s -> s.collection),
            new Badge("collect400", "Hall of Fame", "Find 400 items for your collection", "C", Tier.PINK, 400, s -> s.collection),
            new Badge("complete3", "Set Finisher", "Find every item from 3 dungeons", "★", Tier.GOLD, 3, s -> s.completedDungeons),
            new Badge("complete10", "Dungeon Scholar", "Find every item from 10 dungeons", "★", Tier.PINK, 10, s -> s.completedDungeons),
            new Badge("shiny50", "Glitter Pup", "Find 50 shiny items", "★", Tier.PINK, 50, s -> s.shinies),
            new Badge("trades25", "Market Regular", "Finish 25 trades", "$", Tier.BRONZE, 25, s -> s.trades),
            new Badge("trades250", "Trade Baron", "Finish 250 trades", "$", Tier.PINK, 250, s -> s.trades),
            new Badge("partners5", "Making Friends", "Trade with 5 different players", "$", Tier.BRONZE, 5, s -> s.tradePartners),
            new Badge("pets1", "First Pat", "Pet the pup", "♥", Tier.BRONZE, 1, s -> s.pets),
            new Badge("practice1000", "Puzzle Wizard", "Solve 1,000 practice puzzles", "paw", Tier.PINK, 1000, s -> s.practiceSolved),
            new Badge("fashionista", "Fashionista", "Unlock 25 hats and outfits for the pup", "paw", Tier.PINK, 25, s -> s.wardrobe),
            new Badge("badges50", "Medal Rack", "Unlock 50 badges", "★", Tier.GOLD, 50, s -> s.badges))));

    /** The main badges, then the easy ones (2.12). */
    private static List<Badge> withEasy(List<Badge> main) {
        List<Badge> all = new ArrayList<>(main);
        all.addAll(EasyBadges.all());
        return all;
    }

    // ---------------------------------------------------------------- unlocked badges

    static final class Unlock {
        final String account, id;
        final long at;
        /** Earned before badges existed (or before this badge did): shown, but never announced. */
        final boolean quiet;

        Unlock(String account, String id, long at, boolean quiet) {
            this.account = account;
            this.id = id;
            this.at = at;
            this.quiet = quiet;
        }
    }

    private static final String FILE = "badges.txt", EVENTS = "badge-events.txt";
    /** Set once the game files are read (item names are needed for the collection badges). */
    static volatile boolean ready;
    private static List<Unlock> unlocks;
    private static Map<String, Map<String, Long>> events; // account -> event -> count

    static synchronized List<Unlock> unlocks() {
        if (unlocks == null) {
            unlocks = new ArrayList<>();
            for (String l : read(FILE)) {
                String[] f = l.split("\\|", -1);
                if (f.length < 3) continue;
                try {
                    unlocks.add(new Unlock(f[0], f[1], Long.parseLong(f[2]), f.length > 3 && f[3].equals("1")));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(unlocks));
    }

    /** Notes something the logs don't keep (e.g. "puzzle", "puzzle-perfect", "practice-perfect"). */
    static synchronized void note(String account, String event) {
        events();
        events.computeIfAbsent(account == null ? "" : account, k -> new HashMap<>()).merge(event, 1L, Long::sum);
        append(EVENTS, (account == null ? "" : account) + "|" + event + "|" + System.currentTimeMillis() / 1000);
    }

    private static Map<String, Map<String, Long>> events() {
        if (events == null) {
            events = new HashMap<>();
            for (String l : read(EVENTS)) {
                String[] f = l.split("\\|", -1);
                if (f.length >= 2) events.computeIfAbsent(f[0], k -> new HashMap<>()).merge(f[1], 1L, Long::sum);
            }
        }
        return events;
    }

    private static synchronized long event(String account, String event) {
        Map<String, Long> m = events().get(account);
        Long n = m == null ? null : m.get(event);
        return n == null ? 0 : n;
    }

    /**
     * How many times this happened: for this account, plus any noted before the game said which account it was.
     * Before the game has said which account this is (after a restart), every account's count is included.
     */
    static synchronized long count(String account, String event) {
        if (account == null || account.isEmpty()) {
            long n = 0;
            for (Map<String, Long> m : events().values()) n += m.getOrDefault(event, 0L);
            return n;
        }
        return event("", event) + event(account, event);
    }

    /**
     * Works out which badges this account has now, saves new ones and returns them (the ones to announce).
     * The very first check for an account saves what it already earned quietly, so old history doesn't flood Discord.
     */
    static synchronized List<Badge> check(String account, Predicate<String> mine) {
        if (account == null || account.isEmpty() || !ready) return Collections.emptyList();
        Stats s = stats(account, mine);
        unlocks();
        boolean first = true;
        java.util.Set<String> have = new java.util.HashSet<>();
        for (Unlock u : unlocks) {
            if (!u.account.equals(account)) continue;
            first = false;
            have.add(u.id);
        }
        List<Badge> fresh = new ArrayList<>();
        long now = System.currentTimeMillis() / 1000;
        for (Badge b : ALL) {
            // a badge this account hasn't been checked for yet (new in this version): if it's already earned, it was
            // earned before the badge existed, so it unlocks quietly instead of being announced
            boolean seen = have.contains("~" + b.id);
            if (!seen) {
                unlocks.add(new Unlock(account, "~" + b.id, now, true));
                append(FILE, account + "|~" + b.id + "|" + now + "|1");
            }
            if (have.contains(b.id) || b.count.of(s) < b.need) continue;
            boolean quiet = first || !seen;
            Unlock u = new Unlock(account, b.id, now, quiet);
            unlocks.add(u);
            append(FILE, account + "|" + b.id + "|" + now + "|" + (quiet ? 1 : 0));
            if (!quiet) fresh.add(b);
        }
        if (first && fresh.isEmpty() && have.isEmpty()) {
            // remember that this account has been checked, even with nothing earned yet
            unlocks.add(new Unlock(account, "-", now, true));
            append(FILE, account + "|-|" + now + "|1");
        }
        return fresh;
    }

    /** True if any account has unlocked this badge (the pup's wardrobe is shared). */
    static synchronized boolean anyUnlocked(String id) {
        for (Unlock u : unlocks()) if (u.id.equals(id)) return true;
        return false;
    }

    static Badge byId(String id) {
        for (Badge b : ALL) if (b.id.equals(id)) return b;
        return null;
    }

    /** When this account unlocked each badge (badge id -> unlock). */
    static synchronized Map<String, Unlock> unlocked(String account) {
        Map<String, Unlock> out = new LinkedHashMap<>();
        for (Unlock u : unlocks()) {
            // badges that were taken out of the game (the old prismatic ones) don't count any more
            if (u.account.equals(account) && !u.id.equals("-") && !u.id.startsWith("~") && byId(u.id) != null) out.putIfAbsent(u.id, u);
        }
        return out;
    }

    // ---------------------------------------------------------------- counting

    static Stats stats(String account, Predicate<String> mine) {
        Stats s = new Stats();
        List<LootStats.Entry> bags = new ArrayList<>();
        for (LootStats.Entry e : LootStats.all()) if (mine.test(e.account)) bags.add(e);
        List<RunLog.Run> runs = new ArrayList<>();
        for (RunLog.Run r : RunLog.all()) if (mine.test(r.account)) runs.add(r);

        for (LootStats.Entry e : bags) {
            if (e.map != null && RunLog.isDungeon(e.map)) s.bagsBy.merge(EasyBadges.key(e.map), 1L, Long::sum);
            if (LootStats.colour(e.type) == BagType.WHITE) s.whites++;
            for (int r : e.rarity) {
                if (r == 3) s.legendaries++;
                if (r == 4) s.divines++;
            }
        }
        Map<java.time.LocalDate, Integer> whitesPerDay = new HashMap<>();
        for (LootStats.Entry e : bags) {
            if (LootStats.colour(e.type) != BagType.WHITE) continue;
            java.time.LocalDate d = java.time.Instant.ofEpochSecond(e.at).atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            s.mostWhitesOneDay = Math.max(s.mostWhitesOneDay, whitesPerDay.merge(d, 1, Integer::sum));
        }
        Map<String, List<Long>> whitesAt = new HashMap<>();
        for (LootStats.Entry e : bags) {
            if (LootStats.colour(e.type) == BagType.WHITE) whitesAt.computeIfAbsent(e.map, k -> new ArrayList<>()).add(e.at);
        }
        Map<String, Integer> byMap = new HashMap<>();
        for (RunLog.Run r : runs) {
            if (!RunLog.isDungeon(r.map)) continue;
            s.runs++;
            s.runsBy.merge(EasyBadges.key(r.map), 1L, Long::sum);
            int n = byMap.merge(r.map, 1, Integer::sum);
            if (n > s.mostRunsOneDungeon) {
                s.mostRunsOneDungeon = n;
                s.mostRunDungeon = r.map;
            }
            // two whites in one run
            int whites = 0;
            for (long at : whitesAt.getOrDefault(r.map, Collections.emptyList())) if (at >= r.at && at <= r.at + r.seconds + 10) whites++;
            if (whites >= 2) s.luckyRuns++;
            s.mostWhitesOneRun = Math.max(s.mostWhitesOneRun, whites);
        }
        for (DryStreak.Streak st : DryStreak.compute(runs, bags).values()) {
            if (st.map.isEmpty()) continue;
            for (long[] b : st.broken) s.brokeStreak = Math.max(s.brokeStreak, b[1]);
        }
        List<History.Dps> myKills = new ArrayList<>();
        for (History.Dps d : History.dps()) if (mine.test(d.account) && d.killed) myKills.add(d);
        for (History.Dps d : KillsPage.oneEach(myKills)) {
            boolean event = KillsPage.isEvent(d);
            if (event) s.eventKills++;
            if (event || d.kind.isEmpty() || d.kind.equals("BOSS")) s.bossKills++;
            if (d.rank == 1 && d.players > 1) s.topDamage++;
            if (d.share >= 99 && (d.kind.isEmpty() || d.kind.equals("BOSS") || event)) s.soloKills++;
        }
        for (History.Fame f : History.fame()) if (mine.test(f.account)) s.fame += f.gained;
        for (History.Play p : History.play()) if (mine.test(p.account)) s.playSeconds += p.end - p.start;
        Map<LootBook.Item, LootBook.Found> found = LootBook.found(bags, mine);
        s.collection = found.size();
        for (LootBook.Place p : LootBook.places()) {
            if (p.name.equals("Elsewhere") || p.items.isEmpty()) continue;
            boolean all = true;
            for (LootBook.Item it : p.items) if (!found.containsKey(it)) { all = false; break; }
            if (all) s.completedDungeons++;
        }
        for (TradeLog.Trade t : TradeLog.all()) if (mine.test(t.account)) s.trades++;
        s.puzzleSolved = event(account, "puzzle");
        s.puzzlePerfect = event(account, "puzzle-perfect");
        s.practicePerfect = count(account, "practice-perfect");
        s.practiceSolved = count(account, "practice");
        s.pets = count(account, "pet");
        s.bags = bags.size();
        s.shinies = LootBook.foundShinies(bags, mine).size();
        s.dungeonsVisited = byMap.size();
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        Map<java.time.LocalDate, Integer> perDay = new HashMap<>();
        for (RunLog.Run r : runs) {
            if (!RunLog.isDungeon(r.map)) continue;
            java.time.ZonedDateTime t = java.time.Instant.ofEpochSecond(r.at).atZone(zone);
            s.busiestDay = Math.max(s.busiestDay, perDay.merge(t.toLocalDate(), 1, Integer::sum));
            if (t.getHour() < 4) s.nightRuns++;
        }
        java.util.Set<java.time.LocalDate> days = new java.util.HashSet<>();
        for (History.Play p : History.play()) if (mine.test(p.account)) days.add(java.time.Instant.ofEpochSecond(p.start).atZone(zone).toLocalDate());
        s.playDays = days.size();
        more(s, account, mine, bags, runs, myKills, whitesAt, byMap, zone, days);
        return s;
    }

    /** The counts for the badges added in 2.8. */
    private static void more(Stats s, String account, Predicate<String> mine, List<LootStats.Entry> bags, List<RunLog.Run> runs,
                             List<History.Dps> myKills, Map<String, List<Long>> whitesAt, Map<String, Integer> byMap,
                             java.time.ZoneId zone, java.util.Set<java.time.LocalDate> days) {
        java.util.Set<BagType> colours = java.util.EnumSet.noneOf(BagType.class);
        Map<java.time.LocalDate, java.util.Set<BagType>> coloursByDay = new HashMap<>();
        Map<java.time.LocalDate, Integer> divinesByDay = new HashMap<>();
        java.util.Set<String> whiteMaps = new java.util.HashSet<>();
        for (LootStats.Entry e : bags) {
            BagType c = LootStats.colour(e.type);
            java.time.LocalDate d = java.time.Instant.ofEpochSecond(e.at).atZone(zone).toLocalDate();
            switch (c) {
                case RED: s.reds++; break;
                case ORANGE: s.oranges++; break;
                case GOLD: s.golds++; break;
                case EGG: s.eggs++; break;
                case BLUE: s.blues++; break;
                case CYAN: s.cyans++; break;
                case WHITE:
                    if (!e.map.isEmpty()) whiteMaps.add(e.map);
                    if (d.getMonthValue() == 10) s.spookyWhites++;
                    if (d.getMonthValue() == 12) s.festiveWhites++;
                    if (d.getMonthValue() == 2) s.valentineWhites++;
                    if (d.getMonthValue() == 4) s.springWhites++;
                    break;
                default:
            }
            if (e.type.name().startsWith("BOOSTED_")) {
                s.boosted++;
                if (c == BagType.WHITE) s.boostedWhites++;
            }
            colours.add(c);
            java.util.Set<BagType> today = coloursByDay.computeIfAbsent(d, k -> java.util.EnumSet.noneOf(BagType.class));
            today.add(c);
            s.mostColoursOneDay = Math.max(s.mostColoursOneDay, today.size());
            s.items += e.items.size();
            int legendaries = 0;
            for (int r : e.rarity) {
                if (r == 2) s.rares++;
                if (r == 3) legendaries++;
                if (r == 4) s.mostDivinesOneDay = Math.max(s.mostDivinesOneDay, divinesByDay.merge(d, 1, Integer::sum));
            }
            s.mostLegendariesOneBag = Math.max(s.mostLegendariesOneBag, legendaries);
        }
        s.colours = colours.size();
        s.whiteDungeons = whiteMaps.size();

        // runs, oldest first
        List<RunLog.Run> sorted = new ArrayList<>();
        for (RunLog.Run r : runs) if (RunLog.isDungeon(r.map)) sorted.add(r);
        sorted.sort((a, b) -> Long.compare(a.at, b.at));
        java.util.Set<String> ranBefore = new java.util.HashSet<>();
        long inARow = 0;
        for (RunLog.Run r : sorted) {
            int whites = 0;
            for (long at : whitesAt.getOrDefault(r.map, Collections.emptyList())) if (at >= r.at && at <= r.at + r.seconds + 10) whites++;
            inARow = whites > 0 ? inARow + 1 : 0;
            s.whiteRunsInARow = Math.max(s.whiteRunsInARow, inARow);
            if (whites > 0 && !ranBefore.contains(r.map)) s.firstRunWhites++;
            if (whites > 0 && r.seconds > 0 && r.seconds < 300) s.quickWhites++;
            ranBefore.add(r.map);
            s.longestRunMinutes = Math.max(s.longestRunMinutes, r.seconds / 60);
            int h = java.time.Instant.ofEpochSecond(r.at).atZone(zone).getHour();
            if (h >= 5 && h < 7) s.earlyRuns++;
        }
        for (int n : byMap.values()) {
            if (n >= 10) s.dungeons10++;
            if (n >= 25) s.dungeons25++;
        }

        // days in a row, longest sitting, new year
        List<java.time.LocalDate> dayList = new ArrayList<>(days);
        Collections.sort(dayList);
        long streak = 0;
        java.time.LocalDate prev = null;
        for (java.time.LocalDate d : dayList) {
            streak = prev != null && prev.plusDays(1).equals(d) ? streak + 1 : 1;
            s.dayStreak = Math.max(s.dayStreak, streak);
            if (d.getMonthValue() == 1 && d.getDayOfMonth() == 1) s.newYear = 1;
            prev = d;
        }
        for (History.Play p : History.play()) if (mine.test(p.account)) s.longestSessionHours = Math.max(s.longestSessionHours, (p.end - p.start) / 3600);

        // fights
        java.util.Set<String> bosses = new java.util.HashSet<>();
        Map<java.time.LocalDate, Integer> eventsByDay = new HashMap<>();
        for (History.Dps d : KillsPage.oneEach(myKills)) {
            boolean event = KillsPage.isEvent(d);
            boolean boss = event || d.kind.isEmpty() || d.kind.equals("BOSS");
            if (d.kind.equals("MINIBOSS")) s.minibosses++;
            if (boss && !d.boss.isEmpty()) bosses.add(d.boss);
            if (boss && d.seconds > 0 && d.seconds < 10) s.quickKills++;
            s.biggestHit = Math.max(s.biggestHit, d.damage);
            if (d.rank == 1 && d.players >= 10) s.topBigGroup++;
            if (event) {
                java.time.LocalDate day = java.time.Instant.ofEpochSecond(d.at).atZone(zone).toLocalDate();
                s.mostEventsOneDay = Math.max(s.mostEventsOneDay, eventsByDay.merge(day, 1, Integer::sum));
            }
        }
        s.differentBosses = bosses.size();

        // fame and classes
        Map<java.time.LocalDate, Long> fameByDay = new HashMap<>();
        java.util.Set<String> classes = new java.util.HashSet<>();
        for (History.Fame f : History.fame()) {
            if (!mine.test(f.account)) continue;
            java.time.LocalDate d = java.time.Instant.ofEpochSecond(f.end).atZone(zone).toLocalDate();
            s.mostFameOneDay = Math.max(s.mostFameOneDay, fameByDay.merge(d, (long) f.gained, Long::sum));
            if (!f.cls.isEmpty() && f.gained > 0) classes.add(f.cls);
        }
        s.classes = classes.size();

        // trades, vaults
        java.util.Set<String> partners = new java.util.HashSet<>();
        for (TradeLog.Trade t : TradeLog.all()) if (mine.test(t.account) && !t.partner.isEmpty()) partners.add(t.partner.toLowerCase(java.util.Locale.ROOT));
        s.tradePartners = partners.size();
        java.util.Set<Integer> vault = new java.util.HashSet<>(), seasonal = new java.util.HashSet<>();
        for (Owned.Sighting o : Owned.of(mine)) {
            if (o.where.equals(Owned.VAULT) || o.where.equals(Owned.SEASONAL_VAULT) || o.where.equals(Owned.GIFT_CHEST)
                    || o.where.equals(Owned.SEASONAL_SPOILS)) vault.add(o.item);
            if (o.where.equals(Owned.SEASONAL_VAULT) || o.where.equals(Owned.SEASONAL_CHARACTER) || o.where.equals(Owned.SEASONAL_SPOILS)) seasonal.add(o.item);
        }
        s.vaultItems = vault.size();
        s.seasonalItems = seasonal.size();

        // the pup
        s.badges = unlocked(account).size();
        s.wardrobe = Wardrobe.owned();
        s.wardrobeAll = Wardrobe.owned() >= Wardrobe.total() ? 1 : 0;
        s.titles = Titles.wornBy(mine).size();
        s.darkMode = Theme.DARK ? 1 : 0;
    }

    // ---------------------------------------------------------------- files

    private static List<String> read(String file) {
        try {
            return Files.readAllLines(Paths.get(file), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static void append(String file, String line) {
        try {
            Files.write(Paths.get(file), Collections.singletonList(line), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
}
