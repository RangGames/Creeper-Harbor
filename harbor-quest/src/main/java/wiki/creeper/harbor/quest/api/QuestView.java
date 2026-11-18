package wiki.creeper.harbor.quest.api;

import java.util.Map;
import wiki.creeper.harbor.common.id.QuestId;

/**
 * 다른 모듈에 보여주는 퀘스트 진행 (QuestService.active).
 *
 * @param type 퀘스트 유형 키 (main · region · job · daily · weekly · collect · hidden · event)
 * @param progress 목표 key → 진행 값
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
