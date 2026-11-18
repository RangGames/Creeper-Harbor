package wiki.creeper.harbor.quest.content;

import wiki.creeper.harbor.common.model.Keyed;

/** 목표 진행 방식 (추가 제안 objectives_mode). */
public enum ObjectiveMode implements Keyed {
    /** 위에서부터 하나씩. 지금 목표만 진행된다. */
    SEQUENCE,
    /** 모든 목표가 함께 진행된다. */
    ALL;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
