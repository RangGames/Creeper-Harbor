package wiki.creeper.harbor.common.event;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Game event exported to external integrations (webhooks, website, bots) (proposed). Published on
 * Redis {@code harbor:events}; the envelope payload is this record.
 *
 * <p>Wire format (Gson, {@link WireAdapter}) is snake_case, the same shape as the Harbor-Web REST
 * {@code /v1/events} items and webhook {@code json} bodies. Absent player fields are left out, and
 * integers in {@code data} are read back as {@code Long} (numbers with a fraction or exponent as
 * {@code Double}), so {@code 13} never becomes {@code 13.0} on the receiving side.
 *
 * <pre>{@code
 * {"type":"ExternalEvent","server":"main-1","sent_at":"…",
 *  "payload":{"id":"…","type":"quest.completed","version":1,"server":"main-1",
 *             "occurred_at":"2026-11-20T12:00:00Z","player":"<uuid>","player_name":"Steve",
 *             "data":{"quest":"harbor:quest/main/ch1_first_cast", "quest_name":"First Cast", …}}}
 * }</pre>
 *
 * @param id unique event ID (UUID text). Receivers use it to filter duplicates
 * @param type {@code <module>.<event>} (lowercase, underscores, dots). See the Harbor-Web README
 *     for the list
 * @param version data format version. Bumped only when a field is removed or its meaning changes
 *     (adding fields keeps it)
 * @param occurredAt ISO-8601 UTC (wire key {@code occurred_at})
 * @param player related player UUID (null if none)
 * @param playerName related player name (wire key {@code player_name}, null if unknown)
 * @param data event content. Values are only strings, numbers, booleans, lists and maps (plain
 *     JSON)
 */
@JsonAdapter(ExternalEvent.WireAdapter.class)
public record ExternalEvent(
        String id,
        String type,
        int version,
        String server,
        String occurredAt,
        @Nullable String player,
        @Nullable String playerName,
        Map<String, Object> data) {

    public ExternalEvent {
        data = Map.copyOf(data);
    }

    /**
     * snake_case Gson adapter. Reading also accepts the old camelCase keys ({@code occurredAt},
     * {@code playerName}) so servers on the previous version can still be read during a rolling
     * restart.
     */
    public static final class WireAdapter implements TypeAdapterFactory {

        @Override
        @SuppressWarnings("unchecked")
        public <T> @Nullable TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            if (type.getRawType() != ExternalEvent.class) {
                return null;
            }
            TypeAdapter<JsonElement> elements = gson.getAdapter(JsonElement.class);
            return (TypeAdapter<T>) new Adapter(gson, elements).nullSafe();
        }
    }

    private static final class Adapter extends TypeAdapter<ExternalEvent> {
        private final Gson gson;
        private final TypeAdapter<JsonElement> elements;

        Adapter(Gson gson, TypeAdapter<JsonElement> elements) {
            this.gson = gson;
            this.elements = elements;
        }

        @Override
        public void write(JsonWriter out, ExternalEvent e) throws IOException {
            out.beginObject();
            out.name("id").value(e.id());
            out.name("type").value(e.type());
            out.name("version").value(e.version());
            out.name("server").value(e.server());
            out.name("occurred_at").value(e.occurredAt());
            String player = e.player();
            if (player != null) {
                out.name("player").value(player);
            }
            String name = e.playerName();
            if (name != null) {
                out.name("player_name").value(name);
            }
            out.name("data");
            elements.write(out, gson.toJsonTree(e.data()));
            out.endObject();
        }

        @Override
        public ExternalEvent read(JsonReader in) throws IOException {
            JsonElement tree = elements.read(in);
            if (!tree.isJsonObject()) {
                throw new JsonParseException("ExternalEvent must be a JSON object");
            }
            JsonObject o = tree.getAsJsonObject();
            JsonElement data = o.get("data");
            return new ExternalEvent(
                    required(o, "id"),
                    required(o, "type"),
                    o.has("version") ? o.get("version").getAsInt() : 1,
                    required(o, "server"),
                    required(o, o.has("occurred_at") ? "occurred_at" : "occurredAt"),
                    optional(o, "player"),
                    optional(o, o.has("player_name") ? "player_name" : "playerName"),
                    data != null && data.isJsonObject()
                            ? object(data.getAsJsonObject())
                            : Map.of());
        }
    }

    private static String required(JsonObject o, String key) {
        String v = optional(o, key);
        if (v == null) {
            throw new JsonParseException("ExternalEvent." + key + " is missing");
        }
        return v;
    }

    private static @Nullable String optional(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v == null || v.isJsonNull() ? null : v.getAsString();
    }

    /** JSON object → map with JSON nulls dropped (as on the publishing side). */
    private static Map<String, Object> object(JsonObject o) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            Object v = value(e.getValue());
            if (v != null) {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    private static @Nullable Object value(JsonElement v) {
        if (v.isJsonNull()) {
            return null;
        }
        if (v.isJsonObject()) {
            return object(v.getAsJsonObject());
        }
        if (v.isJsonArray()) {
            JsonArray array = v.getAsJsonArray();
            List<Object> out = new ArrayList<>(array.size());
            for (JsonElement x : array) {
                Object n = value(x);
                if (n != null) {
                    out.add(n);
                }
            }
            return out;
        }
        JsonPrimitive p = v.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return p.getAsBoolean();
        }
        if (p.isNumber()) {
            return number(p.getAsString());
        }
        return p.getAsString();
    }

    /** Integers stay Long; a fraction, exponent or out-of-range integer becomes Double. */
    static Number number(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return Double.parseDouble(text);
        }
    }
}
