package wiki.creeper.harbor.season.ranking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.season.api.RankEntry;
import wiki.creeper.harbor.season.api.RankingSource;

/**
 * Ranking (pure computation). Sorts by value → secondary value → achieved time (earlier ranks
 * higher, docs 20 11.4 ties) → uuid and numbers from 1. Numbers never repeat, even for equal
 * values.
 */
public final class RankOrder {

    private RankOrder() {}

    public static Comparator<RankingSource.Row> comparator(boolean ascending) {
        Comparator<RankingSource.Row> byValue =
                Comparator.comparingDouble(RankingSource.Row::value);
        Comparator<RankingSource.Row> bySecondary =
                Comparator.comparingDouble(RankingSource.Row::secondary);
        if (!ascending) {
            byValue = byValue.reversed();
            bySecondary = bySecondary.reversed();
        }
        return byValue.thenComparing(bySecondary)
                .thenComparing(RankingSource.Row::achievedAt)
                .thenComparing(r -> uuidKey(r.uuid()));
    }

    /**
     * Sorts and ranks the top limit entries. If the same player appears twice, only the better one
     * is kept (party records are kept even with the same uuid).
     */
    public static List<RankEntry> rank(
            List<RankingSource.Row> rows, boolean ascending, int limit, boolean distinctPlayers) {
        List<RankingSource.Row> sorted = new ArrayList<>(rows);
        sorted.sort(comparator(ascending));
        List<RankEntry> result = new ArrayList<>(Math.min(limit, sorted.size()));
        Set<UUID> seen = new HashSet<>();
        for (RankingSource.Row row : sorted) {
            if (result.size() >= limit) {
                break;
            }
            UUID uuid = row.uuid();
            if (distinctPlayers && uuid != null && !seen.add(uuid)) {
                continue;
            }
            result.add(
                    new RankEntry(
                            result.size() + 1,
                            uuid,
                            row.name(),
                            row.value(),
                            row.secondary(),
                            row.achievedAt(),
                            row.members()));
        }
        return List.copyOf(result);
    }

    private static String uuidKey(@Nullable UUID uuid) {
        return uuid == null ? "" : uuid.toString();
    }
}
