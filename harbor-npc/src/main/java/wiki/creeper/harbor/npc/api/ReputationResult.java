package wiki.creeper.harbor.npc.api;

/**
 * Result of a reputation change.
 *
 * @param applied amount actually added (after modifiers and daily cap)
 * @param value reputation after the change
 * @param tier tier after the change
 */
public record ReputationResult(Status status, int applied, int value, int tier) {

    public enum Status {
        APPLIED,
        /** Hit the daily cap (applied is the amount added up to the cap, possibly 0). */
        CAPPED,
        /** The same refId already came in this season. */
        DUPLICATE,
        /** Not online on this server. */
        OFFLINE,
        /** delta is 0, or an error during processing. */
        IGNORED
    }

    public boolean ok() {
        return status == Status.APPLIED || (status == Status.CAPPED && applied != 0);
    }
}
