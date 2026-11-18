package wiki.creeper.harbor.boss.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.boss.api.WorldBossStatus;

/** 월드 보스 25% 단위 단계 진입 (75·50·25%, 공지·하늘 연출). 실행 서버에서만, 메인 스레드. */
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

    /** 1 = 75% 이하, 2 = 50% 이하, 3 = 25% 이하. */
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
