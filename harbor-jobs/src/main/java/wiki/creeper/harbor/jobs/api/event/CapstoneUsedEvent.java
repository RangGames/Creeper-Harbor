package wiki.creeper.harbor.jobs.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.id.SkillId;

/** 캡스톤을 쓴 뒤 발생한다. */
public final class CapstoneUsedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final SkillId node;

    public CapstoneUsedEvent(UUID player, SkillId node) {
        this.player = player;
        this.node = node;
    }

    public UUID player() {
        return player;
    }

    public SkillId node() {
        return node;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
