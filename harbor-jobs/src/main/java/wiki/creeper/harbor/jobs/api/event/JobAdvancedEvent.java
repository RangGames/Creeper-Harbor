package wiki.creeper.harbor.jobs.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.model.JobLine;
import wiki.creeper.harbor.common.model.JobTier;

/**
 * Fired on the main thread after a job advancement is committed (Quest listens and completes the
 * advancement quest).
 */
public final class JobAdvancedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final JobTier tier;
    private final JobLine line;

    public JobAdvancedEvent(UUID player, JobTier tier, JobLine line) {
        this.player = player;
        this.tier = tier;
        this.line = line;
    }

    public UUID player() {
        return player;
    }

    public JobTier tier() {
        return tier;
    }

    public JobLine line() {
        return line;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
