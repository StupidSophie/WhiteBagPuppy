# Changelog

What's new in each version of White Bag Puppy, newest first.
Download the latest from [Releases](../../releases/latest).

## 2.30.50
- Who's online also counts guildmates who only walked past you between updates (a busy Nexus, someone running by in a realm), not just the ones in view at that moment.

## 2.30.49
- Which server you're on: the pup works it out from the realm names on atti.network (after a realm or two on a new server) and remembers it in server-ips.txt, then says it in the diary. It goes to Biscuit with who's online, for guildmates near you too, so /online and /offline show the server.

## 2.30.48
- Who's online: with a Biscuit link, the pup tells Biscuit about once a minute that you're playing (name, class, where you are and your party), and which guildmates it can see near you. Type /online in Discord to see who's on, and /online player:name for when someone was last seen. Ticked from the start; untick "Tell my guild's Biscuit when I'm online" in Settings > Guild deaths to stop. (The game's guild list comes from Deca's website, which the pup can't read, so people only show up when they or a guildmate near them has the pup.)

## 2.30.47
- "Join the friends leaderboard" is now ticked from the start. It only sends anything once there's a bot recap link; untick it to stay off the board.

## 2.30.46
- "Post guildmates' deaths" and "Post my own deaths" (Settings > Guild deaths) are now ticked from the start, and get ticked once when you update to this version. Untick them if you don't want them.

## 2.30.45
- Fixed: the Discord status could drop with "Lost Discord (StringIndexOutOfBoundsException)" when writing its debug file.
- Fixed: guild deaths away from you weren't posted. The game's death message wasn't being read, so only deaths next to you (from the gravestone) were caught. Now it posts every guild death the game tells you about, wherever it happened, with what killed them. A death message the pup can't read is now mentioned in the diary.
- Last hits: when one of your characters dies, the pup lists the last few hits before it ("Abyssal Kraken 140, 140 · Hadopelagic Jellyfish 85"), so you can see what really killed you. They're in the death post, the diary and Me > Characters > All characters > Graveyard.
- With a Biscuit link, a death posted without a picture (seen only from the game's death message) gets the character's card added when another pup that saw them sends it. Biscuit also keeps the deaths for /graveyard: the week's top killers, who died most and the biggest fame lost, with a summary every Sunday evening.
- The Discord status no longer writes discord-status-debug.txt.
- Guild deaths (Settings > Guild deaths): when someone in your guild dies, the pup posts it to Discord straight away, from the game's own death message (the gravestone notice at the top left: "Name died at level 20, killed by Inferno"), so it works wherever they are. If they were near you, the post also has their class, fame and gear (with enchantments) as a picture; if the game's message doesn't come, a gravestone appearing where a guildmate stood is used instead. It also goes in the diary. Leave the webhook empty to use the bag webhook; with a Biscuit link, Biscuit posts it in its death channel (or the one set with /pup channel Guild deaths), and only once when several pups see the same death. Can be switched off. The first death messages are saved in death-notices-debug.txt. Death posts look like Biscuit's graveyard posts: "Oh no!! Name died", Murdered by, Base and Total Fame, Stats (for your own), a comfort note, and the same pink card as bag posts: the character's picture first, then its gear in the bag-post item slots (tier, enchantment tags and glow).
- "Post my own deaths (with what killed me)" (Settings > Guild deaths, off at first): when one of your characters dies, it's posted to the same place with what killed you, your base and total fame, how many stats were maxed, and your character and gear.

## 2.30.44
- Discord status: discord-status-debug.txt also records the first few status updates and Discord's answers, to find out why a status that's sent doesn't show.

## 2.30.43
- Discord status: fixed connecting to Discord (the pup asked for the Discord pipe by a name Java changes into a network path). It also follows whichever of your accounts is playing, says in the diary when its status changes, and writes discord-status-debug.txt to help if it still doesn't work.

## 2.30.42
- Discord status (Settings > Discord status): shows "Playing White Bag Puppy" on your Discord profile while you're in the game, with your name, class and fame, the dungeon you're in, how long you've been there, and your party's name with how full it is (e.g. "2 of 8"). Each part can be switched off. It talks to the Discord app on your PC directly (no login or token), and goes away when you close the game. Just tick "Show my status on Discord".

## 2.30.41
- New: White Bag Puppy.exe, for people who have trouble with OneDrive, zip files or Java. Download it and open it: it installs the pup with its own Java into your AppData folder (away from OneDrive and Windows' folder protection), makes White Bag Puppy shortcuts on the Desktop and in the Start menu, and starts it. No Java install needed. To bring your settings and history along, put the exe in your old White Bag Puppy folder and open it from there the first time. Open a newer exe later to update (the pup also still updates itself).
- White Bag Puppy.exe checks for Npcap (which can't be included: its licence doesn't allow it). If it's missing, or installed without "WinPcap API-compatible mode", it says so and offers to open the Npcap download page.
- "Open White Bag Puppy when Windows starts" now uses the Java the pup is running on, so it works without Java installed.

## 2.30.40
- Loot History: filter bags by the character that looted them and the enemy that dropped them (two new drop-downs), and search finds enemies too. Each bag now says which enemy dropped it and which class looted it. These are saved for bags from this version on, so older bags don't have them.
- Fights > History (new): every DPS leaderboard is saved in full from now on, with everyone's class, gear, enchantments and who died or left. Search by boss, dungeon or player, pick bosses, minibosses or others and a time, and tick "I was in it", "Someone died" or "Only kills". Shows the newest 20 matches.
- Collection: a "recent finds" list at the top with the items you looted for the first time most recently, the date you first found each one and where.
- Ideas from RealmHound by Lovens and Pillar (github.com/pillarzz/RealmHound, MIT licence).

## 2.30.39
- Fixed: in Loot History, a bag with lots of items was cut off when the window was too narrow (you had to resize to see it). The items now move under the bag's name and the row grows to fit them.

## 2.30.38
- Fixed: the tier tag (T13, UT...) and enchantment dots on item boxes were drawn under the item picture, so big pictures hid them. They're on top now (everywhere item boxes show: Players, About me, trades...).
- Fixed: Chat > Players said "Players in {s.nexus}" instead of "Players in Nexus" (and the same for other places with a code name).

## 2.30.37
- Fights > DPS: players' items show their enchantments. Each item's frame is coloured by its enchantment rarity (green uncommon, blue rare, purple legendary, gold divine); hover an item to see its enchantments, or click it to keep them open.

## 2.30.36
- Fights > DPS: each player's class picture is shown before their name (hover it for the class).

## 2.30.35
- Fights > DPS: every player on a leaderboard now has their weapon, ability, armour and ring under their name (hover an item for its name), and a "died" or "left" tag if they died or left the map during that fight ("died" for you too). Copy results includes both.
- Chat > Players: a new "left or died" list. When someone near you disappears, the pup works out why: "died" if a gravestone appeared where they stood, "left" if not (nexused, went to the vault or logged off; it says "probably nexused" when they were under 50% HP). Each one shows the gear they had on. People who teleport or walk out of view don't count, and neither does everyone vanishing at once when you teleport away. When it's someone in your party, it also goes in the pup diary.

## 2.30.34
- Events tab: when RealmStock is down, the pup now gets realm events from atti.network's Encounters list instead (atti.network/encounters), so pings and the diary keep working. Pick the source in the Events tab: "RealmStock, atti if it's down" (the default), "Only RealmStock" or "Only atti.network". atti has realm events and closing realms but no key pops. The header says which site the list came from, and an event seen on both sites only pings once.
- Fixed: 2.30.32 and 2.30.33 were missing the files built into the app (the fonts, the Collection's item list, the fame bonus list, the reskin list and RealmStock's event names), so those parts showed empty or in the wrong font. They're back.

## 2.30.33
- Fixed: the Shatters puzzle badges (Puzzle Solved, Big Brain, Puzzle Master) never ticked. The puzzle room's setup and "locked" objects sit on top of the towers, and the pup counted them as towers, so it never read the board. It now only reads the on/off towers, and counts a shot once even when its flips arrive over a few packets.
- Fixed: Secluded Thicket was treated like the nexus (not a dungeon), so its runs weren't logged and its badges couldn't unlock. Bags you've already looted there count straight away; runs count from now on.

## 2.30.32
- Took out the season and battle pass timers, the exalts-by-class table and the Collection sorting from 2.30.31. Exalts and the Collection are back to how they were in 2.30.30. The dungeon mods stay.

## 2.30.31
- Dungeon mods: when you enter a dungeon with modifiers, the diary says what they are and the loot, XP and dust bonus each one gives, plus the total. A new "Dungeon mods" chip in the widget bar shows the loot bonus (hover it for every mod), and the overlay has a "Dungeon mods" row (right-click the overlay > Rows). The bonuses come from the game's own files, so they stay right after patches.
- Season and battle pass timers: right-click the widget bar > Season dates... and type when the season started and ends. The bar then counts down to the end of the season and the battle pass (the battle pass end is guessed if you leave it empty: halfway through the season, on a Tuesday). The game only gives these dates through its login API, which the pup doesn't use, so you type them once a season. There's also a "Login calendar ends" chip (off at first: right-click the bar to turn it on).
- Me > About me: "exalts by class" is now a RealmEye-style table with every class down the side and a coloured square per stat, your total for each class and for the whole account. Hover a square for how many potions it still needs, or a class for its next level.
- Collection: sort dungeons by Most runs, Closest to done (the ones you're nearly finished go first) or A-Z. The top says how many dungeons you've completed, and a dungeon 1 or 2 items from done says so.
- Parts of this version are based on RealmHound by Lovens and Pillar (github.com/pillarzz/RealmHound, MIT licence).

## 2.30.30
- UPE Undo: if "Use the character I'm on now" was pressed several times, one Undo joins all of that character's runs back together. Pressing Undo more than once no longer takes points off twice.
- A run that was put back is checked again when the pup opens, and its total fixed if 2.30.28 got it wrong.

## 2.30.29
- UPE: Wandering Souls Spell (and its shiny) counts as tier 3 (7 points): it dismantles for 15/30. The game labels it tier 4.

## 2.30.28
- UPE Undo also works after you've already scored on the new run, if it's the same character: the new drops join the old run, and anything the old run already had only adds what's new (a better rarity, a first shiny).

## 2.30.27
- UPE: picked a new character by mistake? The UPE tab now has "Undo: go back to <your last run>", which puts the last run back with its points and items (while the new run has nothing scored yet).

## 2.30.26
- Fixed: the version at the top of the window (and in a few other places) still said 2.30.21 since 2.30.22. Everything says the real version again.

## 2.30.25
- New on Home: "Open White Bag Puppy when Windows starts". It puts a small script in your Windows Startup folder (no console window); untick it to remove it. Tick "Start watching when the app opens" too and the pup is ready by itself.

## 2.30.24
- UPE: if contest mode is on but no UPE character is picked (after a RIP, say), a UT/ST drop now gets a diary line saying it wasn't counted and how to fix it, instead of nothing.

## 2.30.23
- Fame Bonuses updates straight away: when a dungeon gets ticked (you finish it, or the game sends your completions as you load in) and when you switch characters, without having to change tabs.

## 2.30.22
- UPE: Elder Heartwood Staff (and its shiny) counts as tier 4 (10 points): it dismantles for 30/40. The game labels it tier 3.

## 2.30.21
- Biscuit links: if Biscuit is restarting when a bag or badge goes out, the pup keeps it (in the "outbox" folder, even if you close the pup) and sends it as soon as Biscuit is back. Kept for up to two days.
- UPE: old runs are corrected for the reskin rule once: a reskin saved separately from its original, or a reskin of tiered gear, has its extra points taken off, so your total matches Biscuit's.
- Alien invasions: the pup spots wormhole generators in the realm you're in by itself (the ones that come into view) and says which kinds in the diary; the Realms tab shows them too. With wormhole pings on, ticked kinds play a sound.

## 2.30.20
- UPE: forge blueprints that drop on your UPE character are logged (0 points): a line in the contest post ("Divinity Blueprint: blueprint, logged") and in the diary. A bag with only a blueprint still gets a contest post, so Biscuit can list it.

## 2.30.19
- UPE: a reskin counts as its original item (from RealmEye's "Reskin of" lines): Ice Crown and The Forgotten Crown, Stygian Torch and The Phylactery, Ring of Decades and The Forgotten Ring... Getting both scores once; a better rarity or a shiny adds the difference, like another copy.
- UPE: reskins of tiered gear (Cupid's Bow, Legion Elite, the Oryxmas and Halloween T6/T11 reskins...) score 0, like the tiered original.
- Old runs keep the points they already have here; Biscuit's scoreboard applies the new rule to everything.

## 2.30.18
- If you haven't set up a Biscuit link yet, the pup asks when it opens: type /pup link in the Femcore Discord, then press "Paste my link" (it fills in the link from your clipboard, saves it and sends a test post). "Later" asks again next time, "Don't ask again" stops it.

## 2.30.17
- Biscuit links: type /pup link in the Femcore Discord and paste the link Biscuit gives you where the webhook goes (Home > Settings). Biscuit then posts your bags and badges in the right channels, with your Discord name and picture. No webhooks to hand out or mix up. Discord webhooks still work as before.
- The UPE contest webhook stays a normal Discord webhook.

## 2.30.16
- Realms tab: What to watch lists every realm event from the start (the same list as the Events tab, like Bloodroot Heart), not only ones atti.network has shown so far. Realm events that aren't bosses (altars, nests...) count too, in Latest and the Events column.

## 2.30.15
- Realms tab works like the Events tab: a Latest list of realm bosses, invasion wormholes and realms about to close as they show up (only what you're watching, or everything), and What to watch with tick boxes for each wormhole kind, closing realms and every realm boss seen so far (with search and Tick all / Untick all).
- Pings (a sound if you like, and a diary line) for anything ticked that shows up in your region. Wormholes and closing realms start ticked, bosses unticked.

## 2.30.14
- Realms tab: when a region has nothing, it says atti.network isn't tracking realms there right now and lists the servers it does have.

## 2.30.13
- Realms tab updates live: it checks atti.network every 10 seconds while the tab is open (30 with it closed and wormhole pings on), and the "Updated 5s ago" counts up every second.

## 2.30.12
- Realms tab: no extra X-Client header (atti knows it's the pup).

## 2.30.11
- Realms tab: asks atti.network for invasions and bosses the way Chrome does on their Aliens page, as atti asked, so their site lets the pup in. It still says it's White Bag Puppy in its own X-Client header.

## 2.30.10
- Realms tab: alien invasions on every server, with the wormholes each realm has open (Forax ×5, Katalund ×2...) and the wave progress when atti.network knows it, plus a Bosses column with each realm's bosses and their HP. From atti.network, with atti's permission.
- New "Ping me when wormholes open": a sound and a diary line when an invasion's wormholes show up in a realm (in the region you picked). It keeps checking every 30 seconds while it's on.

## 2.30.9
- New Realms tab (next to Events): every open realm on every server, the ones closest to closing first, with players and how full it is, from atti.network's public realm tracker. Pick a region, and any events it lists show at the top. It only checks (every 30 seconds) while the tab is open.

## 2.30.8
- UPE: UTs the game gives no tier now use their RealmEye Dismantling Value. Tier 5 (15 points): Overclocking Amulet, Kogbold Cower Shield, Laser Pointer, Scepter of Rust, Solar Flare Igniter, Steam Pipe, Beekeeper's Flamethrower, Swarmlord's Sigil, Crystalline Sigil, Crystallized Worm Spellblade, Sinister Syringes, Icebreaker Maul, Rusted Mechanism. Tier 4 (10): Draconic Insignia, Adamantine Helm, Spirit's Bane, Morning Square, Sunken Fire Opal Ring, Sweetener Ring, Seismic Flail, Shard Launcher. Tier 3 (7): Occult Bow, Silken Sigil, Spider Silk Bow. Tier 1 (1): Sigil of the Rubber Duck. The rest keep the flat 7.
- Fixed: a no-tier UT scored by 2.30.7 could get its 7 points added a second time when the pup restarted.

## 2.30.7
- UPE: UT items the game gives no dismantling tier (about 60 drops, like Overclocking Amulet, Crystalline Sigil, Swarmlord's Sigil, Tectonus and Conqueror's Crown) now score a flat 7 like ST items, instead of 0. Runs that already have them get the missing points when the pup starts.
- UPE: Hawkmaster Sigil counts as Agents of Oryx (tier 4, 10 points).
- UPE: tier fixes also match items whose game name is a code (like "AoO Sigil"), by the name the game shows.
- The "can't see the game" check no longer lists the game's web connections (port 443) as if they could be the game server.

## 2.30.6
- UPE: Ring of Decades and The Forgotten Ring (and their shinies) count as dismantling tier 5 (15 points). The game labels them tier 4, which gave them only 10. Runs that already have them get the missing points when the pup starts.

## 2.30.5
- UPE: Amulet of Restoration (and its shiny) counts as dismantling tier 5 (15 points). The game labels it tier 2, which gave it only 5. Runs that already have it get the missing points when the pup starts.

## 2.30.4
- Can't see the game? If nothing from the game comes through 45 seconds after you press Start watching, the pup checks why and tells you in the diary: whether the game is running, which server and port it's connected to, and whether a VPN or game booster (ExitLag, NoPing, WARP and others) is hiding its traffic. It only reads Windows' list of programs and connections.

## 2.30.3
- Danger warnings only fire for monsters now: a pet (or anything else) named after a boss that's hurt you no longer sets them off ("Killer Bee Queen is here" in the Ice Citadel).

## 2.30.2
- UPE contest: forging tiered gear (a T6 ring, say) no longer scores a point: only UT and ST forges count.

## 2.30.1
- UPE tab: "Add a game picture to my RIP post" switches the RIP picture on or off.

## 2.30.0
- UPE tab: Mini UPE window, a small always-on-top box with your contest total and the last item that scored. Drag it anywhere; it remembers where.
- UPE tab: item check. Type an item's name to see what it would score on your character: new, what a better copy would add, or that your class can't wear it, plus what forging it is worth.
- UPE contest: the RIP post includes a picture of the game the moment your contest character died. Turn it on or off with "Add a game picture to my RIP post" on the UPE tab.

## 2.29.4
- The settings file is read correctly even if Notepad saved it with a byte-order mark or as "Unicode", and yes/no settings ignore extra spaces and capitals.

## 2.29.3
- UPE contest: a shiny's modification counts again, with the shiny's +4 on top (a shiny divine is 5 + 4 = 9).
- Beehemoth Armor shows its colour (Yellow, Red, Blue, Green Beehemoth Armor) and each colour is its own item. In the UPE contest each scores as tier 2 (5), matching what it dismantles into; the game labels them tier 3.
- UPE contest: upgrading an item you already scored to a better rarity now adds the difference automatically (uncommon to divine = +4), and posts it to the contest channel. Only the same item going up where it sits counts: a better copy arriving by trade doesn't.

## 2.29.2
- UPE contest: every Agents of Oryx item (weapons, abilities, armors and rings, UT or ST) score as tier 4 (10); ST ones no longer get the flat 7, matching what it dismantles into. Any ST item in contest.tiers now scores its tier instead of the flat 7 (at least 9 in the Shatters). Ones already scored are recounted when the pup starts.

## 2.29.1
- UPE contest: a forged item's modification counts now (forged items can roll one): a rare Demon Lord's Skull forged on a Necromancer is 1 + 10 + 2 = 13. A better modification forged later adds the difference.

## 2.29.0
- UPE contest: a UT or ST item you forge now scores like one that dropped (its tier, once per item, no place bonus), on top of the craft's 1 point (2 with Concentrated Soul Fire). Only items your class can wear count.

## 2.28.9
- UPE contest: items already scored with the old tier (Esben's Wedding Ring, Skullish Remains of Esben) are recounted when the pup starts: the missing points are added to your total once, and the diary says how many.

## 2.28.8
- UPE contest: Esben's Wedding Ring and Skullish Remains of Esben now score as tier 5 (15), matching what they dismantle into; the game labels them tier 3. More fixes can go in contest.tiers in the settings, like "Some Item=4; Other Item=5".
- UPE contest: drops on your contest character always count now, even when the pup thinks you're on "not your usual account" (after a name change, say).

## 2.28.7
- If the pup crashes while starting, it now shows a pop-up saying what went wrong (with a tip, like moving it out of a protected OneDrive or Desktop folder) and saves the details to crash-report.txt, instead of the window flashing and vanishing.
- If Windows starts the pup from a different folder (a shortcut, say), it restarts itself from its own folder so your settings and logs are found.

## 2.28.6
- UPE contest: every item you forge now scores, not just Concentrated Soul Fire crafts. A plain Forge craft is worth 1 (contest.points.forgeCraft), a Soul Fire craft 2, once per item and only if your class can wear it. Forging it again with Soul Fire adds +1. Dismantling doesn't count.

## 2.28.5
- A death message that can't be fully read (if the game changes its shape) is still passed on, so the UPE contest and the graveyard still see the death. The diary says when this happens.

## 2.28.4
- UPE contest: a death now ends the run even when the pup thinks you're on "not your usual account" (after a name change, say). Those account checks were quietly throwing the death away.
- The diary now says something for every death it sees: a RIP for your UPE character, or why the run keeps going.

## 2.28.3
- UPE contest: a shiny is +4 for being shiny, and its own modification no longer counts (a shiny legendary is +4, not +7). Normal copies still score their modification, and a better one later adds the difference.

## 2.28.2
- UPE contest: an item's best rarity and the shiny now count separately. A shiny copy adds its +4 the first time even if an earlier copy had a better rarity (divine, then a rare shiny = +4), and a shiny that's also a better rarity adds both.
- Runs scored the old way are fixed when the app starts: the missing points are added to your total (once) and the diary says how many.

## 2.28.1
- UPE contest: when your contest character dies, the run now always ends and the RIP total is posted, even if the game's death message carries a different character number.
- Backup: if your contest character's HP hits 0, the run ends and posts too (in case the death message is missed).

## 2.28.0

- UPE: new **Contest only** switch on the UPE tab. While it's on and you have a contest character, your normal bag posts to Discord take a break, so only the contest posts go out. The diary, sounds and bag log keep going. It turns itself off in effect when the contest character dies (normal posts come back).

## 2.27.2

- UPE: the **modification and shiny bonuses now add together** instead of only the bigger one counting. A shiny divine is +9 (divine 5 + shiny 4), a shiny legendary +7. Upgrades still add just the difference (a legendary Doom Bow, then a shiny legendary one, adds +4).

## 2.27.1

- UPE: **Neo Laser Rifle is +1** on top of its other points (first copy only), next to Neo Reality Reactor's +2.

## 2.27.0

- UPE: **Neo Reality Reactor is +2** on top of its other points (first copy only). Other items can get their own bonus with contest.points.itemBonus in the settings (like "Neo Reality Reactor=2; Some Item=1").
- UPE: **Forge crafts with Concentrated Soul Fire are worth 2 points**, if your class can wear the item (once per item). The pup sees the craft at the Forge, posts it to the contest channel and writes it in the diary.

## 2.26.0

- UPE: **only items your class can equip count**. A Shield of Ogmur on an Archer, or a bow on a Wizard, scores 0 ("can't be equipped by a Wizard (0)") and isn't marked as found, so it isn't posted. Rings count for everyone. The class's weapon, ability and armor slots come from the game files.

## 2.25.2

- UPE: **ST items are a flat 7 points** instead of their tier, and **9 from the Shatters** (that already includes the Shatters bonus, so it isn't added again). Modification or shiny bonuses still go on top, and Oryx's Sanctuary / Moonlight Village / Umi bonuses still apply to STs from those white bags.

## 2.25.1

- UPE: shiny items are now **+4** (was +5). The bigger-bonus rule still applies, so a divine (+5) now beats a shiny, and a divine copy after the shiny adds +1.

## 2.25.0

- **UPE points rework**: a new UT or ST from a **white bag** gets extra points for where it dropped:
  - Oryx's Sanctuary +1
  - Moonlight Village +1, or +2 if it's from Umi (the boss that died just before the bag)
  - The Shatters +2
  The bonus is on top of the tier and modification/shiny points, for the first copy of an item only (not repeats or upgrades). Bag posts show it, like "Doom Bow: tier 3 (7) + white from Oryx's Sanctuary (1) = 8".

## 2.24.6

- Fixed: the pup seeing your game (bags, posts, your character) but not linking it to your account, so Badges said "Start watching and load into the game once". If it can't spot your account in the game's messages after a short while of playing, it now uses the account it already has saved (when there's only one), and says so in the diary.
- New button on Me > Badges when it's empty: **Still empty? Write what the pup sees to the diary**. It writes a line about your accounts and game connections (no names or IDs, just short codes) to help find the cause.

## 2.24.5

- Fixed: the pup not recognising your account ("Start watching and load into the game once" on Badges, empty pages) when the game doesn't send your account ID with your character. It now also looks for it in later updates, and if the game still doesn't send it, it uses the account it already has saved (when there's just one, or with "I only play one account" on). The diary says when this happens.

## 2.24.4

- Fixed: **Me > Badges showing 0** (and other pages looking empty) when the pup opened straight onto them. Since 2.24.0 the pup reopens on the page you were last on, which drew before the game said whose account it is, and it didn't redraw after. Now every page draws again as soon as your account is recognised. No badges were ever lost.

## 2.24.3

- Fixed: badges missing after a name change. When an account has few badges but the pup has more saved under another account ID, **Me > Badges** now says so and has a **Move ...'s badges and stats here** button. It moves them straight onto the account you're looking at, and the badges show up right away (close and reopen the pup so bags and stats catch up too). A backup is made first.
- The Settings > Accounts tool now picks the account with the fewest badges as the one to move into when you're not in game.

## 2.24.2

- Fixed: **Find missing badges and data** said "load in first" unless the pup was watching your character right then. Now it works any time: pick the old data (the one with your badges) and the account to move it into. It suggests the account with the most badges and the newest account, so usually it's just OK.

## 2.24.1

- New **Home > Settings > Accounts > Find missing badges and data...**: if your badges, bags or stats look empty (after a name change, say), the pup may be seeing your account under a new account ID. This lists the older account data it has (with its name, badges, bags and when it was last seen) and moves the one you pick into the account you're playing. A backup is made first. Close and reopen the pup afterwards.

## 2.24.0

- **Fewer tabs**: 6 top tabs instead of 8, and about 22 pages instead of 37. Nothing was removed, things just moved:
  - **Home**: Watch (bags, start, diary) and Settings (all the webhooks, backups, updates, overlay, sounds and looks; the old Webhooks tab).
  - **Loot**: Bags (This session / All time), Stats, Collection (Items / Shinies / Dungeons / Holidays / Quest Chests).
  - **Fights**: DPS, Boss Kills, DPS Calc, Puzzle.
  - **Me**: Characters (This character / All characters, with the Graveyard and Close calls), Quests, Fame (Fame / Fame Bonuses), Vault, Badges (Badges / Titles / Wardrobe).
  - **Chat**: Chat, Trades, Players, Events.
  - **UPE**: the UPE contest has its own tab.
- The pup reopens on the tab and pages you were last on.
- **Search** (top right): type an item, dungeon or player and press Enter. It opens the bags you've had with it, the collection, or the player.
- The Chat tab's count only goes up for whispers, guild and party messages, not every message in public chat.

## 2.23.0

- **UPE contest** (Loot > UPE Contest): the same contest scoring as Loot Puppy. Tick Contest mode and press "Use the character I'm on now" while you're on your UPE character. Only UT and ST items count, for that character, from the moment they drop in your bag, until it dies.
  - Points: dismantling tier 5 = 15, 4 = 10, 3 = 7, 2 = 5, 1 = 1, no tier = 0, plus the bigger of the modification (uncommon +1, rare +2, legendary +3, divine +5) or shiny (+5).
  - Each item counts once. A later copy with a bigger bonus adds the difference (rare to legendary is +1). The shiny and normal version are the same item.
  - Each bag counts once, even if you reconnect into the same dungeon.
  - Put in a contest channel webhook to post each scoring drop there with its points and your total. Your normal bag posts keep going to your usual webhook.
  - When the character dies a "RIP" post goes up with the final total and best drops. "Post my contest total" posts it any time.
  - The page lists this run's items and points. Every scored drop is saved in upe-contest.txt.

## 2.22.1

- Fixed: the overlay's loot boost timer kept counting down in the Nexus. Like in game, the loot drop and loot tier boosts now pause in safe places (the Nexus, your vault, pet yard, guild hall, quest room...) and carry on when you leave. While paused the overlay says "(paused here)".

## 2.22.0

- **Close calls** (Me > Characters > Close calls): when your HP drops to 20% or less and you live, by healing back up or getting out to the Nexus, the puppy notes how low you got, what hit you last, where and when. The diary says "*phew* close call! Down to 9% HP from Septavius the Ghost God in Undead Lair".
- **Smarter danger warnings**: enemies that nearly killed you count too, not just the ones that killed you ("Careful: Septavius the Ghost God (killed you once, nearly killed you 2×)").
- **New item alert**: after a game update, the diary lists the new items it added (with UT/ST and ✨ for shinies), and every update's full list is saved in new-items.txt.
- **Shiny merge list**: the diary says which shinies the game files list more than once (and so were merged into one entry in the Collection), with their dungeons. Only shown when that list changes.

## 2.21.2

- Fixed: shiny items showing up twice in Loot > Collection > Shiny items (and Dungeons). The game files list some shinies more than once under different item numbers, or as "Shiny Coral Bow" as well as "Coral Bow Shiny". Each shiny is now one entry, and finding either copy ticks it off.
- Loot > Shinies counts "Different shinies" by name too, so the same shiny isn't counted twice.

## 2.21.1

- Fixed: new items from a game update showing as a **?** and "Item #44495". The puppy only read the game's files when it started, and only if you pressed Extract. Now it:
  - reads the new game files by itself when the game has updated (no more Extract/Ignore box, unless it can't find the game),
  - checks every 5 minutes while it's open, and straight away when an item it doesn't know turns up,
  - loads the new pictures without a restart, and redraws every page so the names and pictures appear.
- New **Re-read game files** button on Home (next to Check for updates), for doing it by hand.

## 2.21.0

- **Danger warnings**: when something that has killed one of your characters before (from the Graveyard) shows up, the puppy plays a warning sound, writes it in the diary, and the overlay flashes red with "Careful: Septavius the Ghost God (killed you 2×)" under the title. Once per enemy each time you enter a map. Turn it off on Home. Put your own sound in the sounds folder as danger.wav.
- **Shiny counter** (Loot > Shinies): every shiny that's dropped for you, how many different ones, this week and month, how often (1 in every N bags), the last one, where they dropped most, and the full list with dates.
- A sparkle sound when a shiny drops (after the bag's own sound), a gold flash on the overlay, and a "SHINY!" line in the diary. The sound can be turned off on Home; use shiny.wav for your own.
- New overlay row **Shinies**: "12 found · last: Doom Bow Shiny, 3d ago".
- The overlay's flash is now pink for a white bag, gold for a shiny and red for danger.

## 2.20.0

- **Graveyard** (Me > Characters > Graveyard): every character that dies while the puppy is watching, with what killed it, the dungeon it died in, when, its level and maxed stats, its fame (base, total and the death bonuses the game gave) and the gear and inventory it lost.
- Shows how many deaths and how much fame in total, and who has killed you the most.
- A character that dies now comes off the Characters list by itself (no more pressing Forget). Forget is still there for characters you delete.
- The diary gets a line when a character dies.
- Saved in deaths.txt next to the app. "Remove from graveyard" takes one off the list.

## 2.19.0

- **Where to farm it**: every Tinker quest now lists, for each item you still need, the dungeons it drops in (from the loot book) and where it's actually dropped for you before (from your loot history).
- **Daily reset countdown**: Me > Tinker Quests shows how long until the daily quests reset (midnight UTC, shown in your own time), and turns red once the reset has passed so you know to visit the Tinkerer for the new ones.
- Quests with their own end date (like event quests) show "Ends in 2d 4h".
- The mini overlay's Tinker quests row has a new "Daily reset" line with the countdown.

## 2.18.3

- Fixed: daily Tinker quests really are at the top now. Real quests don't have "Daily" in their names, so the puppy now sorts them the way the Tinkerer does (by the quest's category and whether it gives a Quest Chest): Daily Quest Chests, Daily Misc, Weekly, Permanent Account Rewards (like Celestial Stones), Exchange, Skin Exchange, then Other.
- The same order is used for pinned quests on the mini overlay.

## 2.18.2

- Me > Tinker Quests: daily quests are now always at the top, then weekly, then the rest.
- Pinned quests on the mini overlay are listed the same way, dailies first.

## 2.18.1

- Fixed: **Pin to overlay** on Me > Tinker Quests now actually shows up. Each pinned quest gets its own line on the mini overlay with the items you still need, or "Ready to hand in!" when it's done (up to 4).
- The overlay grows to fit when you pin or unpin a quest.

## 2.18.0

- **Fame calculator** (Me > Characters): each character shows about how much fame it would get if it died now (base
  fame plus the dungeon fame bonuses it's earned), and the closest bonus still to get, e.g. "Next bonus: Snake Pit
  finishes First Steps (+2.5% and +100 fame)".
- **Better Tinker Quests**: grouped by kind (Daily, Weekly...), "(ready to hand in!)" when you're carrying
  everything, a reminder at the top when quests are ready, **Hide** for quests you don't care about (shown again
  from the Hidden list), and **Pin to overlay**: when you pin quests, the overlay's Tinker quests row only counts those.
- **Vault filters**: show only Weapons, Abilities, Armor, Rings, Potions or Other, and/or only items **from a dungeon**
  ("shatters"), with a count of each kind.

## 2.17.4

- Widget bar: **forge materials use the game's colours and order** (blue crystal, red, gold, silver), so they match
  the forge screen. Hover the chip for each one's name and cap.

## 2.17.3

- Folded overlay: the **zone and timer are back up front**. They now take the big title line ("Snake Pit · 1:39"),
  with your name, damage and DPS underneath next to the green dot, so neither gets cut off.

## 2.17.2

- The folded overlay now shows your **name, total damage and DPS** next to the green dot, then the zone and timer:
  "Sophie · 30,400 dmg · 2,069/s · Snake Pit · 1:39" (the boss you're fighting, else the whole run). Same switch on
  Home and in the right-click menu to turn it off.

## 2.17.1

- **Your DPS on the folded overlay**: when the overlay is folded to just its title, your own damage per second shows
  next to the zone and timer, e.g. "Snake Pit · 1:39 · 2,069/s" (the boss you're fighting, else the whole run).
  Turn it on or off on Home ("Show my DPS next to the zone when the overlay is folded") or right-click the overlay.

## 2.17.0

- **Characters** (Me > Characters): every character the puppy has seen, Regular and Seasonal, with level, fame,
  stats (and how many are maxed), gear and inventory. **Forget this character** takes one off the list after it dies.
- **Vault chests**: Me > Vault now draws your vault (and gift chest and spoils) like the game does, as numbered
  8-slot chests with the empty slots and "3 free", so you can plan your storage without going in.
- **Loot History** (Loot > History): every bag you've ever looted, newest first. Search by item, dungeon or bag, and
  narrow it by bag colour and today / this week / this month.
- **Tinker quests on the overlay**: a new row with what you still need to bring, e.g. "3 to bring: Potion of Life ×2,
  Pumpkin Treat", or "Everything's ready to hand in!".
- Ideas from RealmHound's feature tour.

## 2.16.6

- **Live DPS on the overlay works on every boss now.** It only counted enemies the game files label as bosses (or
  with 10,000+ health), so many bosses, especially in easier dungeons, never showed up and it stayed on "No boss
  fight yet". When no labelled boss has been hit, it now shows the toughest enemy you've hit in the last 30 seconds.
- If the puppy hasn't seen any damage yet this run, it says "No damage seen yet this run" instead, so you can tell
  the two apart.

## 2.16.5

- **Live DPS on the overlay is now a top 3**: the boss you're fighting (or the last one), then the three biggest
  hitters with their damage per second and share. Your line is pink; if you're not in the top 3, it says where you
  are (e.g. "you: #4, 411/s").
- Turn it on or off with **Show live DPS (top 3) on the overlay** on Home, or right-click the overlay > Show.

## 2.16.4

- **Live DPS in the mini overlay**: two new rows, updated every second.
  - **Live DPS**: your damage per second on the boss you're fighting (or the last one this run), where you rank
    in the group and your share of the damage, e.g. "2,069/s #1/3 56% · Stheno the Snake Queen".
  - **Run DPS**: the same for the whole run so far, every enemy counted.
  Both are on for everyone; untick them under right-click > Show if you don't want them.

## 2.16.3

- **Tinker Quests** have their own tab now (Me > Tinker Quests) instead of hiding at the bottom of About Me. Open the
  Tinkerer's quest window in game once and your daily and weekly quests show up with what to bring (ticked off when
  you're carrying it) and the rewards. The puppy now **remembers them** after you close it, and says when they're
  from an earlier day.

## 2.16.2

- The **widget bar** no longer slides under the tabs: when the window's too narrow for every chip, it wraps onto a
  second line and makes room for it.
- **Mini overlay**: a new **Dust** row with your green, red and purple enchanting dust (on for everyone; untick it
  under right-click > Show if you don't want it).

## 2.16.1

- **Widget bar** under the header: little chips with your character (hover for its stats), account fame, gold, rank
  (a star in your rank colour), enchanting dust (green, red, purple), forge fire, forge materials and today's play
  time. Right-click it to choose which chips show, or turn it off on Home.
- **Vault** and Treasury were the same thing, so there's just one now: Me > **Vault** shows everything you own (vault,
  potion storage, gift chest, seasonal spoils and every character) with the search box.
- The Live Feed is gone from Events (back to just the realm events).

## 2.16.0

- **Treasury** (Me > Treasury, now Me > Vault): everything you own in one place: your vault, potion storage, gift chest, seasonal
  spoils and every character the puppy has seen, with a **search box** ("where's my Doom Bow?") that lists where
  each match is and how many.
- **DPS Calc** (Fights > DPS Calc): your weapon's damage per second at your ATT and DEX against enemy DEF 0 to 75,
  your other weapons (vault and characters) ranked against it, and any weapon in the game by name. Type your own
  ATT, DEX or enemy DEF to try other numbers.
- **Live Feed** (Events > Live Feed): one stream of bags, dungeon runs (in and out, with times), realm events you've
  ticked, party joins, chat pings and badges, newest first, with filters.
- Ideas and weapon formulas from RealmHound by Lovens & Pillar (MIT), credited in the README.

## 2.15.0

- **White Bag Pup is now White Bag Puppy!** New name in the app, the window title, the overlay and your Discord posts.
  The download is now **WhiteBagPuppy.jar** and the repo is **StupidSophie/WhiteBagPuppy**. Your settings, logs,
  badges and backups all carry over; nothing to redo. (If your pup updates itself, its file keeps its old name,
  which is fine. Rename it to WhiteBagPuppy.jar any time while it's closed.)

## 2.14.2

- **Overlay resizing** now does two things: drag the corner **sideways** to make the overlay wider or narrower (more
  room for the words, so long lines like the dry streak fit), and **up or down** to make everything bigger or
  smaller. Right-click > Normal size resets both.
- The dry streak row is shorter: "9 runs dry (all dungeons) · longest 30".

## 2.14.1

- The overlay's right-click menu now looks like the rest of the pup: a soft card with the pink tick boxes, split
  into **Show** (the rows) and **Options**, instead of the plain grey Windows menu. Click anywhere else (or press
  Esc) to close it.

## 2.14.0

- **Mini overlay, your way**: right-click it > **Rows** to pick what it shows. Two new rows:
  - **Dry streak**: runs without a white bag in the dungeon you're in (or all dungeons), and your longest.
  - **Loot boosts**: time left on your loot drop and loot tier boosts.
- **Flash on a white bag**: the overlay glows pink and the pup hops when a white bag drops (right-click to turn it
  off; turning it on shows a preview). The overlay's height follows how many rows you show.

## 2.13.3

- White Bag Puppy has a new home: **[StupidSophie/WhiteBagPuppy](https://github.com/StupidSophie/WhiteBagPuppy)**. Updates
  now come from here. If you're on an older version, download WhiteBagPuppy.jar from the latest release once and
  replace yours; after that it updates by itself.
