package whitebag;

import packets.data.enums.StatType;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static whitebag.AboutMe.heading;
import static whitebag.AboutMe.left;
import static whitebag.AboutMe.note;

/** Players (under Chat): who's in view on your map and what they're wearing, your party first. */
final class PlayersPage extends MoreTabs.Page {
    private static final StatType[] GEAR = {StatType.INVENTORY_0_STAT, StatType.INVENTORY_1_STAT, StatType.INVENTORY_2_STAT, StatType.INVENTORY_3_STAT};
    private final PlayersTracker tracker;

    PlayersPage(PlayersTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    void fill() {
        List<PlayersTracker.Player> all = tracker.players();
        List<PlayersTracker.Player> party = new ArrayList<>(), rest = new ArrayList<>();
        for (PlayersTracker.Player p : all) (p.party ? party : rest).add(p);
        String map = tracker.mapName();

        list.add(heading("your party", party.isEmpty() ? null : party.size() + " here"));
        gap(8);
        if (party.isEmpty()) {
            list.add(note(tracker.partySize() > 0
                    ? "Nobody from your party is in view right now. They show up here when they're on the same map as you."
                    : "Join a party in game and your party members' gear shows up here first when they're nearby."));
        } else {
            for (PlayersTracker.Player p : party) card(p);
        }

        gap(14);
        list.add(heading(map == null || map.isEmpty() ? "players nearby" : "players in " + map.toLowerCase(Locale.ROOT),
                rest.isEmpty() ? null : String.valueOf(rest.size())));
        gap(8);
        if (rest.isEmpty()) {
            list.add(note("Other players show up here with their gear once they're in view (the game only sends the people near you)."));
            return;
        }
        int shown = 0;
        for (PlayersTracker.Player p : rest) {
            if (shown++ >= 60) {
                list.add(note("...and " + (rest.size() - 60) + " more."));
                break;
            }
            card(p);
        }
    }

    private void card(PlayersTracker.Player p) {
        MeTracker.Thing t = p.thing;
        String cls = AboutMe.objectName(t.objectType, "Player");
        int stars = t.i(StatType.NUM_STARS_STAT, -1);
        int fame = t.i(StatType.CURR_FAME_STAT, -1);
        String guild = t.s(StatType.GUILD_NAME_STAT);
        String line2 = "Level " + t.i(StatType.LEVEL_STAT, 1) + " " + cls + (stars >= 0 ? "  ·  " + stars + " stars" : "");
        String line3 = (fame > 0 ? String.format(Locale.ROOT, "%,d fame", fame) : "")
                + (guild.isEmpty() ? "" : (fame > 0 ? "  ·  " : "") + "Guild: " + guild)
                + (p.party ? ((fame > 0 || !guild.isEmpty()) ? "  ·  " : "") + "in your party" : "");
        list.add(new AboutMe.Card(t.objectType, p.name(), line2, line3));
        gap(6);

        List<Integer> gear = new ArrayList<>();
        for (StatType s : GEAR) gear.add(t.i(s, -1));
        AboutMe.ItemStrip strip = new AboutMe.ItemStrip(gear, null);
        String[] data = t.s(StatType.UNIQUE_DATA_STRING).split(",", -1);
        strip.enchants = new Enchant[gear.size()];
        strip.enchantText = new String[gear.size()];
        for (int k = 0; k < gear.size(); k++) {
            if (gear.get(k) <= 0) continue;
            List<Integer> slots = Enchant.slots(k < data.length ? data[k] : null);
            strip.enchants[k] = slots == null ? Enchant.NONE : slots.size() >= 1 && slots.size() <= 4 ? Enchant.values()[slots.size()] : Enchant.NONE;
            if (slots == null || slots.isEmpty()) continue;
            StringBuilder sb = new StringBuilder();
            for (int id : slots) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(id < 0 ? "(empty slot)" : EnchantNames.line(id));
            }
            strip.enchantText[k] = sb.toString();
        }
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.add(strip);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        list.add(left(row));
        gap(2);
        List<Integer> worn = new ArrayList<>();
        for (int id : gear) if (id > 0) worn.add(id);
        list.add(note(worn.isEmpty() ? "Nothing equipped (or not sent yet)." : AboutMe.names(worn)));
        gap(14);
    }
}
