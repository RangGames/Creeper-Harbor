package wiki.creeper.harbor.npc.api;

/**
 * NPC 머리 위 퀘스트 표시 (docs 20 5.4). 우선순위는 TURN_IN(노란 ?) > AVAILABLE_MAIN(금색 !) > AVAILABLE(!) >
 * IN_PROGRESS(회색 ?) > NONE.
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

    /** 둘 중 우선순위가 높은 것. */
    public static QuestMarker max(QuestMarker a, QuestMarker b) {
        return a.priority >= b.priority ? a : b;
    }
}
