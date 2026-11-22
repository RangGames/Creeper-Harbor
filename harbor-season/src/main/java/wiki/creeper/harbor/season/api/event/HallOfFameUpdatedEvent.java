package wiki.creeper.harbor.season.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.season.api.HallOfFameEntry;

/**
 * Fired on the main thread after the provisional hall of fame top (or first-come list) changes
 * (notices, leaderboards). Fired only on the server that processed the submission.
 */
public final class HallOfFameUpdatedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final HallOfFameEntry entry;
    private final @Nullable HallOfFameEntry previous;

    public HallOfFameUpdatedEvent(HallOfFameEntry entry, @Nullable HallOfFameEntry previous) {
        this.entry = entry;
        this.previous = previous;
    }

    public HallOfFameEntry entry() {
        return entry;
    }

    /** The previous top that was displaced (null if none). */
    public @Nullable HallOfFameEntry previous() {
        return previous;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
