package wiki.creeper.harbor.shop.pearl;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import wiki.creeper.harbor.shop.data.ShopRepository;

/**
 * Pearl changes within one transaction. After commit, PearlManager updates the cache and events for
 * each changed wallet.
 */
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

    /** Rejects if the shop ban is active (chargeback debt, etc.). */
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
