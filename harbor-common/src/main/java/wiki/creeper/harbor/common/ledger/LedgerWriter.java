package wiki.creeper.harbor.common.ledger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.util.Result;

/**
 * 재화 원장 쓰기. 한 트랜잭션 안에서 다음 순서로 처리한다.
 *
 * <ol>
 *   <li>잔액 행을 잠근다 (SELECT … FOR UPDATE)
 *   <li>새 잔액이 음수면 거부한다
 *   <li>원장에 (uuid, 재화, 변화량, 처리 후 잔액, 사유, 참조 ID)를 기록한다. 참조 ID가 겹치면 거부한다
 *   <li>잔액을 갱신한다
 * </ol>
 *
 * 골드·물비늘은 {@code ledger}, 진주는 {@code pearl_ledger} 테이블에 쓴다.
 */
public final class LedgerWriter {

    private static final int MARIADB_DUPLICATE_KEY = 1062;

    private final Map<Currency, BalanceStore> stores;

    public LedgerWriter(Map<Currency, BalanceStore> stores) {
        this.stores = stores.isEmpty() ? new EnumMap<>(Currency.class) : new EnumMap<>(stores);
    }

    /**
     * 이미 열린 트랜잭션 안에서 한 건을 처리한다. 실패하면 {@link LedgerException}을 던지고, 트랜잭션은 호출한 쪽이 롤백한다.
     *
     * @return 처리 후 잔액
     */
    public long apply(Connection connection, LedgerEntry entry) throws SQLException {
        BalanceStore store = stores.get(entry.currency());
        if (store == null) {
            throw new LedgerException(LedgerError.UNSUPPORTED_CURRENCY, "잔액 저장소 없음: " + entry.currency());
        }
        long balance = store.lockBalance(connection, entry.player(), entry.currency());
        long newBalance = Math.addExact(balance, entry.delta());
        if (newBalance < 0) {
            throw new LedgerException(
                    LedgerError.INSUFFICIENT_FUNDS, "잔액 부족: " + balance + " + (" + entry.delta() + ")");
        }
        try {
            insertLedger(connection, entry, newBalance);
        } catch (SQLException e) {
            if (isDuplicate(e)) {
                throw new LedgerException(LedgerError.DUPLICATE, "이미 처리된 참조 ID: " + entry.refId());
            }
            throw e;
        }
        store.writeBalance(connection, entry.player(), entry.currency(), newBalance, entry.delta());
        return newBalance;
    }

    /** 한 건을 자기 트랜잭션으로 처리한다. 블로킹이므로 메인 스레드에서 부르지 않는다. */
    public Result<Long, LedgerError> post(Database database, LedgerEntry entry) {
        try {
            return Result.ok(database.tx(connection -> apply(connection, entry)));
        } catch (LedgerException e) {
            return Result.err(e.error());
        } catch (SQLException e) {
            System.getLogger("Harbor/Ledger").log(System.Logger.Level.ERROR, "원장 쓰기 실패: " + entry, e);
            return Result.err(LedgerError.DATABASE);
        }
    }

    /**
     * 여러 건을 한 트랜잭션으로 처리한다 (송금, 경매 낙찰 등). 하나라도 실패하면 모두 취소된다. 교착 상태를 줄이려고 잠금은 (플레이어 UUID,
     * 재화) 순서로 건다.
     *
     * @param extra 같은 트랜잭션에서 실행할 추가 SQL (경매 행 상태 변경 등). 원장 기록이 모두 성공한 뒤 실행한다. 업무 규칙 위반은 {@link
     *     LedgerException}을 던져 알린다.
     * @return 입력 순서대로 처리 후 잔액
     */
    public Result<List<Long>, LedgerError> postAll(
            Database database, List<LedgerEntry> entries, @Nullable SqlConsumer extra) {
        try {
            return Result.ok(database.tx(connection -> applyAll(connection, entries, extra)));
        } catch (LedgerException e) {
            return Result.err(e.error());
        } catch (SQLException e) {
            System.getLogger("Harbor/Ledger").log(System.Logger.Level.ERROR, "원장 쓰기 실패: " + entries, e);
            return Result.err(LedgerError.DATABASE);
        }
    }

    public Result<List<Long>, LedgerError> postAll(Database database, List<LedgerEntry> entries) {
        return postAll(database, entries, null);
    }

    /** 이미 열린 트랜잭션 안에서 여러 건을 처리한다. */
    public List<Long> applyAll(Connection connection, List<LedgerEntry> entries, @Nullable SqlConsumer extra)
            throws SQLException {
        List<LedgerEntry> lockOrder =
                entries.stream()
                        .sorted(Comparator.comparing(LedgerEntry::player).thenComparing(LedgerEntry::currency))
                        .toList();
        Map<LedgerEntry, Long> after = new IdentityHashMap<>();
        for (LedgerEntry entry : lockOrder) {
            after.put(entry, apply(connection, entry));
        }
        if (extra != null) {
            extra.accept(connection);
        }
        List<Long> result = new ArrayList<>(entries.size());
        for (LedgerEntry entry : entries) {
            Long balance = after.get(entry);
            result.add(balance == null ? 0L : balance);
        }
        return result;
    }

    private static void insertLedger(Connection connection, LedgerEntry entry, long balanceAfter)
            throws SQLException {
        if (entry.currency() == Currency.PEARL) {
            try (PreparedStatement ps =
                    connection.prepareStatement(
                            "INSERT INTO pearl_ledger (uuid, delta, reason, ref_id) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, entry.player().toString());
                ps.setLong(2, entry.delta());
                ps.setString(3, entry.reason().name());
                setRef(ps, 4, entry.refId());
                ps.executeUpdate();
            }
            return;
        }
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "INSERT INTO ledger (uuid, currency, delta, balance_after, reason, ref_id)"
                                + " VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, entry.player().toString());
            ps.setString(2, entry.currency().name());
            ps.setLong(3, entry.delta());
            ps.setLong(4, balanceAfter);
            ps.setString(5, entry.reason().name());
            setRef(ps, 6, entry.refId());
            ps.executeUpdate();
        }
    }

    private static void setRef(PreparedStatement ps, int index, @Nullable String refId)
            throws SQLException {
        if (refId == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, refId);
        }
    }

    private static boolean isDuplicate(SQLException e) {
        return e instanceof SQLIntegrityConstraintViolationException || e.getErrorCode() == MARIADB_DUPLICATE_KEY;
    }

    /** 잔액 저장소 (원장 검사용). */
    public Map<Currency, BalanceStore> stores() {
        return Map.copyOf(stores);
    }
}
