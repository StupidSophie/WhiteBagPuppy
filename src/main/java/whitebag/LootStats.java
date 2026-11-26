package whitebag;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every loot bag seen (all colours, not just the ones you get pinged for), kept in loot-log.txt
 * next to the jar so the Loot tab can show session and all-time numbers.
 */
final class LootStats {
    private LootStats() {
    }

    static final class Entry {
        long at; // Unix seconds
        BagType type;
        /** Account id ("" for bags logged before accounts were tracked). */
        String account = "";
        String map = "";
        final List<Integer> items = new ArrayList<>();
        /** Each item's rarity (enchantment slots, 0-4), same order as items; may be shorter for old lines. */
        final List<Integer> rarity = new ArrayList<>();
        /** Who looted it (class object type, character id; -1 for older bags) and which enemy dropped it ("" unknown). */
        int charClass = -1, charId = -1;
        String enemy = "";
        /** Whether the looting character was seasonal: 1 yes, 0 no, -1 not recorded (bags from before 2.30.54). */
        int seasonal = -1;

        String line() {
            StringBuilder sb = new StringBuilder();
            for (int id : items) {
                if (sb.length() > 0) sb.append(',');
                sb.append(id);
            }
            StringBuilder rs = new StringBuilder();
            for (int r : rarity) {
                if (rs.length() > 0) rs.append(',');
                rs.append(r);
            }
            return at + "|" + type.name() + "|" + map.replace('|', '/') + "|" + sb + "|" + account.replace('|', '/') + "|" + rs
                    + "|" + charClass + "|" + enemy.replace('|', '/').replace('\n', ' ') + "|" + charId + "|" + seasonal;
        }

        static Entry parse(String l) {
            String[] f = l.split("\\|", -1);
            if (f.length < 4) return null;
            try {
                Entry e = new Entry();
                e.at = Long.parseLong(f[0]);
                e.type = BagType.valueOf(f[1]);
                e.map = f[2];
                for (String id : f[3].split(",")) if (!id.trim().isEmpty()) e.items.add(Integer.parseInt(id.trim()));
                if (f.length >= 5) e.account = f[4];
                if (f.length >= 6) for (String r : f[5].split(",")) if (!r.trim().isEmpty()) e.rarity.add(Integer.parseInt(r.trim()));
                if (f.length >= 7 && !f[6].trim().isEmpty()) e.charClass = Integer.parseInt(f[6].trim());
                if (f.length >= 8) e.enemy = f[7];
                if (f.length >= 9 && !f[8].trim().isEmpty()) e.charId = Integer.parseInt(f[8].trim());
                if (f.length >= 10 && !f[9].trim().isEmpty()) e.seasonal = Integer.parseInt(f[9].trim());
                return e;
            } catch (Exception ex) {
                return null;
            }
        }
    }

    private static final String FILE = "loot-log.txt";
    /** App start, in Unix seconds: bags after this count as "this session". */
    static final long SESSION_START = System.currentTimeMillis() / 1000L;
    private static List<Entry> entries;

    static synchronized List<Entry> all() {
        if (entries == null) {
            entries = new ArrayList<>();
            try {
                for (String l : Files.readAllLines(Paths.get(FILE), StandardCharsets.UTF_8)) {
                    Entry e = Entry.parse(l);
                    if (e != null) entries.add(e);
                }
            } catch (Exception ignored) {
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    static synchronized void add(BagDrop drop, String account) {
        all();
        Entry e = new Entry();
        e.account = account == null ? "" : account;
        e.at = drop.droppedAt;
        e.type = drop.type;
        e.map = drop.mapName == null ? "" : drop.mapName;
        e.charClass = drop.charClass;
        e.charId = drop.charId;
        e.seasonal = drop.seasonal;
        e.enemy = drop.enemy == null ? "" : drop.enemy;
        for (ItemInfo i : drop.items()) {
            e.items.add(i.id);
            e.rarity.add(i.enchant.ordinal());
        }
        entries.add(e);
        try {
            Files.write(Paths.get(FILE), Collections.singletonList(e.line()), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    /**
     * Whether this bag was looted by a seasonal character. Bags from 2.30.54 on say so themselves; for older ones it's
     * worked out from the character the pup last saw with that id (false if it never saw it).
     */
    static boolean seasonal(Entry e) {
        if (e.seasonal >= 0) return e.seasonal == 1;
        if (e.charId < 0) return false;
        CharacterBags.Char c = CharacterBags.get(e.account, e.charId);
        return c != null && c.seasonal;
    }

    /** The bag colour without "Boosted", so a boosted white counts as a white. */
    static BagType colour(BagType t) {
        switch (t) {
            case BOOSTED_WHITE: return BagType.WHITE;
            case BOOSTED_RED: return BagType.RED;
            case BOOSTED_ORANGE: return BagType.ORANGE;
            case BOOSTED_GOLD: return BagType.GOLD;
            case BOOSTED_EGG: return BagType.EGG;
            case BOOSTED_BLUE: return BagType.BLUE;
            case BOOSTED_CYAN: return BagType.CYAN;
            default: return t;
        }
    }
}
