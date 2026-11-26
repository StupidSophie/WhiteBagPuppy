package whitebag;

import assets.ImageBuffer;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the pictures attached to a Discord post: the bag's own sprite, and a cute strip of the
 * bag's items with rarity badges. Sprites come from the player's game install via Puppy.
 */
public final class LootImage {

    /** Where sprites come from; swappable for tests. */
    public interface SpriteSource {
        BufferedImage sprite(int objectType);
    }

    /**
     * Set once the game's sprite sheets have been extracted. Puppy's sprite loader reads its files
     * only once, so touching it earlier (e.g. a bag dropping during the first-run extraction) would
     * leave it empty until restart.
     */
    static volatile boolean gameSpritesReady = false;

    static SpriteSource sprites = id -> {
        if (!gameSpritesReady) return null;
        try {
            return ImageBuffer.getImage(id);
        } catch (Throwable e) {
            return null;
        }
    };

    private LootImage() {
    }

    /**
     * Reads the sprite sheets again after the game files were re-read (the sprite loader normally reads them only once,
     * so new items would stay a "?" until the pup restarts).
     */
    static synchronized void reloadSprites() {
        try {
            Class<?> fb = Class.forName("assets.SpriteFlatBuffer");
            java.lang.reflect.Field sprites = fb.getDeclaredField("sprites");
            sprites.setAccessible(true);
            ((java.util.Map<?, ?>) sprites.get(null)).clear();
            java.lang.reflect.Field notLoaded = fb.getDeclaredField("notLoaded");
            notLoaded.setAccessible(true);
            notLoaded.setBoolean(null, false);
            java.lang.reflect.Method read = fb.getDeclaredMethod("readFlatBuffer");
            read.setAccessible(true);
            read.invoke(null);
        } catch (Throwable e) {
            System.out.println("Couldn't reload the sprite sheets: " + e);
        }
        try {
            ImageBuffer.clear();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Bag sprite for the embed thumbnail, or null if the sprite isn't available.
     */
    public static byte[] bagPng(int bagObjectType) {
        BufferedImage icon = pixelIcon(sprites.sprite(bagObjectType), 96); // plain bag, no puppy ears
        if (icon == null) return null;
        BufferedImage out = new BufferedImage(112, 112, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        Cute.smooth(g);
        g.drawImage(icon, (112 - icon.getWidth()) / 2, (112 - icon.getHeight()) / 2 + 2, null);
        Cute.drawPaw(g, 100, 102, 8, new Color(0xFF9EBB));
        Cute.drawHeart(g, 96, 8, 13, new Color(0xFF9EBB));
        g.dispose();
        return png(out);
    }

    /**
     * Small square bag sprite (no extras) for the embed's author line, where Discord shows it as a
     * little round icon next to the bag's name. Null if the sprite isn't available.
     */
    public static byte[] bagIconPng(int bagObjectType) {
        BufferedImage icon = pixelIcon(sprites.sprite(bagObjectType), 44);
        if (icon == null) return null;
        BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(icon, (64 - icon.getWidth()) / 2, (64 - icon.getHeight()) / 2, null);
        g.dispose();
        return png(out);
    }

    // Floppy pixel ears, drawn on the sprite's own pixel grid so they match the game art.
    private static final String[] EAR = {
            ".ob",
            "bpb",
            "bpb",
            "bb.",
            "b..",
    };
    private static final int EAR_BROWN = 0xFFC99A74;
    private static final int EAR_PINK = 0xFFFFB3C7;

    /**
     * Returns a copy of the sprite with puppy ears hanging off the top corners of its shape.
     */
    static BufferedImage withPuppyEars(BufferedImage src) {
        if (src == null || isBlank(src)) return src;
        int minX = src.getWidth(), maxX = -1, minY = src.getHeight();
        for (int y = 0; y < src.getHeight(); y++)
            for (int x = 0; x < src.getWidth(); x++)
                if ((src.getRGB(x, y) >>> 24) > 0) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                }
        int earW = EAR[0].length(), m = earW;
        BufferedImage out = new BufferedImage(src.getWidth() + m * 2, src.getHeight() + 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, m, 1, null);
        g.dispose();
        int top = minY + 1 + 1;  // just below the bag's tied top
        int left = m + minX - earW + 1, right = m + maxX;
        for (int y = 0; y < EAR.length; y++) {
            for (int x = 0; x < earW; x++) {
                char c = EAR[y].charAt(x);
                // 'o' only fills where the bag has no pixel, so the ear tucks behind it
                put(out, left + x, top + y, c, true);
                put(out, right + (earW - 1 - x), top + y, c, true);
            }
        }
        return out;
    }

    private static void put(BufferedImage img, int x, int y, char c, boolean behind) {
        if (!inside(img, x, y) || c == '.') return;
        if (c == 'o' && (img.getRGB(x, y) >>> 24) > 0) return;
        img.setRGB(x, y, c == 'p' ? EAR_PINK : EAR_BROWN);
    }

    /**
     * Strip of item slots with rarity badges, or null if no item sprites are available.
     */
    public static byte[] lootPng(List<ItemInfo> items) {
        if (items.isEmpty()) return null;
        List<BufferedImage> icons = new ArrayList<>();
        boolean any = false;
        for (ItemInfo i : items) {
            BufferedImage icon = pixelIcon(sprites.sprite(i.id), SLOT - 30);
            icons.add(icon);
            any |= icon != null;
        }
        if (!any) return null;

        int cols = Math.min(PER_ROW, items.size());
        int rows = (items.size() + PER_ROW - 1) / PER_ROW;
        int w = PAD * 2 + cols * SLOT + (cols - 1) * GAP;
        int h = PAD * 2 + rows * SLOT + (rows - 1) * GAP;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        Cute.smooth(g);

        // card
        g.setColor(Cute.BG);
        g.fill(new RoundRectangle2D.Double(0, 0, w, h, 28, 28));
        g.setColor(Cute.CARD_EDGE);
        g.setStroke(new BasicStroke(2f));
        g.draw(new RoundRectangle2D.Double(1, 1, w - 2, h - 2, 28, 28));

        Cute.drawPaw(g, w - 9, 13, 5, new Color(0xFFC2D4));
        Cute.drawPaw(g, 8, h - 6, 5, new Color(0xFFC2D4));

        for (int n = 0; n < items.size(); n++) {
            ItemInfo item = items.get(n);
            int x = PAD + (n % PER_ROW) * (SLOT + GAP);
            int y = PAD + (n / PER_ROW) * (SLOT + GAP);
            drawSlot(g, x, y, item, icons.get(n));
        }
        g.dispose();
        return png(out);
    }

    /**
     * A slim death card: the character, then its equipment as bare pictures (no slots) on the pup's pink card with
     * paws and hearts. Enchanted items glow in their enchantment colour, and each item has its tier in small letters
     * under it. Null if no pictures are available.
     */
    static byte[] deathPng(int classType, int level, List<ItemInfo> gear) {
        BufferedImage who = classType > 0 ? gameIcon(sprites.sprite(classType), SLOT, null) : null;
        List<BufferedImage> icons = new ArrayList<>();
        boolean any = who != null;
        for (ItemInfo i : gear) {
            BufferedImage icon = gameIcon(sprites.sprite(i.id), SLOT - 24, i.enchant == Enchant.NONE ? null : i.enchant.color);
            icons.add(icon);
            any |= icon != null;
        }
        if (!any) return null;
        // the character on its own (no slot), then the gear exactly like a bag card
        int n = gear.size(), lead = who != null ? SLOT + GAP : 0;
        int w = PAD * 2 + lead + n * SLOT + Math.max(0, n - 1) * GAP, h = PAD * 2 + SLOT;
        if (n == 0) w = PAD * 2 + SLOT;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        Cute.smooth(g);
        g.setColor(Cute.BG);
        g.fill(new RoundRectangle2D.Double(0, 0, w, h, 28, 28));
        g.setColor(Cute.CARD_EDGE);
        g.setStroke(new BasicStroke(2f));
        g.draw(new RoundRectangle2D.Double(1, 1, w - 2, h - 2, 28, 28));
        Cute.drawPaw(g, w - 9, 13, 5, new Color(0xFFC2D4));
        Cute.drawPaw(g, 8, h - 6, 5, new Color(0xFFC2D4));
        if (who != null) {
            g.drawImage(who, PAD + (SLOT - who.getWidth()) / 2, PAD + (SLOT - who.getHeight()) / 2, null);
        }
        for (int k = 0; k < n; k++) {
            int x = PAD + lead + k * (SLOT + GAP);
            drawSlot(g, x, PAD, gear.get(k), icons.get(k));
        }
        g.dispose();
        return png(out);
    }

    /** A small rounded label whose bottom-right corner is at (right, bottom), with a white rim. */
    private static void pill(Graphics2D g, String text, Color c, int right, int bottom) {
        g.setFont(Cute.font(Font.BOLD, 11));
        FontMetrics fm = g.getFontMetrics();
        int bw = fm.stringWidth(text) + 12, bh = 17;
        int bx = right - bw, by = bottom - bh;
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Double(bx - 2, by - 2, bw + 4, bh + 4, bh + 4, bh + 4));
        g.setColor(c);
        g.fill(new RoundRectangle2D.Double(bx, by, bw, bh, bh, bh));
        g.setColor(Color.WHITE);
        g.drawString(text, bx + 6, by + (bh - fm.getHeight()) / 2 + fm.getAscent());
    }

    /** A soft halo around a picture in the given colour (the game's enchantment glow). */
    private static void glow(Graphics2D g, BufferedImage p, int x, int y, Color c) {
        BufferedImage tint = new BufferedImage(p.getWidth(), p.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int yy = 0; yy < p.getHeight(); yy++) {
            for (int xx = 0; xx < p.getWidth(); xx++) {
                if ((p.getRGB(xx, yy) >>> 24) > 0) tint.setRGB(xx, yy, (0x38 << 24) | (c.getRGB() & 0xFFFFFF));
            }
        }
        int ox = x, oy = y;
        for (int r = 6; r >= 2; r -= 2) {
            for (int dx = -r; dx <= r; dx += r) for (int dy = -r; dy <= r; dy += r) g.drawImage(tint, ox + dx, oy + dy, null);
        }
    }

    private static final int SLOT = 88;
    private static final int GAP = 16;
    private static final int PAD = 20;
    private static final int PER_ROW = 4;

    /** One bag on a recap page. */
    static final class RecapBag {
        final BagType bag;
        final String title;
        final List<ItemInfo> items;

        RecapBag(BagType bag, String title, List<ItemInfo> items) {
            this.bag = bag;
            this.title = title;
            this.items = items;
        }
    }

    private static final int PAGE_PER_ROW = 6;
    private static final int BAG_HEAD = 46;

    /**
     * One page of the recap's loot gallery: each bag with its sprite, where and when, and its items.
     * Null if no sprites are available at all.
     */
    static byte[] recapPage(List<RecapBag> bags, int page, int pages) {
        if (bags.isEmpty()) return null;
        boolean any = false;
        List<List<BufferedImage>> icons = new ArrayList<>();
        for (RecapBag b : bags) {
            List<BufferedImage> row = new ArrayList<>();
            for (ItemInfo i : b.items) {
                BufferedImage icon = pixelIcon(sprites.sprite(i.id), SLOT - 30);
                row.add(icon);
                any |= icon != null;
            }
            icons.add(row);
        }
        if (!any) return null;
        int w = PAD * 2 + PAGE_PER_ROW * SLOT + (PAGE_PER_ROW - 1) * GAP;
        int h = PAD + 22;
        for (RecapBag b : bags) {
            int rows = Math.max(1, (b.items.size() + PAGE_PER_ROW - 1) / PAGE_PER_ROW);
            h += BAG_HEAD + rows * (SLOT + GAP) + 8;
        }
        h += PAD - 8;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        Cute.smooth(g);
        g.setColor(Cute.BG);
        g.fill(new RoundRectangle2D.Double(0, 0, w, h, 28, 28));
        g.setColor(Cute.CARD_EDGE);
        g.setStroke(new BasicStroke(2f));
        g.draw(new RoundRectangle2D.Double(1, 1, w - 2, h - 2, 28, 28));
        Cute.drawPaw(g, 8, h - 6, 5, new Color(0xFFC2D4));

        // page number, top right
        g.setFont(Cute.font(Font.BOLD, 13));
        FontMetrics pm = g.getFontMetrics();
        String pn = pages > 1 ? "page " + page + " of " + pages : "";
        g.setColor(Cute.MUTED);
        g.drawString(pn, w - PAD - pm.stringWidth(pn), PAD + pm.getAscent() - 4);

        int y = PAD + 22;
        for (int n = 0; n < bags.size(); n++) {
            RecapBag b = bags.get(n);
            BufferedImage bagIcon = pixelIcon(sprites.sprite(b.bag.spriteType()), 36);
            int tx = PAD;
            if (bagIcon != null) {
                g.drawImage(bagIcon, PAD, y + (BAG_HEAD - 8 - bagIcon.getHeight()) / 2, null);
                tx += bagIcon.getWidth() + 10;
            } else {
                Cute.drawBag(g, PAD, y + 2, 32, new Color(b.bag.color), false, false);
                tx += 42;
            }
            g.setFont(Cute.font(Font.BOLD, 17));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(Cute.TEXT);
            String t = b.title;
            while (fm.stringWidth(t) > w - tx - PAD && t.length() > 4) t = t.substring(0, t.length() - 2);
            if (!t.equals(b.title)) t = t + "…";
            g.drawString(t, tx, y + (BAG_HEAD - 8 - fm.getHeight()) / 2 + fm.getAscent());
            y += BAG_HEAD;
            List<BufferedImage> row = icons.get(n);
            for (int i = 0; i < b.items.size(); i++) {
                int x = PAD + (i % PAGE_PER_ROW) * (SLOT + GAP);
                drawSlot(g, x, y + 8 + (i / PAGE_PER_ROW) * (SLOT + GAP), b.items.get(i), row.get(i));
            }
            int rows = Math.max(1, (b.items.size() + PAGE_PER_ROW - 1) / PAGE_PER_ROW);
            y += rows * (SLOT + GAP) + 8;
            if (n < bags.size() - 1) {
                g.setColor(Cute.CARD_EDGE);
                g.fillRect(PAD, y - 6, w - PAD * 2, 2);
            }
        }
        g.dispose();
        return png(out);
    }

    private static void drawSlot(Graphics2D g, int x, int y, ItemInfo item, BufferedImage icon) {
        Color accent = item.rarityColor;
        Enchant ench = item.enchant;
        if (ench != Enchant.NONE) {
            // the game gives enchanted items a glow in their rarity's colour
            for (int r = 7; r >= 1; r -= 2) {
                g.setColor(new Color(ench.color.getRed(), ench.color.getGreen(), ench.color.getBlue(), 34));
                g.fill(new RoundRectangle2D.Double(x - r, y - r, SLOT + 2 * r, SLOT + 2 * r, 22 + 2 * r, 22 + 2 * r));
            }
        }
        // slot
        g.setColor(Cute.SHADOW);
        g.fill(new RoundRectangle2D.Double(x, y + 3, SLOT, SLOT, 22, 22));
        g.setColor(Cute.mix(Color.WHITE, accent, 0.16));
        g.fill(new RoundRectangle2D.Double(x, y, SLOT, SLOT, 22, 22));
        g.setColor(ench != Enchant.NONE ? ench.color : accent);
        g.setStroke(new BasicStroke(3f));
        g.draw(new RoundRectangle2D.Double(x + 1.5, y + 1.5, SLOT - 3, SLOT - 3, 20, 20));
        Cute.drawPaw(g, x + 13, y + SLOT - 11, 7, Cute.mix(accent, Color.WHITE, 0.45));
        // shine
        g.setColor(new Color(255, 255, 255, 150));
        g.fill(new RoundRectangle2D.Double(x + 8, y + 7, SLOT - 34, 7, 7, 7));

        if (icon != null) {
            g.drawImage(icon, x + (SLOT - icon.getWidth()) / 2, y + (SLOT - icon.getHeight()) / 2 - 2, null);
        } else {
            g.setColor(Cute.MUTED);
            g.setFont(Cute.font(Font.BOLD, 30));
            FontMetrics fm = g.getFontMetrics();
            g.drawString("?", x + (SLOT - fm.stringWidth("?")) / 2, y + (SLOT - fm.getHeight()) / 2 + fm.getAscent());
        }

        if (ench != Enchant.NONE) {
            // rarity name tag on the top edge
            g.setFont(Cute.font(Font.BOLD, 11));
            FontMetrics fm = g.getFontMetrics();
            int tw = fm.stringWidth(ench.label) + 12, th = 18;
            int tx = x - 4, ty = y - 7;
            g.setColor(Color.WHITE);
            g.fill(new RoundRectangle2D.Double(tx - 2, ty - 2, tw + 4, th + 4, th + 4, th + 4));
            g.setColor(ench.color);
            g.fill(new RoundRectangle2D.Double(tx, ty, tw, th, th, th));
            g.setColor(Color.WHITE);
            g.drawString(ench.label, tx + 6, ty + (th - fm.getHeight()) / 2 + fm.getAscent());
        }

        if (!item.rarity.isEmpty()) {
            g.setFont(Cute.font(Font.BOLD, 13));
            FontMetrics fm = g.getFontMetrics();
            int bw = fm.stringWidth(item.rarity) + 16, bh = 22;
            int bx = x + SLOT - bw + 6, by = y + SLOT - bh + 6;
            g.setColor(Color.WHITE);
            g.fill(new RoundRectangle2D.Double(bx - 2, by - 2, bw + 4, bh + 4, bh + 4, bh + 4));
            g.setColor(accent);
            g.fill(new RoundRectangle2D.Double(bx, by, bw, bh, bh, bh));
            g.setColor(Color.WHITE);
            g.drawString(item.rarity, bx + 8, by + (bh - fm.getHeight()) / 2 + fm.getAscent());
            if (item.rarity.equals("UT") || item.rarity.equals("ST") || item.rarityColor == ItemInfo.Rarity.LEGENDARY) {
                Cute.drawHeart(g, x + SLOT - 17, y + 7, 11, Cute.mix(accent, Color.WHITE, 0.15));
            }
        }
    }

    /**
     * Game-style icon: 1px black outline at native size, then scaled up with crisp pixels.
     */
    /**
     * The game's own look, as on RealmEye's renders: the sprite with a 1-pixel dark outline and a small shadow at its
     * own size, scaled up with crisp pixels, and (for enchanted items) a soft blurred glow in the enchantment colour.
     */
    static BufferedImage gameIcon(BufferedImage src, int box, Color glow) {
        if (src == null || isBlank(src)) return null;
        // the size step comes from the whole sprite cell, so every item is drawn at the same scale
        int scale = Math.max(1, box / (Math.max(src.getWidth(), src.getHeight()) + 4));
        // then crop to the drawn part, so the item sits in the middle of its slot
        int minX = src.getWidth(), minY = src.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                if ((src.getRGB(x, y) >>> 24) == 0) continue;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        src = src.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
        int sw = src.getWidth(), sh = src.getHeight();
        int w = sw + 4, h = sh + 4; // outline all round, the shadow down and right, and the same space up and left
        BufferedImage nat = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sx = x - 2, sy = y - 2;
                int rgb = inside(src, sx, sy) ? src.getRGB(sx, sy) : 0;
                if ((rgb >>> 24) > 0) {
                    nat.setRGB(x, y, rgb);
                } else if (opaque(src, sx + 1, sy) || opaque(src, sx - 1, sy) || opaque(src, sx, sy + 1) || opaque(src, sx, sy - 1)) {
                    nat.setRGB(x, y, 0xE6141018); // outline: near black, a touch soft
                }
            }
        }
        // shadow: the outlined shape one pixel down and right, faint, behind it
        BufferedImage shaded = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (x > 0 && y > 0 && (nat.getRGB(x - 1, y - 1) >>> 24) > 0) shaded.setRGB(x, y, 0x40000000);
            }
        }
        Graphics2D sg = shaded.createGraphics();
        sg.drawImage(nat, 0, 0, null);
        sg.dispose();
        int pad = glow == null ? 0 : 10;
        int bw = w * scale, bh = h * scale;
        BufferedImage out = new BufferedImage(bw + pad * 2, bh + pad * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        if (glow != null) {
            // a soft halo: the shape in the glow colour, blurred
            BufferedImage mask = new BufferedImage(out.getWidth(), out.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D mg = mask.createGraphics();
            mg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            mg.drawImage(nat, pad, pad, bw, bh, null);
            mg.dispose();
            int c = glow.getRGB() & 0xFFFFFF;
            for (int y = 0; y < mask.getHeight(); y++) {
                for (int x = 0; x < mask.getWidth(); x++) {
                    int a = mask.getRGB(x, y) >>> 24;
                    mask.setRGB(x, y, a > 0 ? (0xFF << 24) | c : 0);
                }
            }
            BufferedImage blurred = blur(blur(mask, 4), 4);
            g.drawImage(blurred, 0, 0, null);
            g.drawImage(blurred, 0, 0, null); // twice: a stronger glow, like the game's
        }
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(shaded, pad, pad, bw, bh, null);
        g.dispose();
        return out;
    }

    /** A box blur of radius r (alpha and colour). */
    private static BufferedImage blur(BufferedImage src, int r) {
        int k = r * 2 + 1;
        float[] kern = new float[k * k];
        java.util.Arrays.fill(kern, 1f / (k * k));
        java.awt.image.ConvolveOp op = new java.awt.image.ConvolveOp(new java.awt.image.Kernel(k, k, kern), java.awt.image.ConvolveOp.EDGE_NO_OP, null);
        BufferedImage padded = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = padded.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        BufferedImage out = op.filter(padded, null);
        BufferedImage res = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D rg = res.createGraphics();
        rg.drawImage(out, 0, 0, null);
        rg.dispose();
        return res;
    }

    static BufferedImage pixelIcon(BufferedImage src, int box) {
        if (src == null || isBlank(src)) return null;
        int w = src.getWidth() + 2, h = src.getHeight() + 2;
        BufferedImage outlined = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sx = x - 1, sy = y - 1;
                int rgb = inside(src, sx, sy) ? src.getRGB(sx, sy) : 0;
                if ((rgb >>> 24) > 0) {
                    outlined.setRGB(x, y, rgb);
                } else if (opaque(src, sx + 1, sy) || opaque(src, sx - 1, sy) || opaque(src, sx, sy + 1) || opaque(src, sx, sy - 1)) {
                    outlined.setRGB(x, y, 0xFF000000);
                }
            }
        }
        int scale = Math.max(1, box / Math.max(w, h));
        BufferedImage out = new BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(outlined, 0, 0, w * scale, h * scale, null);
        g.dispose();
        return out;
    }

    private static boolean inside(BufferedImage i, int x, int y) {
        return x >= 0 && y >= 0 && x < i.getWidth() && y < i.getHeight();
    }

    private static boolean opaque(BufferedImage i, int x, int y) {
        return inside(i, x, y) && (i.getRGB(x, y) >>> 24) > 0;
    }

    private static boolean isBlank(BufferedImage i) {
        for (int y = 0; y < i.getHeight(); y++)
            for (int x = 0; x < i.getWidth(); x++)
                if ((i.getRGB(x, y) >>> 24) > 0) return false;
        return true;
    }

    private static byte[] png(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
