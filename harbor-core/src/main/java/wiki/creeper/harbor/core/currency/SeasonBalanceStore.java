package wiki.creeper.harbor.core.currency;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import wiki.creeper.harbor.common.ledger.BalanceStore;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.model.SeasonId;

/**
 * Gold and mulbineul balances = player_season (current season row). The mulbineul balance is
 * computed as mulbineul_earned - mulbineul_spent.
 */
public final class SeasonBalanceStore implements BalanceStore {

    private final SeasonId season;

    public SeasonBalanceStore(SeasonId season) {
        this.season = season;
    }

    @Override
    public long lockBalance(Connection c, UUID player, Currency currency) throws SQLException {
        requireGameCurrency(currency);
        // Not INSERT IGNORE: on an existing row it takes a shared lock that the FOR UPDATE below
        // then upgrades, so two writes for the same player deadlock each other. ON DUPLICATE KEY
        // UPDATE takes the exclusive lock right away
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO player_season (uuid, season) VALUES (?, ?)"
                                + " ON DUPLICATE KEY UPDATE season = season")) {
            ps.setString(1, player.toString());
            ps.setInt(2, season.value());
            ps.executeUpdate();
        }
        String column = currency == Currency.GOLD ? "gold" : "mulbineul_earned - mulbineul_spent";
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT "
                                + column
                                + " FROM player_season WHERE uuid = ? AND season = ? FOR UPDATE")) {
            ps.setString(1, player.toString());
            ps.setInt(2, season.value());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("player_season 행이 없습니다: " + player);
                }
                return rs.getLong(1);
            }
        }
    }

    @Override
    public void writeBalance(
            Connection c, UUID player, Currency currency, long newBalance, long delta)
            throws SQLException {
        requireGameCurrency(currency);
        String sql;
        long value;
        if (currency == Currency.GOLD) {
            sql = "UPDATE player_season SET gold = ? WHERE uuid = ? AND season = ?";
            value = newBalance;
        } else if (delta > 0) {
            sql =
                    "UPDATE player_season SET mulbineul_earned = mulbineul_earned + ? WHERE uuid = ? AND season = ?";
            value = delta;
        } else {
            sql =
                    "UPDATE player_season SET mulbineul_spent = mulbineul_spent + ? WHERE uuid = ? AND season = ?";
            value = -delta;
        }
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, value);
            ps.setString(2, player.toString());
            ps.setInt(3, season.value());
            ps.executeUpdate();
        }
    }

    @Override
    public Map<UUID, Long> allBalances(Connection c, Currency currency) throws SQLException {
        requireGameCurrency(currency);
        String column = currency == Currency.GOLD ? "gold" : "mulbineul_earned - mulbineul_spent";
        Map<UUID, Long> result = new HashMap<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT uuid, " + column + " FROM player_season WHERE season = ?")) {
            ps.setInt(1, season.value());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(UUID.fromString(rs.getString(1)), rs.getLong(2));
                }
            }
        }
        return result;
    }

    private static void requireGameCurrency(Currency currency) {
        if (!currency.isGameCurrency()) {
            throw new IllegalArgumentException("player_season은 골드·물비늘만 다룹니다: " + currency);
        }
    }
}
