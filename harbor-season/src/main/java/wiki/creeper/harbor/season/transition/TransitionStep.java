package wiki.creeper.harbor.season.transition;

import java.util.Optional;

/**
 * The 10 season transition steps (docs 20 11.4). They run strictly in order, and only RESET deletes
 * source data.
 */
public enum TransitionStep {
    PRECHECK(1),
    NOTICE(2),
    FREEZE(3),
    VERIFY(4),
    BACKUP(5),
    ARCHIVE(6),
    SNAPSHOT(7),
    RESET(8),
    CONTENT(9),
    OPEN(10);

    private final int number;

    TransitionStep(int number) {
        this.number = number;
    }

    /** Step number in the docs (1-10). */
    public int number() {
        return number;
    }

    public Optional<TransitionStep> next() {
        for (TransitionStep step : values()) {
            if (step.number == number + 1) {
                return Optional.of(step);
            }
        }
        return Optional.empty();
    }

    /**
     * Once this step has started, it cannot be undone with rollback A (dropping the archive
     * schema).
     */
    public boolean pastRollbackA() {
        return number >= RESET.number;
    }

    public static Optional<TransitionStep> parse(String raw) {
        for (TransitionStep step : values()) {
            if (step.name().equals(raw)) {
                return Optional.of(step);
            }
        }
        return Optional.empty();
    }
}
