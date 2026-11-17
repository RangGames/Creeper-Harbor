package wiki.creeper.harbor.common.ledger;

/** 원장 쓰기 실패 사유. */
public enum LedgerError {
    /** 잔액이 모자람 (새 잔액이 음수). */
    INSUFFICIENT_FUNDS,
    /** 같은 참조 ID로 이미 처리됨 (중복 지급 방지). */
    DUPLICATE,
    /** 이 재화를 다루는 잔액 저장소가 없음. */
    UNSUPPORTED_CURRENCY,
    /** DB 오류. */
    DATABASE,
}
