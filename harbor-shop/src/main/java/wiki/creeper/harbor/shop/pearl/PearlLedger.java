package wiki.creeper.harbor.shop.pearl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.ledger.LedgerEntry;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerException;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.ledger.LedgerWriter;
import wiki.creeper.harbor.common.model.Currency;

/**
 * Processes one pearl ledger entry inside an already open transaction (docs 20 12.4 'write order').
 * Using the Common ledger tools: lock wallet row → negative check → pearl_ledger record (unique
 * reference ID) → balance update, then in the same transaction deducts lots (pearl_lot) and syncs
 * the paid balance and balance-after columns. On failure throws {@link LedgerException} and the
 * caller's whole transaction is rolled back.
 *
 * <p>There is no function converting to gold or water scales (economy separation, '6. BM plan' ch.
 * 1).
 */
public final class PearlLedger {

    /**
     * One change.
     *
     * @param refId reference ID (required). The same (uuid, reason, reference ID) is recorded only
     *     once
     * @param paid for grants, whether it is a paid lot (payment and monthly subscription pearls =
     *     paid; pass, events, admin = free)
     * @param lotRef source_ref of the granted lot (refId if null)
     * @param fromLot lot to deduct from (refund and chargeback recovery). If null, free first, then
     *     oldest paid
     */
    public record Change(
            UUID player,
            long delta,
            LedgerReason reason,
            String refId,
            boolean paid,
            @Nullable String lotRef,
            @Nullable Long fromLot) {

        public static Change credit(
                UUID player, long amount, LedgerReason reason, String refId, boolean paid) {
            return new Change(player, amount, reason, refId, paid, null, null);
        }

        public static Change debit(UUID player, long amount, LedgerReason reason, String refId) {
            return new Change(player, -amount, reason, refId, false, null, null);
        }
    }

    /** Processing result. */
    public record Applied(UUID player, long balance, long paidBalance, long paidDelta) {}

    private final PearlWalletStore store = new PearlWalletStore();
    private final LedgerWriter writer = new LedgerWriter(Map.of(Currency.PEARL, store));

    public PearlWalletStore store() {
        return store;
    }

    public Applied apply(Connection c, Change change) throws SQLException {
        long balance =
                writer.apply(
                        c,
                        new LedgerEntry(
                                change.player(),
                                Currency.PEARL,
                                change.delta(),
                                change.reason(),
                                change.refId()));
        long paidDelta;
        if (change.delta() > 0) {
            insertLot(
                    c,
                    change.player(),
                    change.lotRef() != null ? change.lotRef() : change.refId(),
                    change.paid(),
                    change.delta());
            paidDelta = change.paid() ? change.delta() : 0;
        } else {
            List<PearlLots.Take> takes = plan(c, change);
            for (PearlLots.Take take : takes) {
                try (PreparedStatement ps =
                        c.prepareStatement(
                                "UPDATE pearl_lot SET remaining = remaining - ? WHERE lot_id = ? AND remaining >= ?")) {
                    ps.setLong(1, take.amount());
                    ps.setLong(2, take.lotId());
                    ps.setLong(3, take.amount());
                    if (ps.executeUpdate() != 1) {
                        throw new LedgerException(LedgerError.REJECTED, "묶음 차감 실패: " + take);
                    }
                }
            }
            paidDelta = -PearlLots.paidPart(takes);
        }
        long paidBalance;
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE pearl_wallet SET paid_balance = paid_balance + ? WHERE uuid = ?")) {
            ps.setLong(1, paidDelta);
            ps.setString(2, change.player().toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps =
                c.prepareStatement("SELECT paid_balance FROM pearl_wallet WHERE uuid = ?")) {
            ps.setString(1, change.player().toString());
            try (ResultSet rs = ps.executeQuery()) {
                paidBalance = rs.next() ? rs.getLong(1) : 0;
            }
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE pearl_ledger SET balance_after = ?, paid_delta = ?"
                                + " WHERE uuid = ? AND reason = ? AND ref_id = ?")) {
            ps.setLong(1, balance);
            ps.setLong(2, paidDelta);
            ps.setString(3, change.player().toString());
            ps.setString(4, change.reason().name());
            ps.setString(5, change.refId());
            ps.executeUpdate();
        }
        return new Applied(change.player(), balance, paidBalance, paidDelta);
    }

    /** Processes several entries in order (10-open, gifts, etc.). */
    public List<Applied> applyAll(Connection c, List<Change> changes) throws SQLException {
        List<Applied> result = new ArrayList<>();
        for (Change change : changes) {
            result.add(apply(c, change));
        }
        return result;
    }

    private static List<PearlLots.Take> plan(Connection c, Change change) throws SQLException {
        long amount = -change.delta();
        List<PearlLots.Lot> lots = new ArrayList<>();
        String sql =
                change.fromLot() == null
                        ? "SELECT lot_id, paid, remaining FROM pearl_lot WHERE uuid = ? AND remaining > 0 FOR UPDATE"
                        : "SELECT lot_id, paid, remaining FROM pearl_lot WHERE uuid = ? AND lot_id = ? FOR UPDATE";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, change.player().toString());
            if (change.fromLot() != null) {
                ps.setLong(2, change.fromLot());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lots.add(new PearlLots.Lot(rs.getLong(1), rs.getBoolean(2), rs.getLong(3)));
                }
            }
        }
        try {
            return PearlLots.allocate(lots, amount);
        } catch (IllegalArgumentException e) {
            // Wallet balance was enough but lots are short: the invariant is broken. Refuse without
            // writing (the daily check reports it)
            throw new LedgerException(LedgerError.REJECTED, "묶음 잔량 불일치: " + change);
        }
    }

    private static void insertLot(
            Connection c, UUID player, String sourceRef, boolean paid, long amount)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO pearl_lot (uuid, source_ref, paid, amount, remaining) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, player.toString());
            ps.setString(2, sourceRef);
            ps.setBoolean(3, paid);
            ps.setLong(4, amount);
            ps.setLong(5, amount);
            ps.executeUpdate();
        }
    }
}
