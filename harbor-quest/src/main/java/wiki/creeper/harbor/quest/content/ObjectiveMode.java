package wiki.creeper.harbor.quest.content;

import wiki.creeper.harbor.common.model.Keyed;

/** How objectives progress (proposed objectives_mode). */
public enum ObjectiveMode implements Keyed {
    /** One at a time from the top. Only the current objective progresses. */
    SEQUENCE,
    /** All objectives progress together. */
    ALL;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
