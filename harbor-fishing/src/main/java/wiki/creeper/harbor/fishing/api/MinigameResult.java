package wiki.creeper.harbor.fishing.api;

/** Minigame result. */
public enum MinigameResult {
    CAUGHT,
    LINE_BROKE,
    ESCAPED,
    /**
     * Aborted by death, leaving, disconnecting, etc. The bait is returned on a forced move
     * (maintenance).
     */
    ABORTED
}
