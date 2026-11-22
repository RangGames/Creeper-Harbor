package wiki.creeper.harbor.collection.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Aquarium stage increase (Quest climax check). Fired only on the server that raised the stage. */
public final class AquariumStageChangedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int season;
    private final int stage;

    public AquariumStageChangedEvent(int season, int stage) {
        this.season = season;
        this.stage = stage;
    }

    public int season() {
        return season;
    }

    public int stage() {
        return stage;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
