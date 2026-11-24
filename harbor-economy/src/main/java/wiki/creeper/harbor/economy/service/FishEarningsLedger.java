package wiki.creeper.harbor.economy.service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.fishing.api.FishEarnings;

/**
 * {@link FishEarnings} from the gold ledger: the sum of FISH_SELL and BIG_DEAL_SELL entries since
 * the given time (read only, uses the (uuid, currency, id) index range of that player).
 */
public final class FishEarningsLedger implements FishEarnings {

    private final Database database;

    public FishEarningsLedger(Database database) {
        this.database = database;
    }

    @Override
    public CompletableFuture<Long> fishSales(UUID player, Instant since) {
        return database.async(
                c -> {
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "SELECT COALESCE(SUM(delta), 0) FROM ledger WHERE uuid = ?"
                                            + " AND currency = ? AND reason IN (?, ?)"
                                            + " AND created_at >= ?")) {
                        ps.setString(1, player.toString());
                        ps.setString(2, Currency.GOLD.name());
                        ps.setString(3, LedgerReason.FISH_SELL.name());
                        ps.setString(4, LedgerReason.BIG_DEAL_SELL.name());
                        ps.setTimestamp(5, Timestamp.from(since));
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next() ? rs.getLong(1) : 0L;
                        }
                    }
                });
    }
}
