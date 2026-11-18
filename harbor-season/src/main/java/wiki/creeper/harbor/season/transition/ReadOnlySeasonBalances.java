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
 * 원장 검사(VERIFY 단계) 전용 잔액 읽기. Core의 SeasonBalanceStore와 같은 열(player_season 시즌 n 행)을 읽기만 한다. 잔액을 바꾸는
 * 메서드는 막는다 (잔액은 CurrencyService로만 바꾼다).
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

    /** 잔액 열 식 (골드 = gold, 물비늘 = earned − spent). */
    public static String column(Currency currency) {
        return switch (currency) {
            case GOLD -> "gold";
            case MULBINEUL -> "mulbineul_earned - mulbineul_spent";
            case PEARL -> throw new IllegalArgumentException("진주는 player_season에 없습니다");
        };
    }
}
