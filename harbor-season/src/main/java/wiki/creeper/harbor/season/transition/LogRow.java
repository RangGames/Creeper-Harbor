package wiki.creeper.harbor.season.transition;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * season_transition_log 한 행.
 *
 * @param step 단계 이름 (PRECHECK …) 또는 세부 단계 (ARCHIVE:player_season, RESET:currency,
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
