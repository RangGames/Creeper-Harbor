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

/** skill_alloc · jobs_state SQL. Callers open transactions. */
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
                        // Ignore malformed rows (nodes removed from content are refunded after
                        // join)
                    }
                }
            }
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT overflow_exp, free_reset_used, capstone_used_at, capstone_week, capstone_week_uses,"
                                + " tier_first_q4, tier_first_q5, line_changed_at, join_bonus_given"
                                + " FROM jobs_state WHERE uuid = ? AND season = ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, season.value());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return JobsData.EMPTY.withSkills(skills);
                }
                Timestamp used = rs.getTimestamp(3);
                Timestamp lineChanged = rs.getTimestamp(8);
                return new JobsData(
                        skills,
                        rs.getLong(1),
                        rs.getBoolean(2),
                        used == null ? null : used.toInstant(),
                        rs.getInt(4),
                        rs.getInt(5),
                        rs.getBoolean(6),
                        rs.getBoolean(7),
                        lineChanged == null ? null : lineChanged.toInstant(),
                        rs.getBoolean(9));
            }
        }
    }

    /**
     * Saves jobs_state (write-behind or inside a transaction). skill_alloc is written directly by
     * the investment transaction.
     */
    public static void saveState(Connection c, UUID uuid, SeasonId season, JobsData data)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO jobs_state (uuid, season, overflow_exp, free_reset_used, capstone_used_at, capstone_week,"
                                + " capstone_week_uses, tier_first_q4, tier_first_q5, line_changed_at, join_bonus_given)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE overflow_exp = VALUES(overflow_exp), free_reset_used = VALUES(free_reset_used),"
                                + " capstone_used_at = VALUES(capstone_used_at), capstone_week = VALUES(capstone_week),"
                                + " capstone_week_uses = VALUES(capstone_week_uses), tier_first_q4 = VALUES(tier_first_q4),"
                                + " tier_first_q5 = VALUES(tier_first_q5), line_changed_at = VALUES(line_changed_at),"
                                + " join_bonus_given = VALUES(join_bonus_given)")) {
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
            Instant lineChanged = data.lineChangedAt();
            if (lineChanged == null) {
                ps.setNull(10, Types.TIMESTAMP);
            } else {
                ps.setTimestamp(10, Timestamp.from(lineChanged));
            }
            ps.setBoolean(11, data.joinBonusGiven());
            ps.executeUpdate();
        }
    }

    /**
     * Sets a node's rank and adds the mulbineul paid for it to the node's invested total (used to
     * refund nodes removed from content, docs 20 2.4).
     */
    public static void setRank(
            Connection c, UUID uuid, SeasonId season, SkillId node, int rank, long cost)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO skill_alloc (uuid, season, node_id, `rank`, invested) VALUES (?, ?, ?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE `rank` = VALUES(`rank`), invested = invested + VALUES(invested)")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, season.value());
            ps.setString(3, node.toString());
            ps.setInt(4, rank);
            ps.setLong(5, Math.max(0, cost));
            ps.executeUpdate();
        }
    }

    /** Recorded invested mulbineul per node (0 for rows written before the column existed). */
    public static Map<SkillId, Long> invested(
            Connection c, UUID uuid, SeasonId season, Collection<SkillId> nodes)
            throws SQLException {
        Map<SkillId, Long> result = new HashMap<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT invested FROM skill_alloc WHERE uuid = ? AND season = ? AND node_id = ?")) {
            for (SkillId node : nodes) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, season.value());
                ps.setString(3, node.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    result.put(node, rs.next() ? rs.getLong(1) : 0L);
                }
            }
        }
        return result;
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

    /**
     * Job advancement and line change: updates tier and line in player_season (Core table) in the
     * same transaction.
     */
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
