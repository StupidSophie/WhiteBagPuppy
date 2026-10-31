package whitebag;

import java.util.EnumMap;
import java.util.Map;

/** Which badge unlocks each of the pup's hats and outfits, and what the pup is wearing. */
final class Wardrobe {
    private Wardrobe() {
    }

    /** Hat -> the badge that unlocks it (none: always there). */
    static final Map<PixelPup.Hat, String> HATS = new EnumMap<>(PixelPup.Hat.class);
    static final Map<PixelPup.Outfit, String> OUTFITS = new EnumMap<>(PixelPup.Outfit.class);

    static {
        HATS.put(PixelPup.Hat.NONE, null);
        HATS.put(PixelPup.Hat.WITCH, null);
        HATS.put(PixelPup.Hat.SANTA, null);
        HATS.put(PixelPup.Hat.PARTY, "runs10");
        HATS.put(PixelPup.Hat.BOW, "bellyrubs");
        HATS.put(PixelPup.Hat.FLOWERS, "walkies");
        HATS.put(PixelPup.Hat.TOPHAT, "fame10k");
        HATS.put(PixelPup.Hat.VIKING, "boss500");
        HATS.put(PixelPup.Hat.HALO, "blessed");
        HATS.put(PixelPup.Hat.CROWN, "dungeonmaster");
        HATS.put(PixelPup.Hat.NIGHTCAP, null);
        HATS.put(PixelPup.Hat.HEART_BAND, null);   // seasonal: February
        HATS.put(PixelPup.Hat.BUNNY_EARS, null);   // seasonal: April
        HATS.put(PixelPup.Hat.CAT_EARS, "pets1");
        HATS.put(PixelPup.Hat.BEANIE, "time50");
        HATS.put(PixelPup.Hat.CHEF, "trades25");
        HATS.put(PixelPup.Hat.PIRATE, "runs250");
        HATS.put(PixelPup.Hat.WIZARD, "practice1000");
        HATS.put(PixelPup.Hat.ANTLERS, "festive");
        HATS.put(PixelPup.Hat.TIARA, "fame50k");
        OUTFITS.put(PixelPup.Outfit.NONE, null);
        OUTFITS.put(PixelPup.Outfit.BOWTIE, "white10");
        OUTFITS.put(PixelPup.Outfit.BANDANA, "fetch");
        OUTFITS.put(PixelPup.Outfit.SCARF, "loyal");
        OUTFITS.put(PixelPup.Outfit.CAPE, "solo");
        OUTFITS.put(PixelPup.Outfit.GOLD_COLLAR, "white100");
        OUTFITS.put(PixelPup.Outfit.ROYAL_CAPE, "mythic");
        OUTFITS.put(PixelPup.Outfit.PYJAMAS, null);
        OUTFITS.put(PixelPup.Outfit.HOODIE, "days7");
        OUTFITS.put(PixelPup.Outfit.BELL_COLLAR, "partners5");
        OUTFITS.put(PixelPup.Outfit.PEARLS, "legendary10");
        OUTFITS.put(PixelPup.Outfit.TUTU, "sweetheart");
        OUTFITS.put(PixelPup.Outfit.SWEATER, "days60");
        OUTFITS.put(PixelPup.Outfit.ARMOR, "boss1000");
        OUTFITS.put(PixelPup.Outfit.RAINBOW_SCARF, "doublerainbow");
    }

    static boolean has(PixelPup.Hat h) {
        String b = HATS.get(h);
        return b == null || Badges.anyUnlocked(b);
    }

    static boolean has(PixelPup.Outfit o) {
        String b = OUTFITS.get(o);
        return b == null || Badges.anyUnlocked(b);
    }

    /** "the Crown" / "the Hero cape" this badge unlocks, or null. */
    static String unlockedBy(String badgeId) {
        for (Map.Entry<PixelPup.Hat, String> e : HATS.entrySet()) if (badgeId.equals(e.getValue())) return e.getKey().label;
        for (Map.Entry<PixelPup.Outfit, String> e : OUTFITS.entrySet()) if (badgeId.equals(e.getValue())) return e.getKey().label;
        return null;
    }

    static int total() {
        return HATS.size() + OUTFITS.size();
    }

    static int owned() {
        int n = 0;
        for (PixelPup.Hat h : HATS.keySet()) if (has(h)) n++;
        for (PixelPup.Outfit o : OUTFITS.keySet()) if (has(o)) n++;
        return n;
    }

    /** Saves what the pup wears (set by the main window: key, value). */
    static volatile java.util.function.BiConsumer<String, String> save = (k, v) -> { };

    /** Reads what the pup wears from the settings ("pup.hat" is SEASONAL or a hat; "pup.outfit" an outfit). */
    static void load(java.util.Properties settings) {
        String h = settings.getProperty("pup.hat", "SEASONAL");
        try {
            PixelPup.wornHat = h.equals("SEASONAL") ? null : PixelPup.Hat.valueOf(h);
        } catch (IllegalArgumentException e) {
            PixelPup.wornHat = null;
        }
        PixelPup.outfitPicked = settings.getProperty("pup.outfit") != null;
        try {
            PixelPup.wornOutfit = PixelPup.Outfit.valueOf(settings.getProperty("pup.outfit", "NONE"));
        } catch (IllegalArgumentException e) {
            PixelPup.wornOutfit = PixelPup.Outfit.NONE;
        }
    }

    static void wear(PixelPup.Hat h) {
        PixelPup.wornHat = h;
        save.accept("pup.hat", h == null ? "SEASONAL" : h.name());
    }

    static void wear(PixelPup.Outfit o) {
        PixelPup.wornOutfit = o;
        PixelPup.outfitPicked = true;
        save.accept("pup.outfit", o.name());
    }
}
