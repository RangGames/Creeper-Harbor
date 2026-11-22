package wiki.creeper.harbor.season.limit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.season.api.LimitResult;
import wiki.creeper.harbor.season.api.SeasonClock;
import wiki.creeper.harbor.season.api.WeeklyLimitService;

/**
 * weekly_counter implementation (docs 20 11.4 tryConsume).
 *
 * <p>The documented {@code INSERT … ON DUPLICATE KEY UPDATE count = IF(count < limit, count + 1,
 * count)} returns 1 even when the value is unchanged, because the MariaDB driver reports "found
 * rows" by default (indistinguishable from inserting a new row). So within the same transaction the
 * row is created with {@code INSERT IGNORE} and the decision is made with the single statement
 * {@code UPDATE … SET count = count + 1 WHERE … AND count < limit}. Being a single conditional
 * UPDATE under a row lock, it never exceeds the limit even when called concurrently from several
 * servers.
 */
public final class WeeklyLimitManager implements WeeklyLimitService {

    private final Database db;
    private final SeasonClock clock;
    private final Tasks tasks;

    public WeeklyLimitManager(Database db, SeasonClock clock, Tasks tasks) {
        this.db = db;
        this.clock = clock;
        this.tasks = tasks;
    }

    @Override
    public CompletableFuture<LimitResult> tryConsume(UUID player, String key, int limit) {
        WeeklyLimitService.requireKey(key);
        requireLimit(limit);
        int season = clock.current().value();
        int week = Math.max(0, clock.week());
        return db.async(
                        c -> {
                            boolean ok = consume(c, player, season, week, key, limit);
                            return new LimitResult(
                                    ok, used(c, player, season, week, key), limit, week);
                        })
                .thenApplyAsync(r -> r, tasks.main());
    }

    @Override
    public CompletableFuture<LimitResult> remaining(UUID player, String key, int limit) {
        WeeklyLimitService.requireKey(key);
        requireLimit(limit);
        int season = clock.current().value();
        int week = Math.max(0, clock.week());
        return db.async(
                        c ->
                                new LimitResult(
                                        false, used(c, player, season, week, key), limit, week))
                .thenApplyAsync(r -> r, tasks.main());
    }

    @Override
    public boolean tryConsume(Connection connection, UUID player, String key, int limit)
            throws SQLException {
        WeeklyLimitService.requireKey(key);
        requireLimit(limit);
        return consume(
                connection, player, clock.current().value(), Math.max(0, clock.week()), key, limit);
    }

    @Override
    public CompletableFuture<Boolean> refund(UUID player, String key) {
        return refund(player, key, currentWeek());
    }

    @Override
    public int currentWeek() {
        return Math.max(0, clock.week());
    }

    @Override
    public CompletableFuture<Boolean> refund(UUID player, String key, int week) {
        WeeklyLimitService.requireKey(key);
        int season = clock.current().value();
        return db.async(
                        c -> {
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "UPDATE weekly_counter SET count = count - 1"
                                                    + " WHERE uuid = ? AND season = ? AND week = ? AND `key` = ? AND count > 0")) {
                                ps.setString(1, player.toString());
                                ps.setInt(2, season);
                                ps.setInt(3, week);
                                ps.setString(4, key);
                                return ps.executeUpdate() == 1;
                            }
                        })
                .thenApplyAsync(r -> r, tasks.main());
    }

    /** Admin command: this week's usage count per key. */
    public CompletableFuture<Map<String, Integer>> usage(UUID player) {
        int season = clock.current().value();
        int week = Math.max(0, clock.week());
        return db.async(
                        c -> {
                            Map<String, Integer> result = new LinkedHashMap<>();
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT `key`, count FROM weekly_counter WHERE uuid = ? AND season = ? AND week = ? ORDER BY `key`")) {
                                ps.setString(1, player.toString());
                                ps.setInt(2, season);
                                ps.setInt(3, week);
                                try (ResultSet rs = ps.executeQuery()) {
                                    while (rs.next()) {
                                        result.put(rs.getString(1), rs.getInt(2));
                                    }
                                }
                            }
                            return result;
                        })
                .thenApplyAsync(r -> r, tasks.main());
    }

    /** Admin command: clears this week's count. */
    public CompletableFuture<Boolean> reset(UUID player, String key) {
        WeeklyLimitService.requireKey(key);
        int season = clock.current().value();
        int week = Math.max(0, clock.week());
        return db.async(
                        c -> {
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "DELETE FROM weekly_counter WHERE uuid = ? AND season = ? AND week = ? AND `key` = ?")) {
                                ps.setString(1, player.toString());
                                ps.setInt(2, season);
                                ps.setInt(3, week);
                                ps.setString(4, key);
                                return ps.executeUpdate() > 0;
                            }
                        })
                .thenApplyAsync(r -> r, tasks.main());
    }

    static boolean consume(Connection c, UUID player, int season, int week, String key, int limit)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT IGNORE INTO weekly_counter (uuid, season, week, `key`, count) VALUES (?, ?, ?, ?, 0)")) {
            ps.setString(1, player.toString());
            ps.setInt(2, season);
            ps.setInt(3, week);
            ps.setString(4, key);
            ps.executeUpdate();
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE weekly_counter SET count = count + 1"
                                + " WHERE uuid = ? AND season = ? AND week = ? AND `key` = ? AND count < ?")) {
            ps.setString(1, player.toString());
            ps.setInt(2, season);
            ps.setInt(3, week);
            ps.setString(4, key);
            ps.setInt(5, limit);
            return ps.executeUpdate() == 1;
        }
    }

    static int used(Connection c, UUID player, int season, int week, String key)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT count FROM weekly_counter WHERE uuid = ? AND season = ? AND week = ? AND `key` = ?")) {
            ps.setString(1, player.toString());
            ps.setInt(2, season);
            ps.setInt(3, week);
            ps.setString(4, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static void requireLimit(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("주간 한도는 1 이상이어야 합니다: " + limit);
        }
    }
}
