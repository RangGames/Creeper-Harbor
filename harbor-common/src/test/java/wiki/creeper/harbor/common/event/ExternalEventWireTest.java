package wiki.creeper.harbor.common.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.util.Json;

/** Redis harbor:events payload: snake_case keys, integers kept as integers. */
class ExternalEventWireTest {

    @Test
    void writesSnakeCaseInFixedOrder() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("online", 13);
        data.put("size", 41.2);
        data.put("whole", 33.0);
        data.put("big", 9007199254740993L);
        data.put("name", "<b>&");
        ExternalEvent e =
                new ExternalEvent(
                        "e1",
                        "server.player_join",
                        1,
                        "main-1",
                        "2026-11-20T12:00:00Z",
                        "u1",
                        "Steve",
                        data);
        String json = Json.GSON.toJson(e);
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(
                List.of(
                        "id",
                        "type",
                        "version",
                        "server",
                        "occurred_at",
                        "player",
                        "player_name",
                        "data"),
                List.copyOf(o.keySet()));
        assertTrue(json.contains("\"online\":13"), json);
        assertFalse(json.contains("13.0"), json);
        assertTrue(json.contains("\"whole\":33.0"), json);
        assertTrue(json.contains("\"big\":9007199254740993"), json);
        assertTrue(json.contains("\"<b>&\""), json); // no HTML escaping
        assertFalse(json.contains("occurredAt"), json);
        assertFalse(json.contains("playerName"), json);

        ExternalEvent bare =
                new ExternalEvent("e2", "server.started", 1, "s", "t", null, null, Map.of());
        JsonObject b = Json.GSON.toJsonTree(bare).getAsJsonObject();
        assertFalse(b.has("player"));
        assertFalse(b.has("player_name"));
        assertTrue(b.has("data"));
    }

    @Test
    void readsIntegersAsLongAndDropsNulls() {
        String payload =
                "{\"id\":\"e1\",\"type\":\"server.player_join\",\"version\":1,\"server\":\"main-1\","
                        + "\"occurred_at\":\"2026-11-20T12:00:00Z\",\"player\":\"u1\",\"player_name\":\"Steve\","
                        + "\"data\":{\"online\":13,\"size\":41.2,\"whole\":33.0,\"big\":9007199254740993,"
                        + "\"nested\":{\"gold\":100},\"list\":[1,2.5],\"none\":null,\"ok\":true}}";
        ExternalEvent e = Json.GSON.fromJson(payload, ExternalEvent.class);
        assertEquals("2026-11-20T12:00:00Z", e.occurredAt());
        assertEquals("Steve", e.playerName());
        assertEquals(13L, e.data().get("online"));
        assertEquals(41.2, e.data().get("size"));
        assertEquals(33.0, e.data().get("whole"));
        assertEquals(9007199254740993L, e.data().get("big"));
        assertEquals(Map.of("gold", 100L), e.data().get("nested"));
        assertEquals(List.of(1L, 2.5), e.data().get("list"));
        assertEquals(true, e.data().get("ok"));
        assertFalse(e.data().containsKey("none"));
    }

    @Test
    void roundTripAndLegacyKeys() {
        ExternalEvent e =
                new ExternalEvent(
                        "e1", "a.b", 2, "s", "t", "u", null, Map.of("n", 7L, "l", List.of("x")));
        assertEquals(e, Json.GSON.fromJson(Json.GSON.toJson(e), ExternalEvent.class));

        String legacy =
                "{\"id\":\"e1\",\"type\":\"a.b\",\"version\":1,\"server\":\"s\","
                        + "\"occurredAt\":\"t\",\"playerName\":\"Alex\",\"data\":{}}";
        ExternalEvent old = Json.GSON.fromJson(legacy, ExternalEvent.class);
        assertEquals("t", old.occurredAt());
        assertEquals("Alex", old.playerName());
        assertNull(old.player());
    }
}
