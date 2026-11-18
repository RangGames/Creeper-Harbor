package wiki.creeper.harbor.economy.service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.core.api.HandoffBlocker;

/**
 * 처리 중 거래 표시 (docs 20 7.3 EconomyState '처리 중 거래 표시', 7.4 경계 상황). 플레이어마다 한 번에 하나의 거래(판매·구매·경매
 * 등록·낙찰·제작·우편 수령)만 허용하고, 진행 중이면 서버 이동을 그 거래가 끝날 때까지 미룬다 (Core HandoffBlocker Delay). 지급은 우편함을 거치므로
 * 미룬 시간이 넘어 이동해도 도착 서버에서 받는다.
 */
public final class TradeGuard implements HandoffBlocker {

    /** 진행 중인 거래 하나. {@link #done()}을 반드시 부른다 (여러 번 불러도 된다). */
    public static final class Ticket {

        private final TradeGuard guard;
        private final UUID player;
        private final CompletableFuture<Void> future = new CompletableFuture<>();

        private Ticket(TradeGuard guard, UUID player) {
            this.guard = guard;
            this.player = player;
        }

        public void done() {
            guard.inFlight.remove(player, this);
            future.complete(null);
        }

        /** future가 끝나면 (성공·실패 모두) 표시를 지운다. */
        public <T> CompletableFuture<T> track(CompletableFuture<T> work) {
            var _ = work.whenComplete((r, e) -> done());
            return work;
        }
    }

    private final Map<UUID, Ticket> inFlight = new ConcurrentHashMap<>();

    /** 거래를 시작한다. 이미 진행 중인 거래가 있으면 빈 값. */
    public Optional<Ticket> begin(UUID player) {
        Ticket ticket = new Ticket(this, player);
        return inFlight.putIfAbsent(player, ticket) == null
                ? Optional.of(ticket)
                : Optional.empty();
    }

    public boolean busy(UUID player) {
        return inFlight.containsKey(player);
    }

    @Override
    public Verdict check(Player player, String destination) {
        Ticket ticket = inFlight.get(player.getUniqueId());
        return ticket == null ? ALLOW : new Delay(ticket.future);
    }
}
