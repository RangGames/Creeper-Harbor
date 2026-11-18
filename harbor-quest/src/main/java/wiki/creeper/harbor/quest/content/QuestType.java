package wiki.creeper.harbor.quest.content;

import wiki.creeper.harbor.common.model.Keyed;

/** 퀘스트 유형 ('11. 데이터 설계' 2.6). main·region·job은 목표를 차례대로(sequence) 진행하는 것이 기본이다. */
public enum QuestType implements Keyed {
    MAIN,
    REGION,
    JOB,
    /** 어부 길드 게시판 일일 의뢰. */
    DAILY,
    /** 어부 길드 게시판 주간 의뢰. */
    WEEKLY,
    /** 수족관 관장 수집 의뢰 (주간 풀과 별도). */
    COLLECT,
    HIDDEN,
    EVENT;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }

    /** 게시판에서 뽑는 의뢰인지. */
    public boolean board() {
        return this == DAILY || this == WEEKLY || this == COLLECT;
    }

    public ObjectiveMode defaultMode() {
        return this == MAIN || this == REGION || this == JOB
                ? ObjectiveMode.SEQUENCE
                : ObjectiveMode.ALL;
    }

    public Repeat defaultRepeat() {
        return switch (this) {
            case DAILY -> Repeat.DAILY;
            case WEEKLY, COLLECT -> Repeat.WEEKLY;
            default -> Repeat.NONE;
        };
    }
}
