package wiki.creeper.harbor.season.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.season.api.TitleId;

/** 칭호를 새로 얻은 뒤(DB 커밋 후) 메인 스레드에서 발생한다 (추가 제안). 이미 가진 칭호면 발생하지 않는다. 플레이어가 오프라인일 수도 있다. */
public final class TitleGrantedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final TitleId title;
    private final @Nullable String refId;

    public TitleGrantedEvent(UUID player, TitleId title, @Nullable String refId) {
        this.player = player;
        this.title = title;
        this.refId = refId;
    }

    public UUID player() {
        return player;
    }

    public TitleId title() {
        return title;
    }

    public @Nullable String refId() {
        return refId;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
