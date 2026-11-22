package wiki.creeper.harbor.quest.api;

import java.util.Map;
import wiki.creeper.harbor.common.id.QuestId;

/**
 * Quest progress shown to other modules (QuestService.active).
 *
 * @param type quest type key (main · region · job · daily · weekly · collect · hidden · event)
 * @param progress objective key → progress value
 * @param goals objective key → goal count (every objective of the quest, deliver included)
 */
public record QuestView(
        QuestId quest,
        String name,
        String type,
        QuestState state,
        Map<String, Integer> progress,
        String cycle,
        Map<String, Integer> goals) {

    public QuestView {
        progress = Map.copyOf(progress);
        goals = Map.copyOf(goals);
    }

    /** Without goals (kept for callers built before goals existed). */
    public QuestView(
            QuestId quest,
            String name,
            String type,
            QuestState state,
            Map<String, Integer> progress,
            String cycle) {
        this(quest, name, type, state, progress, cycle, Map.of());
    }
}
