package wiki.creeper.harbor.common.session;

/** 도착 서버가 세션 잠금을 가져온 결과. */
public enum ClaimResult {
    /** 잠금이 없어서 새로 가져옴 (첫 접속). */
    CLAIMED,
    /** handoff 상태를 확인하고 소유권을 가져옴 (서버 이동). */
    HANDOFF_CLAIMED,
    /** 다른 서버가 가지고 있음 → 접속 거부, 로비에서 대기. */
    BUSY,
    /** Redis 오류 → 복제 방지를 위해 접속 거부. */
    UNAVAILABLE;

    public boolean isClaimed() {
        return this == CLAIMED || this == HANDOFF_CLAIMED;
    }
}
