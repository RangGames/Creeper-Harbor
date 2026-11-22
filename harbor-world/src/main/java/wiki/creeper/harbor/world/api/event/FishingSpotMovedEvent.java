package wiki.creeper.harbor.world.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.world.api.FishingSpot;

/**
 * When a fish school moved to a new spot (regions on this server only). previous is the old spot
 * (null the first time). Wildlife moves the shoal.
 */
public final class FishingSpotMovedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final FishingSpot spot;
    private final @Nullable FishingSpot previous;

    public FishingSpotMovedEvent(FishingSpot spot, @Nullable FishingSpot previous) {
        this.spot = spot;
        this.previous = previous;
    }

    public FishingSpot spot() {
        return spot;
    }

    public @Nullable FishingSpot previous() {
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
