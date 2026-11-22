package wiki.creeper.harbor.season.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.model.SeasonId;

/**
 * Fired on the main thread on the first tick after the week changes (week-locked quest unlock
 * notices, weekly request refresh). Fired separately on each server.
 */
public final class SeasonWeekChangedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final SeasonId season;
    private final int previousWeek;
    private final int week;

    public SeasonWeekChangedEvent(SeasonId season, int previousWeek, int week) {
        this.season = season;
        this.previousWeek = previousWeek;
        this.week = week;
    }

    public SeasonId season() {
        return season;
    }

    public int previousWeek() {
        return previousWeek;
    }

    /** New week (13 and above is an overtime week). */
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
