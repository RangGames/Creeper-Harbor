package wiki.creeper.harbor.boss.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.boss.api.EncounterInfo;
import wiki.creeper.harbor.boss.api.EncounterState;

/**
 * 페이즈 전환 (사운드·연출 훅). 메인 스레드.
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
     * 전환 이유 (ready, timeout, hooked, hp, tug, tug_failed, hull, time, absent, admin, shutdown …).
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
