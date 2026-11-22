package wiki.creeper.harbor.season.ranking;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.model.JobTier;
import wiki.creeper.harbor.season.api.League;
import wiki.creeper.harbor.season.api.RankingSource;
import wiki.creeper.harbor.season.content.RankingBoardDef;
import wiki.creeper.harbor.season.content.RankingBoards;

/**
 * The 4 built-in ranking sources (docs 20 11.3 rankings.yml source). Reads other modules' tables
 * only through SQL (no code dependency, docs 20 11.2). players is not archived, so names are always
 * read from the current DB.
 *
 * <ul>
 *   <li>records: Collection records (biggest catch per species, index fish_id·season·league·size)
 *   <li>level: Core player_season (level → experience, league = job tier)
 *   <li>collection_season: Collection codex_season (season collection entry count; whoever
 *       registered their last entry earlier ranks higher)
 *   <li>boss_records: Boss boss_records (fastest kill, members = JSON array of UUID strings, first
 *       one is the party leader)
 * </ul>
 */
public final class BuiltinSources {

    private BuiltinSources() {}

    public static List<RankingSource> all() {
        return List.of(new Records(), new Level(), new CollectionSeason(), new Boss());
    }

    static String recordsSql(@Nullable String schema) {
        return "SELECT r.uuid, p.name, r.size, r.caught_at FROM "
                + SchemaName.prefix(schema)
                + "records r LEFT JOIN players p ON p.uuid = r.uuid"
                + " WHERE r.fish_id = ? AND r.season = ? AND r.league = ?"
                + " ORDER BY r.size DESC, r.caught_at ASC LIMIT ?";
    }

    static String levelSql(@Nullable String schema, boolean tierFilter) {
        return "SELECT s.uuid, p.name, s.level, s.exp FROM "
                + SchemaName.prefix(schema)
                + "player_season s LEFT JOIN players p ON p.uuid = s.uuid WHERE s.season = ?"
                + (tierFilter ? " AND s.job_tier = ?" : "")
                + " ORDER BY s.level DESC, s.exp DESC, s.uuid ASC LIMIT ?";
    }

    static String collectionSql(@Nullable String schema) {
        return "SELECT c.uuid, MAX(p.name), COUNT(*) AS n, MAX(c.first_caught) AS last_at FROM "
                + SchemaName.prefix(schema)
                + "codex_season c LEFT JOIN players p ON p.uuid = c.uuid WHERE c.season = ?"
                + " GROUP BY c.uuid ORDER BY n DESC, last_at ASC LIMIT ?";
    }

    static String bossSql() {
        // boss_records is a carry-over (keep) table and is not archived. Filter by the season
        // column only
        return "SELECT members, duration_ms, cleared_at FROM boss_records"
                + " WHERE boss_id = ? AND season = ? ORDER BY duration_ms ASC, cleared_at ASC LIMIT ?";
    }

    private static String subject(RankingBoardDef board) {
        HarborId subject = board.subject();
        if (subject == null) {
            throw new IllegalArgumentException("보드 " + board.id() + "에 subject가 없습니다");
        }
        return subject.toString();
    }

    private static Instant instant(@Nullable Timestamp ts) {
        return ts == null ? Instant.EPOCH : ts.toInstant();
    }

    private static String name(@Nullable String name, @Nullable UUID uuid) {
        if (name != null) {
            return name;
        }
        return uuid == null ? "?" : uuid.toString().substring(0, 8);
    }

    private static @Nullable UUID uuid(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static final class Records implements RankingSource {
        @Override
        public String id() {
            return RankingBoards.RECORDS;
        }

        @Override
        public List<Row> query(
                Connection c,
                RankingBoardDef board,
                League league,
                int season,
                @Nullable String schema,
                int limit)
                throws SQLException {
            List<Row> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(recordsSql(schema))) {
                ps.setString(1, subject(board));
                ps.setInt(2, season);
                ps.setString(3, league.key());
                ps.setInt(4, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID uuid = uuid(rs.getString(1));
                        rows.add(
                                new Row(
                                        uuid,
                                        name(rs.getString(2), uuid),
                                        rs.getDouble(3),
                                        0,
                                        instant(rs.getTimestamp(4)),
                                        1));
                    }
                }
            }
            return rows;
        }
    }

    static final class Level implements RankingSource {
        @Override
        public String id() {
            return RankingBoards.LEVEL;
        }

        @Override
        public List<Row> query(
                Connection c,
                RankingBoardDef board,
                League league,
                int season,
                @Nullable String schema,
                int limit)
                throws SQLException {
            Optional<JobTier> tier = league.tier();
            List<Row> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(levelSql(schema, tier.isPresent()))) {
                int i = 1;
                ps.setInt(i++, season);
                if (tier.isPresent()) {
                    ps.setInt(i++, tier.get().level());
                }
                ps.setInt(i, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID uuid = uuid(rs.getString(1));
                        rows.add(
                                new Row(
                                        uuid,
                                        name(rs.getString(2), uuid),
                                        rs.getInt(3),
                                        rs.getLong(4),
                                        Instant.EPOCH,
                                        1));
                    }
                }
            }
            return rows;
        }
    }

    static final class CollectionSeason implements RankingSource {
        @Override
        public String id() {
            return RankingBoards.COLLECTION_SEASON;
        }

        @Override
        public List<Row> query(
                Connection c,
                RankingBoardDef board,
                League league,
                int season,
                @Nullable String schema,
                int limit)
                throws SQLException {
            List<Row> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(collectionSql(schema))) {
                ps.setInt(1, season);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID uuid = uuid(rs.getString(1));
                        rows.add(
                                new Row(
                                        uuid,
                                        name(rs.getString(2), uuid),
                                        rs.getInt(3),
                                        0,
                                        instant(rs.getTimestamp(4)),
                                        1));
                    }
                }
            }
            return rows;
        }
    }

    static final class Boss implements RankingSource {
        @Override
        public String id() {
            return RankingBoards.BOSS_RECORDS;
        }

        @Override
        public boolean ascending() {
            return true;
        }

        @Override
        public List<Row> query(
                Connection c,
                RankingBoardDef board,
                League league,
                int season,
                @Nullable String schema,
                int limit)
                throws SQLException {
            record Raw(List<UUID> members, long duration, Instant at) {}
            List<Raw> raws = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(bossSql())) {
                ps.setString(1, subject(board));
                ps.setInt(2, season);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        raws.add(
                                new Raw(
                                        BossMembers.parse(rs.getString(1)),
                                        rs.getLong(2),
                                        instant(rs.getTimestamp(3))));
                    }
                }
            }
            Map<UUID, String> names =
                    BossMembers.names(c, raws.stream().flatMap(r -> r.members().stream()).toList());
            List<Row> rows = new ArrayList<>();
            for (Raw raw : raws) {
                UUID leader = raw.members().isEmpty() ? null : raw.members().getFirst();
                rows.add(
                        new Row(
                                leader,
                                leader == null
                                        ? "?"
                                        : names.getOrDefault(leader, name(null, leader)),
                                raw.duration(),
                                0,
                                raw.at(),
                                Math.max(1, raw.members().size())));
            }
            return rows;
        }
    }
}
