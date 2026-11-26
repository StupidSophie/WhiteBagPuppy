package whitebag;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Pictures for femcore.gay. The bot draws items from RealmShark's sprite sheet, which lacks some items (or has an empty
 * spot for them). The pup has the game's own pictures, so now and then it asks the bot which items it's missing
 * (GET /pup/icons/wanted, just item numbers) and sends the ones it has (POST /pup/icons?code=...). Only with a Biscuit
 * link; nothing about you is sent.
 */
final class ItemPictures {
    private ItemPictures() {
    }

    private static final long AGAIN_MS = 6 * 3600_000L;   // don't send the same item twice in this long
    private static final int MAX_EACH = 100;
    private static final Map<Integer, Long> sent = new HashMap<>();

    /** "https://femcore.gay" and the code, from a Biscuit link, or null. */
    static String[] baseAndCode(String biscuitLink) {
        if (!DiscordWebhook.isBiscuitLink(biscuitLink)) return null;
        String t = biscuitLink.trim();
        int p = t.indexOf("/pup/post?");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[?&]code=([\\w-]+)").matcher(t);
        if (p < 0 || !m.find()) return null;
        return new String[]{t.substring(0, p), m.group(1)};
    }

    /** Asks the bot what it's missing and sends what the game has. Quiet unless something was sent. */
    static synchronized void share(String biscuitLink, Consumer<String> log) {
        if (!LootImage.gameSpritesReady) return;
        String[] bc = baseAndCode(biscuitLink);
        if (bc == null) return;
        try {
            String wanted = get(bc[0] + "/pup/icons/wanted");
            if (wanted == null) return;
            JsonElement items = JsonParser.parseString(wanted).getAsJsonObject().get("items");
            if (items == null || !items.isJsonArray()) return;
            long now = System.currentTimeMillis();
            JsonObject icons = new JsonObject();
            for (JsonElement e : (JsonArray) items) {
                if (icons.size() >= MAX_EACH) break;
                int id;
                try {
                    id = e.getAsInt();
                } catch (Exception ex) {
                    continue;
                }
                Long last = sent.get(id);
                if (last != null && now - last < AGAIN_MS) continue;
                String png = png(id);
                if (png != null) icons.addProperty(String.valueOf(id), png);
            }
            if (icons.size() == 0) return;
            JsonObject body = new JsonObject();
            body.add("icons", icons);
            String answer = post(bc[0] + "/pup/icons?code=" + bc[1], body.toString());
            for (String k : icons.keySet()) sent.put(Integer.parseInt(k), now);
            int kept = 0;
            try {
                kept = JsonParser.parseString(answer).getAsJsonObject().get("kept").getAsInt();
            } catch (Exception ignored) {
            }
            if (kept > 0 && log != null) {
                log.accept("*proud tail wag* sent Biscuit " + kept + (kept == 1 ? " item picture" : " item pictures")
                        + " it was missing, for the website.");
            }
        } catch (Exception ignored) {
            // Biscuit is busy or down: try again next time
        }
    }

    /** The game's picture of an item as base64 PNG, or null if the game has none (or it's empty). */
    static String png(int id) {
        BufferedImage img;
        try {
            img = LootImage.sprites.sprite(id);
        } catch (Throwable t) {
            return null;
        }
        if (img == null || img.getWidth() > 128 || img.getHeight() > 128) return null;
        boolean any = false;
        for (int y = 0; y < img.getHeight() && !any; y++)
            for (int x = 0; x < img.getWidth(); x++)
                if ((img.getRGB(x, y) >>> 24) != 0) {
                    any = true;
                    break;
                }
        if (!any) return null;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            if (out.size() > 60_000) return null;
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        try {
            con.setConnectTimeout(10_000);
            con.setReadTimeout(15_000);
            con.setRequestProperty("User-Agent", "WhiteBagPuppy/" + UpdateCheck.VERSION);
            if (con.getResponseCode() / 100 != 2) return null;
            return read(con.getInputStream());
        } finally {
            con.disconnect();
        }
    }

    private static String post(String url, String json) throws Exception {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        try {
            con.setRequestMethod("POST");
            con.setConnectTimeout(10_000);
            con.setReadTimeout(30_000);
            con.setDoOutput(true);
            con.setFixedLengthStreamingMode(body.length);
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            con.setRequestProperty("User-Agent", "WhiteBagPuppy/" + UpdateCheck.VERSION);
            try (OutputStream os = con.getOutputStream()) {
                os.write(body);
            }
            int code = con.getResponseCode();
            return code / 100 == 2 ? read(con.getInputStream()) : "{}";
        } finally {
            con.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0 && b.size() < 1_000_000) b.write(buf, 0, n);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
