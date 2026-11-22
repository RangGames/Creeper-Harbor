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

/** traps SQL. 설치·계산·수확·회수는 모두 즉시 트랜잭션이다 (CLAUDE.md 쓰기 원칙). */
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

    /** 설치. 같은 부표 자리(시즌·월드·x·z)에 이미 있으면 SQLIntegrityConstraintViolationException. */
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
     * 계산 결과 저장. 조건부 갱신(WHERE last_calc = 이전 값)으로 두 번 계산을 막는다.
     *
     * @return 갱신했으면 true
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

    /** 수확: 항목을 지우고 last_harvest를 갱신한다. 지운 항목을 돌려준다 (이미 없으면 빠진다). */
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

    /** 우편 회수 중 한 항목을 지운다 (우편 커밋 뒤). */
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

    /** 미끼를 넣는다. 용량을 넘으면 false (아무것도 바뀌지 않음). */
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

    /** 슬롯 확장 (CurrencyService.atomic의 추가 SQL). 한도를 넘으면 LedgerException(REJECTED)으로 트랜잭션을 되돌린다. */
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
     * 철거 (주인 확인, 내용물이 비었을 때만): 빈 통발을 지운다. 지웠으면 그때 들어 있던 미끼 수, 못 지웠으면 -1. 미끼 수는 같은 트랜잭션에서 잠그고 읽는다:
     * 메뉴를 열 때 읽은 값으로 돌려주면 그 사이 넣은 미끼가 환불 없이 사라졌다.
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

    /** last_harvest가 before보다 오래된 통발 (자동 회수 대상). */
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
