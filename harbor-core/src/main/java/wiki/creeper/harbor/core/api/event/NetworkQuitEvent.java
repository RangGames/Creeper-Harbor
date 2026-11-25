package wiki.creeper.harbor.core.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A player left the network (disconnected from the proxy), fired once on every backend server on
 * the main thread. Unlike PlayerQuitEvent it does not fire on server moves inside the network. Only
 * fires for players whose {@link NetworkJoinEvent} fired. Needs Redis and Harbor-Proxy.
 */
public final class NetworkQuitEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final String name;
    private final String lastServer;

    public NetworkQuitEvent(UUID player, String name, String lastServer) {
        this.player = player;
        this.name = name;
        this.lastServer = lastServer;
    }

    public UUID player() {
        return player;
    }

    public String name() {
        return name;
    }

    /** The backend the player was on when leaving ("" if they never reached one). */
    public String lastServer() {
        return lastServer;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
