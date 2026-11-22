package wiki.creeper.harbor.season.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.model.SeasonId;

/**
 * Fired on the main thread right after a player's first login in a new season, once cleanup
 * (SeasonGate) is done and saved.
 */
public final class PlayerSeasonEnteredEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final int previousSeason;
    private final SeasonId season;
    private final int removedStacks;

    public PlayerSeasonEnteredEvent(
            Player player, int previousSeason, SeasonId season, int removedStacks) {
        this.player = player;
        this.previousSeason = previousSeason;
        this.season = season;
        this.removedStacks = removedStacks;
    }

    public Player player() {
        return player;
    }

    /** Last cleaned season (0 if first time). */
    public int previousSeason() {
        return previousSeason;
    }

    public SeasonId season() {
        return season;
    }

    /** Number of item stacks removed. */
    public int removedStacks() {
        return removedStacks;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
