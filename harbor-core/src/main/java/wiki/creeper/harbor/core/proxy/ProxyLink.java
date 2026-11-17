package wiki.creeper.harbor.core.proxy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import wiki.creeper.harbor.common.config.InfraConfig;
import wiki.creeper.harbor.common.proxy.ProxyMessages;
import wiki.creeper.harbor.common.proxy.ProxyMessages.InstanceRequest;
import wiki.creeper.harbor.common.proxy.ProxyMessages.MoveRequest;
import wiki.creeper.harbor.common.proxy.ProxyMessages.MoveResult;
import wiki.creeper.harbor.common.proxy.ProxyMessages.ServerHeartbeat;
import wiki.creeper.harbor.common.redis.MessageBus;
import wiki.creeper.harbor.core.api.ProxyBridge;
import wiki.creeper.harbor.core.task.Tasks;

/** 프록시 연동: 이동 요청(ProxyBridge)과 5초마다 하트비트. */
public final class ProxyLink implements ProxyBridge {

    private static final long HEARTBEAT_TICKS = 5 * 20L;
    /** 이동 요청 응답 제한. 프록시의 핸드오프 제한(5초)보다 길게 둔다. */
    private static final Duration MOVE_TIMEOUT = Duration.ofSeconds(10);

    private final InfraConfig infra;
    private final MessageBus bus;
    private final Tasks tasks;
    private final BooleanSupplier accepting;
    private final Map<String, CompletableFuture<MoveResult>> pending = new ConcurrentHashMap<>();

    public ProxyLink(InfraConfig infra, MessageBus bus, Tasks tasks, BooleanSupplier accepting) {
        this.infra = infra;
        this.bus = bus;
        this.tasks = tasks;
        this.accepting = accepting;
    }

    public void start() {
        bus.subscribe(ProxyMessages.toServer(infra.serverId(), MoveResult.class), envelope -> {
            MoveResult result = envelope.payload();
            CompletableFuture<MoveResult> future = pending.remove(result.requestId());
            if (future != null) {
                tasks.main().execute(() -> future.complete(result));
            }
        });
        var unused = tasks.timer(HEARTBEAT_TICKS, HEARTBEAT_TICKS, this::heartbeat);
    }

    @Override
    public CompletableFuture<MoveResult> requestMove(UUID player, String destination, String reason) {
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<MoveResult> future = track(requestId, player);
        bus.publish(ProxyMessages.toProxy(MoveRequest.class),
                new MoveRequest(requestId, player, infra.serverId(), destination, reason));
        return future;
    }

    @Override
    public CompletableFuture<MoveResult> requestInstance(List<UUID> party, String kind) {
        if (party.isEmpty()) {
            throw new IllegalArgumentException("파티가 비었습니다");
        }
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<MoveResult> future = track(requestId, party.getFirst());
        bus.publish(ProxyMessages.toProxy(InstanceRequest.class),
                new InstanceRequest(requestId, List.copyOf(party), kind, infra.serverId()));
        return future;
    }

    private CompletableFuture<MoveResult> track(String requestId, UUID player) {
        CompletableFuture<MoveResult> future = new CompletableFuture<>();
        pending.put(requestId, future);
        var unused = tasks.later(MOVE_TIMEOUT.toSeconds() * 20, () -> {
            CompletableFuture<MoveResult> timedOut = pending.remove(requestId);
            if (timedOut != null) {
                timedOut.complete(new MoveResult(requestId, player, false, "timeout"));
            }
        });
        return future;
    }

    private void heartbeat() {
        ServerHeartbeat beat = new ServerHeartbeat(
                infra.serverId(),
                infra.role().key(),
                Bukkit.getOnlinePlayers().size(),
                Bukkit.getMaxPlayers(),
                Bukkit.getTPS()[0],
                accepting.getAsBoolean());
        var unused = tasks.async(() -> bus.publish(ProxyMessages.HEARTBEATS, beat));
    }
}
