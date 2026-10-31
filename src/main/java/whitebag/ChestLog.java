package whitebag;

import packets.Packet;
import packets.PacketType;
import packets.incoming.ChestRewardResultPacket;
import packets.incoming.ClaimChestRewardPacket;
import packets.incoming.ClaimRewardsInfoPromptPacket;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Quest chests (and other reward chests) you open, with what came out: kept in quest-chests.txt next to the app.
 * The game says which chest you used when you open it, then sends what was inside.
 */
final class ChestLog {
    static final class Opened {
        long at;                 // Unix seconds
        String account = "";
        int chest;               // the chest's item type, or 0 if the game didn't say
        final List<Integer> items = new ArrayList<>();

        String line() {
            StringBuilder sb = new StringBuilder();
            for (int id : items) {
                if (sb.length() > 0) sb.append(',');
                sb.append(id);
            }
            return at + "|" + account.replace('|', '/') + "|" + chest + "|" + sb;
        }

        static Opened parse(String l) {
            String[] f = l.split("\\|", -1);
            if (f.length < 4) return null;
            try {
                Opened o = new Opened();
                o.at = Long.parseLong(f[0]);
                o.account = f[1];
                o.chest = Integer.parseInt(f[2]);
                for (String p : f[3].split(",")) if (!p.trim().isEmpty()) o.items.add(Integer.parseInt(p.trim()));
                return o;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** "Mighty Quest Chest", or a plain name if the game didn't say. */
        String chestName() {
            if (chest <= 0) return "Reward chest";
            String n = ItemInfo.of(chest).name;
            return n == null || n.startsWith("Item #") ? "Reward chest" : n;
        }
    }

    private static final String FILE = "quest-chests.txt";
    private static List<Opened> all;

    static synchronized List<Opened> all() {
        if (all == null) {
            all = new ArrayList<>();
            try {
                for (String l : Files.readAllLines(Paths.get(FILE), StandardCharsets.UTF_8)) {
                    Opened o = Opened.parse(l);
                    if (o != null) all.add(o);
                }
            } catch (Exception ignored) {
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(all));
    }

    private static synchronized void add(Opened o) {
        all();
        all.add(o);
        try {
            Files.write(Paths.get(FILE), Collections.singletonList(o.line()), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- one account

    private final java.util.function.Supplier<String> account;
    private final java.util.function.Consumer<Opened> opened;
    private volatile int lastChest;
    private volatile long lastChestAt;

    ChestLog(java.util.function.Supplier<String> account, java.util.function.Consumer<Opened> opened) {
        this.account = account;
        this.opened = opened;
    }

    void register(Bus bus) {
        bus.register(PacketType.CLAIM_CHEST_REWARD, p -> {
            ClaimChestRewardPacket c = (ClaimChestRewardPacket) p;
            if (c.accepted && c.slotObjectData != null && c.slotObjectData.objectType > 0) {
                lastChest = c.slotObjectData.objectType;
                lastChestAt = System.currentTimeMillis();
            }
        });
        bus.register(PacketType.CLAIM_REWARDS_INFO_PROMPT, p -> {
            ClaimRewardsInfoPromptPacket c = (ClaimRewardsInfoPromptPacket) p;
            System.out.println("[White Bag Pup] chest prompt: type " + c.chestType + ", " + (c.contents == null ? 0 : c.contents.length) + " things");
        });
        bus.register(PacketType.CHEST_REWARD_RESULT, this::onResult);
    }

    private void onResult(Packet p) {
        ChestRewardResultPacket r = (ChestRewardResultPacket) p;
        if (r.contents == null || r.contents.length == 0) return;
        Opened o = new Opened();
        o.at = System.currentTimeMillis() / 1000;
        o.account = account.get() == null ? "" : account.get();
        o.chest = System.currentTimeMillis() - lastChestAt < 60_000 ? lastChest : 0;
        for (int id : r.contents) if (id > 0) o.items.add(id);
        lastChest = 0;
        if (o.items.isEmpty()) return;
        add(o);
        try {
            opened.accept(o);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
