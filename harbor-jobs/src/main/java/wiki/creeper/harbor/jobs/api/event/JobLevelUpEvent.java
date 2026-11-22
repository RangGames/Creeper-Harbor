package wiki.creeper.harbor.jobs.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired on the main thread after a level up. */
public final class JobLevelUpEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final int level;

    public JobLevelUpEvent(UUID player, int level) {
        this.player = player;
        this.level = level;
    }

    public UUID player() {
        return player;
    }

    public int level() {
        return level;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
