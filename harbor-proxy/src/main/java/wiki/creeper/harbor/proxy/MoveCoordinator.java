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
import java.util.function.BooleanSupplier;
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
    private final SeatReservations seats;
    private final BooleanSupplier redisUp;
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
            SeatReservations seats,
            BooleanSupplier redisUp,
            Logger log) {
        this.proxy = proxy;
        this.config = config;
        this.directory = directory;
        this.bus = bus;
        this.db = db;
        this.seats = seats;
        this.redisUp = redisUp;
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
                    boolean toLobby = request.destination().equals(config.lobby());
                    LobbyHook hook = lobbyHook;
                    if (toLobby && hook != null) {
                        hook.before(request.player());
                    }
                    var _ =
                            move(
                                            player.get(),
                                            request.destination(),
                                            request.reason(),
                                            request.requestId())
                                    .thenAccept(
                                            result -> {
                                                if (toLobby && hook != null) {
                                                    hook.after(
                                                            request.player(),
                                                            request.origin(),
                                                            result.ok());
                                                }
                                                reply(request.origin(), result);
                                            });
                });
    }

    /** 백엔드가 요청한 로비 이동 앞뒤 (대기열: 자동 출항 막기·자리 유예). */
    interface LobbyHook {
        void before(UUID player);

        void after(UUID player, String origin, boolean ok);
    }

    private volatile @Nullable LobbyHook lobbyHook;

    void onLobbyMove(LobbyHook hook) {
        this.lobbyHook = hook;
    }

    /** ServerPreConnectEvent: 백엔드 간 연결은 여기서 승인한 것만 허용한다. 승인 표시는 한 번 쓰면 지운다. */
    boolean consumeApproval(UUID player, String server) {
        return approved.remove(player, server);
    }

    /**
     * 저장 절차 없이 바로 보내도 되는 이동을 승인한다. 본 서버가 내보낸(꺼진) 플레이어를 로비로 돌려보낼 때만 쓴다: 정상 종료면 그 서버가 이미 저장·잠금 해제를
     * 했고, 비정상 종료면 잠금이 남아 로비 입장이 잠시(잠금 만료까지) 거부된다 (로비도 잠금을 잡으므로 복제는 없다).
     */
    void approveFallback(UUID player, String lobby) {
        approved.put(player, lobby);
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
        // Redis 장애 시 이동 중단 (docs 20 1.6: 핸드오프 잠금을 못 다루면 복제 방지가 깨진다)
        if (!redisUp.getAsBoolean()) {
            return done(requestId, uuid, false, "redis_down");
        }
        if (!destination.equals(config.lobby()) && !destinationOpen(uuid, destination)) {
            return done(requestId, uuid, false, "destination_unavailable");
        }
        if (!inProgress.add(uuid)) {
            return done(requestId, uuid, false, "busy");
        }
        directory.moveStarted(uuid, destination);
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
                .whenComplete(
                        (r, e) -> {
                            directory.moveFinished(uuid, destination, r != null && r.ok());
                            inProgress.remove(uuid);
                        });
    }

    /** 예약 자리로의 귀환은 수용 한도를 넘어도 받는다 (하트비트·입장 허용만 확인). */
    private boolean destinationOpen(UUID player, String destination) {
        return seats.holds(player, destination)
                ? directory.isAlive(destination)
                : directory.canAccept(destination, 1);
    }

    /** 모든 백엔드(로비 포함)가 잠금을 잡고 저장하므로, 출발 서버가 있으면 항상 저장 → 잠금 넘김을 거친다. */
    private boolean needsHandoff(@Nullable String origin) {
        return origin != null;
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
