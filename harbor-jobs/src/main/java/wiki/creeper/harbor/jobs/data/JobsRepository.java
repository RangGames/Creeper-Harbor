package wiki.creeper.harbor.jobs.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.SkillId;
import wiki.creeper.harbor.common.model.JobLine;
import wiki.creeper.harbor.common.model.JobTier;
import wiki.creeper.harbor.common.model.SeasonId;

/** skill_alloc · jobs_state SQL. 트랜잭션은 호출하는 쪽이 연다. */
public final class JobsRepository {

    private JobsRepository() {}

    public static JobsData load(Connection c, UUID uuid, SeasonId season) throws SQLException {
        Map<SkillId, Integer> skills = new HashMap<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT node_id, `rank` FROM skill_alloc WHERE uuid = ? AND season = ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, season.value());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        skills.put(SkillId.parse(rs.getString(1)), rs.getInt(2));
                    } catch (IllegalArgumentException e) {
                        // 형식이 틀린 행은 무시한다 (콘텐츠에서 사라진 노드는 접속 후 환급 처리)
                    }
                }
            }
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT overflow_exp, free_reset_used, capstone_used_at, capstone_week, capstone_week_uses,"
                                + " tier_first_q4, tier_first_q5 FROM jobs_state WHERE uuid = ? AND season = ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, season.value());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return JobsData.EMPTY.withSkills(skills);
                }
                Timestamp used = rs.getTimestamp(3);
                return new JobsData(
                        skills,
                        rs.getLong(1),
                        rs.getBoolean(2),
                        used == null ? null : used.toInstant(),
                        rs.getInt(4),
                        rs.getInt(5),
                        rs.getBoolean(6),
                        rs.getBoolean(7));
            }
        }
    }

    /** jobs_state 저장 (지연 쓰기 또는 트랜잭션 안). skill_alloc은 투자 트랜잭션이 직접 쓴다. */
    public static void saveState(Connection c, UUID uuid, SeasonId season, JobsData data)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO jobs_state (uuid, season, overflow_exp, free_reset_used, capstone_used_at, capstone_week,"
                                + " capstone_week_uses, tier_first_q4, tier_first_q5) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE overflow_exp = VALUES(overflow_exp), free_reset_used = VALUES(free_reset_used),"
                                + " capstone_used_at = VALUES(capstone_used_at), capstone_week = VALUES(capstone_week),"
                                + " capstone_week_uses = VALUES(capstone_week_uses), tier_first_q4 = VALUES(tier_first_q4),"
                                + " tier_first_q5 = VALUES(tier_first_q5)")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, season.value());
            ps.setLong(3, data.overflowExp());
            ps.setBoolean(4, data.freeResetUsed());
            Instant used = data.capstoneUsedAt();
            if (used == null) {
                ps.setNull(5, Types.TIMESTAMP);
            } else {
                ps.setTimestamp(5, Timestamp.from(used));
            }
            ps.setInt(6, data.capstoneWeek());
            ps.setInt(7, data.capstoneWeekUses());
            ps.setBoolean(8, data.tierFirstQ4());
            ps.setBoolean(9, data.tierFirstQ5());
            ps.executeUpdate();
        }
    }

    public static void setRank(Connection c, UUID uuid, SeasonId season, SkillId node, int rank)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO skill_alloc (uuid, season, node_id, `rank`) VALUES (?, ?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE `rank` = VALUES(`rank`)")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, season.value());
            ps.setString(3, node.toString());
            ps.setInt(4, rank);
            ps.executeUpdate();
        }
    }

    public static void deleteNodes(
            Connection c, UUID uuid, SeasonId season, Collection<SkillId> nodes)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "DELETE FROM skill_alloc WHERE uuid = ? AND season = ? AND node_id = ?")) {
            for (SkillId node : nodes) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, season.value());
                ps.setString(3, node.toString());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** 전직·계열 변경: player_season(Core 테이블)의 단계·계열을 같은 트랜잭션에서 바꾼다. */
    public static void updateJob(
            Connection c, UUID uuid, SeasonId season, @Nullable JobLine line, JobTier tier)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE player_season SET job_line = ?, job_tier = ? WHERE uuid = ? AND season = ?")) {
            if (line == null) {
                ps.setNull(1, Types.VARCHAR);
            } else {
                ps.setString(1, line.key());
            }
            ps.setInt(2, tier.level());
            ps.setString(3, uuid.toString());
            ps.setInt(4, season.value());
            ps.executeUpdate();
        }
    }
}
