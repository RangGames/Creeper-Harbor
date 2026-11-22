package wiki.creeper.harbor.quest.api;

import java.util.Map;
import wiki.creeper.harbor.common.id.QuestId;

/**
 * Quest progress shown to other modules (QuestService.active).
 *
 * @param type quest type key (main · region · job · daily · weekly · collect · hidden · event)
 * @param progress objective key → progress value
 */
public record QuestView(
        QuestId quest,
        String name,
        String type,
        QuestState state,
        Map<String, Integer> progress,
        String cycle) {

    public QuestView {
        progress = Map.copyOf(progress);
    }
}
