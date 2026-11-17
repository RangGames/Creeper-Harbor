package wiki.creeper.harbor.common.ledger;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import wiki.creeper.harbor.common.model.Currency;

/**
 * 잔액이 저장된 곳. 지급·차감 규칙을 정하는 모듈이 구현한다 (골드·물비늘: Core의 player_season, 진주: Shop의 pearl_wallet).
 *
 * <p>원장 합계 검사가 맞으려면 잔액을 원장 밖에서 바꾸면 안 된다. 시즌 초기화처럼 잔액을 0으로 만들 때도 {@link LedgerReason#SEASON_RESET}으로
 * 원장에 기록한다.
 */
public interface BalanceStore {

    /** 잔액 행을 잠그고 (SELECT … FOR UPDATE) 현재 잔액을 돌려준다. 행이 없으면 0으로 만든다. */
    long lockBalance(Connection connection, UUID player, Currency currency) throws SQLException;

    /** 잔액을 갱신한다. lockBalance 이후 같은 트랜잭션에서만 부른다. */
    void writeBalance(
            Connection connection, UUID player, Currency currency, long newBalance, long delta)
            throws SQLException;

    /** 모든 플레이어의 잔액 (원장 검사용). */
    Map<UUID, Long> allBalances(Connection connection, Currency currency) throws SQLException;
}
