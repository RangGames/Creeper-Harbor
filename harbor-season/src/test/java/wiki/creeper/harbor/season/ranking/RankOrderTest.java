package wiki.creeper.harbor.season.ranking;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.season.api.RankEntry;
import wiki.creeper.harbor.season.api.RankingSource;

class RankOrderTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private static RankingSource.Row row(UUID uuid, double value, double secondary, long at) {
        return new RankingSource.Row(
                uuid,
                uuid.toString().substring(35),
                value,
                secondary,
                Instant.ofEpochSecond(at),
                1);
    }

    @Test
    void higherValueFirstAndEarlierWinsTies() {
        List<RankEntry> ranked =
                RankOrder.rank(
                        List.of(row(A, 50.0, 0, 30), row(B, 52.5, 0, 40), row(C, 50.0, 0, 10)),
                        false,
                        10,
                        true);
        assertEquals(List.of(B, C, A), ranked.stream().map(RankEntry::uuid).toList());
        assertEquals(List.of(1, 2, 3), ranked.stream().map(RankEntry::rank).toList());
    }

    @Test
    void secondaryBreaksTiesBeforeTime() {
        // Level board: at the same level, more experience ranks higher
        List<RankEntry> ranked =
                RankOrder.rank(List.of(row(A, 40, 100, 0), row(B, 40, 900, 0)), false, 10, true);
        assertEquals(B, ranked.getFirst().uuid());
    }

    @Test
    void ascendingForBossTimesAndLimit() {
        List<RankEntry> ranked =
                RankOrder.rank(
                        List.of(row(A, 90_000, 0, 1), row(B, 60_000, 0, 2), row(C, 75_000, 0, 3)),
                        true,
                        2,
                        false);
        assertEquals(List.of(B, C), ranked.stream().map(RankEntry::uuid).toList());
    }

    @Test
    void distinctKeepsBestRowPerPlayer() {
        List<RankEntry> ranked =
                RankOrder.rank(
                        List.of(row(A, 10, 0, 1), row(A, 20, 0, 2), row(B, 15, 0, 3)),
                        false,
                        10,
                        true);
        assertEquals(2, ranked.size());
        assertEquals(20, ranked.getFirst().value());
        // Party records (boss) are kept even when the same party leader appears multiple times
        assertEquals(
                3,
                RankOrder.rank(
                                List.of(row(A, 10, 0, 1), row(A, 20, 0, 2), row(B, 15, 0, 3)),
                                true,
                                10,
                                false)
                        .size());
    }

    @Test
    void bossMembersJson() {
        assertEquals(List.of(A, B), BossMembers.parse("[\"" + A + "\", \"" + B + "\"]"));
        assertEquals(
                List.of(C), BossMembers.parse("[{\"uuid\": \"" + C + "\", \"role\": \"anchor\"}]"));
        assertEquals(List.of(), BossMembers.parse("not json"));
        assertEquals(List.of(), BossMembers.parse(null));
    }

    @Test
    void builtinSqlUsesArchiveSchema() {
        String sql = BuiltinSources.recordsSql("archive_s3");
        assertEquals(true, sql.contains("FROM `archive_s3`.records r"));
        assertEquals(true, BuiltinSources.levelSql(null, true).contains("AND s.job_tier = ?"));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> SchemaName.prefix("harbor; DROP"));
    }
}
