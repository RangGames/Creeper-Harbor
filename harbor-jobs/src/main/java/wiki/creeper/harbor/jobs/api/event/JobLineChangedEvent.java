package wiki.creeper.harbor.jobs.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.model.JobLine;

/** Fired after a line change is committed. */
public final class JobLineChangedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final JobLine from;
    private final JobLine to;

    public JobLineChangedEvent(UUID player, JobLine from, JobLine to) {
        this.player = player;
        this.from = from;
        this.to = to;
    }

    public UUID player() {
        return player;
    }

    public JobLine from() {
        return from;
    }

    public JobLine to() {
        return to;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
