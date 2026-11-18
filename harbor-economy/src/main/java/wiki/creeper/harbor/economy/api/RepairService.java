package wiki.creeper.harbor.economy.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.util.Result;

/**
 * 수리비 (docs 20 7.4): 장비 가격 × 3% (내구도 0이면 10%) × repair_cost 보정 (수리공 −30%). World 선박 수리도 선박 가격을 넘겨
 * 같은 식으로 계산한다. 메인 스레드에서 부르고 future는 메인 스레드에서 완료된다.
 */
public interface RepairService {

    /** 수리비 견적 (보정 포함, 최소 1G). */
    long quote(UUID player, long equipmentPrice, boolean broken);

    /**
     * 수리비를 받는다 (사유 REPAIR). extra는 같은 트랜잭션에서 실행할 SQL (선박 내구도 복구 등).
     *
     * @return 처리 후 잔액
     */
    CompletableFuture<Result<Long, LedgerError>> pay(
            UUID player, long cost, String refId, @Nullable SqlConsumer extra);
}
