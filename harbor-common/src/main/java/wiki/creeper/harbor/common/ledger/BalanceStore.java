package wiki.creeper.harbor.common.ledger;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import wiki.creeper.harbor.common.model.Currency;

/**
 * Where balances are stored. Implemented by the module that defines the grant/deduct rules (gold
 * and mulbineul: Core's player_season; pearls: Shop's pearl_wallet).
 *
 * <p>For the ledger sum audit to hold, balances must never change outside the ledger. Even zeroing
 * balances, as in a season reset, is recorded in the ledger as {@link LedgerReason#SEASON_RESET}.
 */
public interface BalanceStore {

    /**
     * Locks the balance row (SELECT … FOR UPDATE) and returns the current balance. Creates the row
     * with 0 if missing.
     */
    long lockBalance(Connection connection, UUID player, Currency currency) throws SQLException;

    /** Updates the balance. Call only after lockBalance in the same transaction. */
    void writeBalance(
            Connection connection, UUID player, Currency currency, long newBalance, long delta)
            throws SQLException;

    /** Balances of all players (for the ledger audit). */
    Map<UUID, Long> allBalances(Connection connection, Currency currency) throws SQLException;
}
