package wiki.creeper.harbor.season.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.season.api.HallOfFameEntry;

/** 명예의 전당 잠정 1위(또는 선착순 명단)가 바뀐 뒤 메인 스레드에서 발생한다 (공지·전광판). 제출을 처리한 서버에서만 발생한다. */
public final class HallOfFameUpdatedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final HallOfFameEntry entry;
    private final @Nullable HallOfFameEntry previous;

    public HallOfFameUpdatedEvent(HallOfFameEntry entry, @Nullable HallOfFameEntry previous) {
        this.entry = entry;
        this.previous = previous;
    }

    public HallOfFameEntry entry() {
        return entry;
    }

    /** 밀려난 기존 1위 (없으면 null). */
    public @Nullable HallOfFameEntry previous() {
        return previous;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
