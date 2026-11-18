package wiki.creeper.harbor.boss.api;

import wiki.creeper.harbor.common.model.Keyed;

/** 조우 상태 (docs 20 10.4 페이즈 상태 기계). DB boss_encounter.state에는 RUNNING·CLEARED·FAILED·ABORTED만 쓴다. */
public enum EncounterState implements Keyed {
    PREPARING,
    INTRO,
    /** 걸기: 앵커가 줄을 걸고 유지한다. */
    HOOK,
    /** 반격: 패턴 회피, 약점 노출 때 작살 피해, 수리. */
    COUNTER,
    /** 최후의 줄다리기: 전원 입력 QTE. */
    FINAL_TUG,
    CLEARED,
    FAILED,
    ABORTED;

    public boolean ended() {
        return this == CLEARED || this == FAILED || this == ABORTED;
    }

    /** 전투가 진행 중인지 (준비·종료 제외). */
    public boolean fighting() {
        return this == INTRO || this == HOOK || this == COUNTER || this == FINAL_TUG;
    }

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
