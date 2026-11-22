package wiki.creeper.harbor.fishing.trap;

import com.google.gson.reflect.TypeToken;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerException;
import wiki.creeper.harbor.common.util.Json;

/**
 * traps SQL. Placement, calculation, harvest and retrieval are all immediate transactions
 * (CLAUDE.md write principles).
 */
public final class TrapRepository {

    private static final String COLUMNS =
            "id, owner, season, region, world, x, y, z, tier, item_id, slots, extra_slots,"
                    + " bait_count, last_calc, contents, value_24h, value_window_start,"
                    + " last_harvest, placed_at";

    private static final java.lang.reflect.Type CONTENTS =
            new TypeToken<List<TrapCatch>>() {}.getType();

    private TrapRepository() {}

    static String toJson(List<TrapCatch> contents) {
        return Json.GSON.toJson(contents, CONTENTS);
    }

    static List<TrapCatch> fromJson(String json) {
        List<TrapCatch> list = Json.GSON.fromJson(json, CONTENTS);
        return list == null ? List.of() : list;
    }

    private static TrapRow read(ResultSet rs) throws SQLException {
        return new TrapRow(
                rs.getLong("id"),
                UUID.fromString(rs.getString("owner")),
                rs.getInt("season"),
                RegionId.parse(rs.getString("region")),
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                rs.getInt("tier"),
                ItemId.parse(rs.getString("item_id")),
                rs.getInt("slots"),
                rs.getInt("extra_slots"),
                rs.getInt("bait_count"),
                rs.getTimestamp("last_calc").toInstant(),
                fromJson(rs.getString("contents")),
                rs.getDouble("value_24h"),
                rs.getTimestamp("value_window_start").toInstant(),
                rs.getTimestamp("last_harvest").toInstant(),
                rs.getTimestamp("placed_at").toInstant());
    }

    private static List<TrapRow> readAll(PreparedStatement ps) throws SQLException {
        List<TrapRow> rows = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(read(rs));
            }
        }
        return rows;
    }

    /**
     * Placement. SQLIntegrityConstraintViolationException if one already exists at the same buoy
     * spot (season, world, x, z).
     */
    public static long insert(
            Connection c,
            UUID owner,
            int season,
            RegionId region,
            String world,
            int x,
            int y,
            int z,
            TrapMath.Spec spec,
            ItemId item,
            Instant now)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO traps (owner, season, region, world, x, y, z, tier, item_id, slots,"
                                + " extra_slots, bait_count, last_calc, contents, value_24h,"
                                + " value_window_start, last_harvest, placed_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, '[]', 0, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
            Timestamp ts = Timestamp.from(now);
            ps.setString(1, owner.toString());
            ps.setInt(2, season);
            ps.setString(3, region.toString());
            ps.setString(4, world);
            ps.setInt(5, x);
            ps.setInt(6, y);
            ps.setInt(7, z);
            ps.setInt(8, spec.tier());
            ps.setString(9, item.toString());
            ps.setInt(10, spec.slots());
            ps.setTimestamp(11, ts);
            ps.setTimestamp(12, ts);
            ps.setTimestamp(13, ts);
            ps.setTimestamp(14, ts);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("통발 ID를 받지 못했습니다");
                }
                return keys.getLong(1);
            }
        }
    }

    public static int count(Connection c, UUID owner, int season) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("SELECT COUNT(*) FROM traps WHERE owner = ? AND season = ?")) {
            ps.setString(1, owner.toString());
            ps.setInt(2, season);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public static List<TrapRow> byOwner(Connection c, UUID owner, int season, boolean lock)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT "
                                + COLUMNS
                                + " FROM traps WHERE owner = ? AND season = ? ORDER BY id"
                                + (lock ? " FOR UPDATE" : ""))) {
            ps.setString(1, owner.toString());
            ps.setInt(2, season);
            return readAll(ps);
        }
    }

    public static Optional<TrapRow> byId(Connection c, long id, boolean lock) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT "
                                + COLUMNS
                                + " FROM traps WHERE id = ?"
                                + (lock ? " FOR UPDATE" : ""))) {
            ps.setLong(1, id);
            List<TrapRow> rows = readAll(ps);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
        }
    }

    /**
     * Saves a calculation result. A conditional update (WHERE last_calc = previous value) prevents
     * double calculation.
     *
     * @return true if updated
     */
    public static boolean saveCalc(
            Connection c,
            long id,
            Instant previousCalc,
            Instant lastCalc,
            List<TrapCatch> contents,
            int bait,
            double value24h,
            Instant windowStart)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE traps SET last_calc = ?, contents = ?, bait_count = ?, value_24h = ?,"
                                + " value_window_start = ? WHERE id = ? AND last_calc = ?")) {
            ps.setTimestamp(1, Timestamp.from(lastCalc));
            ps.setString(2, toJson(contents));
            ps.setInt(3, bait);
            ps.setDouble(4, value24h);
            ps.setTimestamp(5, Timestamp.from(windowStart));
            ps.setLong(6, id);
            ps.setTimestamp(7, Timestamp.from(previousCalc));
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Harvest: removes entries and updates last_harvest. Returns the removed entries (ones already
     * gone are left out).
     */
    public static List<TrapCatch> take(
            Connection c, long id, UUID owner, Set<String> uids, Instant now) throws SQLException {
        Optional<TrapRow> row = byId(c, id, true);
        if (row.isEmpty() || !row.get().owner().equals(owner)) {
            return List.of();
        }
        List<TrapCatch> taken = new ArrayList<>();
        List<TrapCatch> left = new ArrayList<>();
        for (TrapCatch entry : row.get().contents()) {
            (uids.contains(entry.uid()) ? taken : left).add(entry);
        }
        if (taken.isEmpty()) {
            return List.of();
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE traps SET contents = ?, last_harvest = ? WHERE id = ?")) {
            ps.setString(1, toJson(left));
            ps.setTimestamp(2, Timestamp.from(now));
            ps.setLong(3, id);
            ps.executeUpdate();
        }
        return taken;
    }

    /** Removes one entry during mail retrieval (after the mail commits). */
    public static void dropEntry(Connection c, long id, String uid) throws SQLException {
        Optional<TrapRow> row = byId(c, id, true);
        if (row.isEmpty()) {
            return;
        }
        List<TrapCatch> left =
                row.get().contents().stream().filter(e -> !e.uid().equals(uid)).toList();
        try (PreparedStatement ps =
                c.prepareStatement("UPDATE traps SET contents = ? WHERE id = ?")) {
            ps.setString(1, toJson(left));
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /** Adds bait. False if it would exceed capacity (nothing changes). */
    public static boolean addBait(Connection c, long id, UUID owner, int amount, int capacity)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE traps SET bait_count = bait_count + ?"
                                + " WHERE id = ? AND owner = ? AND bait_count + ? <= ?")) {
            ps.setInt(1, amount);
            ps.setLong(2, id);
            ps.setString(3, owner.toString());
            ps.setInt(4, amount);
            ps.setInt(5, capacity);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Slot expansion (extra SQL for CurrencyService.atomic). Over the limit, rolls back the
     * transaction with LedgerException (REJECTED).
     */
    public static void expandSlot(Connection c, long id, UUID owner, int maxExtra)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE traps SET extra_slots = extra_slots + 1"
                                + " WHERE id = ? AND owner = ? AND extra_slots < ?")) {
            ps.setLong(1, id);
            ps.setString(2, owner.toString());
            ps.setInt(3, maxExtra);
            if (ps.executeUpdate() != 1) {
                throw new LedgerException(LedgerError.REJECTED, "통발 슬롯을 더 늘릴 수 없습니다: " + id);
            }
        }
    }

    /**
     * Removal (owner check, only when empty): deletes an empty trap. Returns the bait count it held
     * if deleted, -1 if not. The bait count is locked and read in the same transaction: returning
     * the value read when the menu was opened made bait added in the meantime vanish without a
     * refund.
     */
    public static int deleteEmpty(Connection c, long id, UUID owner) throws SQLException {
        int bait;
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT bait_count FROM traps WHERE id = ? AND owner = ? AND contents = '[]' FOR UPDATE")) {
            ps.setLong(1, id);
            ps.setString(2, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return -1;
                }
                bait = rs.getInt(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM traps WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() == 1 ? bait : -1;
        }
    }

    public static boolean delete(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM traps WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() == 1;
        }
    }

    /** Traps whose last_harvest is older than before (auto-retrieval targets). */
    public static List<TrapRow> stale(Connection c, int season, Instant before, int limit)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT "
                                + COLUMNS
                                + " FROM traps WHERE season = ? AND last_harvest < ? ORDER BY id LIMIT ?")) {
            ps.setInt(1, season);
            ps.setTimestamp(2, Timestamp.from(before));
            ps.setInt(3, limit);
            return readAll(ps);
        }
    }
}
