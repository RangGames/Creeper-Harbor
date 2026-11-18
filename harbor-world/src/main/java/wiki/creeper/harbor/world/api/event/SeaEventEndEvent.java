package wiki.creeper.harbor.world.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.world.api.ActiveSeaEvent;

/** 해상 이벤트 종료 (모든 서버). */
public final class SeaEventEndEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ActiveSeaEvent seaEvent;

    public SeaEventEndEvent(ActiveSeaEvent seaEvent) {
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
