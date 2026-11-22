package wiki.creeper.harbor.economy.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.util.Result;

/**
 * Repair cost (docs 20 7.4): gear price × 3% (10% at 0 durability) × repair_cost modifier (repairer
 * −30%). World ship repair passes the ship price and is computed the same way. Call on the main
 * thread; the future completes on the main thread.
 */
public interface RepairService {

    /** Repair cost quote (modifiers included, minimum 1G). */
    long quote(UUID player, long equipmentPrice, boolean broken);

    /**
     * Charges the repair cost (reason REPAIR). extra is SQL to run in the same transaction
     * (restoring ship durability, etc.).
     *
     * @return balance after processing
     */
    CompletableFuture<Result<Long, LedgerError>> pay(
            UUID player, long cost, String refId, @Nullable SqlConsumer extra);
}
