package wiki.creeper.harbor.quest.api;

import wiki.creeper.harbor.common.model.Keyed;

/**
 * 퀘스트 상태 (docs 20 6.4 상태 기계). LOCKED·AVAILABLE은 계산 값, COMPLETING은 메모리 상태, 나머지는
 * quest_progress.state에 저장한다 (ABANDONED는 행 삭제).
 */
public enum QuestState implements Keyed {
    /** 시작 조건 미충족. */
    LOCKED,
    /** 수락 가능 (NPC 머리 위 !). */
    AVAILABLE,
    /** 목표 진행 중. */
    ACTIVE,
    /** 추적 목표를 모두 채움, 보고 가능 (?). deliver는 보고할 때 확인. */
    READY,
    /** 보상 처리 중 (다른 조작 거부). */
    COMPLETING,
    /** 완료. 반복 퀘스트는 cycle을 기록. */
    COMPLETED,
    /** 이벤트 기간 종료. */
    FAILED,
    /** 포기 (행 삭제). */
    ABANDONED,
    /** 리로드로 정의가 사라진 퀘스트 (행은 남기고 메모리에서 숨김). */
    ORPHANED;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }

    /** DB에 저장하는 상태인지. */
    public boolean stored() {
        return this == ACTIVE || this == READY || this == COMPLETED || this == FAILED;
    }

    /** 진행 중 (목표·보고 대기). */
    public boolean open() {
        return this == ACTIVE || this == READY || this == COMPLETING;
    }
}
