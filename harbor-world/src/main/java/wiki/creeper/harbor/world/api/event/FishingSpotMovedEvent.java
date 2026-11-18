package wiki.creeper.harbor.world.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.world.api.FishingSpot;

/** 물고기 떼가 새 자리로 옮겼을 때 (이 서버의 지역만). previous는 이전 자리 (처음이면 null). Wildlife가 무리를 옮긴다. */
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
