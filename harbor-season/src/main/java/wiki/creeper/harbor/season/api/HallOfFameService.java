package wiki.creeper.harbor.season.api;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import wiki.creeper.harbor.season.content.HallOfFameCategory;

/**
 * Hall of fame (docs 20 11.4). Season does not subscribe to other modules' events, so Collection
 * (server biggest catch) and Boss (fastest kill) call {@link #submit} directly.
 *
 * <ul>
 *   <li>Ongoing season: a submission updates the provisional record (final=0); if the top changes,
 *       HallOfFameUpdatedEvent fires and a harbor:announce notice is sent
 *   <li>Season close (transition SNAPSHOT step): recomputed from archive data, finalized with
 *       final=1 and the linked title granted (reference ID {@code
 *       hof:<season>:<category>:<subject>})
 *   <li>Result futures complete on the main thread
 * </ul>
 *
 * <pre>{@code
 * // Collection: when the all-league server biggest catch changes
 * hof.submit(HallOfFameSubmission.biggestFish(fish.id().id(), uuid, name, size, caughtAt));
 * }</pre>
 */
public interface HallOfFameService {

    /** Submission result. */
    enum Outcome {
        /** New top (or newly entered the first-come list). */
        NEW_LEADER,
        /** Ranked, but not first. */
        RANKED,
        /** Worse than the existing record, or the first-come list is full. */
        NOT_RANKED,
        /** Unknown category, or a season transition is in progress. */
        REJECTED,
        FAILED
    }

    CompletableFuture<Outcome> submit(HallOfFameSubmission submission);

    /** Category definitions (hall_of_fame.yml). */
    List<HallOfFameCategory> categories();

    Optional<HallOfFameCategory> category(String category);

    /**
     * Records for a season and category (by rank per subject). Provisional records for an ongoing
     * season.
     */
    CompletableFuture<List<HallOfFameEntry>> entries(int season, String category);

    /** Season numbers that have records (most recent first). */
    CompletableFuture<List<Integer>> seasons();
}
