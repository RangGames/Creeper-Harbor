package wiki.creeper.harbor.core.api.event;

import java.util.Set;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.content.ContentSnapshot;

/** 새 콘텐츠 스냅샷이 적용된 뒤 메인 스레드에서 발생한다. 캐시를 쓰는 모듈은 여기서 다시 만든다. */
public final class ContentReloadedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ContentSnapshot snapshot;
    private final Set<String> reloadedTypes;

    public ContentReloadedEvent(ContentSnapshot snapshot, Set<String> reloadedTypes) {
        this.snapshot = snapshot;
        this.reloadedTypes = Set.copyOf(reloadedTypes);
    }

    public ContentSnapshot snapshot() {
        return snapshot;
    }

    /** 이번에 다시 읽은 형식 이름들. */
    public Set<String> reloadedTypes() {
        return reloadedTypes;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
