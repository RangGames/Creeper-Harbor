package wiki.creeper.harbor.collection.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;

/**
 * Codex milestone (region completion, cumulative completion stage). Titles are granted directly by
 * Collection via Season TitleService (reference ID {@code codex:<season>:<title key>}); cosmetics
 * are granted by Shop listening to this event. title is the granted title key.
 *
 * @see Kind
 */
public final class CodexMilestoneEvent extends Event {

    /** Milestone kind. */
    public enum Kind {
        REGION_COMPLETE,
        TOTAL_PERCENT
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final Kind kind;
    private final String key;
    private final int percent;
    private final @Nullable String title;

    public CodexMilestoneEvent(
            UUID player, Kind kind, String key, int percent, @Nullable String title) {
        this.player = player;
        this.kind = kind;
        this.key = key;
        this.percent = percent;
        this.title = title;
    }

    public UUID player() {
        return player;
    }

    public Kind kind() {
        return kind;
    }

    /** Region name or completion stage key. */
    public String key() {
        return key;
    }

    public int percent() {
        return percent;
    }

    /** Title ID to grant (null if none). */
    public @Nullable String title() {
        return title;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
