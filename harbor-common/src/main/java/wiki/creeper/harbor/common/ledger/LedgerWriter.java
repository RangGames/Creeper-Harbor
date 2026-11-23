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
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.util.Result;

/**
 * Currency ledger writes. Processed in this order within one transaction.
 *
 * <ol>
 *   <li>Lock the balance row (SELECT … FOR UPDATE)
 *   <li>Reject if the new balance is negative
 *   <li>Record (uuid, currency, delta, balance after, reason, reference ID) in the ledger. Reject
 *       on a duplicate reference ID
 *   <li>Update the balance
 * </ol>
 *
 * Gold and mulbineul go to the {@code ledger} table, pearls to {@code pearl_ledger}.
 */
public final class LedgerWriter {

    private static final int MARIADB_DUPLICATE_KEY = 1062;

    private final Map<Currency, BalanceStore> stores;

    public LedgerWriter(Map<Currency, BalanceStore> stores) {
        this.stores = stores.isEmpty() ? new EnumMap<>(Currency.class) : new EnumMap<>(stores);
    }

    /**
     * Processes one entry inside an already open transaction. On failure throws {@link
     * LedgerException}; the caller rolls back the transaction.
     *
     * @return balance after processing
     */
    public long apply(Connection connection, LedgerEntry entry) throws SQLException {
        BalanceStore store = stores.get(entry.currency());
        if (store == null) {
            throw new LedgerException(
                    LedgerError.UNSUPPORTED_CURRENCY, "잔액 저장소 없음: " + entry.currency());
        }
        long balance = store.lockBalance(connection, entry.player(), entry.currency());
        long newBalance = Math.addExact(balance, entry.delta());
        if (newBalance < 0) {
            throw new LedgerException(
                    LedgerError.INSUFFICIENT_FUNDS,
                    "잔액 부족: " + balance + " + (" + entry.delta() + ")");
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

    /** Processes one entry in its own transaction. Blocking, so do not call on the main thread. */
    public Result<Long, LedgerError> post(Database database, LedgerEntry entry) {
        try {
            return Result.ok(database.tx(connection -> apply(connection, entry)));
        } catch (LedgerException e) {
            return Result.err(e.error());
        } catch (SQLException e) {
            System.getLogger("Harbor/Ledger")
                    .log(System.Logger.Level.ERROR, "원장 쓰기 실패: " + entry, e);
            return Result.err(LedgerError.DATABASE);
        }
    }

    /**
     * Result of {@link #postSet}: the entry actually posted ({@code null} when the balance already
     * matched) and the balance after.
     */
    public record SetOutcome(@Nullable LedgerEntry entry, long balance) {}

    /**
     * Sets a balance to {@code target} (admin set). In one transaction: locks the balance row,
     * computes the difference against the locked value, and posts it as a {@code up} (positive) or
     * {@code down} (negative) entry. No entry when the difference is 0. Blocking, so do not call on
     * the main thread.
     */
    public Result<SetOutcome, LedgerError> postSet(
            Database database,
            UUID player,
            Currency currency,
            long target,
            LedgerReason up,
            LedgerReason down,
            @Nullable String refId) {
        if (target < 0) {
            throw new IllegalArgumentException("잔액은 0 이상이어야 합니다: " + target);
        }
        try {
            return Result.ok(
                    database.tx(
                            connection -> {
                                BalanceStore store = stores.get(currency);
                                if (store == null) {
                                    throw new LedgerException(
                                            LedgerError.UNSUPPORTED_CURRENCY,
                                            "잔액 저장소 없음: " + currency);
                                }
                                long now = store.lockBalance(connection, player, currency);
                                LedgerEntry entry =
                                        setEntry(player, currency, now, target, up, down, refId);
                                if (entry == null) {
                                    return new SetOutcome(null, now);
                                }
                                // apply locks the same row again (already held by this tx)
                                return new SetOutcome(entry, apply(connection, entry));
                            }));
        } catch (LedgerException e) {
            return Result.err(e.error());
        } catch (SQLException e) {
            System.getLogger("Harbor/Ledger")
                    .log(System.Logger.Level.ERROR, "원장 잔액 설정 실패: " + player + " " + currency, e);
            return Result.err(LedgerError.DATABASE);
        }
    }

    /**
     * The entry that moves {@code current} to {@code target}, or {@code null} if they are equal.
     */
    static @Nullable LedgerEntry setEntry(
            UUID player,
            Currency currency,
            long current,
            long target,
            LedgerReason up,
            LedgerReason down,
            @Nullable String refId) {
        long diff = Math.subtractExact(target, current);
        if (diff == 0) {
            return null;
        }
        return new LedgerEntry(player, currency, diff, diff > 0 ? up : down, refId);
    }

    /**
     * Processes several entries in one transaction (transfers, auction wins, etc.). If any fails,
     * all are cancelled. Locks are taken in (player UUID, currency) order to reduce deadlocks.
     *
     * @param extra extra SQL to run in the same transaction (auction row status change, etc.). Runs
     *     after all ledger records succeed. Business rule violations are reported by throwing
     *     {@link LedgerException}.
     * @return balances after processing, in input order
     */
    public Result<List<Long>, LedgerError> postAll(
            Database database, List<LedgerEntry> entries, @Nullable SqlConsumer extra) {
        try {
            return Result.ok(database.tx(connection -> applyAll(connection, entries, extra)));
        } catch (LedgerException e) {
            return Result.err(e.error());
        } catch (SQLException e) {
            System.getLogger("Harbor/Ledger")
                    .log(System.Logger.Level.ERROR, "원장 쓰기 실패: " + entries, e);
            return Result.err(LedgerError.DATABASE);
        }
    }

    public Result<List<Long>, LedgerError> postAll(Database database, List<LedgerEntry> entries) {
        return postAll(database, entries, null);
    }

    /** Processes several entries inside an already open transaction. */
    public List<Long> applyAll(
            Connection connection, List<LedgerEntry> entries, @Nullable SqlConsumer extra)
            throws SQLException {
        List<LedgerEntry> lockOrder =
                entries.stream()
                        .sorted(
                                Comparator.comparing(LedgerEntry::player)
                                        .thenComparing(LedgerEntry::currency))
                        .toList();
        IdentityHashMap<LedgerEntry, Long> after = new IdentityHashMap<>();
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
        return e instanceof SQLIntegrityConstraintViolationException
                || e.getErrorCode() == MARIADB_DUPLICATE_KEY;
    }

    /** Balance stores (for the ledger audit). */
    public Map<Currency, BalanceStore> stores() {
        return Map.copyOf(stores);
    }
}
