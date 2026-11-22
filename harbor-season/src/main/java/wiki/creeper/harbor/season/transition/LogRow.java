package wiki.creeper.harbor.season.transition;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One row of season_transition_log.
 *
 * @param step step name (PRECHECK ...) or sub-step (ARCHIVE:player_season, RESET:currency,
 *     HOOK:shop.pass:archive)
 * @param state STARTED · DONE · FAILED · SKIPPED
 */
public record LogRow(
        String transitionId,
        int fromSeason,
        int toSeason,
        String step,
        String state,
        long rows,
        long checksum,
        @Nullable String detail,
        Instant startedAt,
        @Nullable Instant finishedAt,
        String operator) {

    public static final String STARTED = "STARTED";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";

    public boolean done() {
        return state.equals(DONE) || state.equals(SKIPPED);
    }
}
