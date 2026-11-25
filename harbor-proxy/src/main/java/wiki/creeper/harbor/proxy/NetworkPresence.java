package wiki.creeper.harbor.proxy;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import wiki.creeper.harbor.common.proxy.ProxyMessages;
import wiki.creeper.harbor.common.proxy.ProxyMessages.NetworkJoin;
import wiki.creeper.harbor.common.proxy.ProxyMessages.NetworkQuit;
import wiki.creeper.harbor.common.redis.MessageBus;

/**
 * Network join and quit (proposed): tells every backend when a player connects to or leaves the
 * whole network, as opposed to a backend's own join/quit, which also fire on every server move.
 * Backends turn these into Core NetworkJoinEvent / NetworkQuitEvent. A quit is only sent for a
 * player whose join was sent (a login refused after PostLogin never happens; one refused before it
 * sends neither).
 */
public final class NetworkPresence {

    private final MessageBus bus;
    private final Set<UUID> announced = ConcurrentHashMap.newKeySet();

    NetworkPresence(MessageBus bus) {
        this.bus = bus;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        announced.add(player.getUniqueId());
        bus.publish(
                ProxyMessages.NETWORK_JOINS,
                new NetworkJoin(player.getUniqueId(), player.getUsername()));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        if (!announced.remove(player.getUniqueId())) {
            return;
        }
        String server =
                player.getCurrentServer()
                        .map(ServerConnection::getServerInfo)
                        .map(i -> i.getName())
                        .orElse("");
        bus.publish(
                ProxyMessages.NETWORK_QUITS,
                new NetworkQuit(player.getUniqueId(), player.getUsername(), server));
    }
}
