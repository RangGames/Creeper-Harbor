package wiki.creeper.harbor.web.relay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.redis.Channel;
import wiki.creeper.harbor.common.redis.Channels;

/**
 * Reading harbor:events and writing events for REST and webhooks.
 *
 * <ul>
 *   <li>Reading: the shared bus would parse {@code data} with the default Gson number policy, which
 *       turns every integer into a double ({@code 13} → {@code 13.0}). {@link #CHANNEL} subscribes
 *       with {@link ExternalEvent}, a record of the same simple name whose {@code data} stays raw
 *       JSON, and {@link #decode} parses it with {@link ToNumberPolicy#LONG_OR_DOUBLE} (integers
 *       stay Long, numbers with a fraction or exponent become Double).
 *   <li>Writing: {@link #toJson} gives the snake_case shape used by REST {@code /v1/events} and
 *       webhook {@code json} bodies; {@link #GSON} writes it (no HTML escaping, like the REST
 *       responses).
 * </ul>
 *
 * No server API (unit-tested).
 */
public final class EventWire {

    /**
     * Wire form of Common {@link wiki.creeper.harbor.common.event.ExternalEvent}. The bus
     * dispatches by record simple name, so this name must stay {@code ExternalEvent}.
     */
    public record ExternalEvent(
            String id,
            String type,
            int version,
            String server,
            String occurredAt,
            @Nullable String player,
            @Nullable String playerName,
            @Nullable JsonObject data) {}

    /** harbor:events with raw data. */
    public static final Channel<ExternalEvent> CHANNEL =
            new Channel<>(Channels.EVENTS.name(), ExternalEvent.class);

    /** Output Gson for webhook bodies (REST uses Common Json.GSON, same settings). */
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final Gson NUMBERS =
            new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();

    private static final Type MAP = new TypeToken<Map<String, Object>>() {}.getType();

    private EventWire() {}

    /** Wire record → event with integer-preserving data. */
    public static wiki.creeper.harbor.common.event.ExternalEvent decode(ExternalEvent w) {
        JsonObject raw = w.data();
        Map<String, Object> data = raw == null ? null : NUMBERS.fromJson(raw, MAP);
        return new wiki.creeper.harbor.common.event.ExternalEvent(
                w.id(),
                w.type(),
                w.version(),
                w.server(),
                w.occurredAt(),
                w.player(),
                w.playerName(),
                data == null ? Map.of() : withoutNulls(data));
    }

    /** Payload JSON text (the envelope's payload object) → event. For tests and tools. */
    public static wiki.creeper.harbor.common.event.ExternalEvent decode(String payloadJson) {
        ExternalEvent w =
                NUMBERS.fromJson(JsonParser.parseString(payloadJson), ExternalEvent.class);
        return decode(w);
    }

    /**
     * snake_case event object: id, type, version, server, occurred_at, player?, player_name?, data.
     * Absent player fields are left out.
     */
    public static Map<String, Object> toJson(wiki.creeper.harbor.common.event.ExternalEvent e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", e.id());
        out.put("type", e.type());
        out.put("version", e.version());
        out.put("server", e.server());
        out.put("occurred_at", e.occurredAt());
        String player = e.player();
        if (player != null) {
            out.put("player", player);
        }
        String name = e.playerName();
        if (name != null) {
            out.put("player_name", name);
        }
        out.put("data", e.data());
        return out;
    }

    /** Map.copyOf rejects null values (JSON null in data is dropped, as on the publishing side). */
    private static Map<String, Object> withoutNulls(Map<String, @Nullable Object> data) {
        Map<String, Object> out = new LinkedHashMap<>();
        data.forEach(
                (k, v) -> {
                    if (v != null) {
                        out.put(k, v);
                    }
                });
        return out;
    }
}
