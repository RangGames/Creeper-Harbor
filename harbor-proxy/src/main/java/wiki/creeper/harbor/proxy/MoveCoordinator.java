package wiki.creeper.harbor.proxy;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.proxy.ProxyMessages;
import wiki.creeper.harbor.common.proxy.ProxyMessages.MoveRequest;
import wiki.creeper.harbor.common.proxy.ProxyMessages.MoveResult;
import wiki.creeper.harbor.common.proxy.ProxyMessages.TransferAbort;
import wiki.creeper.harbor.common.proxy.ProxyMessages.TransferPrepare;
import wiki.creeper.harbor.common.proxy.ProxyMessages.TransferReady;
import wiki.creeper.harbor.common.redis.MessageBus;
import wiki.creeper.harbor.common.session.SessionAudit;
import wiki.creeper.harbor.common.session.SessionLocks;

/**
 * 서버 이동 지휘 (docs 20 1.4, 세션 핸드오프 '16. 플러그인 설계' 4.5). 모든 백엔드 간 이동은 여기를 거친다.
 *
 * <ol>
 *   <li>목적지 상태 확인 (하트비트 15초 이상 끊김, 입장 불허, 수용 초과면 거절)
 *   <li>출발 서버가 로비가 아니면 TransferPrepare → 5초 안에 TransferReady
 *   <li>승인 표시 후 connect(). 실패하면 TransferAbort로 잠금을 되돌린다
 * </ol>
 *
 * 플레이어당 이동은 하나만 진행한다.
 */
final class MoveCoordinator {

    static final long READY_TIMEOUT_SECONDS = 5;

    private final ProxyServer proxy;
    private final ProxyConfig config;
    private final ServerDirectory directory;
    private final MessageBus bus;
    private final Database db;
    private final Logger log;
    private final Set<UUID> inProgress = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> approved = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<TransferReady>> waitingReady =
            new ConcurrentHashMap<>();

    MoveCoordinator(
            ProxyServer proxy,
            ProxyConfig config,
            ServerDirectory directory,
            MessageBus bus,
            Database db,
            Logger log) {
        this.proxy = proxy;
        this.config = config;
        this.directory = directory;
        this.bus = bus;
        this.db = db;
        this.log = log;
    }

    void start() {
        bus.subscribe(
                ProxyMessages.toProxy(TransferReady.class),
                envelope -> {
                    CompletableFuture<TransferReady> future =
                            waitingReady.remove(envelope.payload().requestId());
                    if (future != null) {
                        future.complete(envelope.payload());
                    }
                });
        bus.subscribe(
                ProxyMessages.toProxy(MoveRequest.class),
                envelope -> {
                    MoveRequest request = envelope.payload();
                    Optional<Player> player = proxy.getPlayer(request.player());
                    if (player.isEmpty()) {
                        reply(
                                request.origin(),
                                new MoveResult(
                                        request.requestId(), request.player(), false, "offline"));
                        return;
                    }
                    var _ =
                            move(
                                            player.get(),
                                            request.destination(),
                                            request.reason(),
                                            request.requestId())
                                    .thenAccept(result -> reply(request.origin(), result));
                });
    }

    /** ServerPreConnectEvent: 백엔드 간 연결은 여기서 승인한 것만 허용한다. 승인 표시는 한 번 쓰면 지운다. */
    boolean consumeApproval(UUID player, String server) {
        return approved.remove(player, server);
    }

    boolean isMoving(UUID player) {
        return inProgress.contains(player);
    }

    CompletableFuture<MoveResult> move(Player player, String destination, String reason) {
        return move(player, destination, reason, UUID.randomUUID().toString());
    }

    CompletableFuture<MoveResult> move(
            Player player, String destination, String reason, String requestId) {
        UUID uuid = player.getUniqueId();
        Optional<RegisteredServer> target = proxy.getServer(destination);
        if (target.isEmpty()) {
            return done(requestId, uuid, false, "unknown_server");
        }
        String origin =
                player.getCurrentServer()
                        .map(ServerConnection::getServerInfo)
                        .map(i -> i.getName())
                        .orElse(null);
        if (destination.equals(origin)) {
            return done(requestId, uuid, false, "already_there");
        }
        if (!destination.equals(config.lobby()) && !directory.canAccept(destination, 1)) {
            return done(requestId, uuid, false, "destination_unavailable");
        }
        if (!inProgress.add(uuid)) {
            return done(requestId, uuid, false, "busy");
        }
        CompletableFuture<Boolean> prepared =
                needsHandoff(origin)
                        ? prepare(origin, uuid, destination, requestId)
                        : CompletableFuture.completedFuture(true);
        return prepared.thenCompose(
                        ok -> {
                            if (!ok) {
                                return CompletableFuture.completedFuture(
                                        new MoveResult(requestId, uuid, false, "prepare_failed"));
                            }
                            approved.put(uuid, destination);
                            return player.createConnectionRequest(target.get())
                                    .connect()
                                    .thenApply(
                                            result -> {
                                                approved.remove(uuid, destination);
                                                if (result.isSuccessful()) {
                                                    recordSession(uuid, destination);
                                                    return new MoveResult(
                                                            requestId, uuid, true, "ok");
                                                }
                                                if (origin != null && needsHandoff(origin)) {
                                                    abort(origin, uuid, requestId);
                                                }
                                                return new MoveResult(
                                                        requestId,
                                                        uuid,
                                                        false,
                                                        "connect_"
                                                                + result.getStatus()
                                                                        .name()
                                                                        .toLowerCase(Locale.ROOT));
                                            });
                        })
                .exceptionally(
                        e -> {
                            log.warn(
                                    "서버 이동 실패 {} → {}: {}",
                                    player.getUsername(),
                                    destination,
                                    e.getMessage());
                            if (origin != null && needsHandoff(origin)) {
                                abort(origin, uuid, requestId);
                            }
                            return new MoveResult(requestId, uuid, false, "error");
                        })
                .whenComplete((r, e) -> inProgress.remove(uuid));
    }

    /** 로비는 잠금을 잡지 않으므로 로비에서 나갈 때는 핸드오프가 필요 없다. */
    private boolean needsHandoff(@Nullable String origin) {
        return origin != null && !origin.equals(config.lobby());
    }

    private CompletableFuture<Boolean> prepare(
            @Nullable String origin, UUID uuid, String destination, String requestId) {
        if (origin == null) {
            return CompletableFuture.completedFuture(true);
        }
        CompletableFuture<TransferReady> ready = new CompletableFuture<>();
        waitingReady.put(requestId, ready);
        bus.publish(
                ProxyMessages.toServer(origin, TransferPrepare.class),
                new TransferPrepare(requestId, uuid, destination));
        return ready.orTimeout(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .handle(
                        (reply, e) -> {
                            waitingReady.remove(requestId);
                            if (e != null || reply == null || !reply.ok()) {
                                // 시간 초과면 출발 서버가 handoff로 바꿨을 수도 있으므로 되돌리게 한다
                                abort(origin, uuid, requestId);
                                return false;
                            }
                            return true;
                        });
    }

    private void abort(String origin, UUID uuid, String requestId) {
        bus.publish(
                ProxyMessages.toServer(origin, TransferAbort.class),
                new TransferAbort(requestId, uuid));
    }

    private void recordSession(UUID uuid, String server) {
        if (server.equals(config.lobby())) {
            return;
        }
        Instant until = Instant.now().plus(SessionLocks.TTL);
        var _ =
                db.asyncVoid(c -> SessionAudit.recordOwner(c, uuid, server, until))
                        .exceptionally(
                                e -> {
                                    log.warn("sessions 기록 실패: {}", uuid);
                                    return null;
                                });
    }

    private void reply(String origin, MoveResult result) {
        bus.publish(ProxyMessages.toServer(origin, MoveResult.class), result);
    }

    private static CompletableFuture<MoveResult> done(
            String requestId, UUID uuid, boolean ok, String reason) {
        return CompletableFuture.completedFuture(new MoveResult(requestId, uuid, ok, reason));
    }
}
