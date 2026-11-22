package wiki.creeper.harbor.npc.api;

/**
 * Quest marker above an NPC's head (docs 20 5.4). Priority is TURN_IN (yellow ?) > AVAILABLE_MAIN
 * (gold !) > AVAILABLE (!) > IN_PROGRESS (gray ?) > NONE.
 */
public enum QuestMarker {
    NONE(0),
    IN_PROGRESS(1),
    AVAILABLE(2),
    AVAILABLE_MAIN(3),
    TURN_IN(4);

    private final int priority;

    QuestMarker(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }

    /** The higher-priority of the two. */
    public static QuestMarker max(QuestMarker a, QuestMarker b) {
        return a.priority >= b.priority ? a : b;
    }
}
