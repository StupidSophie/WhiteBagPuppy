package whitebag;

import packets.data.enums.StatType;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static whitebag.AboutMe.heading;
import static whitebag.AboutMe.left;
import static whitebag.AboutMe.note;
import static whitebag.AboutMe.small;

/**
 * DPS Calc (under Fights): your weapon's theoretical damage per second from your ATT and DEX against enemies with
 * different DEF, how your other weapons compare, and any weapon in the game by name. Idea from RealmHound.
 */
final class DpsCalcPage extends MoreTabs.Page {
    private static final int[] DEFS = {0, 10, 25, 50, 75};
    private final MeTracker me;
    private final VaultStore vault;
    private final java.util.function.Predicate<String> mine;
    private final JTextField att = Pixel.field("", 3, "ATT"), dex = Pixel.field("", 3, "DEX"), def = Pixel.field("0", 3, "DEF");
    private final JTextField compare = Pixel.field("", 18, "Compare any weapon by name");
    /** True while the ATT/DEX boxes follow your character (until you type in them). */
    private boolean auto = true, filling;

    DpsCalcPage(MeTracker me, VaultStore vault, java.util.function.Predicate<String> mine) {
        this.me = me;
        this.vault = vault;
        this.mine = mine;
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setOpaque(false);
        top.setBorder(new EmptyBorder(0, 0, 10, 0));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.setOpaque(false);
        row.add(label("ATT"));
        row.add(att);
        row.add(Box.createHorizontalStrut(6));
        row.add(label("DEX"));
        row.add(dex);
        row.add(Box.createHorizontalStrut(6));
        row.add(label("Enemy DEF"));
        row.add(def);
        row.add(Box.createHorizontalStrut(6));
        Pixel.Button useMine = new Pixel.Button("Use my character", false);
        useMine.addActionListener(e -> {
            auto = true;
            changed();
        });
        row.add(useMine);
        row.setAlignmentX(LEFT_ALIGNMENT);
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row2.setOpaque(false);
        row2.add(compare);
        row2.setAlignmentX(LEFT_ALIGNMENT);
        top.add(row);
        top.add(Box.createVerticalStrut(8));
        top.add(row2);
        add(top, BorderLayout.NORTH);
        DocumentListener typed = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { typed(); }
            public void removeUpdate(DocumentEvent e) { typed(); }
            public void changedUpdate(DocumentEvent e) { typed(); }
        };
        att.getDocument().addDocumentListener(typed);
        dex.getDocument().addDocumentListener(typed);
        DocumentListener refresh = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { changed(); }
            public void removeUpdate(DocumentEvent e) { changed(); }
            public void changedUpdate(DocumentEvent e) { changed(); }
        };
        def.getDocument().addDocumentListener(refresh);
        compare.getDocument().addDocumentListener(refresh);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) changed();
        });
    }

    private void typed() {
        if (filling) return;
        auto = false;
        changed();
    }

    private static JLabel label(String s) {
        JLabel l = new JLabel(s);
        l.setFont(Cute.font(Font.BOLD, 13));
        l.setForeground(Pixel.INK);
        return l;
    }

    private static int num(JTextField f, int fallback) {
        try {
            return Integer.parseInt(f.getText().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%,.0f", v);
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
        MeTracker.Thing c = me.character();
        if (auto && c != null) {
            filling = true;
            att.setText(String.valueOf(Math.max(0, c.i(StatType.ATTACK_STAT, 0))));
            dex.setText(String.valueOf(Math.max(0, c.i(StatType.DEXTERITY_STAT, 0))));
            filling = false;
        }
        int a = num(att, 50), d = num(dex, 50), enemy = Math.max(0, num(def, 0));
        int exalt = c == null ? 0 : Math.max(0, c.i(StatType.EXALTATION_BONUS_DAMAGE, 0));
        WeaponDps weapon = c == null ? null : WeaponDps.of(c.i(StatType.INVENTORY_0_STAT, -1));

        // ---- your weapon
        list.add(heading("your weapon", c == null ? null : AboutMe.objectName(c.objectType, "")));
        gap(8);
        if (weapon == null) {
            list.add(note(c == null ? "Load in on a character and your weapon's damage shows up here. You can still compare weapons below."
                    : "No weapon equipped, or the pup couldn't read its stats from the game files yet."));
        } else {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
            row.setOpaque(false);
            List<Integer> one = new ArrayList<>();
            one.add(weapon.id);
            row.add(new AboutMe.ItemStrip(one, null));
            JLabel l = new JLabel("<html><b>" + esc(name(weapon.id)) + "</b><br>" + esc(weapon.damageText()) + "  ·  "
                    + String.format(Locale.ROOT, "%.2f", weapon.shotsPerSecond(d)) + " shots a second</html>");
            l.setFont(Cute.font(Font.PLAIN, 13));
            l.setForeground(Pixel.INK);
            row.add(l);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
            list.add(left(row));
            gap(8);
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{"Enemy DEF", "DPS"});
            boolean mineShown = false;
            for (int x : DEFS) {
                if (x == enemy) mineShown = true;
                rows.add(new String[]{x == enemy ? x + "  (yours)" : String.valueOf(x), fmt(weapon.dps(a, d, exalt, x))});
            }
            if (!mineShown) rows.add(new String[]{enemy + "  (yours)", fmt(weapon.dps(a, d, exalt, enemy))});
            list.add(table(rows));
        }
        gap(6);
        list.add(note("Damage per second if every shot hits, at ATT " + a + " and DEX " + d
                + (exalt > 0 ? " with your exaltation damage bonus" : "") + ". Enchantments, mastery and weapon procs aren't counted. "
                + "Type your own ATT, DEX or enemy DEF above to try other numbers."));

        // ---- your other weapons (vault and characters), best first for this enemy
        int slot = weapon != null ? weapon.slotType : -1;
        Map<Integer, String> yours = new LinkedHashMap<>();
        for (int id : vault.vault()) if (id > 0) yours.putIfAbsent(id, "Vault");
        for (int id : vault.gifts()) if (id > 0) yours.putIfAbsent(id, "Gift chest");
        for (CharacterBags.Char ch : CharacterBags.of(mine)) {
            for (int id : ch.gear) if (id > 0) yours.putIfAbsent(id, AboutMe.objectName(ch.classType, "a character"));
            for (int id : ch.bags) if (id > 0) yours.putIfAbsent(id, AboutMe.objectName(ch.classType, "a character"));
        }
        List<Object[]> ranked = new ArrayList<>();
        for (Map.Entry<Integer, String> e : yours.entrySet()) {
            WeaponDps w = WeaponDps.of(e.getKey());
            if (w == null || (slot > 0 && w.slotType != slot) || (weapon != null && w.id == weapon.id)) continue;
            ranked.add(new Object[]{w, e.getValue(), w.dps(a, d, exalt, enemy)});
        }
        ranked.sort((x, y) -> Double.compare((double) y[2], (double) x[2]));
        if (!ranked.isEmpty()) {
            gap(16);
            list.add(heading("your other weapons", "vs DEF " + enemy));
            gap(8);
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{"Weapon", "DPS", "vs yours", "Where"});
            double base = weapon == null ? 0 : weapon.dps(a, d, exalt, enemy);
            for (int i = 0; i < Math.min(12, ranked.size()); i++) {
                WeaponDps w = (WeaponDps) ranked.get(i)[0];
                double v = (double) ranked.get(i)[2];
                rows.add(new String[]{name(w.id), fmt(v), base > 0 ? pct(v, base) : "", (String) ranked.get(i)[1]});
            }
            list.add(table(rows));
        }

        // ---- compare any weapon by name
        String q = compare.getText().trim().toLowerCase(Locale.ROOT);
        if (!q.isEmpty()) {
            gap(16);
            list.add(heading("compare", "vs DEF " + enemy));
            gap(8);
            List<Object[]> found = new ArrayList<>();
            for (int id : ItemXml.ids()) {
                String n = name(id);
                if (!n.toLowerCase(Locale.ROOT).contains(q)) continue;
                WeaponDps w = WeaponDps.of(id);
                if (w == null) continue;
                found.add(new Object[]{w, n, w.dps(a, d, exalt, enemy)});
            }
            found.sort((x, y) -> Double.compare((double) y[2], (double) x[2]));
            if (found.isEmpty()) {
                list.add(note("No weapon called \"" + compare.getText().trim() + "\" in the game files."));
            } else {
                List<String[]> rows = new ArrayList<>();
                rows.add(new String[]{"Weapon", "Damage", "DPS", "vs yours"});
                double base = weapon == null ? 0 : weapon.dps(a, d, exalt, enemy);
                for (int i = 0; i < Math.min(15, found.size()); i++) {
                    WeaponDps w = (WeaponDps) found.get(i)[0];
                    double v = (double) found.get(i)[2];
                    rows.add(new String[]{(String) found.get(i)[1], w.damageText(), fmt(v), base > 0 ? pct(v, base) : ""});
                }
                list.add(table(rows));
                if (found.size() > 15) {
                    gap(4);
                    list.add(small("...and " + (found.size() - 15) + " more: type more of the name"));
                }
            }
        }
    }

    private static String pct(double v, double base) {
        double p = (v / base - 1) * 100;
        return Math.abs(p) < 0.5 ? "same" : String.format(Locale.ROOT, "%+.0f%%", p);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
