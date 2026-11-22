package wiki.creeper.harbor.shop.webhook;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.shop.data.ShopRepository;

/** purchases / pearl_lot SQL used by {@link PaymentProcessor} and {@link PaymentReversals}. */
final class PurchaseSql {

    private PurchaseSql() {}

    /** One purchase row (tx_id or tx_id#index). */
    record Row(
            String txId,
            @Nullable UUID player,
            String kind,
            long pearls,
            String state,
            long reversed,
            long debtAdded) {}

    static Optional<String> purchaseState(Connection c, String tx) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("SELECT state FROM purchases WHERE tx_id = ? FOR UPDATE")) {
            ps.setString(1, tx);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    static List<Row> rows(Connection c, String tx) throws SQLException {
        return rows(c, tx, true);
    }

    static List<Row> rowsNoLock(Connection c, String tx) throws SQLException {
        return rows(c, tx, false);
    }

    private static List<Row> rows(Connection c, String tx, boolean lock) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT tx_id, uuid, COALESCE(kind, ''), pearls, state, reversed_pearls, debt_added"
                                + " FROM purchases WHERE tx_id = ? OR tx_id LIKE ?"
                                + (lock ? " FOR UPDATE" : ""))) {
            ps.setString(1, tx);
            ps.setString(2, tx + "#%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String uuid = rs.getString(2);
                    rows.add(
                            new Row(
                                    rs.getString(1),
                                    uuid == null ? null : UUID.fromString(uuid),
                                    rs.getString(3),
                                    rs.getLong(4),
                                    rs.getString(5),
                                    rs.getLong(6),
                                    rs.getLong(7)));
                }
            }
        }
        return rows;
    }

    static void setState(Connection c, String tx, String state, @Nullable String detail)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE purchases SET state = ?, detail = COALESCE(?, detail) WHERE tx_id = ?")) {
            ps.setString(1, state);
            ShopRepository.setNullable(ps, 2, detail);
            ps.setString(3, tx);
            ps.executeUpdate();
        }
    }

    static @Nullable Long lotId(Connection c, UUID player, String sourceRef) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT lot_id FROM pearl_lot WHERE uuid = ? AND source_ref = ?")) {
            ps.setString(1, player.toString());
            ps.setString(2, sourceRef);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    static long lotRemaining(Connection c, long lot) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("SELECT remaining FROM pearl_lot WHERE lot_id = ?")) {
            ps.setLong(1, lot);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    static void unlockIfDebt(Connection c, UUID player) throws SQLException {
        Optional<String> reason = ShopRepository.lockReason(c, player);
        if (reason.isPresent() && reason.get().startsWith("debt:")) {
            ShopRepository.setLock(c, player, false, null);
        }
    }
}
