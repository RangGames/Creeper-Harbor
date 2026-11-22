package wiki.creeper.harbor.world.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.world.api.ActiveSeaEvent;

/** Sea event announced (WARN, all servers). */
public final class SeaEventWarnEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ActiveSeaEvent seaEvent;

    public SeaEventWarnEvent(ActiveSeaEvent seaEvent) {
        this.seaEvent = seaEvent;
    }

    public ActiveSeaEvent seaEvent() {
        return seaEvent;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
