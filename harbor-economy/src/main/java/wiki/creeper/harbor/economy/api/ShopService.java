package wiki.creeper.harbor.economy.api;

import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.util.Result;

/** NPC 상점 (NPC 클릭, World의 선박·장식 구매도 이것을 쓴다). 메인 스레드. */
public interface ShopService {

    /** 상점 화면을 연다. 없는 상점이면 false. */
    boolean open(Player player, HarborId shop);

    /**
     * 다른 모듈의 골드 결제 (World 하우징 장식 HOUSING_DECOR, Fishing 통발 슬롯 확장 TRAP_SLOT_EXPAND …). 처리 중 거래 표시를
     * 걸어 서버 이동을 미루고, 한 번에 하나만 받는다 (진행 중이면 REJECTED). extra는 같은 트랜잭션에서 실행할 SQL (구매한 장식 개수 등).
     * future는 메인 스레드에서 완료된다.
     *
     * @return 처리 후 잔액
     */
    CompletableFuture<Result<Long, LedgerError>> charge(
            Player player,
            long price,
            LedgerReason reason,
            String refId,
            @Nullable SqlConsumer extra);
}
