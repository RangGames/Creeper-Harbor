package wiki.creeper.harbor.npc.api;

/** 대화 세션이 끝난 이유. */
public enum DialogEndReason {
    /** 닫기 버튼·close 액션·goto 없는 선택지. */
    CLOSED,
    /** 액션이 다른 화면을 열었다 (Handoff). */
    HANDOFF,
    /** 다른 대화가 열려 대체됨. */
    REPLACED,
    /** 5분 초과 또는 NPC에서 멀어짐. */
    EXPIRED,
    QUIT
}
