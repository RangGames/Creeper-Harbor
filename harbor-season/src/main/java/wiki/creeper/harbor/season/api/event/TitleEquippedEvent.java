package wiki.creeper.harbor.season.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.season.api.TitleId;

/** 장착 칭호가 바뀐 뒤 메인 스레드에서 발생한다 (추가 제안, 이름표·채팅 접두사 갱신). title이 null이면 해제. */
public final class TitleEquippedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final @Nullable TitleId title;

    public TitleEquippedEvent(UUID player, @Nullable TitleId title) {
        this.player = player;
        this.title = title;
    }

    public UUID player() {
        return player;
    }

    public @Nullable TitleId title() {
        return title;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
