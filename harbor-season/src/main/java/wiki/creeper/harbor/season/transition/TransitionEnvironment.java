package wiki.creeper.harbor.season.transition;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.season.api.SeasonHook;
import wiki.creeper.harbor.season.api.TitleId;

/**
 * Work the transition engine delegates to the server (or the harbor-tools rehearsal CLI). The
 * engine knows nothing of the Paper API and talks to the outside only through this interface. All
 * methods are called on virtual threads and may block.
 */
public interface TransitionEnvironment {

    /** The season this server is currently running (harbor-infra.yml season.id). */
    int currentSeason();

    /** PRECHECK: validation errors in the next season's content (seasons.yml, content_dir). */
    List<String> checkContent(int nextSeason);

    /**
     * PRECHECK: downloads the next season's resource pack URL and checks the SHA-1. List of errors.
     */
    List<String> checkResourcePack(int nextSeason);

    /** Free disk space on the DB server (bytes). Empty if unknown (only a warning is logged). */
    OptionalLong freeDiskBytes();

    boolean titleExists(TitleId title);

    /** Start and end time of the next season (for creating the season_meta row if missing). */
    SeasonWindow window(int season);

    /** Start and end time (epoch seconds). */
    record SeasonWindow(long startsAt, long endsAt) {}

    /** Network-wide notice (harbor:announce). Message key and placeholders. */
    void announce(String messageKey, Map<String, String> placeholders);

    /**
     * Progress updates for admins (online holders of harbor.admin.season.transition and the
     * console).
     */
    void progress(String messageKey, Map<String, String> placeholders);

    /** FREEZE: waits until every server has kicked players and drained its write-behind queue. */
    FreezeResult freeze(String transitionId, int season, Duration timeout)
            throws InterruptedException;

    /** Result. missing = servers that did not respond or failed. */
    record FreezeResult(boolean ok, List<String> servers, List<String> missing) {}

    /** Lifts the login block after rollback A. */
    void thaw(String transitionId, int season);

    /** Notifies all servers that season_meta has changed. */
    void metaChanged(int season);

    /**
     * RESET: zeroes season-n balances with SEASON_RESET ledger entries (reference ID
     * season_reset:&lt;n&gt;:&lt;uuid&gt;). Must go through CurrencyService (Common LedgerWriter).
     * Calling it again must give the same result.
     *
     * @return number of ledger entries processed
     */
    long resetBalances(int season, Set<Currency> currencies) throws Exception;

    /** SNAPSHOT: stores the final rankings in ranking_snapshot. */
    int snapshotRankings(Connection c, int season, String archiveSchema) throws SQLException;

    /** SNAPSHOT: finalizes the hall of fame and grants titles. Row count per category. */
    Map<String, Integer> finalizeHallOfFame(Connection c, int season, String archiveSchema)
            throws SQLException;

    /** Registered SeasonHooks (registration order). */
    List<SeasonHook> hooks();

    /**
     * CONTENT: prepares and verifies the switch to next season's content and resource pack. List of
     * errors (empty on success).
     */
    List<String> switchContent(int nextSeason);

    /** Path relative to the plugin folder (backup marker file). */
    Path dataPath(String relative);
}
