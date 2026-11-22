package wiki.creeper.harbor.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.event.ExternalEvent;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.model.JobTier;
import wiki.creeper.harbor.common.util.Json;
import wiki.creeper.harbor.core.api.ExternalEvents;
import wiki.creeper.harbor.web.http.NetworkOnline;
import wiki.creeper.harbor.web.http.RateLimiter;
import wiki.creeper.harbor.web.http.RecentEvents;
import wiki.creeper.harbor.web.http.Router;
import wiki.creeper.harbor.web.relay.EventCatalog;
import wiki.creeper.harbor.web.relay.EventSerializer;
import wiki.creeper.harbor.web.relay.EventWire;
import wiki.creeper.harbor.web.relay.Globs;
import wiki.creeper.harbor.web.webhook.WebhookRules;
import wiki.creeper.harbor.web.webhook.WebhookTarget;

class WebTest {

    /**
     * Every event class in the catalog exists and is a Bukkit Event, and types are unique and
     * well-formed.
     */
    @Test
    void catalogClassesExist() throws ClassNotFoundException {
        Set<String> types = new HashSet<>();
        for (EventCatalog.Entry e : EventCatalog.ENTRIES) {
            Class<?> c = Class.forName(e.className(), false, getClass().getClassLoader());
            assertTrue(Event.class.isAssignableFrom(c), e.className());
            assertTrue(types.add(e.type()), "type 중복: " + e.type());
            assertTrue(e.type().matches(ExternalEvents.TYPE_PATTERN), e.type());
        }
        EventCatalog.SERVER_TYPES.forEach(t -> assertTrue(t.matches(ExternalEvents.TYPE_PATTERN)));
    }

    public static final class SampleEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        public record Reward(long gold, List<String> items) {}

        public UUID player() {
            return UUID.fromString("00000000-0000-0000-0000-000000000001");
        }

        public FishId fish() {
            return new FishId(HarborId.parse("fish/harbor_mackerel"));
        }

        public double getSizeCm() {
            return 31.5;
        }

        public boolean isFirst() {
            return true;
        }

        public Reward reward() {
            return new Reward(100, List.of("item/bait/earthworm"));
        }

        public Optional<String> note() {
            return Optional.empty();
        }

        public JobTier tier() {
            return JobTier.SECOND;
        }

        public Set<String> tags() {
            return new java.util.LinkedHashSet<>(List.of("b", "c", "a"));
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    @Test
    void serializerReadsGettersAndResolvesNames() {
        EventSerializer s =
                new EventSerializer(
                        id ->
                                id.toString().equals("harbor:fish/harbor_mackerel")
                                        ? Optional.of("고등어")
                                        : Optional.empty());
        EventSerializer.Result r = s.serialize(new SampleEvent());
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000001"), r.player());
        Map<String, Object> d = r.data();
        assertEquals("harbor:fish/harbor_mackerel", d.get("fish"));
        assertEquals("고등어", d.get("fish_name"));
        assertEquals(31.5, d.get("size_cm"));
        assertEquals(true, d.get("first"));
        assertEquals(
                Map.of("gold", 100L, "items", List.of("item/bait/earthworm")), d.get("reward"));
        assertFalse(d.containsKey("note"));
        assertFalse(d.containsKey("handlers"));
        // job tier: numeric level + stable key
        assertEquals(2, d.get("tier"));
        assertEquals("second", d.get("tier_key"));
        // sets are sorted
        assertEquals(List.of("a", "b", "c"), d.get("tags"));
    }

    /** Integers survive the Redis round trip (no 13.0), and output keys are snake_case. */
    @Test
    void wireKeepsIntegersAndSnakeCase() {
        String payload =
                "{\"id\":\"e1\",\"type\":\"server.player_join\",\"version\":1,\"server\":\"main-1\","
                        + "\"occurredAt\":\"2026-11-20T12:00:00Z\",\"player\":\"u1\",\"playerName\":\"Steve\","
                        + "\"data\":{\"online\":13,\"size\":41.2,\"whole\":33.0,\"big\":9007199254740993,"
                        + "\"nested\":{\"gold\":100},\"list\":[1,2.5],\"none\":null}}";
        ExternalEvent e = EventWire.decode(payload);
        assertEquals(13L, e.data().get("online"));
        assertEquals(41.2, e.data().get("size"));
        assertEquals(33.0, e.data().get("whole"));
        assertEquals(9007199254740993L, e.data().get("big"));
        assertEquals(Map.of("gold", 100L), e.data().get("nested"));
        assertEquals(List.of(1L, 2.5), e.data().get("list"));
        assertFalse(e.data().containsKey("none"));

        Map<String, Object> out = EventWire.toJson(e);
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
                List.copyOf(out.keySet()));
        String json = Json.GSON.toJson(out);
        assertTrue(json.contains("\"online\":13,") || json.contains("\"online\":13}"), json);
        assertTrue(json.contains("\"big\":9007199254740993"), json);
        assertTrue(json.contains("\"whole\":33.0"), json);
        assertFalse(json.contains("13.0"), json);
        String webhook = EventWire.GSON.toJson(out);
        assertTrue(webhook.contains("\"occurred_at\":\"2026-11-20T12:00:00Z\""), webhook);
        assertTrue(webhook.contains("\"gold\":100"), webhook);
        assertEquals("13", WebhookRules.flatten(e.data()).get("online"));

        // absent player fields are left out
        ExternalEvent bare =
                new ExternalEvent("e2", "server.started", 1, "s", "t", null, null, Map.of());
        assertFalse(EventWire.toJson(bare).containsKey("player"));
        assertFalse(EventWire.toJson(bare).containsKey("player_name"));
    }

    @Test
    void queryValidation() throws Router.ApiException {
        Router.Request ok =
                new Router.Request(Map.of(), Router.query("limit=9999&scope=season&n=-5"));
        assertEquals(500, ok.queryInt("limit", 100, 1, 500));
        assertEquals(1, ok.queryInt("n", 10, 1, 10));
        assertEquals(7, ok.queryInt("missing", 7, 1, 10));
        assertEquals("season", ok.queryChoice("scope", "total", List.of("total", "season")));
        assertEquals("total", ok.queryChoice("missing", "total", List.of("total", "season")));

        Router.Request bad = new Router.Request(Map.of(), Router.query("limit=abc&scope=SEASON"));
        Router.ApiException notNumber =
                assertThrows(Router.ApiException.class, () -> bad.queryInt("limit", 50, 1, 200));
        assertEquals(400, notNumber.status());
        assertEquals("bad_parameter", notNumber.code());
        assertEquals("limit", notNumber.param());
        Router.ApiException notChoice =
                assertThrows(
                        Router.ApiException.class,
                        () -> bad.queryChoice("scope", "total", List.of("total", "season")));
        assertEquals("bad_parameter", notChoice.code());
        assertEquals(List.of("total", "season"), notChoice.allowed());
    }

    @Test
    void networkOnlineIgnoresStaleBeats() {
        NetworkOnline n = new NetworkOnline();
        assertTrue(n.snapshot(0).online().isEmpty());
        n.beat("main-1", 10, 1_000);
        n.beat("lobby-1", 3, 10_000);
        assertEquals(13, n.snapshot(12_000).online().orElseThrow());
        assertEquals(2, n.snapshot(12_000).servers());
        // main-1's beat is now older than 15 s
        assertEquals(3, n.snapshot(20_000).online().orElseThrow());
        assertTrue(n.snapshot(40_000).online().isEmpty());
    }

    @Test
    void recentEventsPageCursor() {
        RecentEvents recent = new RecentEvents(10);
        for (int i = 0; i < 5; i++) {
            recent.add(
                    new ExternalEvent(
                            String.valueOf(i),
                            i % 2 == 0 ? "a.even" : "a.odd",
                            1,
                            "s",
                            "t",
                            null,
                            null,
                            Map.of()));
        }
        RecentEvents.Page full = recent.page(0, "", 2);
        assertEquals(List.of(1L, 2L), full.items().stream().map(RecentEvents.Item::seq).toList());
        assertEquals(2, full.next());
        RecentEvents.Page rest = recent.page(2, "a.even", 10);
        assertEquals(List.of(3L, 5L), rest.items().stream().map(RecentEvents.Item::seq).toList());
        assertEquals(5, rest.next());
        assertEquals(5, recent.page(5, "", 10).next());
        // after beyond last: the buffer restarted
        assertEquals(0, recent.page(99, "", 10).next());
    }

    @Test
    void globsAndWebhookRules() {
        assertTrue(Globs.matches("quest.*", "quest.completed"));
        assertFalse(Globs.matches("quest.*", "questx"));
        assertTrue(Globs.matches("*", "anything"));
        ExternalEvent e =
                new ExternalEvent(
                        "1",
                        "fishing.caught",
                        1,
                        "main-1",
                        "t",
                        null,
                        null,
                        Map.of(
                                "grade",
                                "legendary",
                                "reward",
                                Map.of("gold", 100.0),
                                "tags",
                                List.of("a", "b")));
        Map<String, String> flat = WebhookRules.flatten(e.data());
        assertEquals("100", flat.get("reward.gold"));
        assertEquals("a, b", flat.get("tags"));
        WebhookTarget legendary =
                new WebhookTarget(
                        "d",
                        "http://x",
                        WebhookTarget.Format.DISCORD,
                        "",
                        List.of("fishing.*"),
                        Map.of("grade", "legendary"));
        WebhookTarget rare =
                new WebhookTarget(
                        "d",
                        "http://x",
                        WebhookTarget.Format.DISCORD,
                        "",
                        List.of("fishing.*"),
                        Map.of("grade", "rare"));
        assertTrue(WebhookRules.accepts(legendary, e));
        assertFalse(WebhookRules.accepts(rare, e));
        assertEquals(
                "sha256=49f24e537407743fa4a0242bb63b94b9a47ee99cbbe071ccd8a22550ae411686",
                WebhookRules.signature("secret", "1700000000", "{\"a\":1}"));
    }

    @Test
    void routerAndLimiterAndRecent() throws Router.ApiException {
        Router router = new Router();
        router.get(
                "players/{id}/collection",
                "",
                r -> r.param("id") + ":" + r.query("scope", "total"));
        var m = router.match("/players/Steve/collection").orElseThrow();
        assertEquals(
                "Steve:season",
                m.getKey().handle(new Router.Request(m.getValue(), Router.query("scope=season"))));
        assertTrue(router.match("/players/Steve").isEmpty());
        assertEquals("a b", Router.query("q=a%20b").get("q"));

        RateLimiter limiter = new RateLimiter(2);
        assertTrue(limiter.allow("t", 0));
        assertTrue(limiter.allow("t", 1));
        assertFalse(limiter.allow("t", 2));
        assertTrue(limiter.allow("t", 60_000));

        RecentEvents recent = new RecentEvents(10);
        for (int i = 0; i < 15; i++) {
            recent.add(
                    new ExternalEvent(
                            String.valueOf(i),
                            i % 2 == 0 ? "a.even" : "a.odd",
                            1,
                            "s",
                            "t",
                            null,
                            null,
                            Map.of()));
        }
        assertEquals(15, recent.last());
        assertEquals(10, recent.after(0, "", 100).size());
        assertEquals(
                List.of(14L, 15L),
                recent.after(13, "", 100).stream().map(RecentEvents.Item::seq).toList());
        assertTrue(
                recent.after(0, "a.even", 100).stream()
                        .allMatch(i -> i.event().type().equals("a.even")));
    }
}
