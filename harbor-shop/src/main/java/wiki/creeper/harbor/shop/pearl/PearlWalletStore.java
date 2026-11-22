package wiki.creeper.harbor.shop.pearl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import wiki.creeper.harbor.common.ledger.BalanceStore;
import wiki.creeper.harbor.common.model.Currency;

/**
 * Pearl balance store (pearl_wallet). Common LedgerWriter calls lock and update. Handles nothing
 * but pearls.
 */
public final class PearlWalletStore implements BalanceStore {

    @Override
    public long lockBalance(Connection connection, UUID player, Currency currency)
            throws SQLException {
        requirePearl(currency);
        try (PreparedStatement ps =
                connection.prepareStatement("INSERT IGNORE INTO pearl_wallet (uuid) VALUES (?)")) {
            ps.setString(1, player.toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "SELECT balance FROM pearl_wallet WHERE uuid = ? FOR UPDATE")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    @Override
    public void writeBalance(
            Connection connection, UUID player, Currency currency, long newBalance, long delta)
            throws SQLException {
        requirePearl(currency);
        try (PreparedStatement ps =
                connection.prepareStatement("UPDATE pearl_wallet SET balance = ? WHERE uuid = ?")) {
            ps.setLong(1, newBalance);
            ps.setString(2, player.toString());
            ps.executeUpdate();
        }
    }

    @Override
    public Map<UUID, Long> allBalances(Connection connection, Currency currency)
            throws SQLException {
        requirePearl(currency);
        Map<UUID, Long> balances = new HashMap<>();
        try (PreparedStatement ps =
                        connection.prepareStatement("SELECT uuid, balance FROM pearl_wallet");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                balances.put(UUID.fromString(rs.getString(1)), rs.getLong(2));
            }
        }
        return balances;
    }

    private static void requirePearl(Currency currency) {
        if (currency != Currency.PEARL) {
            throw new IllegalArgumentException("pearl_wallet은 진주만 다룹니다: " + currency);
        }
    }
}
