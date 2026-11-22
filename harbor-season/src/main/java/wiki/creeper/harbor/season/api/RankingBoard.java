package wiki.creeper.harbor.season.api;

import java.time.Instant;
import java.util.List;

/**
 * Cached leaderboard for one board (one league).
 *
 * @param board board ID (season/rankings.yml)
 * @param league league
 * @param season season aggregated
 * @param computedAt time aggregated
 * @param entries from first place (up to the board size)
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
