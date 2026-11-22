package wiki.creeper.harbor.common.ledger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.model.Currency;

/**
 * Checks that ledger sums match balances. Runs on one server every early morning (distributed lock)
 * and alerts admins on mismatch.
 */
public final class LedgerAuditor {

    /** A player whose ledger sum differs from the balance. */
    public record Mismatch(UUID player, Currency currency, long ledgerSum, long balance) {}

    private LedgerAuditor() {}

    public static List<Mismatch> audit(Database database, Currency currency, BalanceStore store)
            throws SQLException {
        return database.tx(
                connection -> {
                    Map<UUID, Long> sums = ledgerSums(connection, currency);
                    Map<UUID, Long> balances = store.allBalances(connection, currency);
                    Set<UUID> players = new HashSet<>(sums.keySet());
                    players.addAll(balances.keySet());
                    List<Mismatch> mismatches = new ArrayList<>();
                    for (UUID player : players) {
                        long sum = sums.getOrDefault(player, 0L);
                        long balance = balances.getOrDefault(player, 0L);
                        if (sum != balance) {
                            mismatches.add(new Mismatch(player, currency, sum, balance));
                        }
                    }
                    return mismatches;
                });
    }

    private static Map<UUID, Long> ledgerSums(Connection connection, Currency currency)
            throws SQLException {
        String sql =
                currency == Currency.PEARL
                        ? "SELECT uuid, SUM(delta) FROM pearl_ledger GROUP BY uuid"
                        : "SELECT uuid, SUM(delta) FROM ledger WHERE currency = ? GROUP BY uuid";
        Map<UUID, Long> sums = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (currency != Currency.PEARL) {
                ps.setString(1, currency.name());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sums.put(UUID.fromString(rs.getString(1)), rs.getLong(2));
                }
            }
        }
        return sums;
    }
}
