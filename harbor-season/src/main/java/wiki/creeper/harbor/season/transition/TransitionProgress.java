package wiki.creeper.harbor.season.transition;

import java.util.List;
import java.util.Optional;

/** Decides the next step to run from the log (pure computation, resume decision). */
public final class TransitionProgress {

    private TransitionProgress() {}

    /**
     * The step after the last completed major step. PRECHECK if nothing has completed, empty
     * Optional if everything through OPEN has completed.
     *
     * <p>If there is a gap between major steps (the log is damaged), starts from the first missing
     * step.
     */
    public static Optional<TransitionStep> next(List<LogRow> rows) {
        for (TransitionStep step : TransitionStep.values()) {
            if (!isDone(rows, step.name())) {
                return Optional.of(step);
            }
        }
        return Optional.empty();
    }

    public static boolean isDone(List<LogRow> rows, String step) {
        return rows.stream().anyMatch(r -> r.step().equals(step) && r.done());
    }

    /** Whether this step has ever started (regardless of state). */
    public static boolean started(List<LogRow> rows, String step) {
        return rows.stream()
                .anyMatch(r -> r.step().equals(step) || r.step().startsWith(step + ":"));
    }

    /** Whether a step after RESET has started (rollback A not possible). */
    public static boolean pastRollbackA(List<LogRow> rows) {
        for (TransitionStep step : TransitionStep.values()) {
            if (step.pastRollbackA() && started(rows, step.name())) {
                return true;
            }
        }
        return false;
    }
}
