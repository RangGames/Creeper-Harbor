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
 * Server move orchestration (docs 20 1.4, session handoff '16. Plugin design' 4.5). Every
 * backend-to-backend move goes through here.
 *
 * <ol>
 *   <li>Check the destination state (refuse if heartbeat missing for 15+ seconds, entry disallowed,
 *       or over capacity)
 *   <li>If the source server is not the lobby, TransferPrepare → TransferReady within 5 seconds
 *   <li>Mark as approved, then connect(). On failure, revert the lock with TransferAbort
 * </ol>
 *
 * Only one move per player is in progress at a time.
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

    /** Before/after a backend-requested lobby move (queue: block auto-departure, seat grace). */
    interface LobbyHook {
        void before(UUID player);

        void after(UUID player, String origin, boolean ok);
    }

    private volatile @Nullable LobbyHook lobbyHook;

    void onLobbyMove(LobbyHook hook) {
        this.lobbyHook = hook;
    }

    /**
     * ServerPreConnectEvent: only backend-to-backend connections approved here are allowed. An
     * approval mark is cleared once used.
     */
    boolean consumeApproval(UUID player, String server) {
        return approved.remove(player, server);
    }

    /**
     * Approves a move that may be sent directly without the save procedure. Used only to send
     * players kicked by a (shut down) main server back to the lobby: on a clean shutdown that
     * server already saved and released the lock; on a crash the lock remains and lobby entry is
     * refused briefly (until the lock expires) (the lobby also takes the lock, so there is no
     * duplication).
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
        // Abort the move on Redis failure (docs 20 1.6: if the handoff lock cannot be handled,
        // duplication protection breaks)
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

    /**
     * A return to a reserved seat is accepted even over capacity (only heartbeat and entry
     * permission are checked).
     */
    private boolean destinationOpen(UUID player, String destination) {
        return seats.holds(player, destination)
                ? directory.isAlive(destination)
                : directory.canAccept(destination, 1);
    }

    /**
     * Every backend (including the lobby) takes the lock and saves, so with a source server it
     * always goes through save → lock handoff.
     */
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
                                // On timeout the source server may have switched to handoff, so
                                // have it revert
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
