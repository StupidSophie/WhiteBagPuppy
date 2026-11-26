package whitebag;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dungeon modifiers ("Weak Boss III", "Generous"...) and the loot, XP and dust bonuses they give. The game's MapInfo
 * only names the modifiers; the bonuses come from the game's own mods.xml, which the pup extracts with the other game
 * files, so they stay right after game patches.
 * <p>
 * Based on RealmHound's dungeon modifier lookup (github.com/pillarzz/RealmHound, MIT licence, by Lovens and Pillar).
 */
final class DungeonMods {
    private DungeonMods() {
    }

    /** One modifier: its display name and its bonuses in percent. */
    static final class Mod {
        final String token, name;
        final int loot, xp, dust;
        /** False when mods.xml doesn't have it (bonuses unknown). */
        final boolean known;

        Mod(String token, String name, int loot, int xp, int dust, boolean known) {
            this.token = token;
            this.name = name;
            this.loot = loot;
            this.xp = xp;
            this.dust = dust;
            this.known = known;
        }

        /** "+10% loot, +5% XP" or "" when it gives nothing. */
        String bonusText() {
            return bonus(loot, xp, dust);
        }
    }

    /** The modifiers of the dungeon you're in. */
    static final class Here {
        final String map;
        final List<Mod> mods;
        /** The dungeon's grade (S, A...) when the game sends one, else "". */
        final String grade;
        final int loot, xp, dust;

        Here(String map, List<Mod> mods, String grade) {
            this.map = map;
            this.mods = Collections.unmodifiableList(mods);
            this.grade = grade == null ? "" : grade;
            int l = 0, x = 0, d = 0;
            for (Mod m : mods) {
                l += m.loot;
                x += m.xp;
                d += m.dust;
            }
            loot = l;
            xp = x;
            dust = d;
        }

        boolean isEmpty() {
            return mods.isEmpty();
        }

        /** "+10% loot, +15% XP, +3% dust" or "no bonus". */
        String totalText() {
            String b = bonus(loot, xp, dust);
            return b.isEmpty() ? "no bonus" : b;
        }

        /** "Weak Boss III, Generous" */
        String names() {
            StringBuilder b = new StringBuilder();
            for (Mod m : mods) {
                if (b.length() > 0) b.append(", ");
                b.append(m.name);
            }
            return b.toString();
        }

        /** The diary line: the modifiers, each one's bonus, and the total. */
        String diary() {
            StringBuilder b = new StringBuilder();
            for (Mod m : mods) {
                if (b.length() > 0) b.append(", ");
                b.append(m.name);
                String t = m.bonusText();
                if (!t.isEmpty()) b.append(" (").append(t).append(')');
            }
            return b + ". Total: " + totalText() + (grade.isEmpty() ? "" : "  ·  grade " + grade);
        }

        /** Tooltip: one line per modifier. */
        String tooltip() {
            StringBuilder b = new StringBuilder("<html><b>" + esc(map) + "</b>" + (grade.isEmpty() ? "" : "  ·  grade " + esc(grade)));
            for (Mod m : mods) {
                String t = m.bonusText();
                b.append("<br>").append(esc(m.name)).append(t.isEmpty() ? (m.known ? "" : " (bonus unknown)") : ": " + t);
            }
            b.append("<br><br><b>Total: ").append(totalText()).append("</b></html>");
            return b.toString();
        }
    }

    static String bonus(int loot, int xp, int dust) {
        List<String> parts = new ArrayList<>();
        if (loot != 0) parts.add(String.format(Locale.ROOT, "%+d%% loot", loot));
        if (xp != 0) parts.add(String.format(Locale.ROOT, "%+d%% XP", xp));
        if (dust != 0) parts.add(String.format(Locale.ROOT, "%+d%% dust", dust));
        return String.join(", ", parts);
    }

    // ---------------------------------------------------------------- reading MapInfo

    /** The modifiers in a MapInfo packet (the packet reader splits them into up to four fields). */
    static Here of(packets.incoming.MapInfoPacket p, String map) {
        List<Mod> out = new ArrayList<>();
        String grade = p.dungeonGrade;
        for (String raw : new String[]{p.dungeonModifiers, p.dungeonModifiers2, p.dungeonModifiers3, p.dungeonModifiers4}) {
            if (raw == null) continue;
            for (String part : raw.split(";")) {
                String token = part;
                int bar = token.indexOf('|');
                if (bar >= 0) {
                    String g = token.substring(bar + 1).trim();
                    if (!g.isEmpty() && (grade == null || grade.isEmpty())) grade = g;
                    token = token.substring(0, bar);
                }
                token = token.trim();
                if (token.isEmpty()) continue;
                out.add(lookup(token));
            }
        }
        return new Here(map, out, grade);
    }

    /** A modifier by its token (e.g. WEAKBOSS_3), from mods.xml, or a readable guess when it isn't there. */
    static Mod lookup(String token) {
        Mod m = table().get(canonical(token));
        if (m != null) return new Mod(token, m.name, m.loot, m.xp, m.dust, true);
        return new Mod(token, guessName(token), 0, 0, 0, false);
    }

    /** Uppercase letters and digits only, so WEAKBOSS_3, WeakBoss3 and "weak boss 3" all match. */
    static String canonical(String token) {
        StringBuilder b = new StringBuilder();
        for (char c : token.toCharArray()) if (c < 128 && Character.isLetterOrDigit(c)) b.append(Character.toUpperCase(c));
        return b.toString();
    }

    private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V", "VI"};

    /** WEAKBOSS_3 -> "Weakboss III" (only used when mods.xml is missing). */
    static String guessName(String token) {
        String t = token.trim();
        String tier = "";
        int us = t.lastIndexOf('_');
        if (us > 0) {
            try {
                int n = Integer.parseInt(t.substring(us + 1));
                tier = n > 0 && n < ROMAN.length ? " " + ROMAN[n] : " " + n;
                t = t.substring(0, us);
            } catch (NumberFormatException ignored) {
            }
        }
        StringBuilder b = new StringBuilder();
        for (String w : t.replace('_', ' ').trim().split("\\s+")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase(Locale.ROOT));
        }
        return b + tier;
    }

    // ---------------------------------------------------------------- mods.xml

    private static volatile Map<String, Mod> table;
    private static volatile long tableRead;

    /** True once mods.xml has been found and read. */
    static boolean haveTable() {
        return !table().isEmpty();
    }

    private static Map<String, Mod> table() {
        Map<String, Mod> t = table;
        // read again now and then while it's missing (the game files may be extracted after start-up)
        if (t != null && (!t.isEmpty() || System.currentTimeMillis() - tableRead < 60_000)) return t;
        synchronized (DungeonMods.class) {
            if (table != null && (!table.isEmpty() || System.currentTimeMillis() - tableRead < 60_000)) return table;
            table = load(new File("assets/xml"));
            tableRead = System.currentTimeMillis();
            return table;
        }
    }

    /** Forget mods.xml so it's read again (after the game files are extracted again). */
    static void reload() {
        table = null;
    }

    static Map<String, Mod> load(File dir) {
        Map<String, Mod> t = new HashMap<>();
        File preferred = new File(dir, "mods.xml");
        if (preferred.isFile()) read(preferred, t);
        if (t.isEmpty()) {
            // the file name can change between game versions: any file listing dungeon modifiers
            File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".xml"));
            if (files != null) {
                for (File f : files) {
                    try {
                        if (f.length() > 20_000_000) continue;
                        String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                        if (s.contains("<DungeonModifier ")) read(f, t);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return t;
    }

    private static void read(File f, Map<String, Mod> out) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            try {
                dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignored) {
            }
            Document doc = dbf.newDocumentBuilder().parse(f);
            NodeList mods = doc.getElementsByTagName("DungeonModifier");
            for (int i = 0; i < mods.getLength(); i++) {
                Element m = (Element) mods.item(i);
                String id = m.getAttribute("id").trim();
                if (id.isEmpty()) continue;
                String name = childText(m, "DisplayId");
                if (name == null || name.isEmpty()) name = guessName(id);
                int loot = 0, xp = 0, dust = 0;
                Element mut = child(m, "Mutators");
                if (mut != null) {
                    for (Node n = mut.getFirstChild(); n != null; n = n.getNextSibling()) {
                        if (!(n instanceof Element)) continue;
                        Element e = (Element) n;
                        // per-item lines (a specific potion's drop, say) aren't a general bonus
                        if (e.hasAttribute("itemId") || e.hasAttribute("excludeLabels")) continue;
                        int pct = percent(e.getAttribute("amount"));
                        switch (e.getTagName()) {
                            case "EntityStatMultiplier":
                                if ("XP".equalsIgnoreCase(e.getAttribute("stat").trim())) xp += pct;
                                break;
                            case "EntityDropMultiplier":
                                loot += pct;
                                break;
                            case "EntityDustMultiplier":
                                dust += pct;
                                break;
                            default:
                        }
                    }
                }
                out.put(canonical(id), new Mod(id, name, loot, xp, dust, true));
            }
        } catch (Exception ignored) {
        }
    }

    /** "1.18" -> 18 */
    private static int percent(String amount) {
        try {
            return (int) Math.round((Double.parseDouble(amount.trim()) - 1) * 100);
        } catch (Exception e) {
            return 0;
        }
    }

    private static Element child(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && ((Element) n).getTagName().equals(tag)) return (Element) n;
        }
        return null;
    }

    private static String childText(Element parent, String tag) {
        Element e = child(parent, tag);
        return e == null ? null : e.getTextContent().trim();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
