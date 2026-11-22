package wiki.creeper.harbor.jobs.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired after a skill reset is committed. scope: common · line · all. */
public final class SkillResetEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final String scope;
    private final long refunded;

    public SkillResetEvent(UUID player, String scope, long refunded) {
        this.player = player;
        this.scope = scope;
        this.refunded = refunded;
    }

    public UUID player() {
        return player;
    }

    public String scope() {
        return scope;
    }

    public long refunded() {
        return refunded;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
