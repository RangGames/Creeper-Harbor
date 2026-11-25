package wiki.creeper.harbor.npc.data;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.account.BestReputation;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.npc.api.ReputationLogEntry;
import wiki.creeper.harbor.npc.api.ReputationSource;

/**
 * npc_affinity · npc_reputation_log · best tier (players.best_rep_tier, npc_rep_best fallback) ·
 * cutscene_restore SQL. Call only from virtual threads.
 */
public final class NpcRepository {

    private NpcRepository() {}

    // ------------------------------------------------------------------ player data

    public static NpcData load(
            Connection c, UUID uuid, SeasonId season, LocalDate today, int recentSize)
            throws SQLException {
        Map<NpcId, AffinityState> affinity = new HashMap<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT npc_id, points, gift_day, gifts_today, greet_day FROM npc_affinity"
                                + " WHERE uuid = ? AND season = ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, season.value());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        affinity.put(
                                NpcId.parse(rs.getString(1)),
                                new AffinityState(
                                        rs.getInt(2),
                                        localDate(rs.getDate(3)),
                                        rs.getInt(4),
                                        localDate(rs.getDate(5))));
                    } catch (IllegalArgumentException e) {
                        // Ignore malformed rows
                    }
                }
            }
        }
        Map<ReputationSource, Integer> todayBySource = new EnumMap<>(ReputationSource.class);
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT source, SUM(delta) FROM npc_reputation_log"
                                + " WHERE uuid = ? AND season = ? AND day = ? AND delta > 0 GROUP BY source")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, season.value());
            ps.setDate(3, Date.valueOf(today));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Optional<ReputationSource> source =
                            Keyed.fromKey(ReputationSource.class, rs.getString(1));
                    int sum = rs.getInt(2);
                    source.ifPresent(s -> todayBySource.put(s, sum));
                }
            }
        }
        List<ReputationLogEntry> recent = new ArrayList<>();
        Set<String> refs = new LinkedHashSet<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT source, delta, ref_id, created_at FROM npc_reputation_log"
                                + " WHERE uuid = ? AND season = ? ORDER BY id DESC LIMIT ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, season.value());
            ps.setInt(3, NpcData.MAX_REFS);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Optional<ReputationSource> source =
                            Keyed.fromKey(ReputationSource.class, rs.getString(1));
                    String ref = rs.getString(3);
                    if (ref != null) {
                        refs.add(ref);
                    }
                    if (source.isPresent() && recent.size() < recentSize) {
                        java.sql.Timestamp at = rs.getTimestamp(4);
                        recent.add(
                                new ReputationLogEntry(
                                        source.get(),
                                        rs.getInt(2),
                                        ref,
                                        at == null ? Instant.EPOCH : at.toInstant()));
                    }
                }
            }
        }
        int best = loadBest(c, uuid);
        return new NpcData(affinity, today, todayBySource, recent, refs, Math.max(1, best));
    }

    /** Saves affinity (write-behind queue, or immediately on quit). */
    public static void saveAffinity(
            Connection c, UUID uuid, SeasonId season, Map<NpcId, AffinityState> affinity)
            throws SQLException {
        if (affinity.isEmpty()) {
            return;
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO npc_affinity (uuid, season, npc_id, points, gift_day, gifts_today, greet_day)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE points = VALUES(points),"
                                + " gift_day = VALUES(gift_day), gifts_today = VALUES(gifts_today),"
                                + " greet_day = VALUES(greet_day)")) {
            for (Map.Entry<NpcId, AffinityState> e : affinity.entrySet()) {
                AffinityState s = e.getValue();
                ps.setString(1, uuid.toString());
                ps.setInt(2, season.value());
                ps.setString(3, e.getKey().toString());
                ps.setInt(4, s.points());
                setDate(ps, 5, s.giftDay());
                ps.setInt(6, s.giftsToday());
                setDate(ps, 7, s.greetDay());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ------------------------------------------------------------------ reputation

    /** Inserts a reputation log entry. False if the same (uuid, season, ref_id) already exists. */
    public static boolean insertReputation(
            Connection c,
            UUID uuid,
            SeasonId season,
            ReputationSource source,
            int delta,
            @Nullable String refId,
            LocalDate day)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT IGNORE INTO npc_reputation_log (uuid, season, source, delta, ref_id, day)"
                                + " VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, season.value());
            ps.setString(3, source.key());
            ps.setInt(4, delta);
            if (refId == null) {
                ps.setNull(5, Types.VARCHAR);
            } else {
                ps.setString(5, refId);
            }
            ps.setDate(6, Date.valueOf(day));
            return ps.executeUpdate() == 1;
        }
    }

    /** Deletes an entry that could not be applied (so it can be received again). */
    public static void deleteReputation(Connection c, UUID uuid, SeasonId season, String refId)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "DELETE FROM npc_reputation_log WHERE uuid = ? AND season = ? AND ref_id = ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, season.value());
            ps.setString(3, refId);
            ps.executeUpdate();
        }
    }

    /**
     * Best tier across seasons: Common players.best_rep_tier ('11. Data design' 3.2), with the
     * older npc_rep_best table as a fallback (rows written before the column existed, or players
     * without a players row). The higher of the two wins; 1 if neither has a value.
     */
    public static int loadBest(Connection c, UUID uuid) throws SQLException {
        int best = BestReputation.read(c, uuid).orElse(1);
        try (PreparedStatement ps =
                c.prepareStatement("SELECT best_tier FROM npc_rep_best WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Math.max(best, rs.getInt(1)) : best;
            }
        }
    }

    /**
     * The best tier only goes up. Writes players.best_rep_tier and keeps npc_rep_best in step (the
     * fallback for players without a players row).
     */
    public static void raiseBest(Connection c, UUID uuid, int tier) throws SQLException {
        BestReputation.raise(c, uuid, tier);
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO npc_rep_best (uuid, best_tier) VALUES (?, ?) ON DUPLICATE KEY UPDATE"
                                + " reached_at = IF(VALUES(best_tier) > best_tier, CURRENT_TIMESTAMP(3), reached_at),"
                                + " best_tier = GREATEST(best_tier, VALUES(best_tier))")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, tier);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ cutscene restore

    public static void saveRestore(Connection c, UUID uuid, RestoreInfo info) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "REPLACE INTO cutscene_restore (uuid, server, cutscene, world, x, y, z, yaw, pitch, gamemode,"
                                + " allow_flight, flying) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, info.server());
            ps.setString(3, info.cutscene());
            ps.setString(4, info.world());
            ps.setDouble(5, info.x());
            ps.setDouble(6, info.y());
            ps.setDouble(7, info.z());
            ps.setFloat(8, info.yaw());
            ps.setFloat(9, info.pitch());
            ps.setString(10, info.gameMode());
            ps.setBoolean(11, info.allowFlight());
            ps.setBoolean(12, info.flying());
            ps.executeUpdate();
        }
    }

    public static Optional<RestoreInfo> loadRestore(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT server, cutscene, world, x, y, z, yaw, pitch, gamemode, allow_flight, flying"
                                + " FROM cutscene_restore WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        new RestoreInfo(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getDouble(4),
                                rs.getDouble(5),
                                rs.getDouble(6),
                                rs.getFloat(7),
                                rs.getFloat(8),
                                rs.getString(9),
                                rs.getBoolean(10),
                                rs.getBoolean(11)));
            }
        }
    }

    public static void deleteRestore(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("DELETE FROM cutscene_restore WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }

    private static @Nullable LocalDate localDate(@Nullable Date date) {
        return date == null ? null : date.toLocalDate();
    }

    private static void setDate(PreparedStatement ps, int index, @Nullable LocalDate day)
            throws SQLException {
        if (day == null) {
            ps.setNull(index, Types.DATE);
        } else {
            ps.setDate(index, Date.valueOf(day));
        }
    }
}
