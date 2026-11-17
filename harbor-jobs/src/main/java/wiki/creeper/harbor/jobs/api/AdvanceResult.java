package wiki.creeper.harbor.jobs.api;

/** 전직 결과. Quest는 OK가 아니면 퀘스트를 "보고 대기"로 남긴다. */
public enum AdvanceResult {
    OK,
    WRONG_TIER,
    LEVEL_TOO_LOW,
    NOT_ENOUGH_GOLD,
    CANCELLED,
    BUSY,
    FAILED
}
