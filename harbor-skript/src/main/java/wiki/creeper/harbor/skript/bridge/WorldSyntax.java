package wiki.creeper.harbor.skript.bridge;

import java.util.List;
import org.bukkit.event.Event;
import wiki.creeper.harbor.skript.lambda.Registrar;
import wiki.creeper.harbor.skript.lambda.Spec;
import wiki.creeper.harbor.world.api.ActiveSeaEvent;
import wiki.creeper.harbor.world.api.event.SeaEventEndEvent;
import wiki.creeper.harbor.world.api.event.SeaEventStartEvent;

/**
 * Harbor-World가 켜져 있을 때: 해역 이벤트 시작·끝 (event-string = 해역 이벤트 ID, harbor sea event name · region).
 */
public final class WorldSyntax {

    private WorldSyntax() {}

    public static void register(Registrar r) {
        r.event(
                "Harbor Sea Event Start",
                SeaEventStartEvent.class,
                "해역 이벤트가 시작된 뒤 (event-string = 해역 이벤트 ID)",
                "harbor sea event start");
        r.value(SeaEventStartEvent.class, String.class, e -> e.seaEvent().eventId().toString());
        r.event(
                "Harbor Sea Event End",
                SeaEventEndEvent.class,
                "해역 이벤트가 끝난 뒤 (event-string = 해역 이벤트 ID)",
                "harbor sea event end");
        r.value(SeaEventEndEvent.class, String.class, e -> e.seaEvent().eventId().toString());

        r.text(
                Spec.single("harbor sea event name"),
                (e, x) -> {
                    ActiveSeaEvent s = seaEvent(e);
                    return s == null ? List.of() : List.of(s.name());
                },
                "[the] harbor sea event name");
        r.text(
                Spec.single("harbor sea event region"),
                (e, x) -> {
                    ActiveSeaEvent s = seaEvent(e);
                    return s == null || s.region() == null
                            ? List.of()
                            : List.of(String.valueOf(s.region()));
                },
                "[the] harbor sea event region");
    }

    private static @org.jspecify.annotations.Nullable ActiveSeaEvent seaEvent(Event e) {
        if (e instanceof SeaEventStartEvent s) {
            return s.seaEvent();
        }
        if (e instanceof SeaEventEndEvent s) {
            return s.seaEvent();
        }
        return null;
    }
}
