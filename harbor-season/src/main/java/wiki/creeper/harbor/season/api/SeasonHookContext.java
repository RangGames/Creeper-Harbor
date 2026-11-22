package wiki.creeper.harbor.season.api;

import wiki.creeper.harbor.common.db.Database;

/**
 * Information passed to season transition hooks. Hooks are called on virtual threads and may block.
 * No regular players are on the server at this point (after FREEZE).
 */
public interface SeasonHookContext {

    /** Transition ID (UUID). Using it to build reference IDs gives the same value on re-run. */
    String transitionId();

    /** Ending season n. */
    int fromSeason();

    /** Opening season n+1. */
    int toSeason();

    /** Archive schema name {@code archive_s<n>}. Readable after the ARCHIVE step. */
    default String archiveSchema() {
        return "archive_s" + fromSeason();
    }

    /** If true, dry-run: write nothing and only report tasks via {@link #report}. */
    boolean dryRun();

    Database database();

    /** One line for the transition log (season_transition_log) and the admin screen. */
    void report(String message);
}
