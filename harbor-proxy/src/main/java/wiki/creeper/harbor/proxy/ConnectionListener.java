package wiki.creeper.harbor.proxy;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Optional;
import net.kyori.adventure.text.Component;

/** 접속 흐름 (docs 20 1.4): 점검 확인 → 첫 서버는 항상 로비 → 백엔드 간 이동은 MoveCoordinator 승인분만 → 본 서버가 죽으면 로비로 회수. */
final class ConnectionListener {

    private final ProxyServer proxy;
    private final ProxyConfig config;
    private final ProxyText text;
    private final MaintenanceService maintenance;
    private final MoveCoordinator mover;
    private final QueueManager queue;
    private final ChatRelay chat;
    private final JoinLog joinLog;

    ConnectionListener(
            ProxyServer proxy,
            ProxyConfig config,
            ProxyText text,
            MaintenanceService maintenance,
            MoveCoordinator mover,
            QueueManager queue,
            ChatRelay chat,
            JoinLog joinLog) {
        this.proxy = proxy;
        this.config = config;
        this.text = text;
        this.maintenance = maintenance;
        this.mover = mover;
        this.queue = queue;
        this.chat = chat;
        this.joinLog = joinLog;
    }

    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        if (maintenance.blocks(event.getUniqueId())) {
            event.setResult(
                    PreLoginEvent.PreLoginComponentResult.denied(maintenance.kickMessage()));
        }
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        joinLog.joined(
                player.getUniqueId(),
                player.getUsername(),
                player.getRemoteAddress().getAddress(),
                player.getProtocolVersion().getProtocol(),
                player.getClientBrand(),
                config.lobby());
    }

    @Subscribe
    public void onChooseInitial(PlayerChooseInitialServerEvent event) {
        proxy.getServer(config.lobby()).ifPresent(event::setInitialServer);
    }

    /** 승인하지 않은 백엔드 간 이동은 모두 거부한다 (저장을 건너뛰는 길을 막는다). */
    @Subscribe
    public void onPreConnect(ServerPreConnectEvent event) {
        RegisteredServer previous = event.getPreviousServer();
        if (previous == null) {
            return;
        }
        Optional<RegisteredServer> target = event.getResult().getServer();
        String name = target.map(s -> s.getServerInfo().getName()).orElse("");
        if (!mover.consumeApproval(event.getPlayer().getUniqueId(), name)) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            event.getPlayer().sendMessage(text.get("move.unapproved"));
        }
    }

    /** 본 서버·인스턴스가 죽거나 재시작하면 로비로 보내고 재접속 유예를 준다. */
    @Subscribe
    public void onKicked(KickedFromServerEvent event) {
        Component reason = event.getServerKickReason().orElse(Component.empty());
        if (event.kickedDuringServerConnect()) {
            event.setResult(KickedFromServerEvent.Notify.create(reason));
            return;
        }
        String from = event.getServer().getServerInfo().getName();
        if (from.equals(config.lobby())) {
            return;
        }
        Optional<RegisteredServer> lobby = proxy.getServer(config.lobby());
        if (lobby.isPresent()) {
            queue.grantGrace(event.getPlayer().getUniqueId());
            event.setResult(
                    KickedFromServerEvent.RedirectPlayer.create(
                            lobby.get(), text.get("move.returned_lobby")));
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        String server =
                player.getCurrentServer()
                        .map(ServerConnection::getServerInfo)
                        .map(i -> i.getName())
                        .orElse("");
        if (config.mainServers().containsKey(server)) {
            queue.grantGrace(player.getUniqueId());
        }
        queue.remove(player.getUniqueId());
        chat.forget(player.getUniqueId());
        joinLog.left(player.getUniqueId(), null);
    }
}
