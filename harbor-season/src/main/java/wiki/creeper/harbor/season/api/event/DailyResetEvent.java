package wiki.creeper.harbor.season.api.event;

import java.time.LocalDate;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired on the main thread at the daily reset time (default 06:00). Fired separately on each
 * server.
 */
public final class DailyResetEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final LocalDate gameDay;

    public DailyResetEvent(LocalDate gameDay) {
        this.gameDay = gameDay;
    }

    /** New game day (Core HarborTime.gameDay). */
    public LocalDate gameDay() {
        return gameDay;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
