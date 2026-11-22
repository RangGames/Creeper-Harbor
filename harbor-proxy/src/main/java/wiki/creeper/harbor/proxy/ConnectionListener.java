package wiki.creeper.harbor.proxy;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.util.ServerLink;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.donor.DonorTier;
import wiki.creeper.harbor.common.proxy.ProxyMessages;
import wiki.creeper.harbor.common.redis.Redis;

/**
 * Connection flow (docs 20 1.4).
 *
 * <ol>
 *   <li>PreLogin: refuse during maintenance (the UUID here is client-supplied, so the bypass
 *       decision is made again at Login)
 *   <li>Login (EventTask, parallel on virtual threads): BAN sanctions (DB, refuse on failure),
 *       donor tier (Redis), leftover session lock (Redis). Lookups fall back to defaults if not
 *       done within 1 second
 *   <li>The first server is always the lobby. join_log is recorded, then multi-account detection
 *       (async, does not affect the connection)
 *   <li>Backend-to-backend moves only when approved by MoveCoordinator. If the main server or an
 *       instance dies, players are recovered to the lobby
 * </ol>
 */
final class ConnectionListener {

    private final ProxyServer proxy;
    private final ProxyConfig config;
    private final ProxyText text;
    private final MaintenanceService maintenance;
    private final MoveCoordinator mover;
    private final QueueManager queue;
    private final ChatRelay chat;
    private final JoinLog joinLog;
    private final SanctionService sanctions;
    private final AltDetector alts;
    private final StaleLockWatcher lockWatcher;
    private final SeatReservations seats;
    private final Redis redis;
    private final Executor io;
    private final Logger log;
    private final List<ServerLink> links;

    ConnectionListener(
            ProxyServer proxy,
            ProxyConfig config,
            ProxyText text,
            MaintenanceService maintenance,
            MoveCoordinator mover,
            QueueManager queue,
            ChatRelay chat,
            JoinLog joinLog,
            SanctionService sanctions,
            AltDetector alts,
            StaleLockWatcher lockWatcher,
            SeatReservations seats,
            Redis redis,
            Executor io,
            Logger log) {
        this.proxy = proxy;
        this.config = config;
        this.text = text;
        this.maintenance = maintenance;
        this.mover = mover;
        this.queue = queue;
        this.chat = chat;
        this.joinLog = joinLog;
        this.sanctions = sanctions;
        this.alts = alts;
        this.lockWatcher = lockWatcher;
        this.seats = seats;
        this.redis = redis;
        this.io = io;
        this.log = log;
        this.links = buildLinks(config.links(), log);
    }

    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        if (maintenance.blocks(event.getUniqueId())) {
            event.setResult(
                    PreLoginEvent.PreLoginComponentResult.denied(maintenance.kickMessage()));
        }
    }

    @Subscribe
    public EventTask onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (maintenance.blocks(uuid)) {
            event.setResult(ResultedEvent.ComponentResult.denied(maintenance.kickMessage()));
            return EventTask.resumeWhenComplete(CompletableFuture.completedFuture(null));
        }
        long lookupMs = config.login().lookupTimeoutMs();
        CompletableFuture<Void> sanction =
                sanctions
                        .loadAtLogin(uuid)
                        .orTimeout(config.login().sanctionTimeoutMs(), TimeUnit.MILLISECONDS)
                        .handle(
                                (check, error) -> {
                                    if (error != null) {
                                        // On DB failure, refuse only new connections (docs 20 1.6)
                                        log.warn(
                                                "제재 확인 실패, 접속 거부 {}: {}",
                                                player.getUsername(),
                                                String.valueOf(error.getMessage()));
                                        event.setResult(
                                                ResultedEvent.ComponentResult.denied(
                                                        text.get("login.unavailable")));
                                    } else {
                                        Sanction ban = check.ban();
                                        if (ban != null) {
                                            event.setResult(
                                                    ResultedEvent.ComponentResult.denied(
                                                            sanctions.banMessage(ban)));
                                        }
                                    }
                                    return null;
                                });
        CompletableFuture<Void> donor =
                CompletableFuture.supplyAsync(() -> DonorTier.lookup(redis, uuid), io)
                        .completeOnTimeout(DonorTier.NONE, lookupMs, TimeUnit.MILLISECONDS)
                        .exceptionally(e -> DonorTier.NONE)
                        .thenAccept(tier -> queue.setDonor(uuid, tier));
        CompletableFuture<Void> lock =
                CompletableFuture.runAsync(() -> lockWatcher.checkAtLogin(uuid), io)
                        .completeOnTimeout(null, lookupMs, TimeUnit.MILLISECONDS)
                        .exceptionally(e -> null);
        return EventTask.resumeWhenComplete(CompletableFuture.allOf(sanction, donor, lock));
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        seats.markOnline(uuid);
        String hash = joinLog.ipHash(player.getRemoteAddress().getAddress());
        String name = player.getUsername();
        var _ =
                joinLog.joined(
                                uuid,
                                name,
                                hash,
                                player.getProtocolVersion().getProtocol(),
                                player.getClientBrand(),
                                config.lobby())
                        .thenCompose(ignored -> alts.observe(uuid, name, hash));
    }

    @Subscribe
    public void onChooseInitial(PlayerChooseInitialServerEvent event) {
        proxy.getServer(config.lobby()).ifPresent(event::setInitialServer);
    }

    /** Refuses all unapproved backend-to-backend moves (closes the path that skips saving). */
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

    /**
     * Releases the instance return seat reservation when the player re-enters the main server.
     * Removes them from the queue when they leave the lobby (left by another route).
     */
    @Subscribe
    public void onConnected(ServerConnectedEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        String server = event.getServer().getServerInfo().getName();
        if (config.mainServers().containsKey(server)) {
            var _ = seats.release(uuid);
            queue.leaveStay(uuid);
        }
        if (!server.equals(config.lobby())) {
            queue.remove(uuid);
        }
    }

    /** Sends pause-menu server links once connected to the first server (lobby). */
    @Subscribe
    public void onPostConnect(ServerPostConnectEvent event) {
        if (event.getPreviousServer() != null || links.isEmpty()) {
            return;
        }
        try {
            event.getPlayer().setServerLinks(links);
        } catch (RuntimeException e) {
            // Unsupported connection, e.g. clients older than 1.21
            log.debug("서버 링크를 보내지 못했습니다: {}", String.valueOf(e.getMessage()));
        }
    }

    /**
     * When the main server or an instance dies or restarts, sends players to the lobby and grants a
     * reconnect grace period.
     */
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
            // Sending back to the lobby is an approved move (so onPreConnect does not block it)
            mover.approveFallback(event.getPlayer().getUniqueId(), config.lobby());
            event.setResult(
                    KickedFromServerEvent.RedirectPlayer.create(
                            lobby.get(), text.get("move.returned_lobby")));
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String server =
                player.getCurrentServer()
                        .map(ServerConnection::getServerInfo)
                        .map(i -> i.getName())
                        .orElse("");
        if (config.mainServers().containsKey(server)) {
            queue.grantGrace(uuid);
        } else if (!server.equals(config.lobby()) && seats.seatOf(uuid).isPresent()) {
            // Disconnected from an instance: keep the main server seat for the grace period (can
            // return via the Boss rejoin key)
            seats.markOffline(uuid, Instant.now());
            queue.grantGrace(uuid);
        }
        queue.forget(uuid);
        chat.forget(uuid);
        sanctions.forget(uuid);
        joinLog.left(uuid);
        // The resource pack must be received again on the next connection (the client unloaded the
        // pack)
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                redis.sync().del(ProxyMessages.PACK_APPLIED_PREFIX + uuid);
                            } catch (RuntimeException e) {
                                // Redis failure: expires via TTL
                            }
                        });
    }

    private static List<ServerLink> buildLinks(List<ProxyConfig.Link> links, Logger log) {
        List<ServerLink> result = new ArrayList<>();
        for (ProxyConfig.Link link : links) {
            String type = link.type();
            try {
                result.add(
                        type != null
                                ? ServerLink.serverLink(
                                        ServerLink.Type.valueOf(type), link.url().toString())
                                : ServerLink.serverLink(
                                        MiniMessage.miniMessage().deserialize(link.label()),
                                        link.url().toString()));
            } catch (IllegalArgumentException e) {
                log.warn("서버 링크 설정 오류 ({}): {}", type, String.valueOf(e.getMessage()));
            }
        }
        return List.copyOf(result);
    }
}
