package whitebag;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.ArrayList;
import java.util.List;

/**
 * A weapon's damage per second, worked out from the game's item data the same way RealmHound's theoretical DPS does:
 * shots per second = (1.5 + 6.5 × DEX / 75) × rate of fire; each shot rolls min..max-1, times
 * (ATT + 25) × 0.02 × the exaltation damage bonus, then loses the target's DEF (never below 10% of the shot).
 * Enchantments, mastery and procs aren't counted.
 */
final class WeaponDps {
    /** The weapon slot types: sword, dagger, bow, wand, staff, katana. */
    static final int[] WEAPON_SLOTS = {1, 2, 3, 8, 17, 24};

    static final class Shot {
        final int min, max;
        final boolean armorPiercing;

        Shot(int min, int max, boolean armorPiercing) {
            this.min = min;
            this.max = max;
            this.armorPiercing = armorPiercing;
        }
    }

    final int id, slotType, projectiles;
    final double rateOfFire;
    final List<Shot> shots;

    private WeaponDps(int id, int slotType, double rateOfFire, int projectiles, List<Shot> shots) {
        this.id = id;
        this.slotType = slotType;
        this.rateOfFire = rateOfFire;
        this.projectiles = projectiles;
        this.shots = shots;
    }

    static boolean isWeaponSlot(int slot) {
        for (int s : WEAPON_SLOTS) if (s == slot) return true;
        return false;
    }

    /** This item as a weapon, or null if it isn't one (or the game files aren't read yet). */
    static WeaponDps of(int id) {
        ItemXml.Info info = id > 0 ? ItemXml.of(id) : null;
        if (info == null || info.element == null || !isWeaponSlot(info.slotType)) return null;
        Element o = info.element;
        double rof = num(text(o, "RateOfFire"), 1);
        int count = (int) num(text(o, "NumProjectiles"), 1);
        List<Shot> shots = new ArrayList<>();
        for (Node n = o.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element) || !"Projectile".equals(n.getNodeName())) continue;
            Element p = (Element) n;
            int min = (int) num(text(p, "MinDamage"), -1), max = (int) num(text(p, "MaxDamage"), -1);
            if (min < 0 || max < 0) {
                int d = (int) num(text(p, "Damage"), -1);
                if (d < 0) continue;
                min = max = d;
            }
            boolean ap = child(p, "ArmorPiercing") != null;
            shots.add(new Shot(min, max, ap));
        }
        if (shots.isEmpty()) return null;
        return new WeaponDps(id, info.slotType, rof, Math.max(1, count), shots);
    }

    /** Shots per second at this DEX. */
    double shotsPerSecond(int dex) {
        return (1.5 + 6.5 * (Math.max(0, dex) / 75.0)) * rateOfFire;
    }

    /** The damage multiplier from ATT and the exaltation damage bonus (per thousand, from the game). */
    static double attackMultiplier(int att, int exaltBonus) {
        return (att + 25) * 0.02 * ((1000.0 + exaltBonus) / 1000.0);
    }

    /** Expected damage of one projectile of this kind against this DEF. */
    static double avgHit(Shot s, double mult, int def) {
        int lo = s.min, hi = s.max > s.min ? s.max - 1 : s.min;
        double sum = 0;
        for (int d = lo; d <= hi; d++) {
            int shot = (int) (d * mult);
            int floor = shot * 2 / 20;
            sum += s.armorPiercing ? shot : Math.max(floor, shot - Math.max(0, def));
        }
        return sum / (hi - lo + 1);
    }

    /** Damage per second against an enemy with this DEF, if every projectile hits. */
    double dps(int att, int dex, int exaltBonus, int def) {
        double mult = attackMultiplier(att, exaltBonus);
        double perVolley = 0;
        for (Shot s : shots) perVolley += avgHit(s, mult, def) * projectiles;
        return perVolley * shotsPerSecond(dex);
    }

    /** "45-70 ×2" for the first kind of shot (plus "+1 more kind" when there are others). */
    String damageText() {
        Shot s = shots.get(0);
        String d = s.min == s.max ? String.valueOf(s.min) : s.min + "–" + s.max;
        return d + (projectiles > 1 ? " ×" + projectiles : "") + (s.armorPiercing ? " (armor piercing)" : "")
                + (shots.size() > 1 ? "  +" + (shots.size() - 1) + " more shot" + (shots.size() > 2 ? "s" : "") : "");
    }

    private static Element child(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && tag.equals(n.getNodeName())) return (Element) n;
        }
        return null;
    }

    private static String text(Element parent, String tag) {
        Element c = child(parent, tag);
        return c == null ? "" : c.getTextContent().trim();
    }

    private static double num(String s, double fallback) {
        try {
            return s.isEmpty() ? fallback : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
