package wiki.creeper.harbor.quest.api;

import wiki.creeper.harbor.common.model.Keyed;

/**
 * Quest state (docs 20 6.4 state machine). LOCKED and AVAILABLE are computed, COMPLETING is in
 * memory only, the rest are stored in quest_progress.state (ABANDONED deletes the row).
 */
public enum QuestState implements Keyed {
    /** Start requirements not met. */
    LOCKED,
    /** Can be accepted (! above the NPC's head). */
    AVAILABLE,
    /** Objectives in progress. */
    ACTIVE,
    /** All tracked objectives filled, can be turned in (?). deliver is checked on turn-in. */
    READY,
    /** Rewards being processed (other actions are refused). */
    COMPLETING,
    /** Completed. Repeatable quests record the cycle. */
    COMPLETED,
    /** Event period ended. */
    FAILED,
    /** Abandoned (row deleted). */
    ABANDONED,
    /** Quest whose definition disappeared on reload (the row is kept but hidden in memory). */
    ORPHANED;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }

    /** Whether this state is stored in the DB. */
    public boolean stored() {
        return this == ACTIVE || this == READY || this == COMPLETED || this == FAILED;
    }

    /** In progress (objectives or awaiting turn-in). */
    public boolean open() {
        return this == ACTIVE || this == READY || this == COMPLETING;
    }
}
