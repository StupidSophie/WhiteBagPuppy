package whitebag;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
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

/**
 * Vault (under Me; was "Treasury"): everything you own in one place (vault, potion storage, gift chest, seasonal spoils and every
 * character the pup has seen), with a search box: "where's my Doom Bow?". Idea from RealmHound's Treasury.
 */
final class TreasuryPage extends MoreTabs.Page {
    private final VaultStore vault;
    private final Predicate<String> mine;
    private final JTextField search = Pixel.field("", 22, "Search your items (e.g. doom bow)");
    private final JTextField fromDungeon = Pixel.field("", 16, "From dungeon (e.g. shatters)");
    private static final String[] KINDS = {"Everything", "Weapons", "Abilities", "Armor", "Rings", "Potions", "Other"};
    private int kind = 0;

    TreasuryPage(VaultStore vault, Predicate<String> mine) {
        this.vault = vault;
        this.mine = mine;
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setOpaque(false);
        top.setBorder(new EmptyBorder(0, 0, 10, 0));
        JPanel r1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        r1.setOpaque(false);
        r1.add(search);
        r1.add(fromDungeon);
        r1.setAlignmentX(LEFT_ALIGNMENT);
        JPanel r2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        r2.setOpaque(false);
        r2.add(new LogTabs.Segments(KINDS, 0, i -> {
            kind = i;
            changed();
        }));
        r2.setAlignmentX(LEFT_ALIGNMENT);
        top.add(r1);
        top.add(Box.createVerticalStrut(8));
        top.add(r2);
        add(top, BorderLayout.NORTH);
        fromDungeon.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { changed(); }
            public void removeUpdate(DocumentEvent e) { changed(); }
            public void changedUpdate(DocumentEvent e) { changed(); }
        });
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { changed(); }
            public void removeUpdate(DocumentEvent e) { changed(); }
            public void changedUpdate(DocumentEvent e) { changed(); }
        });
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) changed();
        });
    }

    /** One place things are kept, and what's there. */
    private static final class Place {
        final String name, sub;
        final List<Integer> items = new ArrayList<>();
        /** For the vault, gift chest and spoils: every slot in order, empty ones as -1, drawn as 8-slot chests. */
        int[] slots;

        Place(String name, String sub) {
            this.name = name;
            this.sub = sub;
        }
    }

    private static String when(long s) {
        return s <= 0 ? "" : "seen " + new SimpleDateFormat("d MMM, h:mm a", Locale.ROOT).format(new Date(s * 1000L));
    }

    private List<Place> places() {
        List<Place> out = new ArrayList<>();
        long at = vault.updatedAt();
        String seen = at > 0 ? when(at / (at > 100_000_000_000L ? 1000 : 1)) : "";
        int[] v = vault.vault(), pots = vault.potions();
        int used = 0;
        for (int id : v) if (id > 0) used++;
        add(out, new Place("Vault", (v.length > used ? (v.length - used) + " empty slots  ·  " : "") + seen), v);
        int potUsed = 0;
        for (int id : pots) if (id > 0) potUsed++;
        add(out, new Place("Potion storage", (vault.potionMax() > 0 ? potUsed + " / " + vault.potionMax() + "  ·  " : "") + seen), pots);
        add(out, new Place("Gift chest", seen), vault.gifts());
        add(out, new Place("Seasonal spoils", seen), vault.spoils());
        for (CharacterBags.Char c : CharacterBags.of(mine)) {
            String cls = AboutMe.objectName(c.classType, "Character");
            Place p = new Place((c.name.isEmpty() ? "" : c.name + "'s ") + cls + (c.seasonal ? " (seasonal)" : ""),
                    "#" + c.charId + "  ·  " + when(c.at));
            for (int id : c.gear) if (id > 0) p.items.add(id);
            for (int id : c.bags) if (id > 0) p.items.add(id);
            out.add(p);
        }
        return out;
    }

    private static void add(List<Place> out, Place p, int[] ids) {
        for (int id : ids) if (id > 0) p.items.add(id);
        if (!p.name.equals("Potion storage")) p.slots = ids;
        out.add(p);
    }

    /** Slots drawn like the game's storage chests: boxes of 8 (two rows of 4), numbered, side by side. */
    private JComponent chests(int[] slots) {
        JPanel wrap = new JPanel(new WidgetBar.WrapLayout(12, 10));
        wrap.setOpaque(false);
        for (int c = 0; c * 8 < slots.length; c++) {
            JPanel chest = new JPanel();
            chest.setLayout(new BoxLayout(chest, BoxLayout.Y_AXIS));
            chest.setOpaque(false);
            int free = 0;
            for (int k = 0; k < 2; k++) {
                List<Integer> row = new ArrayList<>();
                for (int i = c * 8 + k * 4; i < c * 8 + k * 4 + 4; i++) {
                    int id = i < slots.length ? slots[i] : -1;
                    if (id <= 0) free++;
                    row.add(id);
                }
                AboutMe.ItemStrip strip = new AboutMe.ItemStrip(row, null);
                strip.setAlignmentX(LEFT_ALIGNMENT);
                chest.add(strip);
            }
            JLabel label = new JLabel("Chest " + (c + 1) + (free == 8 ? "  ·  empty" : free > 0 ? "  ·  " + free + " free" : ""));
            label.setFont(Cute.font(Font.PLAIN, 11));
            label.setForeground(Theme.c(0x8A7F99));
            label.setAlignmentX(LEFT_ALIGNMENT);
            chest.add(Box.createVerticalStrut(2));
            chest.add(label);
            wrap.add(chest);
        }
        wrap.setAlignmentX(LEFT_ALIGNMENT);
        return wrap;
    }

    private static String name(int id) {
        try {
            return ItemInfo.of(id).name;
        } catch (Throwable e) {
            return "Item #" + id;
        }
    }

    @Override
    void fill() {
        List<Place> places = places();
        // ---- the kind and dungeon filters: only what matches, place by place, with totals
        String dq = fromDungeon.getText().trim().toLowerCase(Locale.ROOT);
        if (kind != 0 || !dq.isEmpty()) {
            int[] perKind = new int[KINDS.length];
            List<Place> kept = new ArrayList<>();
            int n = 0;
            for (Place p : places) {
                Place k = new Place(p.name, p.sub);
                for (int id : p.items) {
                    int kd = kindOf(id);
                    if (!dq.isEmpty() && !fromDungeon(id, dq)) continue;
                    perKind[kd]++;
                    if (kind != 0 && kd != kind) continue;
                    k.items.add(id);
                }
                n += k.items.size();
                if (!k.items.isEmpty()) kept.add(k);
            }
            list.add(heading(dq.isEmpty() ? KINDS[kind].toLowerCase(Locale.ROOT) : "from \"" + fromDungeon.getText().trim() + "\"",
                    String.format(Locale.ROOT, "%,d items", n)));
            gap(6);
            StringBuilder totals = new StringBuilder();
            for (int k = 1; k < KINDS.length; k++) {
                if (perKind[k] == 0) continue;
                if (totals.length() > 0) totals.append("   ·   ");
                totals.append(KINDS[k]).append(" ").append(perKind[k]);
            }
            if (totals.length() > 0) list.add(note(totals.toString()));
            gap(10);
            if (kept.isEmpty()) {
                list.add(note("Nothing like that in the places the puppy has seen."));
                return;
            }
            for (Place p : kept) {
                list.add(small(p.name + "   " + p.items.size() + (p.items.size() == 1 ? " item" : " items")));
                gap(4);
                for (int k = 0; k < p.items.size(); k += 12) {
                    JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
                    row.setOpaque(false);
                    row.add(new AboutMe.ItemStrip(p.items.subList(k, Math.min(p.items.size(), k + 12)), null));
                    row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
                    list.add(left(row));
                }
                gap(12);
            }
            return;
        }
        int total = 0;
        for (Place p : places) total += p.items.size();
        String q = search.getText().trim().toLowerCase(Locale.ROOT);

        if (!q.isEmpty()) {
            // ---- search: where each matching item is, and how many
            Map<String, Map<String, Integer>> found = new LinkedHashMap<>();
            Map<String, Integer> firstId = new LinkedHashMap<>();
            for (Place p : places) {
                for (int id : p.items) {
                    String n = name(id);
                    if (!n.toLowerCase(Locale.ROOT).contains(q)) continue;
                    found.computeIfAbsent(n, k -> new LinkedHashMap<>()).merge(p.name, 1, Integer::sum);
                    firstId.putIfAbsent(n, id);
                }
            }
            list.add(heading("search", found.isEmpty() ? null : found.size() + (found.size() == 1 ? " item" : " items")));
            gap(8);
            if (found.isEmpty()) {
                list.add(note("You don't have anything called \"" + search.getText().trim() + "\" in the places the pup has seen. "
                        + "It only knows your vault from the last time you opened it, and characters you've played while it was watching."));
                return;
            }
            for (Map.Entry<String, Map<String, Integer>> e : found.entrySet()) {
                JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
                row.setOpaque(false);
                java.util.List<Integer> one = new ArrayList<>();
                one.add(firstId.get(e.getKey()));
                row.add(new AboutMe.ItemStrip(one, null));
                StringBuilder where = new StringBuilder();
                int n = 0;
                for (Map.Entry<String, Integer> w : e.getValue().entrySet()) {
                    if (where.length() > 0) where.append(",  ");
                    where.append(w.getKey()).append(w.getValue() > 1 ? " ×" + w.getValue() : "");
                    n += w.getValue();
                }
                JLabel l = new JLabel("<html><b>" + esc(e.getKey()) + "</b>" + (n > 1 ? "  ×" + n : "") + "<br>" + esc(where.toString()) + "</html>");
                l.setFont(Cute.font(Font.PLAIN, 13));
                l.setForeground(Pixel.INK);
                row.add(l);
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
                list.add(left(row));
                gap(6);
            }
            return;
        }

        // ---- everything, place by place
        list.add(heading("vault", total == 0 ? null : String.format(Locale.ROOT, "%,d items", total)));
        gap(8);
        list.add(note("Everything you own, from what the puppy has seen: your vault the last time you went in, and each "
                + "character the last time you played it while the puppy was watching. Search above to find an item."));
        gap(12);
        // ---- stat potions across the vault, potion storage and gift chest
        if (vault.updatedAt() > 0) {
            String[] stats = {"Life", "Mana", "Attack", "Defense", "Speed", "Dexterity", "Vitality", "Wisdom"};
            int[] normal = new int[8], greater = new int[8];
            for (int[] where : new int[][]{vault.vault(), vault.potions(), vault.gifts()}) {
                for (int id : where) {
                    if (id <= 0) continue;
                    String n = name(id);
                    for (int k = 0; k < 8; k++) {
                        if (n.equalsIgnoreCase("Potion of " + stats[k])) normal[k]++;
                        else if (n.equalsIgnoreCase("Greater Potion of " + stats[k])) greater[k]++;
                    }
                }
            }
            list.add(small("Stat potions (vault, potion storage and gift chest)"));
            gap(6);
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{"Potion", "Normal", "Greater"});
            for (int k = 0; k < 8; k++) rows.add(new String[]{stats[k], String.valueOf(normal[k]), String.valueOf(greater[k])});
            list.add(table(rows));
            gap(14);
        }
        boolean any = false;
        for (Place p : places) {
            if (p.items.isEmpty()) continue;
            any = true;
            list.add(small(p.name + "   " + p.items.size() + (p.items.size() == 1 ? " item" : " items")
                    + (p.sub.isEmpty() ? "" : "   ·   " + p.sub)));
            gap(4);
            if (p.slots != null && p.slots.length > 0) {
                list.add(chests(p.slots));
                gap(8);
                continue;
            }
            for (int k = 0; k < p.items.size(); k += 12) {
                JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
                row.setOpaque(false);
                row.add(new AboutMe.ItemStrip(p.items.subList(k, Math.min(p.items.size(), k + 12)), null));
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
                list.add(left(row));
            }
            gap(14);
        }
        if (!any) list.add(note("Open your vault in game, or load in on a character, and your things show up here."));
    }

    /** Which of the KINDS an item is: 1 weapons, 2 abilities, 3 armor, 4 rings, 5 potions and consumables, 6 other. */
    static int kindOf(int id) {
        ItemXml.Info x = ItemXml.of(id);
        if (x == null) return 6;
        if (x.potion || x.consumable || x.slotType == 10) return 5;
        if (WeaponDps.isWeaponSlot(x.slotType)) return 1;
        if (x.slotType == 6 || x.slotType == 7 || x.slotType == 14) return 3;
        if (x.slotType == 9) return 4;
        if (x.slotType > 0) return 2;
        return 6;
    }

    /** True if the Collection says this item drops in a dungeon whose name has these words. */
    private static boolean fromDungeon(int id, String q) {
        LootBook.Item it = LootBook.byName(name(id));
        if (it == null) return false;
        for (String place : it.places) if (place.toLowerCase(Locale.ROOT).contains(q)) return true;
        return false;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
