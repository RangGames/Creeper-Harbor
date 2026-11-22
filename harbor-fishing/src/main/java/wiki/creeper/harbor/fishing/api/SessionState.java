package wiki.creeper.harbor.fishing.api;

/** Fishing session state (docs 20 3.4). */
public enum SessionState {
    IDLE,
    CAST,
    WAITING,
    BITE,
    HOOKED,
    MINIGAME,
    RESULT
}
