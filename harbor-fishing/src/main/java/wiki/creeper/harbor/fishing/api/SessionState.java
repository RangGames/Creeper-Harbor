package wiki.creeper.harbor.fishing.api;

/** 낚시 세션 상태 (docs 20 3.4). */
public enum SessionState {
    IDLE,
    CAST,
    WAITING,
    BITE,
    HOOKED,
    MINIGAME,
    RESULT
}
