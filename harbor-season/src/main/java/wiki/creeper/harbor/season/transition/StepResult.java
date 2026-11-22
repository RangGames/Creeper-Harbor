package wiki.creeper.harbor.season.transition;

import org.jspecify.annotations.Nullable;

/** Step result (log rows, checksum, detail). */
record StepResult(long rows, long checksum, @Nullable String detail) {
    static StepResult of(long rows, @Nullable String detail) {
        return new StepResult(rows, 0, detail);
    }
}
