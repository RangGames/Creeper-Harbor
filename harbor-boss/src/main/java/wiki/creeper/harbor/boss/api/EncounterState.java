package wiki.creeper.harbor.boss.api;

import wiki.creeper.harbor.common.model.Keyed;

/**
 * Encounter state (docs 20 10.4 phase state machine). DB boss_encounter.state only stores RUNNING,
 * CLEARED, FAILED and ABORTED.
 */
public enum EncounterState implements Keyed {
    PREPARING,
    INTRO,
    /** Hook: anchors hook and hold the line. */
    HOOK,
    /** Counter: dodge patterns, harpoon damage while the weak point is exposed, repairs. */
    COUNTER,
    /** Final tug-of-war: QTE with input from everyone. */
    FINAL_TUG,
    CLEARED,
    FAILED,
    ABORTED;

    public boolean ended() {
        return this == CLEARED || this == FAILED || this == ABORTED;
    }

    /** Whether combat is in progress (excluding preparation and end). */
    public boolean fighting() {
        return this == INTRO || this == HOOK || this == COUNTER || this == FINAL_TUG;
    }

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
