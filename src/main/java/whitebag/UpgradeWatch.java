package whitebag;

import packets.data.StatData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Watches your character's slots (equipment, inventory, backpack) for an item going up in rarity where it sits:
 * the same item in the same slot, with more enchantment slots than before. Used by the UPE contest to add the
 * difference when you upgrade an item you already scored. A different item arriving in a slot (a trade, a pick-up)
 * is never an upgrade.
 */
final class UpgradeWatch {
    /** One upgrade: the item, its slot, and its rarity before and after. */
    static final class Upgrade {
        final int item, slot;
        final Enchant from, to;

        Upgrade(int item, int slot, Enchant from, Enchant to) {
            this.item = item;
            this.slot = slot;
            this.from = from;
            this.to = to;
        }
    }

    static final int SLOTS = 20;
    private static final int UNIQUE_DATA = 80;
    private final int[] items = new int[SLOTS];
    private final Enchant[] rarity = new Enchant[SLOTS];
    private int charId = -1;

    UpgradeWatch() {
        Arrays.fill(items, -2);
    }

    /** The stat for a slot: equipment and inventory 8-19, backpack 131-138. -1 if it isn't a slot stat. */
    static int slotOf(int statTypeNum) {
        if (statTypeNum >= 8 && statTypeNum <= 19) return statTypeNum - 8;
        if (statTypeNum >= 131 && statTypeNum <= 138) return 12 + statTypeNum - 131;
        return -1;
    }

    /** A new character: start over (the old one's slots mean nothing now). */
    synchronized void character(int charId) {
        if (charId == this.charId) return;
        this.charId = charId;
        Arrays.fill(items, -2);
        Arrays.fill(rarity, null);
    }

    /** Takes one stats update for your character; returns any upgrades it shows (usually none). */
    synchronized List<Upgrade> update(StatData[] stats) {
        if (stats == null) return java.util.Collections.emptyList();
        int[] before = items.clone();
        String unique = null;
        for (StatData s : stats) {
            if (s == null) continue;
            int slot = slotOf(s.statTypeNum);
            if (slot >= 0) items[slot] = s.statValue;
            else if (s.statTypeNum == UNIQUE_DATA && s.stringStatValue != null) unique = s.stringStatValue;
        }
        if (unique == null) {
            // an item moved without new rarity data: forget that slot's rarity so it isn't compared wrongly later
            for (int i = 0; i < SLOTS; i++) if (items[i] != before[i]) rarity[i] = null;
            return java.util.Collections.emptyList();
        }
        String[] parts = unique.split(",", -1);
        List<Upgrade> out = new ArrayList<>();
        for (int i = 0; i < SLOTS; i++) {
            Enchant now = i < parts.length ? Enchant.of(parts[i]) : Enchant.NONE;
            Enchant was = rarity[i];
            boolean sameItem = items[i] > 0 && items[i] == before[i];
            if (sameItem && was != null && now.ordinal() > was.ordinal()) out.add(new Upgrade(items[i], i, was, now));
            rarity[i] = items[i] > 0 ? now : null;
        }
        return out;
    }
}
