package whitebag;

import javax.swing.*;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import static whitebag.AboutMe.heading;
import static whitebag.AboutMe.left;
import static whitebag.AboutMe.note;
import static whitebag.AboutMe.small;

/**
 * Characters (under Me): every character the puppy has seen, Regular and Seasonal, with gear, inventory, stats and
 * how close each is to maxed. Idea from RealmHound's Characters page.
 */
final class CharactersPage extends MoreTabs.Page {
    private static final String[] NAMES = {"HP", "MP", "ATT", "DEF", "SPD", "DEX", "VIT", "WIS"};
    private final Predicate<String> mine;

    /** 0 = your characters, 1 = the graveyard. */
    private int mode = 0;

    CharactersPage(Predicate<String> mine) {
        this.mine = mine;
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        top.setOpaque(false);
        top.setBorder(new javax.swing.border.EmptyBorder(0, 0, 10, 0));
        top.add(new LogTabs.Segments(new String[]{"Characters", "Graveyard", "Close calls"}, 0, i -> {
            mode = i;
            changed();
        }));
        add(top, BorderLayout.NORTH);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) changed();
        });
    }

    @Override
    void fill() {
        if (mode == 1) {
            graveyard();
            return;
        }
        if (mode == 2) {
            closeCalls();
            return;
        }
        List<CharacterBags.Char> all = CharacterBags.of(mine);
        List<CharacterBags.Char> regular = new ArrayList<>(), seasonal = new ArrayList<>();
        for (CharacterBags.Char c : all) (c.seasonal ? seasonal : regular).add(c);
        list.add(heading("characters", all.isEmpty() ? null : all.size() + (all.size() == 1 ? " character" : " characters")));
        gap(8);
        if (all.isEmpty()) {
            list.add(note("Load in on each of your characters once while the puppy is watching and they all show up here, "
                    + "with their gear, inventory and how close they are to maxed."));
            return;
        }
        list.add(note("Each character as the puppy last saw it. Characters that die while the puppy is watching move to the Graveyard by themselves; if you deleted one, press Forget under it."));
        section("regular", regular);
        section("seasonal", seasonal);
    }

    private void section(String title, List<CharacterBags.Char> chars) {
        if (chars.isEmpty()) return;
        gap(16);
        list.add(heading(title, String.valueOf(chars.size())));
        gap(8);
        for (CharacterBags.Char c : chars) {
            String cls = AboutMe.objectName(c.classType, "Character");
            int[] max = ClassMax.of(c.classType);
            int maxed = 0;
            boolean known = c.stats.length == 8 && c.stats[0] >= 0;
            if (known && max != null) for (int k = 0; k < 8; k++) if (max[k] > 0 && c.stats[k] >= max[k]) maxed++;
            String line2 = (c.level > 0 ? "Level " + c.level + " " : "") + cls
                    + (known && max != null ? "  ·  " + maxed + "/8 maxed" : "");
            String line3 = (c.fame >= 0 ? String.format(Locale.ROOT, "%,d fame  ·  ", c.fame) : "")
                    + "seen " + new SimpleDateFormat("d MMM, h:mm a", Locale.ROOT).format(new Date(c.at * 1000L));
            list.add(new AboutMe.Card(c.classType, (c.name.isEmpty() ? cls : c.name) + "  #" + c.charId, line2, line3));
            gap(6);
            // fame if it died now (with the dungeon bonuses the puppy knows about), and the next bonus to get
            if (c.fame >= 0) {
                FameDungeons.Char fd = FameDungeons.get(c.account, c.charId);
                double[] got = FameDungeons.earned(fd);
                long total = Math.round(c.fame * (1 + got[0] / 100.0) + got[1]);
                String bonus = got[0] > 0 || got[1] > 0 ? String.format(Locale.ROOT, "  (%,d base + %s%% and %,d from dungeon bonuses)",
                        c.fame, got[0] == Math.rint(got[0]) ? String.valueOf((int) got[0]) : String.valueOf(got[0]), Math.round(got[1])) : "";
                JLabel fame = new JLabel("<html><b>If it died now:</b> about " + String.format(Locale.ROOT, "%,d", total) + " fame" + bonus + "</html>");
                fame.setFont(Cute.font(Font.PLAIN, 12));
                fame.setForeground(Pixel.INK);
                fame.setToolTipText("Base fame plus the dungeon fame bonuses on Me > Fame > Fame Bonuses. Other death bonuses (like Well Equipped or Thirsty) aren't counted.");
                list.add(left(fame));
                String next = FameDungeons.nextBonus(fd);
                if (next != null) {
                    gap(2);
                    list.add(note("Next bonus: " + next));
                }
                gap(6);
            }
            if (known) {
                // e.g. "ATT 70/75   DEF 25 MAX ..."
                StringBuilder sb = new StringBuilder("<html>");
                for (int k = 0; k < 8; k++) {
                    boolean full = max != null && max[k] > 0 && c.stats[k] >= max[k];
                    String v = full ? "<b><span style='color:" + Theme.hex(0x1F8A72) + "'>" + c.stats[k] + " MAX</span></b>"
                            : c.stats[k] + (max != null && max[k] > 0 ? "/" + max[k] : "");
                    sb.append("<b>").append(NAMES[k]).append("</b>&nbsp;").append(v).append(k == 3 ? "<br>" : "&nbsp;&nbsp;&nbsp;&nbsp;");
                }
                JLabel stats = new JLabel(sb.append("</html>").toString());
                stats.setFont(Cute.font(Font.PLAIN, 12));
                stats.setForeground(Pixel.INK);
                list.add(left(stats));
                gap(6);
            }
            list.add(small("Equipped"));
            gap(3);
            List<Integer> gear = new ArrayList<>();
            for (int id : c.gear) gear.add(id);
            list.add(strip(gear));
            List<Integer> bag = new ArrayList<>();
            for (int id : c.bags) if (id > 0) bag.add(id);
            if (!bag.isEmpty()) {
                gap(6);
                list.add(small("Inventory and backpack  (" + bag.size() + ")"));
                gap(3);
                for (int k = 0; k < bag.size(); k += 12) list.add(strip(bag.subList(k, Math.min(bag.size(), k + 12))));
            }
            gap(6);
            Pixel.Button forget = new Pixel.Button("Forget this character", false);
            forget.addActionListener(e -> {
                CharacterBags.forget(c.account, c.charId);
                changed();
            });
            JPanel fr = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            fr.setOpaque(false);
            fr.add(forget);
            fr.setMaximumSize(new Dimension(Integer.MAX_VALUE, fr.getPreferredSize().height));
            list.add(left(fr));
            gap(18);
        }
    }

    /** Every time your HP got really low but you lived. */
    private void closeCalls() {
        List<CloseCalls.Call> all = CloseCalls.of(mine);
        list.add(heading("close calls", all.isEmpty() ? null : all.size() + (all.size() == 1 ? " close call" : " close calls")));
        gap(8);
        if (all.isEmpty()) {
            list.add(note("No close calls yet. When your HP drops to " + Math.round(CloseCalls.LOW * 100) + "% or less and you live "
                    + "(you heal up, or get out to the Nexus), it's noted here with what hit you. Danger warnings then warn you about "
                    + "those enemies too, not just the ones that killed you."));
            return;
        }
        java.util.Map<String, Integer> by = new java.util.HashMap<>();
        for (CloseCalls.Call c : all) if (c.by != null && !c.by.isEmpty()) by.merge(c.by, 1, Integer::sum);
        if (!by.isEmpty() && all.size() > 1) {
            List<java.util.Map.Entry<String, Integer>> k = new ArrayList<>(by.entrySet());
            k.sort((a, b) -> b.getValue() - a.getValue());
            StringBuilder sb = new StringBuilder("Nearly got you the most: ");
            for (int i = 0; i < Math.min(3, k.size()); i++)
                sb.append(i == 0 ? "" : ", ").append(k.get(i).getKey()).append(" ×").append(k.get(i).getValue());
            list.add(note(sb.toString()));
            gap(10);
        }
        SimpleDateFormat date = new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ROOT);
        for (CloseCalls.Call c : all) {
            String cls = AboutMe.objectName(c.classType, "Character");
            String title = (c.name == null || c.name.isEmpty() ? cls : c.name) + "  ·  " + c.percent() + "% HP";
            String line2 = (c.hp >= 0 && c.maxHp > 0 ? String.format(Locale.ROOT, "%,d of %,d HP", c.hp, c.maxHp) : "")
                    + (c.by == null || c.by.isEmpty() ? "" : "  ·  hit by " + c.by)
                    + (c.escaped ? "  ·  got out of there" : "  ·  healed up");
            String line3 = (c.where.isEmpty() ? "" : "in " + c.where + ", ") + date.format(new Date(c.at * 1000L));
            list.add(new AboutMe.Card(c.classType, title, line2, line3));
            gap(4);
            Pixel.Button rm = new Pixel.Button("Remove", false);
            rm.addActionListener(e -> {
                CloseCalls.remove(c);
                changed();
            });
            JPanel fr = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            fr.setOpaque(false);
            fr.add(rm);
            fr.setMaximumSize(new Dimension(Integer.MAX_VALUE, fr.getPreferredSize().height));
            list.add(left(fr));
            gap(14);
        }
    }

    /** Every character that died while the puppy was watching. */
    private void graveyard() {
        List<DeathLog.Death> all = DeathLog.of(mine);
        long fame = 0;
        for (DeathLog.Death d : all) fame += Math.max(0, d.totalFame);
        list.add(heading("graveyard", all.isEmpty() ? null : all.size() + (all.size() == 1 ? " death" : " deaths")
                + (fame > 0 ? String.format(Locale.ROOT, "  ·  %,d fame", fame) : "")));
        gap(8);
        if (all.isEmpty()) {
            list.add(note("No deaths yet. If a character dies while the puppy is watching, it's remembered here: what killed it, "
                    + "where, its fame and the gear it had on. It also comes off your Characters list by itself."));
            return;
        }
        // who's killed you the most
        java.util.Map<String, Integer> killers = new java.util.HashMap<>();
        for (DeathLog.Death d : all) if (!d.killedBy.isEmpty()) killers.merge(d.killedBy, 1, Integer::sum);
        if (killers.size() > 0 && all.size() > 1) {
            List<java.util.Map.Entry<String, Integer>> k = new ArrayList<>(killers.entrySet());
            k.sort((a, b) -> b.getValue() - a.getValue());
            StringBuilder sb = new StringBuilder("Killed you the most: ");
            for (int i = 0; i < Math.min(3, k.size()); i++)
                sb.append(i == 0 ? "" : ", ").append(k.get(i).getKey()).append(" ×").append(k.get(i).getValue());
            list.add(note(sb.toString()));
            gap(10);
        }
        SimpleDateFormat date = new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ROOT);
        for (DeathLog.Death d : all) {
            String cls = AboutMe.objectName(d.classType, "Character");
            int[] max = ClassMax.of(d.classType);
            int maxed = 0;
            boolean known = d.stats != null && d.stats.length == 8 && d.stats[0] >= 0;
            if (known && max != null) for (int k = 0; k < 8; k++) if (max[k] > 0 && d.stats[k] >= max[k]) maxed++;
            String title = (d.name == null || d.name.isEmpty() ? cls : d.name) + (d.seasonal ? "  (seasonal)" : "");
            String line2 = (d.level > 0 ? "Level " + d.level + " " : "") + cls + (known && max != null ? "  ·  " + maxed + "/8 maxed" : "")
                    + (d.killedBy.isEmpty() ? "" : "  ·  killed by " + d.killedBy);
            String line3 = (d.where.isEmpty() ? "" : "in " + d.where + ", ") + date.format(new Date(d.at * 1000L))
                    + (d.lastHits == null || d.lastHits.isEmpty() ? "" : "  ·  last hits: " + d.lastHits);
            list.add(new AboutMe.Card(d.classType, title, line2, line3));
            gap(6);
            if (d.totalFame >= 0 || d.baseFame >= 0) {
                String f = d.totalFame >= 0 && d.baseFame >= 0 && d.totalFame != d.baseFame
                        ? String.format(Locale.ROOT, "<b>Fame:</b> %,d total  (%,d base + %,d from death bonuses)", d.totalFame, d.baseFame, d.totalFame - d.baseFame)
                        : String.format(Locale.ROOT, "<b>Fame:</b> %,d", Math.max(d.totalFame, d.baseFame));
                JLabel fl = new JLabel("<html>" + f + "</html>");
                fl.setFont(Cute.font(Font.PLAIN, 12));
                fl.setForeground(Pixel.INK);
                list.add(left(fl));
                gap(2);
            }
            if (d.bonuses != null && !d.bonuses.isEmpty()) {
                list.add(note("Bonuses: " + String.join(", ", d.bonuses)));
                gap(2);
            }
            List<Integer> gear = new ArrayList<>();
            if (d.gear != null) for (int id : d.gear) gear.add(id);
            if (!gear.isEmpty()) {
                gap(4);
                list.add(small("Lost with it"));
                gap(3);
                list.add(strip(gear));
                List<Integer> bag = new ArrayList<>();
                if (d.bags != null) for (int id : d.bags) if (id > 0) bag.add(id);
                if (!bag.isEmpty()) {
                    gap(3);
                    for (int k = 0; k < bag.size(); k += 12) list.add(strip(bag.subList(k, Math.min(bag.size(), k + 12))));
                }
            }
            gap(6);
            Pixel.Button rm = new Pixel.Button("Remove from graveyard", false);
            rm.addActionListener(e -> {
                DeathLog.remove(d);
                changed();
            });
            JPanel fr = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            fr.setOpaque(false);
            fr.add(rm);
            fr.setMaximumSize(new Dimension(Integer.MAX_VALUE, fr.getPreferredSize().height));
            list.add(left(fr));
            gap(18);
        }
    }

    private static JComponent strip(List<Integer> ids) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.add(new AboutMe.ItemStrip(ids, null));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return left(row);
    }
}
