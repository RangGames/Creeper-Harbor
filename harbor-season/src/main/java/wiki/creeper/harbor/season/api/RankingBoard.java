package wiki.creeper.harbor.season.api;

import java.time.Instant;
import java.util.List;

/**
 * 보드 하나의 캐시된 순위표 (한 리그).
 *
 * @param board 보드 ID (season/rankings.yml)
 * @param league 리그
 * @param season 집계한 시즌
 * @param computedAt 집계 시각
 * @param entries 1위부터 (보드 size까지)
 */
public record RankingBoard(
        String board, League league, int season, Instant computedAt, List<RankEntry> entries) {

    public RankingBoard {
        entries = List.copyOf(entries);
    }

    public static RankingBoard empty(String board, League league, int season) {
        return new RankingBoard(board, league, season, Instant.EPOCH, List.of());
    }
}
