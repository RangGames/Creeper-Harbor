package wiki.creeper.harbor.core.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;

/** 서버 플래그가 바뀐 뒤 (다른 서버에서 바뀐 것 포함) 메인 스레드에서 발생한다. value가 null이면 꺼진 것. */
public final class ServerFlagChangedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String flag;
    private final @Nullable String value;

    public ServerFlagChangedEvent(String flag, @Nullable String value) {
        this.flag = flag;
        this.value = value;
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
