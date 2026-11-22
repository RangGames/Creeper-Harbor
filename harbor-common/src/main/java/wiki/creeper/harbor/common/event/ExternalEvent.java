package wiki.creeper.harbor.common.event;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Game event exported to external integrations (webhooks, website, bots) (proposed). Published on
 * Redis {@code harbor:events}; the envelope payload is this record.
 *
 * <pre>{@code
 * {"type":"ExternalEvent","server":"main-1","sentAt":"…",
 *  "payload":{"id":"…","type":"quest.completed","version":1,"server":"main-1",
 *             "occurredAt":"2026-11-20T12:00:00Z","player":"<uuid>","playerName":"Steve",
 *             "data":{"quest":"harbor:quest/main/ch1_first_cast", "quest_name":"First Cast", …}}}
 * }</pre>
 *
 * @param id unique event ID (UUID text). Receivers use it to filter duplicates
 * @param type {@code <module>.<event>} (lowercase, underscores, dots). See the Harbor-Web README
 *     for the list
 * @param version data format version. Bumped only when a field is removed or its meaning changes
 *     (adding fields keeps it)
 * @param occurredAt ISO-8601 UTC
 * @param player related player UUID (null if none)
 * @param data event content. Values are only strings, numbers, booleans, lists and maps (plain
 *     JSON)
 */
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
}
