package wiki.creeper.harbor.core.player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.model.JobLine;
import wiki.creeper.harbor.common.model.JobTier;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.common.model.SeasonId;

/** players · player_season 읽기·쓰기. */
final class PlayerRepository {

    /** 접속 때 읽는 기본 데이터. */
    record Row(
            UUID uuid,
            String name,
            Instant firstJoin,
            long playtimeSeconds,
            SeasonId season,
            @Nullable JobLine jobLine,
            JobTier jobTier,
            int level,
            long exp,
            long gold,
            long mulbineul,
            int reputation) {}

    private PlayerRepository() {}

    /** 계정 행을 만들거나 이름·마지막 접속을 갱신하고, 이번 시즌 행이 없으면 만든 뒤 읽는다. */
    static Row loadOrCreate(Connection c, UUID uuid, String name, SeasonId season)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO players (uuid, name) VALUES (?, ?)"
                                + " ON DUPLICATE KEY UPDATE name = VALUES(name), last_join = CURRENT_TIMESTAMP(3)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.executeUpdate();
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT IGNORE INTO player_season (uuid, season) VALUES (?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, season.value());
            ps.executeUpdate();
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT p.first_join, p.playtime, s.job_line, s.job_tier, s.level, s.exp, s.gold,"
                                + " s.mulbineul_earned - s.mulbineul_spent, s.reputation"
                                + " FROM players p JOIN player_season s ON s.uuid = p.uuid AND s.season = ?"
                                + " WHERE p.uuid = ?")) {
            ps.setInt(1, season.value());
            ps.setString(2, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("플레이어 행을 만들지 못했습니다: " + uuid);
                }
                String line = rs.getString(3);
                return new Row(
                        uuid,
                        name,
                        rs.getTimestamp(1).toInstant(),
                        rs.getLong(2),
                        season,
                        line == null ? null : Keyed.fromKey(JobLine.class, line).orElse(null),
                        JobTier.of(rs.getInt(4)),
                        rs.getInt(5),
                        rs.getLong(6),
                        rs.getLong(7),
                        rs.getLong(8),
                        rs.getInt(9));
            }
        }
    }

    static void saveBase(Connection c, PlayerState.BaseSnapshot s) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("UPDATE players SET name = ?, playtime = ? WHERE uuid = ?")) {
            ps.setString(1, s.name());
            ps.setLong(2, s.playtimeSeconds());
            ps.setString(3, s.uuid().toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE player_season SET job_line = ?, job_tier = ?, level = ?, exp = ?, reputation = ?"
                                + " WHERE uuid = ? AND season = ?")) {
            JobLine line = s.jobLine();
            if (line == null) {
                ps.setNull(1, Types.VARCHAR);
            } else {
                ps.setString(1, line.key());
            }
            ps.setInt(2, s.jobTier().level());
            ps.setInt(3, s.level());
            ps.setLong(4, s.exp());
            ps.setInt(5, s.reputation());
            ps.setString(6, s.uuid().toString());
            ps.setInt(7, s.season().value());
            ps.executeUpdate();
        }
    }
}
