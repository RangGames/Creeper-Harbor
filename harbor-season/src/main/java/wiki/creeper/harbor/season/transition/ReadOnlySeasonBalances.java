package wiki.creeper.harbor.season.transition;

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
 * Balance reads for the ledger check (VERIFY step) only. Reads the same columns as Core's
 * SeasonBalanceStore (player_season rows for season n), read-only. Methods that change balances are
 * blocked (balances change only through CurrencyService).
 */
public final class ReadOnlySeasonBalances implements BalanceStore {

    private final int season;

    public ReadOnlySeasonBalances(int season) {
        this.season = season;
    }

    @Override
    public long lockBalance(Connection connection, UUID player, Currency currency) {
        throw new UnsupportedOperationException("읽기 전용 잔액 저장소입니다");
    }

    @Override
    public void writeBalance(
            Connection connection, UUID player, Currency currency, long newBalance, long delta) {
        throw new UnsupportedOperationException("읽기 전용 잔액 저장소입니다");
    }

    @Override
    public Map<UUID, Long> allBalances(Connection c, Currency currency) throws SQLException {
        String column = column(currency);
        Map<UUID, Long> result = new HashMap<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT uuid, " + column + " FROM player_season WHERE season = ?")) {
            ps.setInt(1, season);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(UUID.fromString(rs.getString(1)), rs.getLong(2));
                }
            }
        }
        return result;
    }

    /** Balance column expression (gold = gold, mulbineul = earned − spent). */
    public static String column(Currency currency) {
        return switch (currency) {
            case GOLD -> "gold";
            case MULBINEUL -> "mulbineul_earned - mulbineul_spent";
            case PEARL -> throw new IllegalArgumentException("진주는 player_season에 없습니다");
        };
    }
}
