package wiki.creeper.harbor.common.session;

/** Result of the destination server claiming the session lock. */
public enum ClaimResult {
    /** No lock existed, so a new one was taken (first join). */
    CLAIMED,
    /** Confirmed the handoff state and took ownership (server move). */
    HANDOFF_CLAIMED,
    /** Another server holds it → join refused, wait in the lobby. */
    BUSY,
    /** Redis error → join refused to prevent duplication. */
    UNAVAILABLE;

    public boolean isClaimed() {
        return this == CLAIMED || this == HANDOFF_CLAIMED;
    }
}
