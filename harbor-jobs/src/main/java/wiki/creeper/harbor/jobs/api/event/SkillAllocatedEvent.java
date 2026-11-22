package wiki.creeper.harbor.jobs.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.SkillId;

/** Fired after a skill investment is committed. */
public final class SkillAllocatedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final SkillId node;
    private final int rank;

    public SkillAllocatedEvent(UUID player, SkillId node, int rank) {
        this.player = player;
        this.node = node;
        this.rank = rank;
    }

    public UUID player() {
        return player;
    }

    public SkillId node() {
        return node;
    }

    public int rank() {
        return rank;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
