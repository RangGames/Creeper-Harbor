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
import wiki.creeper.harbor.common.redis.Redis;

/**
 * 접속 흐름 (docs 20 1.4).
 *
 * <ol>
 *   <li>PreLogin: 점검 중이면 거부 (여기 UUID는 클라이언트가 보낸 값이라 bypass 판단은 Login에서 다시 한다)
 *   <li>Login (EventTask, 가상 스레드 병렬): BAN 제재(DB, 실패하면 거부), 후원 등급(Redis), 남은 세션 잠금(Redis). 조회는 1초 안에
 *       안 끝나면 기본값
 *   <li>첫 서버는 항상 로비. join_log 기록 후 다계정 탐지 (비동기, 접속에 영향 없음)
 *   <li>백엔드 간 이동은 MoveCoordinator 승인분만. 본 서버·인스턴스가 죽으면 로비로 회수
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
                                        // DB 장애 시 신규 접속만 거부 (docs 20 1.6)
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

    /** 본 서버에 다시 들어오면 인스턴스 귀환 자리 예약을 푼다. */
    @Subscribe
    public void onConnected(ServerConnectedEvent event) {
        if (config.mainServers().containsKey(event.getServer().getServerInfo().getName())) {
            var _ = seats.release(event.getPlayer().getUniqueId());
            queue.leaveStay(event.getPlayer().getUniqueId());
        }
    }

    /** 처음 들어온 서버(로비)에 연결되면 일시정지 메뉴 서버 링크를 보낸다. */
    @Subscribe
    public void onPostConnect(ServerPostConnectEvent event) {
        if (event.getPreviousServer() != null || links.isEmpty()) {
            return;
        }
        try {
            event.getPlayer().setServerLinks(links);
        } catch (RuntimeException e) {
            // 1.21 미만 클라이언트 등 지원하지 않는 연결
            log.debug("서버 링크를 보내지 못했습니다: {}", String.valueOf(e.getMessage()));
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
            // 로비로 돌려보내는 것은 승인된 이동이다 (onPreConnect가 막지 않게)
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
            // 인스턴스에서 끊김: 본 서버 자리는 유예 시간만큼 유지 (Boss 재접속 키로 돌아올 수 있음)
            seats.markOffline(uuid, Instant.now());
            queue.grantGrace(uuid);
        }
        queue.forget(uuid);
        chat.forget(uuid);
        sanctions.forget(uuid);
        joinLog.left(uuid);
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
