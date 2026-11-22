package wiki.creeper.harbor.season.api;

/**
 * Weekly limit result.
 *
 * @param consumed whether this call used one (always false for a remaining query)
 * @param used usage count this week (value after the call)
 * @param limit limit
 * @param week season week recorded
 */
public record LimitResult(boolean consumed, int used, int limit, int week) {

    /** Remaining count (0 or more). */
    public int remaining() {
        return Math.max(0, limit - used);
    }

    /** Whether this week's limit has been reached. */
    public boolean exhausted() {
        return used >= limit;
    }
}
