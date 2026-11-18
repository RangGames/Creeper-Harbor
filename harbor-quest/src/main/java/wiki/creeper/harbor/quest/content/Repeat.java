package wiki.creeper.harbor.quest.content;

import wiki.creeper.harbor.common.model.Keyed;

/** 반복 주기. 반복 퀘스트는 cycle마다 한 번만 보상을 준다. */
public enum Repeat implements Keyed {
    NONE,
    DAILY,
    WEEKLY;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
