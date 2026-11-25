package wiki.creeper.harbor.core.proxy;

import org.bukkit.Bukkit;
import wiki.creeper.harbor.common.proxy.ProxyMessages;
import wiki.creeper.harbor.common.proxy.ProxyMessages.NetworkJoin;
import wiki.creeper.harbor.common.proxy.ProxyMessages.NetworkQuit;
import wiki.creeper.harbor.common.redis.MessageBus;
import wiki.creeper.harbor.core.api.event.NetworkJoinEvent;
import wiki.creeper.harbor.core.api.event.NetworkQuitEvent;
import wiki.creeper.harbor.core.task.Tasks;

/** Turns the proxy's network join/quit messages (harbor:network) into Core events. */
public final class NetworkEvents {

    private NetworkEvents() {}

    public static void subscribe(MessageBus bus, Tasks tasks) {
        bus.subscribe(
                ProxyMessages.NETWORK_JOINS,
                envelope -> {
                    NetworkJoin m = envelope.payload();
                    tasks.main()
                            .execute(
                                    () ->
                                            Bukkit.getPluginManager()
                                                    .callEvent(
                                                            new NetworkJoinEvent(
                                                                    m.player(), m.name())));
                });
        bus.subscribe(
                ProxyMessages.NETWORK_QUITS,
                envelope -> {
                    NetworkQuit m = envelope.payload();
                    tasks.main()
                            .execute(
                                    () ->
                                            Bukkit.getPluginManager()
                                                    .callEvent(
                                                            new NetworkQuitEvent(
                                                                    m.player(),
                                                                    m.name(),
                                                                    m.lastServer())));
                });
    }
}
