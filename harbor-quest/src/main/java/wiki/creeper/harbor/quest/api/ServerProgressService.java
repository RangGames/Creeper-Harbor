package wiki.creeper.harbor.quest.api;

import java.util.concurrent.CompletableFuture;

/**
 * Server progress (docs 20 6.4): chapter clear counts and climax unlock conditions. Reads the DB,
 * so futures complete on the main thread.
 */
public interface ServerProgressService {

    /**
     * Climax status.
     *
     * @param requiredClears max(minimum count, active players × ratio)
     * @param flagged whether the server flag climax_ready is already set
     */
    record ClimaxStatus(
            double donationRate,
            double requiredRate,
            long clears,
            int requiredClears,
            long activePlayers,
            boolean ready,
            boolean flagged) {}

    /** Clear count for that chapter this season (including chronicle recaps). */
    CompletableFuture<Long> chapterClearCount(int chapter);

    CompletableFuture<ClimaxStatus> climaxStatus();
}
