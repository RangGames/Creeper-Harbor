package wiki.creeper.harbor.world.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.world.api.ActiveSeaEvent;

/** 해상 이벤트 시작 (모든 서버). Wildlife는 물고기 대이동 때 wildlifeBoost로 밀도를 올린다. */
public final class SeaEventStartEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ActiveSeaEvent seaEvent;

    public SeaEventStartEvent(ActiveSeaEvent seaEvent) {
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
