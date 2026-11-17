package wiki.creeper.harbor.core.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;

/** 플레이어 플래그가 DB에 반영된 뒤 메인 스레드에서 발생한다. value가 null이면 꺼진 것. */
public final class PlayerFlagChangedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final String flag;
    private final @Nullable String value;

    public PlayerFlagChangedEvent(UUID player, String flag, @Nullable String value) {
        this.player = player;
        this.flag = flag;
        this.value = value;
    }

    public UUID player() {
        return player;
    }

    public String flag() {
        return flag;
    }

    public @Nullable String value() {
        return value;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
