import re, sys
src = open(sys.argv[1]).read()
core = re.findall(r'new Badge\("([^"]+)", "([^"]+)", "[^"]*", "[^"]*", Tier\.\w+, ([\d_]+), s -> ([^)]+?)\)', src)
taken = {(c.strip(), int(n.replace('_',''))) for _,_,n,c in core}
core_ids = {i for i,_,_,_ in core}
core_names = {n for _,n,_,_ in core}

def num(n): return f"{n:,}"
# counter, glyph, desc template ({n} / {s} plural), [(need, name)], one-desc
L = [
 ("s.bags", "bone", "Loot {n} bags of any colour", "Loot your first bag", [
   (1,"First Fetch"),(2,"Double Fetch"),(3,"Hat Trick of Bags"),(5,"Handful of Bags"),(10,"Bag Buddy"),(15,"Bag Pal"),
   (20,"Bag Bestie"),(30,"Sack Sniffer"),(40,"Pouch Pup"),(50,"Bag Fifty"),(60,"Satchel Seeker"),(75,"Loot Hound"),
   (150,"Bag Bundle"),(200,"Bag Barrel"),(250,"Bag Mountain"),(300,"Bag Tower"),(400,"Bag Castle"),(500,"Bag Kingdom")]),
 ("s.runs", "R", "Finish {n} dungeon runs", "Finish your first dungeon run", [
   (1,"First Steps"),(2,"Second Helping"),(3,"Third Time Lucky"),(5,"High Five"),(15,"Warming Up"),(20,"Into the Groove"),
   (25,"Quarter Century"),(30,"Thirty Trips"),(40,"Forty Forays"),(50,"Half Century"),(60,"Sixty Strolls"),(75,"Seasoned Sniffer"),
   (150,"Trail Blazer"),(200,"Path Finder"),(250,"Map Maker"),(300,"Dungeon Diver")]),
 ("s.whites", "W", "Loot {n} white bags", None, [
   (2,"Snow Twins"),(3,"Snow Trio"),(4,"Four Flakes"),(5,"Flurry"),(15,"Snow Angel"),(20,"Snowdrift"),(25,"Frosty")]),
 ("s.reds", "bone", "Loot {n} red bags", "Loot a red bag", [
   (2,"Cherry Pair"),(3,"Rosy Trio"),(5,"Ruby Handful"),(10,"Red Ten"),(15,"Scarlet Stash"),(20,"Crimson Crate"),(50,"Red Fifty"),(75,"Red Hoard")]),
 ("s.oranges", "bone", "Loot {n} orange bags", "Loot an orange bag", [
   (1,"Tangerine Treat"),(2,"Citrus Pair"),(3,"Orange Trio"),(5,"Juicy Five"),(10,"Orange Ten"),(15,"Marmalade"),(20,"Orchard"),(50,"Orange Fifty"),(75,"Orange Grove")]),
 ("s.golds", "bone", "Loot {n} gold bags", "Loot a gold bag", [
   (1,"Gold Star"),(2,"Golden Pair"),(3,"Golden Trio"),(5,"Gold Rush"),(10,"Gold Ten"),(15,"Gilded Pup"),(50,"Gold Fifty"),(75,"Gold Vault")]),
 ("s.eggs", "bone", "Loot {n} egg baskets", "Loot an egg basket", [
   (1,"First Egg"),(2,"Egg Pair"),(3,"Egg Trio"),(5,"Egg Hunt Begins"),(15,"Basket Case"),(20,"Egg Stash"),(25,"Egg Hoard")]),
 ("s.blues", "bone", "Loot {n} blue bags", "Loot a blue bag", [
   (1,"Into the Blue"),(2,"Blue Pair"),(3,"Blue Trio"),(5,"Blue Five"),(10,"Blue Ten"),(15,"Sky High"),(50,"Ocean Pup"),(75,"Blue Lagoon")]),
 ("s.cyans", "bone", "Loot {n} cyan bags", None, [(2,"Cyan Pair"),(3,"Cyan Trio"),(5,"Cyan Five")]),
 ("s.rares", "L", "Loot {n} Rare items", "Loot a Rare item", [(1,"Rare Find"),(5,"Rare Five"),(10,"Rare Ten"),(25,"Rarely Seen"),(50,"Rare Fifty")]),
 ("s.legendaries", "L", "Loot {n} Legendary items", None, [(2,"Legend Pair"),(3,"Legend Trio"),(5,"Legend Five"),(10,"Legend Ten"),(15,"Legendary Pup"),(20,"Legend Twenty")]),
 ("s.items", "bone", "Loot {n} items from bags", None, [(10,"Pocketful"),(25,"Armful"),(50,"Backpack Full"),(100,"Item Hundred"),(250,"Item Pile"),(500,"Item Heap")]),
 ("s.boosted", "W", "Loot {n} boosted bags", "Loot a boosted bag", [(1,"Boosted!"),(5,"Boost Five"),(10,"Boost Ten"),(25,"Turbo Pup"),(50,"Boost Fifty")]),
 ("s.colours", "★", "Loot bags of {n} different colours", None, [(2,"Two Tone"),(3,"Tricolour"),(4,"Four Colours"),(5,"Five Colours"),(6,"Nearly Rainbow")]),
 ("s.bossKills", "B", "Help kill {n} bosses", "Help kill a boss", [
   (1,"First Boss"),(3,"Boss Trio"),(5,"Boss Five"),(10,"Boss Ten"),(15,"Boss Fifteen"),(20,"Boss Twenty"),(25,"Boss Basher"),
   (75,"Boss Bother"),(100,"Boss Hundred"),(150,"Boss Brawler"),(200,"Boss Buster"),(250,"Boss Breaker"),(300,"Boss Boxer")]),
 ("s.topDamage", "1", "Do the most damage in a group {n} times", "Do the most damage in a group", [(1,"Top Pup"),(3,"Top Three"),(5,"Top Five"),(10,"Top Ten"),(15,"Top Fifteen"),(50,"Top Fifty")]),
 ("s.eventKills", "E", "Help kill {n} realm events", "Help kill a realm event", [(1,"Event Newbie"),(3,"Event Trio"),(5,"Event Five"),(10,"Event Ten"),(15,"Event Fifteen"),(50,"Event Fifty"),(75,"Event Expert"),(100,"Event Hundred")]),
 ("s.minibosses", "B", "Help kill {n} minibosses", "Help kill a miniboss", [(1,"Mini Me"),(5,"Mini Five"),(10,"Mini Ten"),(25,"Mini Mob"),(50,"Mini Fifty"),(250,"Mini Mayhem")]),
 ("s.differentBosses", "B", "Kill {n} different bosses", "Kill a boss", [(1,"Boss Sampler"),(3,"Boss Variety"),(5,"Boss Buffet"),(10,"Boss Bingo"),(15,"Boss Album"),(20,"Boss Catalogue"),(25,"Boss Encyclopedia")]),
 ("s.fame", "F", "Gain {n} fame", None, [(100,"Noticed"),(250,"Talked About"),(500,"Up and Coming"),(1000,"Local Hero"),(2500,"Well Known"),(5000,"Celebrity Pup"),(7500,"Fan Club"),(15000,"Headline"),(20000,"Front Page"),(25000,"Star Pup"),(50000,"Household Name")]),
 ("s.playSeconds / 3600", "T", "Play for {n} hours", "Play for an hour", [
   (1,"First Hour"),(2,"Two Hours"),(3,"Three Hours"),(4,"Four Hours"),(5,"Five Hours"),(6,"Six Hours"),(8,"Full Day's Work"),(12,"Half a Day"),
   (15,"Fifteen Hours"),(20,"Twenty Hours"),(25,"Day and a Bit"),(30,"Thirty Hours"),(40,"Forty Hours"),(50,"Fifty Hours"),(60,"Sixty Hours"),(75,"Seventy-Five Hours")]),
 ("s.playDays", "♥", "Play on {n} different days", "Play on a day the pup is watching", [(1,"Hello Pup"),(2,"Back Again"),(3,"Third Visit"),(5,"Five Days"),(7,"A Week of Days"),(10,"Ten Days"),(14,"Two Weeks of Days"),(21,"Three Weeks of Days"),(30,"A Month of Days")]),
 ("s.dayStreak", "♥", "Play {n} days in a row", None, [(2,"Two in a Row"),(3,"Three in a Row"),(5,"Five in a Row")]),
 ("s.collection", "C", "Find {n} items for your collection", "Find an item for your collection", [(1,"First Exhibit"),(5,"Little Shelf"),(10,"Display Case"),(15,"Showcase"),(20,"Gallery Wall"),(30,"Exhibition"),(40,"Wing of Wonders"),(50,"Half a Hundred"),(60,"Museum Pup"),(75,"Grand Display")]),
 ("s.dungeonsVisited", "paw", "Run {n} different dungeons", "Run a dungeon", [(1,"Off We Go"),(2,"Wanderer"),(3,"Tourist"),(5,"Sightseer"),(7,"Globetrotter"),(15,"Voyager"),(20,"Pathfinder"),(25,"Cartographer")]),
 ("s.busiestDay", "Z", "Finish {n} dungeon runs in one day", None, [(2,"Busy Day"),(3,"Busier Day"),(5,"Five a Day"),(7,"Seven a Day"),(15,"Marathon Day")]),
 ("s.trades", "$", "Finish {n} trades", "Finish a trade", [(1,"First Trade"),(2,"Swap Shop"),(3,"Trade Trio"),(5,"Deal Maker"),(15,"Bargain Hunter"),(20,"Market Pup"),(50,"Trade Fifty")]),
 ("s.tradePartners", "$", "Trade with {n} different players", "Trade with someone", [(1,"New Friend"),(3,"Friendly Pup"),(10,"Trade Circle")]),
 ("s.pets", "♥", "Pet the pup {n} times", "Pet the pup", [(1,"Hello Pat"),(5,"Chin Scratches"),(10,"Ear Rubs"),(50,"Good Boy Pats"),(75,"Snuggles"),(100,"Cuddle Time"),(150,"Pup Pampering"),(200,"Lap Dog"),(250,"Teacher's Pet"),(300,"Very Spoiled")]),
 ("s.practiceSolved", "paw", "Solve {n} practice puzzles", "Solve a practice puzzle", [(1,"Puzzle Paw"),(3,"Puzzle Trio"),(5,"Puzzle Five"),(25,"Puzzle Fan"),(100,"Puzzle Pro")]),
 ("s.practicePerfect", "paw", "Solve {n} practice puzzles perfectly", None, [(3,"Neat Paws"),(5,"Tidy Paws"),(10,"Perfect Paws")]),
 ("s.shinies", "★", "Find {n} shiny items", None, [(2,"Twinkle"),(3,"Glitter"),(5,"Shimmer")]),
 ("s.vaultItems", "V", "Have {n} different items in your vaults", None, [(10,"Vault Starter"),(25,"Vault Shelf"),(50,"Vault Stash"),(200,"Vault Hoard")]),
 ("s.classes", "C", "Gain fame on {n} different classes", "Gain fame on a class", [(1,"Class Begins"),(2,"Second Class"),(3,"Third Class"),(4,"Fourth Class"),(6,"Six Classes"),(8,"Eight Classes")]),
 ("s.titles", "★", "Wear {n} different titles", "Wear a title", [(1,"Named Pup"),(2,"Two Titles"),(3,"Three Titles"),(5,"Five Titles"),(10,"Ten Titles")]),
 ("s.wardrobe", "♥", "Unlock {n} hats and outfits for the pup", "Unlock a hat or outfit for the pup", [(1,"First Outfit"),(2,"Accessorised"),(3,"Fashionable"),(5,"Trendsetter"),(8,"Model Pup"),(10,"Fashion Icon")]),
 ("s.badges", "★", "Unlock {n} badges", None, [(5,"Medal Start"),(10,"Medal Ten"),(15,"Medal Fifteen"),(100,"Medal Wall"),(150,"Medal Shelf"),(200,"Medal Hall"),(300,"Medal Museum"),(400,"Medal Palace")]),
 ("s.nightRuns", "M", "Start {n} runs between midnight and 4 am", None, [(3,"After Dark"),(5,"Moonlit Pup"),(10,"Moon Walker")]),
 ("s.earlyRuns", "M", "Start {n} runs between 5 and 7 am", None, [(5,"Sunrise Pup"),(10,"Dawn Patrol")]),
]
out = []; ids=set(); names=set(core_names); skipped=0
for counter, glyph, desc, one, steps in L:
    n_steps = len(steps)
    for i,(need,name) in enumerate(steps):
        if (counter, need) in taken: skipped+=1; continue
        tier = "BRONZE" if i < n_steps*0.45 else "SILVER" if i < n_steps*0.85 else "GOLD"
        d = one if (need == 1 and one) else desc.replace("{n}", num(need))
        bid = "e-" + re.sub(r'[^a-z0-9]+','',counter.split('/')[0].replace('s.','')) + str(need)
        assert bid not in ids and bid not in core_ids, bid
        assert name not in names, name
        ids.add(bid); names.add(name)
        out.append(f'        add(out, "{bid}", "{name}", "{d}", "{glyph}", Tier.{tier}, {need}, s -> {counter});')
print(len(out), "ladder badges,", skipped, "skipped (already a badge)", file=sys.stderr)
java = open(sys.argv[2]).read().replace("        //LADDERS", "\n".join(out))
open(sys.argv[3],'w').write(java)
