package wiki.creeper.harbor.core.api;

import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Publishes external integration events (proposed). Events from every server gather on Redis {@code
 * harbor:events}, and Harbor-Web sends them as webhooks or exposes them over REST. External
 * services may also subscribe directly. Format: Common {@link
 * wiki.creeper.harbor.common.event.ExternalEvent}.
 *
 * <p>Game module events are relayed automatically by Harbor-Web, so modules rarely need to call
 * this. It is for other plugins (Skript, etc.) to publish their own events.
 *
 * <pre>{@code
 * Harbor.api().events().emit("skript.lottery_won", player.getUniqueId(), player.getName(),
 *         Map.of("prize", 1000));
 * }</pre>
 *
 * Safe to call from any thread (publishing is asynchronous; failures are only logged).
 */
public interface ExternalEvents {

    /** type format: lowercase letters, digits and underscores joined by dots (module.event). */
    String TYPE_PATTERN = "[a-z0-9_]+(\\.[a-z0-9_]+)+";

    /**
     * @param data values may be strings, numbers, booleans, UUIDs, lists or maps (anything else
     *     uses toString)
     */
    void emit(String type, @Nullable UUID player, @Nullable String playerName, Map<String, ?> data);

    /** When Redis is unavailable, etc.: does nothing. */
    ExternalEvents NOOP = (type, player, playerName, data) -> {};
}
