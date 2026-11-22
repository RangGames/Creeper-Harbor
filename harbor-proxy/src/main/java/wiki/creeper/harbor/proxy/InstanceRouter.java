package wiki.creeper.harbor.proxy;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.proxy.ProxyMessages;
import wiki.creeper.harbor.common.proxy.ProxyMessages.InstanceRequest;
import wiki.creeper.harbor.common.proxy.ProxyMessages.MoveResult;
import wiki.creeper.harbor.common.redis.MessageBus;
import wiki.creeper.harbor.common.redis.Redis;

/**
 * Instance moves (docs 20 1.4 instance assignment, 0.1). Boss (harbor:instance) completes
 * assignment and lifecycle management; Proxy only moves players as assigned.
 *
 * <ul>
 *   <li>The server in an InstanceRequest is the server ID Boss assigned. Proxy does not pick
 *       servers; it only checks via heartbeat that the server is alive and accepting entries
 *   <li>Runs the MoveCoordinator procedure per party member; if even one fails, those already moved
 *       stay and a partial failure is returned
 *   <li>Players departing the main server have their seat reserved so their return skips the queue
 *   <li>Rejoin: once the resource pack is applied in the lobby, checks whether
 *       harbor:boss:rejoin:&lt;uuid&gt; is alive and, if so, sends the player to that server
 *       without the queue
 * </ul>
 */
final class InstanceRouter {

    private final ProxyServer proxy;
    private final ProxyConfig config;
    private final ServerDirectory directory;
    private final MoveCoordinator mover;
    private final SeatReservations seats;
    private final MessageBus bus;
    private final Redis redis;
    private final Executor io;
    private final Logger log;

    InstanceRouter(
            ProxyServer proxy,
            ProxyConfig config,
            ServerDirectory directory,
            MoveCoordinator mover,
            SeatReservations seats,
            MessageBus bus,
            Redis redis,
            Executor io,
            Logger log) {
        this.proxy = proxy;
        this.config = config;
        this.directory = directory;
        this.mover = mover;
        this.seats = seats;
        this.bus = bus;
        this.redis = redis;
        this.io = io;
        this.log = log;
    }

    void start() {
        bus.subscribe(
                ProxyMessages.toProxy(InstanceRequest.class),
                envelope -> {
                    InstanceRequest request = envelope.payload();
                    var _ =
                            route(request)
                                    .thenAccept(
                                            result ->
                                                    bus.publish(
                                                            ProxyMessages.toServer(
                                                                    request.origin(),
                                                                    MoveResult.class),
                                                            result));
                });
    }

    CompletableFuture<MoveResult> route(InstanceRequest request) {
        List<UUID> party = List.copyOf(request.party());
        String server = request.server();
        if (party.isEmpty()) {
            return CompletableFuture.completedFuture(
                    new MoveResult(request.requestId(), new UUID(0, 0), false, "empty_party"));
        }
        UUID first = party.getFirst();
        if (proxy.getServer(server).isEmpty()) {
            // kind is not an assigned server ID (e.g. a kind name like dungeon/raid). Server
            // selection is Boss's job, so do not pick
            return fail(request, first, "unassigned_server");
        }
        if (!directory.isAlive(server)) {
            return fail(request, first, "destination_unavailable");
        }
        List<CompletableFuture<InstanceRules.MemberResult>> moves = new ArrayList<>();
        for (int i = 0; i < party.size(); i++) {
            UUID member = party.get(i);
            // The request ID goes into the session lock value (handoff:<server>:<request ID>), so
            // ':' is not used
            String memberRequest = request.requestId() + "-" + i;
            moves.add(moveMember(member, server, memberRequest));
        }
        return CompletableFuture.allOf(moves.toArray(CompletableFuture[]::new))
                .thenApply(
                        ignored -> {
                            List<InstanceRules.MemberResult> results =
                                    moves.stream().map(CompletableFuture::join).toList();
                            String summary = InstanceRules.summarize(results);
                            boolean ok = summary.equals("ok");
                            if (!ok) {
                                log.warn(
                                        "인스턴스 이동 부분 실패 {} → {}: {}",
                                        request.requestId(),
                                        server,
                                        summary);
                            }
                            return new MoveResult(request.requestId(), first, ok, summary);
                        });
    }

    private CompletableFuture<InstanceRules.MemberResult> moveMember(
            UUID member, String server, String requestId) {
        Optional<Player> player = proxy.getPlayer(member);
        if (player.isEmpty()) {
            return CompletableFuture.completedFuture(
                    new InstanceRules.MemberResult(member, false, "offline"));
        }
        String origin =
                player.get()
                        .getCurrentServer()
                        .map(ServerConnection::getServerInfo)
                        .map(i -> i.getName())
                        .orElse("");
        return mover.move(player.get(), server, "instance", requestId)
                .thenApply(
                        result -> {
                            if (result.ok() && config.mainServers().containsKey(origin)) {
                                seats.reserve(member, origin);
                            }
                            return new InstanceRules.MemberResult(
                                    member, result.ok(), result.reason());
                        })
                .exceptionally(e -> new InstanceRules.MemberResult(member, false, "error"));
    }

    /**
     * If the rejoin key is alive, sends the player to that instance server (no queue). true if
     * moved. false on Redis failure, missing key or server problems (the caller puts them in the
     * queue).
     */
    CompletableFuture<Boolean> tryRejoin(Player player) {
        UUID uuid = player.getUniqueId();
        return CompletableFuture.supplyAsync(
                        () ->
                                InstanceRules.parseRejoin(
                                        redis.sync().get(InstanceRules.rejoinKey(uuid))),
                        io)
                .exceptionally(e -> Optional.empty())
                .thenCompose(
                        server -> {
                            if (server.isEmpty()
                                    || proxy.getServer(server.get()).isEmpty()
                                    || !directory.isAlive(server.get())) {
                                return CompletableFuture.completedFuture(false);
                            }
                            log.info("인스턴스 재접속: {} → {}", player.getUsername(), server.get());
                            return mover.move(player, server.get(), "boss_rejoin")
                                    .thenApply(MoveResult::ok);
                        });
    }

    private static CompletableFuture<MoveResult> fail(
            InstanceRequest request, UUID first, String reason) {
        return CompletableFuture.completedFuture(
                new MoveResult(request.requestId(), first, false, reason));
    }
}
