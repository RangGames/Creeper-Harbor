package wiki.creeper.harbor.boss.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.boss.api.EncounterInfo;
import wiki.creeper.harbor.boss.api.EncounterState;

/**
 * Phase change (hook for sounds and effects). Main thread.
 *
 * @see wiki.creeper.harbor.boss.api.EncounterState
 */
public final class BossPhaseChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final EncounterInfo encounter;
    private final EncounterState from;
    private final EncounterState to;
    private final String cause;

    public BossPhaseChangeEvent(
            EncounterInfo encounter, EncounterState from, EncounterState to, String cause) {
        this.encounter = encounter;
        this.from = from;
        this.to = to;
        this.cause = cause;
    }

    public EncounterInfo encounter() {
        return encounter;
    }

    public EncounterState from() {
        return from;
    }

    public EncounterState to() {
        return to;
    }

    /**
     * Reason for the change (ready, timeout, hooked, hp, tug, tug_failed, hull, time, absent,
     * admin, shutdown …).
     */
    public String cause() {
        return cause;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
