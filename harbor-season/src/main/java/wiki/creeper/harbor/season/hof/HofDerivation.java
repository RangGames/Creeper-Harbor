package wiki.creeper.harbor.season.hof;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.season.SeasonContentView;
import wiki.creeper.harbor.season.api.HallOfFameSubmission;
import wiki.creeper.harbor.season.content.HallOfFameCategory;
import wiki.creeper.harbor.season.ranking.BossMembers;
import wiki.creeper.harbor.season.ranking.SchemaName;

/**
 * Builds hall-of-fame entries from game data: first-come lists for level_first · collection_first,
 * and the recomputation used when a season is finalized.
 */
final class HofDerivation {

    private final SeasonContentView content;
    private final Logger log;

    HofDerivation(SeasonContentView content, Logger log) {
        this.content = content;
        this.log = log;
    }

    /**
     * Builds the first-come list for level_first · collection_first categories from data (by time
     * achieved).
     */
    List<HallOfFameSubmission> derive(
            Connection c, HallOfFameCategory cat, int season, @Nullable String schema)
            throws SQLException {
        return switch (cat.source()) {
            case LEVEL_FIRST -> deriveLevel(c, cat, season, schema);
            case COLLECTION_FIRST -> deriveCollection(c, cat, season, schema);
            default -> List.of();
        };
    }

    /**
     * First to reach a level. Finds candidates via player_season (index season·level) and orders
     * them by the time of the LEVEL_UP ledger entry Jobs writes on level-up (reference ID {@code
     * lvl:<season>:<uuid>:<level>}, unique index). Season does not depend on Jobs code, only on
     * this data format.
     */
    private List<HallOfFameSubmission> deriveLevel(
            Connection c, HallOfFameCategory cat, int season, @Nullable String schema)
            throws SQLException {
        record Candidate(UUID uuid, String name, Instant at) {}
        List<Candidate> list = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT s.uuid, p.name, l.created_at FROM "
                                + SchemaName.prefix(schema)
                                + "player_season s"
                                + " LEFT JOIN players p ON p.uuid = s.uuid"
                                + " JOIN ledger l ON l.uuid = s.uuid AND l.currency = 'MULBINEUL' AND l.reason = 'LEVEL_UP'"
                                + " AND l.ref_id = CONCAT('lvl:', ?, ':', s.uuid, ':', ?)"
                                + " WHERE s.season = ? AND s.level >= ?"
                                + " ORDER BY l.created_at ASC LIMIT ?")) {
            ps.setInt(1, season);
            ps.setInt(2, cat.level());
            ps.setInt(3, season);
            ps.setInt(4, cat.level());
            ps.setInt(5, cat.count() > 0 ? cat.count() : 1000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString(1));
                    String name = rs.getString(2);
                    list.add(
                            new Candidate(
                                    uuid,
                                    name == null ? uuid.toString().substring(0, 8) : name,
                                    rs.getTimestamp(3).toInstant()));
                }
            }
        }
        List<HallOfFameSubmission> result = new ArrayList<>();
        for (Candidate cand : list) {
            result.add(
                    new HallOfFameSubmission(
                            cat.category(),
                            null,
                            cand.uuid(),
                            cand.name(),
                            cat.level(),
                            Map.of("level", String.valueOf(cat.level())),
                            cand.at()));
        }
        return result;
    }

    /**
     * First to complete the season collection (all species except mythic, by the last first-catch
     * time).
     */
    private List<HallOfFameSubmission> deriveCollection(
            Connection c, HallOfFameCategory cat, int season, @Nullable String schema)
            throws SQLException {
        List<String> fish =
                content.snapshot().all(FishDef.TYPE).values().stream()
                        .filter(f -> f.grade() != Grade.MYTHIC)
                        .map(f -> f.id().toString())
                        .toList();
        if (fish.isEmpty()) {
            return List.of();
        }
        String marks = String.join(",", Collections.nCopies(fish.size(), "?"));
        List<HallOfFameSubmission> result = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT c.uuid, MAX(p.name), COUNT(*) AS n, MAX(c.first_caught) AS done_at FROM "
                                + SchemaName.prefix(schema)
                                + "codex_season c LEFT JOIN players p ON p.uuid = c.uuid"
                                + " WHERE c.season = ? AND c.fish_id IN ("
                                + marks
                                + ") GROUP BY c.uuid HAVING n >= ? ORDER BY done_at ASC LIMIT ?")) {
            int i = 1;
            ps.setInt(i++, season);
            for (String f : fish) {
                ps.setString(i++, f);
            }
            ps.setInt(i++, fish.size());
            ps.setInt(i, cat.count() > 0 ? cat.count() : 1000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString(1));
                    String name = rs.getString(2);
                    result.add(
                            new HallOfFameSubmission(
                                    cat.category(),
                                    null,
                                    uuid,
                                    name == null ? uuid.toString().substring(0, 8) : name,
                                    rs.getInt(3),
                                    Map.of("species", String.valueOf(rs.getInt(3))),
                                    rs.getTimestamp(4).toInstant()));
                }
            }
        }
        return result;
    }

    /**
     * Recomputation for finalization. Empty list if the source table is missing (provisional
     * entries are finalized as-is).
     */
    List<HallOfFameSubmission> recompute(
            Connection c, HallOfFameCategory cat, int season, String archiveSchema)
            throws SQLException {
        try {
            return switch (cat.source()) {
                case RECORDS -> topRecords(c, cat, season, archiveSchema);
                case BOSS_RECORDS -> topBoss(c, cat, season);
                case LEVEL_FIRST, COLLECTION_FIRST -> derive(c, cat, season, archiveSchema);
                case SUBMITTED -> List.of();
            };
        } catch (SQLSyntaxErrorException e) {
            log.warn("명예의 전당 {} 재계산 원천이 없어 잠정 기록을 확정합니다: {}", cat.category(), e.getMessage());
            return List.of();
        }
    }

    /** Overall-league #1 per species (archived records). */
    private List<HallOfFameSubmission> topRecords(
            Connection c, HallOfFameCategory cat, int season, String archiveSchema)
            throws SQLException {
        Map<String, HallOfFameSubmission> best = new HashMap<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT r.fish_id, r.uuid, p.name, r.size, r.caught_at FROM "
                                + SchemaName.prefix(archiveSchema)
                                + "records r LEFT JOIN players p ON p.uuid = r.uuid"
                                + " WHERE r.season = ? AND r.league = 'all'"
                                + " ORDER BY r.fish_id, r.size DESC, r.caught_at ASC")) {
            ps.setInt(1, season);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String fish = rs.getString(1);
                    if (best.containsKey(fish)) {
                        continue;
                    }
                    Optional<HarborId> id = HarborId.tryParse(fish);
                    if (id.isEmpty()) {
                        continue;
                    }
                    UUID uuid = UUID.fromString(rs.getString(2));
                    String name = rs.getString(3);
                    best.put(
                            fish,
                            new HallOfFameSubmission(
                                    cat.category(),
                                    id.get(),
                                    uuid,
                                    name == null ? uuid.toString().substring(0, 8) : name,
                                    rs.getDouble(4),
                                    Map.of(),
                                    rs.getTimestamp(5).toInstant()));
                }
            }
        }
        return List.copyOf(best.values());
    }

    /**
     * Fastest clear per boss (boss_records is a carry-over table, so season n rows in the current
     * DB).
     */
    private List<HallOfFameSubmission> topBoss(Connection c, HallOfFameCategory cat, int season)
            throws SQLException {
        record Raw(HarborId boss, List<UUID> members, long duration, Instant at) {}
        Map<String, Raw> best = new LinkedHashMap<>();
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT boss_id, members, duration_ms, cleared_at FROM boss_records"
                                + " WHERE season = ? ORDER BY boss_id, duration_ms ASC, cleared_at ASC")) {
            ps.setInt(1, season);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String boss = rs.getString(1);
                    if (best.containsKey(boss)) {
                        continue;
                    }
                    Optional<HarborId> id = HarborId.tryParse(boss);
                    List<UUID> members = BossMembers.parse(rs.getString(2));
                    if (id.isEmpty() || members.isEmpty()) {
                        continue;
                    }
                    best.put(
                            boss,
                            new Raw(
                                    id.get(),
                                    members,
                                    rs.getLong(3),
                                    rs.getTimestamp(4).toInstant()));
                }
            }
        }
        Map<UUID, String> names =
                BossMembers.names(
                        c, best.values().stream().map(r -> r.members().getFirst()).toList());
        List<HallOfFameSubmission> result = new ArrayList<>();
        for (Raw raw : best.values()) {
            UUID leader = raw.members().getFirst();
            result.add(
                    new HallOfFameSubmission(
                            cat.category(),
                            raw.boss(),
                            leader,
                            names.getOrDefault(leader, leader.toString().substring(0, 8)),
                            raw.duration(),
                            Map.of("members", String.valueOf(raw.members().size())),
                            raw.at()));
        }
        return result;
    }
}
