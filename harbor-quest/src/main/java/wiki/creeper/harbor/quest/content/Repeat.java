package wiki.creeper.harbor.quest.content;

import wiki.creeper.harbor.common.model.Keyed;

/** Repeat cycle. Repeatable quests give rewards only once per cycle. */
public enum Repeat implements Keyed {
    NONE,
    DAILY,
    WEEKLY;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
