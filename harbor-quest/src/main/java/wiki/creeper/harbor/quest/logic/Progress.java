package wiki.creeper.harbor.quest.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * quest_progress.progress JSON (docs 20 6.3): {@code {v: content hash, o: {objective key: count},
 * i: current objective index, cycle: "d2027-05-03", r: {snap: reward snapshot at accept, paid:
 * [paid entries]}, a: accept time (ms)}}. Immutable.
 *
 * @param paid payout record: {@code grant} (experience, affinity, hints and items were given;
 *     written together with the completion transaction), {@code done} (all rewards processed)
 */
public record Progress(
        int version,
        Map<String, Integer> counts,
        int index,
        String cycle,
        Snapshot snap,
        List<String> paid,
        long acceptedAt) {

    /**
     * Currency and reputation fixed at accept time (request gold depends on the highest unlocked
     * region, so it is fixed to the value at accept time).
     *
     * @param region highest unlocked region at accept time (for the record)
     */
    public record Snapshot(
            long gold, long mulbineul, int reputation, long exp, @Nullable String region) {
        public static final Snapshot NONE = new Snapshot(0, 0, 0, 0, null);
    }

    public static final String PAID_GRANT = "grant";
    public static final String PAID_DONE = "done";

    public Progress {
        counts = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(counts));
        paid = List.copyOf(paid);
    }

    public static Progress start(int version, String cycle, Snapshot snap, long now) {
        return new Progress(version, Map.of(), 0, cycle, snap, List.of(), now);
    }

    public int count(String key) {
        return counts.getOrDefault(key, 0);
    }

    public boolean paid(String item) {
        return paid.contains(item);
    }

    public Progress withCounts(Map<String, Integer> next, int nextIndex) {
        return new Progress(version, next, nextIndex, cycle, snap, paid, acceptedAt);
    }

    public Progress withVersion(int v) {
        return new Progress(v, counts, index, cycle, snap, paid, acceptedAt);
    }

    public Progress withPaid(String item) {
        if (paid.contains(item)) {
            return this;
        }
        List<String> next = new ArrayList<>(paid);
        next.add(item);
        return new Progress(version, counts, index, cycle, snap, next, acceptedAt);
    }

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("v", version);
        JsonObject o = new JsonObject();
        counts.forEach(o::addProperty);
        root.add("o", o);
        root.addProperty("i", index);
        root.addProperty("cycle", cycle);
        JsonObject r = new JsonObject();
        JsonObject s = new JsonObject();
        s.addProperty("gold", snap.gold());
        s.addProperty("mulbineul", snap.mulbineul());
        s.addProperty("rep", snap.reputation());
        s.addProperty("exp", snap.exp());
        if (snap.region() != null) {
            s.addProperty("region", snap.region());
        }
        r.add("snap", s);
        JsonArray p = new JsonArray();
        paid.forEach(p::add);
        r.add("paid", p);
        root.add("r", r);
        root.addProperty("a", acceptedAt);
        return root.toString();
    }

    /** Broken JSON is read as empty progress (cycle "once"). */
    public static Progress fromJson(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return start(0, "once", Snapshot.NONE, 0);
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            Map<String, Integer> counts = new LinkedHashMap<>();
            JsonElement o = root.get("o");
            if (o != null && o.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject().entrySet()) {
                    counts.put(e.getKey(), e.getValue().getAsInt());
                }
            }
            Snapshot snap = Snapshot.NONE;
            List<String> paid = new ArrayList<>();
            JsonElement r = root.get("r");
            if (r != null && r.isJsonObject()) {
                JsonElement s = r.getAsJsonObject().get("snap");
                if (s != null && s.isJsonObject()) {
                    JsonObject so = s.getAsJsonObject();
                    JsonElement region = so.get("region");
                    snap =
                            new Snapshot(
                                    longOf(so, "gold"),
                                    longOf(so, "mulbineul"),
                                    (int) longOf(so, "rep"),
                                    longOf(so, "exp"),
                                    region == null || region.isJsonNull()
                                            ? null
                                            : region.getAsString());
                }
                JsonElement p = r.getAsJsonObject().get("paid");
                if (p != null && p.isJsonArray()) {
                    p.getAsJsonArray().forEach(x -> paid.add(x.getAsString()));
                }
            }
            JsonElement cycle = root.get("cycle");
            return new Progress(
                    (int) longOf(root, "v"),
                    counts,
                    (int) longOf(root, "i"),
                    cycle == null || cycle.isJsonNull() ? "once" : cycle.getAsString(),
                    snap,
                    paid,
                    longOf(root, "a"));
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
            return start(0, "once", Snapshot.NONE, 0);
        }
    }

    private static long longOf(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? 0 : e.getAsLong();
    }
}
