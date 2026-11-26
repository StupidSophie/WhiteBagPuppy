package whitebag;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every DPS leaderboard in full (fights-log.txt, one JSON line per fight), so old fights can be searched and opened
 * again: who was there, their class, gear and enchantments, damage, and who died or left. dps-log.txt only keeps your
 * own line. Saved from 2.30.40 on. Idea from RealmHound's Combat History.
 */
final class FightLog {
    private FightLog() {
    }

    static final class Fight {
        long at;           // when it ended, ms
        String account = "", map = "";
        DpsTracker.Board board;
    }

    private static final String FILE = "fights-log.txt";
    private static List<Fight> fights;

    static synchronized List<Fight> all() {
        if (fights == null) {
            fights = new ArrayList<>();
            try {
                if (Files.exists(Paths.get(FILE))) {
                    for (String l : Files.readAllLines(Paths.get(FILE), StandardCharsets.UTF_8)) {
                        Fight f = parse(l);
                        if (f != null) fights.add(f);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(fights));
    }

    /** Saves the boards of a finished run (or a realm boss that just died). */
    static synchronized void add(DpsTracker.Run run, List<DpsTracker.Board> boards, String account) {
        all();
        List<String> lines = new ArrayList<>();
        for (DpsTracker.Board b : boards) {
            if (b.kind == DpsTracker.Kind.WHOLE_RUN || b.savedEarly || b.rows.isEmpty()) continue;
            Fight f = new Fight();
            f.at = b.endMs > 0 ? b.endMs : System.currentTimeMillis();
            f.account = account == null ? "" : account;
            f.map = run.mapName == null ? "" : run.mapName;
            f.board = b;
            fights.add(f);
            lines.add(line(f));
        }
        if (lines.isEmpty()) return;
        try {
            Files.write(Paths.get(FILE), lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    static String line(Fight f) {
        DpsTracker.Board b = f.board;
        JsonObject o = new JsonObject();
        o.addProperty("at", f.at);
        o.addProperty("acc", f.account);
        o.addProperty("map", f.map);
        o.addProperty("title", b.title);
        o.addProperty("kind", b.kind.name());
        o.addProperty("killed", b.killed);
        o.addProperty("sec", b.seconds);
        o.addProperty("total", b.total);
        o.addProperty("maxHp", b.maxHp);
        if (b.takenDmg >= 0) {
            o.addProperty("td", b.takenDmg);
            o.addProperty("th", b.takenHits);
        }
        if (b.hasGuard) o.addProperty("guard", true);
        JsonArray rows = new JsonArray();
        for (DpsTracker.Row r : b.rows) {
            JsonObject j = new JsonObject();
            j.addProperty("n", r.name);
            if (r.you) j.addProperty("y", true);
            j.addProperty("d", r.damage);
            j.addProperty("p", r.percent);
            j.addProperty("s", r.dps);
            if (r.classType > 0) j.addProperty("c", r.classType);
            if (r.gear != null) {
                JsonArray g = new JsonArray();
                for (int id : r.gear) g.add(id);
                j.add("g", g);
            }
            if (r.gearData != null) {
                JsonArray g = new JsonArray();
                for (String s : r.gearData) g.add(s == null ? "" : s);
                j.add("gd", g);
            }
            if (r.goneMs > 0) {
                j.addProperty("gone", r.goneMs);
                if (r.died) j.addProperty("died", true);
            }
            if (r.takenDmg >= 0) {
                j.addProperty("td", r.takenDmg);
                j.addProperty("th", r.takenHits);
            }
            if (r.guardHits > 0) {
                j.addProperty("gh", r.guardHits);
                j.addProperty("gdm", r.guardDmg);
            }
            rows.add(j);
        }
        o.add("rows", rows);
        return o.toString();
    }

    static Fight parse(String l) {
        try {
            JsonObject o = JsonParser.parseString(l).getAsJsonObject();
            List<DpsTracker.Row> rows = new ArrayList<>();
            for (JsonElement el : o.getAsJsonArray("rows")) {
                JsonObject j = el.getAsJsonObject();
                DpsTracker.Row r = new DpsTracker.Row(j.get("n").getAsString(), j.has("y") && j.get("y").getAsBoolean(),
                        j.get("d").getAsLong(), j.get("p").getAsDouble(), j.get("s").getAsDouble());
                if (j.has("c")) r.classType = j.get("c").getAsInt();
                if (j.has("g")) {
                    JsonArray g = j.getAsJsonArray("g");
                    r.gear = new int[g.size()];
                    for (int i = 0; i < g.size(); i++) r.gear[i] = g.get(i).getAsInt();
                }
                if (j.has("gd")) {
                    JsonArray g = j.getAsJsonArray("gd");
                    r.gearData = new String[g.size()];
                    for (int i = 0; i < g.size(); i++) r.gearData[i] = g.get(i).getAsString();
                }
                if (j.has("gone")) {
                    r.goneMs = j.get("gone").getAsLong();
                    r.died = j.has("died") && j.get("died").getAsBoolean();
                }
                if (j.has("td")) {
                    r.takenDmg = j.get("td").getAsLong();
                    r.takenHits = j.get("th").getAsLong();
                }
                if (j.has("gh")) {
                    r.guardHits = j.get("gh").getAsLong();
                    r.guardDmg = j.get("gdm").getAsLong();
                }
                rows.add(r);
            }
            DpsTracker.Board b = new DpsTracker.Board(o.get("title").getAsString(), o.get("maxHp").getAsInt(),
                    o.get("sec").getAsDouble(), o.get("killed").getAsBoolean(), o.get("total").getAsLong(), rows);
            try {
                b.kind = DpsTracker.Kind.valueOf(o.get("kind").getAsString());
            } catch (Exception ignored) {
            }
            if (o.has("td")) {
                b.takenDmg = o.get("td").getAsLong();
                b.takenHits = o.get("th").getAsLong();
            }
            b.hasGuard = o.has("guard");
            Fight f = new Fight();
            f.at = o.get("at").getAsLong();
            b.endMs = f.at;
            f.account = o.has("acc") ? o.get("acc").getAsString() : "";
            f.map = o.has("map") ? o.get("map").getAsString() : "";
            f.board = b;
            return f;
        } catch (Exception e) {
            return null;
        }
    }
}
