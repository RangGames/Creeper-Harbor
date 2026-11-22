package wiki.creeper.harbor.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import wiki.creeper.harbor.core.api.ExternalEvents;
import wiki.creeper.harbor.web.http.RateLimiter;
import wiki.creeper.harbor.web.http.RecentEvents;
import wiki.creeper.harbor.web.http.Router;
import wiki.creeper.harbor.web.relay.EventCatalog;
import wiki.creeper.harbor.web.relay.EventSerializer;
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
        router.get("players/{id}/codex", "", r -> r.param("id") + ":" + r.query("scope", "total"));
        var m = router.match("/players/Steve/codex").orElseThrow();
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
