package wiki.creeper.harbor.quest.content;

import wiki.creeper.harbor.common.model.Keyed;

/**
 * Quest type ('11. Data Design' 2.6). main, region and job progress objectives in order (sequence)
 * by default.
 */
public enum QuestType implements Keyed {
    MAIN,
    REGION,
    JOB,
    /** Fishermen's guild board daily request. */
    DAILY,
    /** Fishermen's guild board weekly request. */
    WEEKLY,
    /** Aquarium curator collection request (separate from the weekly pool). */
    COLLECT,
    HIDDEN,
    EVENT;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }

    /** Whether this request is drawn from the board. */
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
