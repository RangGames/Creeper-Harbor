package wiki.creeper.harbor.proxy;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import java.time.Instant;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

/**
 * 서버 목록 화면 MOTD (docs 20 1.4): 동접·대기 인원, 점검 중이면 점검 문구. 최대 인원은 본 서버 수용 한도 합계. 메모리 값만 읽는다 (핑은 자주 온다).
 */
final class ServerListPing {

    private final ProxyServer proxy;
    private final ProxyConfig config;
    private final ProxyText text;
    private final MaintenanceService maintenance;
    private final QueueManager queue;
    private final int maxPlayers;

    ServerListPing(
            ProxyServer proxy,
            ProxyConfig config,
            ProxyText text,
            MaintenanceService maintenance,
            QueueManager queue) {
        this.proxy = proxy;
        this.config = config;
        this.text = text;
        this.maintenance = maintenance;
        this.queue = queue;
        this.maxPlayers = config.mainServers().values().stream().mapToInt(Integer::intValue).sum();
    }

    @Subscribe
    public void onPing(ProxyPingEvent event) {
        ServerPing.Builder ping = event.getPing().asBuilder();
        int online = proxy.getPlayerCount();
        ping.onlinePlayers(online);
        ping.maximumPlayers(maxPlayers);
        ping.description(describe(online));
        event.setPing(ping.build());
    }

    private Component describe(int online) {
        MaintenanceService.State state = maintenance.state();
        if (state.on()) {
            return text.get(
                    "motd.maintenance",
                    Placeholder.unparsed("reason", state.reason()),
                    Placeholder.unparsed(
                            "until",
                            state.until() == 0
                                    ? text.raw("common.none")
                                    : text.time(Instant.ofEpochMilli(state.until()))));
        }
        return text.get(
                "motd.normal",
                Placeholder.unparsed("online", String.valueOf(online)),
                Placeholder.unparsed("queue", String.valueOf(queue.size())),
                Placeholder.unparsed("lobby", config.lobby()));
    }
}
