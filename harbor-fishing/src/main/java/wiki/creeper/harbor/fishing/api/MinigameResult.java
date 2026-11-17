package wiki.creeper.harbor.fishing.api;

/** 미니게임 결과. */
public enum MinigameResult {
    CAUGHT,
    LINE_BROKE,
    ESCAPED,
    /** 사망·이탈·접속 종료 등으로 중단. 강제 이동(점검)이면 미끼를 돌려준다. */
    ABORTED
}
