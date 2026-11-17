package wiki.creeper.harbor.jobs.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.model.JobLine;
import wiki.creeper.harbor.common.model.JobTier;

/** 전직이 커밋된 뒤 메인 스레드에서 발생한다 (Quest가 듣고 전직 퀘스트를 완료 처리). */
public final class JobAdvancedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final JobTier tier;
    private final JobLine line;

    public JobAdvancedEvent(UUID player, JobTier tier, JobLine line) {
        this.player = player;
        this.tier = tier;
        this.line = line;
    }

    public UUID player() {
        return player;
    }

    public JobTier tier() {
        return tier;
    }

    public JobLine line() {
        return line;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
