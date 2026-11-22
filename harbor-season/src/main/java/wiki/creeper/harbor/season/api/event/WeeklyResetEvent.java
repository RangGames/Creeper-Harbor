package wiki.creeper.harbor.season.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.model.SeasonId;

/**
 * Fired on the main thread at the weekly reset time (default Monday 06:00). Fired separately on
 * each server.
 */
public final class WeeklyResetEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final SeasonId season;
    private final int week;

    public WeeklyResetEvent(SeasonId season, int week) {
        this.season = season;
        this.week = week;
    }

    public SeasonId season() {
        return season;
    }

    /** Season week right after the reset. */
    public int week() {
        return week;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
