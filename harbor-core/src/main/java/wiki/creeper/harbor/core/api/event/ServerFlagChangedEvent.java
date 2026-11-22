package wiki.creeper.harbor.core.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;

/**
 * Fired on the main thread after a server flag changes (including changes from other servers). A
 * null value means the flag was turned off.
 */
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
