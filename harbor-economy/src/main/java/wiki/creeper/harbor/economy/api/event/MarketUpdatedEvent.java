package wiki.creeper.harbor.economy.api.event;

import java.time.Instant;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** 시세 표가 새 값으로 바뀐 뒤 (어시장 게시판 갱신). */
public final class MarketUpdatedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Instant hour;

    public MarketUpdatedEvent(Instant hour) {
        this.hour = hour;
    }

    public Instant hour() {
        return hour;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
