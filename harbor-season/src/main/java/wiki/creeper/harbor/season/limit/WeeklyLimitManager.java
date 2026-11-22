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
 * weekly_counter 구현 (docs 20 11.4 tryConsume).
 *
 * <p>문서의 {@code INSERT … ON DUPLICATE KEY UPDATE count = IF(count < limit, count + 1, count)}는
 * MariaDB 드라이버가 기본으로 "찾은 행 수"를 돌려줘서 값이 그대로일 때도 1이 나온다 (새 행 삽입과 구별되지 않음). 그래서 같은 트랜잭션에서 {@code
 * INSERT IGNORE}로 행을 만든 뒤 {@code UPDATE … SET count = count + 1 WHERE … AND count < limit} 한 문장으로
 * 판정한다. 행 잠금 아래 조건부 UPDATE 하나라 서버 여러 대에서 동시에 불러도 한도를 넘지 않는다.
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

    /** 관리자 명령: 이번 주 키별 사용 횟수. */
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

    /** 관리자 명령: 이번 주 횟수를 지운다. */
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
