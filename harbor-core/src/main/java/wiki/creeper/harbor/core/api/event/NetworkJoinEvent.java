package wiki.creeper.harbor.core.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A player connected to the network (the proxy finished their login), fired once on every backend
 * server on the main thread. Unlike PlayerJoinEvent it does not fire on server moves inside the
 * network. The player is usually not on this server yet (they head to the lobby), and the order
 * against the lobby's own PlayerJoinEvent is not guaranteed. Needs Redis and Harbor-Proxy.
 */
public final class NetworkJoinEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final String name;

    public NetworkJoinEvent(UUID player, String name) {
        this.player = player;
        this.name = name;
    }

    public UUID player() {
        return player;
    }

    public String name() {
        return name;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
