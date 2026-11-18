package wiki.creeper.harbor.quest.data;

import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.logic.Progress;

/**
 * quest_progress 한 행의 메모리 값 (변경 불가).
 *
 * @param season 행의 시즌 (메인 퀘스트는 이월되어 지난 시즌 값일 수 있다)
 */
public record QuestEntry(QuestId quest, int season, QuestState state, Progress progress) {

    public QuestEntry withState(QuestState next) {
        return new QuestEntry(quest, season, next, progress);
    }

    public QuestEntry withProgress(Progress next) {
        return new QuestEntry(quest, season, state, next);
    }
}
