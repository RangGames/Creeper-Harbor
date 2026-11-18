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
 * 순위 매기기 (순수 계산). 값 → 보조 값 → 달성 시각(먼저 달성한 사람이 앞섬, docs 20 11.4 동점) → uuid 순으로 정렬해 1부터 번호를 붙인다. 같은
 * 값이어도 번호는 겹치지 않는다.
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

    /** 정렬하고 상위 limit개에 순위를 붙인다. 같은 플레이어가 두 번 나오면 좋은 쪽만 남긴다 (파티 기록은 uuid가 같아도 남김). */
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
