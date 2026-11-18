package wiki.creeper.harbor.boss.api.event;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.boss.api.EncounterInfo;

/** 조우 시작 직전 (소환 조건 검사 통과 뒤, 준비 단계 전). 취소하면 시작하지 않는다 (점검 중 차단 등). 메인 스레드. */
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

    /** 관리자 강제 시작·월드 보스·인스턴스 자동 시작이면 true. */
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
