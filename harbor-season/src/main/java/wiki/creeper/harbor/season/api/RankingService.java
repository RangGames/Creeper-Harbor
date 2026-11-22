package wiki.creeper.harbor.season.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import wiki.creeper.harbor.season.content.RankingBoardDef;

/**
 * Rankings (docs 20 11.4). The one server holding harbor:lock:ranking aggregates the top N per
 * board on its refresh interval (default 10 minutes) and sends them to all servers over the
 * harbor:rank channel. Other servers and the leaderboard displays (Collection) read only this
 * cache, so {@link #board} does not access the DB (callable from any thread).
 *
 * <pre>{@code
 * RankingBoard top = SeasonServices.rankings().board("level", League.ALL);
 * top.entries().stream().limit(10).forEach(e -> draw(e.rank(), e.name(), e.value()));
 * }</pre>
 */
public interface RankingService {

    /** Board definitions (season/rankings.yml). */
    List<RankingBoardDef> boards();

    Optional<RankingBoardDef> boardDef(String board);

    /** Cached leaderboard. An empty table if not yet aggregated or the board does not exist. */
    RankingBoard board(String board, League league);

    /** Rank within the cached top N. Empty Optional if outside. */
    Optional<RankEntry> rankOf(UUID player, String board, League league);

    /**
     * Re-aggregates immediately (admin command). false if the lock could not be acquired. Completes
     * on the main thread.
     */
    CompletableFuture<Boolean> refreshNow();

    /**
     * Registers a ranking source from another module (in module enable). Overwrites on the same id.
     */
    void registerSource(RankingSource source);
}
