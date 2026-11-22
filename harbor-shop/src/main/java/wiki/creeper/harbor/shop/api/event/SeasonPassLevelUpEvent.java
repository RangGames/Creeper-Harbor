package wiki.creeper.harbor.shop.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * After the season pass level rises (main thread). bought means a pearl level purchase or premium+
 * instant levels.
 */
public final class SeasonPassLevelUpEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final int season;
    private final int previousLevel;
    private final int level;
    private final boolean bought;

    public SeasonPassLevelUpEvent(
            UUID player, int season, int previousLevel, int level, boolean bought) {
        this.player = player;
        this.season = season;
        this.previousLevel = previousLevel;
        this.level = level;
        this.bought = bought;
    }

    public UUID player() {
        return player;
    }

    public int season() {
        return season;
    }

    public int previousLevel() {
        return previousLevel;
    }

    public int level() {
        return level;
    }

    public boolean bought() {
        return bought;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
