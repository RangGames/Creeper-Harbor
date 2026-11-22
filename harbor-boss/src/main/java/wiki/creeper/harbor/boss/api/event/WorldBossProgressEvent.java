package wiki.creeper.harbor.boss.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.boss.api.WorldBossStatus;

/**
 * World boss entered a 25% stage (75/50/25%, announcements and sky effects). Only on the running
 * server, main thread.
 */
public final class WorldBossProgressEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final WorldBossStatus status;
    private final int stage;

    public WorldBossProgressEvent(WorldBossStatus status, int stage) {
        this.status = status;
        this.stage = stage;
    }

    public WorldBossStatus status() {
        return status;
    }

    /** 1 = 75% or less, 2 = 50% or less, 3 = 25% or less. */
    public int stage() {
        return stage;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
