package wiki.creeper.harbor.wildlife.api;

/** 개체 상태 기계 ('17-3' 9.4 표). */
public enum WildState {
    /** 집 위치 반경 안 목표점을 골라 헤엄. */
    CRUISE,
    /** 쉬기 (속도 ×0.15, idle 재생). */
    IDLE,
    /** 고유 동작 재생. */
    ACTION,
    /** 위협 반대 방향으로 도주. */
    FLEE,
    /** 찌 주변을 천천히 돎. */
    APPROACH,
    /** 입질 (Fishing 그림자로 넘김). */
    BITE,
    /** 무리 대장을 따라 분리·정렬·응집. */
    SCHOOL
}
