package wiki.creeper.harbor.npc.api;

import wiki.creeper.harbor.common.model.Keyed;

/** 호감도 출처. */
public enum AffinitySource implements Keyed {
    /** 하루 첫 대화. */
    GREETING,
    GIFT,
    /** 퀘스트 보상 (상한 없음). */
    QUEST,
    /** 대화 액션 affinity:add (once 플래그로 한 번만). */
    DIALOG,
    ADMIN,
    OTHER;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
