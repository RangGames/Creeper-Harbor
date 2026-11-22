package wiki.creeper.harbor.core.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;

/**
 * Fired on the main thread after a player flag is written to the DB. A null value means the flag
 * was turned off.
 */
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
