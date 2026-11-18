package wiki.creeper.harbor.quest.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** 메인 스토리 장 클리어 (final 퀘스트 완료) 또는 연대기 회상(recap). 공지·연대기. 메인 스레드에서 발생한다. */
public final class ChapterCompletedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final int chapter;
    private final boolean recap;

    public ChapterCompletedEvent(UUID player, int chapter, boolean recap) {
        this.player = player;
        this.chapter = chapter;
        this.recap = recap;
    }

    public UUID player() {
        return player;
    }

    public int chapter() {
        return chapter;
    }

    public boolean recap() {
        return recap;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
