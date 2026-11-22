package wiki.creeper.harbor.season.api;

/**
 * Season state (season_meta.state, docs 20 11.3). State transitions happen only through UPDATEs
 * conditioned on the expected state.
 *
 * <pre>PLANNED → ACTIVE → TRANSITIONING → ARCHIVED</pre>
 */
public enum SeasonPhase {
    /** Only defined in seasons.yml; not opened yet. */
    PLANNED,
    /** In progress. */
    ACTIVE,
    /**
     * Season transition in progress (FREEZE to OPEN). Regular player logins are blocked in this
     * state.
     */
    TRANSITIONING,
    /** Ended and archived. */
    ARCHIVED;

    /** Reads the DB value. PLANNED if unknown. */
    public static SeasonPhase parse(String raw) {
        for (SeasonPhase phase : values()) {
            if (phase.name().equalsIgnoreCase(raw)) {
                return phase;
            }
        }
        return PLANNED;
    }
}
