package wiki.creeper.harbor.jobs.api;

/** Job advancement result. Unless OK, Quest leaves the quest "waiting for report". */
public enum AdvanceResult {
    OK,
    WRONG_TIER,
    LEVEL_TOO_LOW,
    NOT_ENOUGH_GOLD,
    CANCELLED,
    BUSY,
    FAILED
}
