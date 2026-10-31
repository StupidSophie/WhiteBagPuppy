package whitebag;

import javax.swing.*;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import static whitebag.AboutMe.heading;
import static whitebag.AboutMe.left;
import static whitebag.AboutMe.note;
import static whitebag.AboutMe.small;

/** Quest Chests (under Loot): every reward chest you've opened and what came out, plus the bags in your vault. */
final class QuestChestPage extends MoreTabs.Page {
    private final Predicate<String> mine;

    QuestChestPage(Predicate<String> mine) {
        this.mine = mine;
    }

    private static String when(long s) {
        return new SimpleDateFormat("d MMM, h:mm a", Locale.ROOT).format(new Date(s * 1000L));
    }

    @Override
    void fill() {
        List<ChestLog.Opened> chests = new ArrayList<>();
        for (ChestLog.Opened o : ChestLog.all()) if (mine == null || mine.test(o.account)) chests.add(o);
        list.add(heading("quest chests", chests.isEmpty() ? null : chests.size() + (chests.size() == 1 ? " opened" : " opened")));
        gap(8);
        if (chests.isEmpty()) {
            list.add(note("Open a quest chest (or any reward chest) in your vault and what came out of it shows up here. "
                    + "It's kept in quest-chests.txt next to the app."));
        } else {
            // how many of each kind
            Map<String, Integer> kinds = new LinkedHashMap<>();
            for (ChestLog.Opened o : chests) kinds.merge(o.chestName(), 1, Integer::sum);
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : kinds.entrySet()) {
                if (sb.length() > 0) sb.append("   ");
                sb.append(e.getKey()).append(" x").append(e.getValue());
            }
            list.add(note(sb.toString()));
            gap(10);
            int shown = 0;
            for (int i = chests.size() - 1; i >= 0 && shown < 60; i--, shown++) {
                ChestLog.Opened o = chests.get(i);
                list.add(small(o.chestName() + "   " + when(o.at)));
                gap(4);
                for (int k = 0; k < o.items.size(); k += 8) {
                    JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
                    row.setOpaque(false);
                    row.add(new AboutMe.ItemStrip(o.items.subList(k, Math.min(o.items.size(), k + 8)), null));
                    row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
                    list.add(left(row));
                }
                gap(2);
                list.add(note(AboutMe.names(o.items)));
                gap(12);
            }
        }

        // bags that dropped in your vault (where chests and some rewards are opened)
        List<LootStats.Entry> vaultBags = new ArrayList<>();
        for (LootStats.Entry e : LootStats.all()) if ((mine == null || mine.test(e.account)) && RunLog.isVault(e.map)) vaultBags.add(e);
        gap(10);
        list.add(heading("bags in your vault", vaultBags.isEmpty() ? null : String.valueOf(vaultBags.size())));
        gap(8);
        if (vaultBags.isEmpty()) {
            list.add(note("Loot bags that drop in your vault show up here too."));
            return;
        }
        int shown = 0;
        for (int i = vaultBags.size() - 1; i >= 0 && shown < 30; i--, shown++) {
            LootStats.Entry e = vaultBags.get(i);
            list.add(small(e.type.displayName + " bag   " + when(e.at)));
            gap(4);
            AboutMe.ItemStrip s = new AboutMe.ItemStrip(e.items, null);
            Enchant[] en = new Enchant[e.items.size()];
            for (int k = 0; k < en.length; k++) {
                int r = k < e.rarity.size() ? e.rarity.get(k) : 0;
                en[k] = r >= 0 && r < Enchant.values().length ? Enchant.values()[r] : Enchant.NONE;
            }
            s.enchants = en;
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            row.setOpaque(false);
            row.add(s);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
            list.add(left(row));
            gap(10);
        }
    }
}
