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
 * quest_progress.progress JSON (docs 20 6.3): {@code {v: 콘텐츠 해시, o: {목표 key: 수}, i: 현재 목표 순서,
 * cycle: "d2027-05-03", r: {snap: 수락 시 보상 스냅샷, paid: [지급 완료 항목]}, a: 수락 시각(ms)}}. 변경 불가.
 *
 * @param paid 지급 기록: {@code grant}(경험치·호감도·힌트·아이템을 줬음, 완료 트랜잭션과 같이 씀), {@code done}(모든 보상 처리 끝)
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
     * 수락할 때 정한 재화·평판 (의뢰 골드는 해금한 가장 높은 지역 기준이라 수락 시점 값으로 고정).
     *
     * @param region 수락 시점의 해금한 가장 높은 지역 (기록용)
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

    /** 깨진 JSON이면 빈 진행 (cycle "once")으로 읽는다. */
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
