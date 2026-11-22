package wiki.creeper.harbor.boss.api.event;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.boss.api.EncounterInfo;

/**
 * Right before an encounter starts (after summon checks pass, before the preparation stage).
 * Cancelling it prevents the start (e.g. blocking during maintenance). Main thread.
 */
public final class BossEncounterStartEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final EncounterInfo encounter;
    private final boolean forced;
    private boolean cancelled;

    public BossEncounterStartEvent(EncounterInfo encounter, boolean forced) {
        this.encounter = encounter;
        this.forced = forced;
    }

    public EncounterInfo encounter() {
        return encounter;
    }

    /** True for admin forced starts, world bosses and automatic instance starts. */
    public boolean forced() {
        return forced;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
