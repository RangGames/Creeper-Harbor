package wiki.creeper.harbor.shop.pearl;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import wiki.creeper.harbor.shop.data.ShopRepository;

/** 한 트랜잭션 안의 진주 변경 모음. 커밋 뒤 PearlManager가 바뀐 지갑마다 캐시·이벤트를 갱신한다. */
public final class PearlTx {

    private final Connection connection;
    private final PearlLedger ledger;
    private final List<PearlLedger.Applied> applied = new ArrayList<>();
    private final List<PearlLedger.Change> changes = new ArrayList<>();

    PearlTx(Connection connection, PearlLedger ledger) {
        this.connection = connection;
        this.ledger = ledger;
    }

    public Connection connection() {
        return connection;
    }

    public PearlLedger.Applied apply(PearlLedger.Change change) throws SQLException {
        PearlLedger.Applied result = ledger.apply(connection, change);
        applied.add(result);
        changes.add(change);
        return result;
    }

    /** 상점 이용 정지 중이면 거부한다 (지불 거절 debt 등). */
    public void requireUnlocked(UUID player) throws SQLException {
        if (ShopRepository.locked(connection, player)) {
            throw new ShopReject("shop.error.locked");
        }
    }

    List<PearlLedger.Applied> applied() {
        return applied;
    }

    List<PearlLedger.Change> changes() {
        return changes;
    }
}
