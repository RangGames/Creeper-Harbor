package wiki.creeper.harbor.economy.service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.core.api.HandoffBlocker;

/**
 * 처리 중 거래 표시 (docs 20 7.3 EconomyState '처리 중 거래 표시', 7.4 경계 상황). 플레이어마다 한 번에 하나의 거래(판매·구매·경매
 * 등록·낙찰·제작·우편 수령)만 허용하고, 진행 중이면 서버 이동을 그 거래가 끝날 때까지 미룬다 (Core HandoffBlocker Delay). 제한 시간 안에 끝나지
 * 않으면 Core가 이동을 취소한다. 거래가 끝나면 인벤토리를 곧 저장한다 (PlayerService.saveSoon).
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
            if (guard.inFlight.remove(player, this)) {
                // 거래 결과(재화·경매·우편 행)는 이미 커밋됐다: 인벤토리도 곧 저장해 서버가 죽어도 어긋나지 않게 한다
                guard.afterTrade.accept(player);
            }
            future.complete(null);
        }

        /** future가 끝나면 (성공·실패 모두) 표시를 지운다. */
        public <T> CompletableFuture<T> track(CompletableFuture<T> work) {
            var _ = work.whenComplete((r, e) -> done());
            return work;
        }
    }

    private final Map<UUID, Ticket> inFlight = new ConcurrentHashMap<>();
    private final Consumer<UUID> afterTrade;
    private final Predicate<UUID> frozen;

    /**
     * @param afterTrade 거래가 끝날 때 (성공·실패 모두, 아무 스레드). 보통 {@code PlayerService::saveSoon}
     * @param frozen 서버 이동 저장본을 뜬 플레이어인지 (메인 스레드). 보통 {@code PlayerService::frozen}
     */
    public TradeGuard(Consumer<UUID> afterTrade, Predicate<UUID> frozen) {
        this.afterTrade = afterTrade;
        this.frozen = frozen;
    }

    /** 거래를 시작한다. 이미 진행 중인 거래가 있으면 빈 값. */
    public Optional<Ticket> begin(UUID player) {
        // 서버 이동 저장본을 뜬 뒤 시작한 거래는 저장본 밖에서 아이템을 옮겨 복제된다
        if (frozen.test(player)) {
            return Optional.empty();
        }
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
