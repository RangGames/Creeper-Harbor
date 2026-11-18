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
 * 인스턴스 이동 (docs 20 1.4 인스턴스 배정, 0.1). 배정·수명 관리는 Boss(harbor:instance)가 끝내고, Proxy는 받은 배정대로 옮기기만 한다.
 *
 * <ul>
 *   <li>InstanceRequest의 server는 Boss가 배정한 서버 ID다. Proxy는 서버를 고르지 않고, 하트비트로 그 서버가 살아 있고 입장을 받는지만 본다
 *   <li>파티원마다 MoveCoordinator 절차를 실행하고, 한 명이라도 실패하면 이미 옮긴 인원은 그대로 두고 부분 실패를 돌려준다
 *   <li>본 서버에서 출발한 사람은 그 자리를 예약해 귀환이 대기열을 거치지 않게 한다
 *   <li>재접속: 로비에서 리소스팩 적용이 끝나면 harbor:boss:rejoin:&lt;uuid&gt;가 살아 있는지 보고, 있으면 대기열 없이 그 서버로
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
            // kind가 배정된 서버 ID가 아님 (예: dungeon·raid 종류 이름). 서버 선택은 Boss 몫이라 고르지 않는다
            return fail(request, first, "unassigned_server");
        }
        if (!directory.isAlive(server)) {
            return fail(request, first, "destination_unavailable");
        }
        List<CompletableFuture<InstanceRules.MemberResult>> moves = new ArrayList<>();
        for (int i = 0; i < party.size(); i++) {
            UUID member = party.get(i);
            // 요청 ID는 세션 잠금 값(handoff:<서버>:<요청 ID>)에 들어가므로 ':'를 쓰지 않는다
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
     * 재접속 키가 살아 있으면 그 인스턴스 서버로 보낸다 (대기열 없음). 옮겼으면 true. Redis 장애·키 없음·서버 이상이면 false (호출한 쪽이 대기열에
     * 넣는다).
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
