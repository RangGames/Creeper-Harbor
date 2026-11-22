package wiki.creeper.harbor.quest.data;

import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.logic.Progress;

/**
 * In-memory value of one quest_progress row (immutable).
 *
 * @param season the row's season (main quests are carried over, so it may be a past season)
 */
public record QuestEntry(QuestId quest, int season, QuestState state, Progress progress) {

    public QuestEntry withState(QuestState next) {
        return new QuestEntry(quest, season, next, progress);
    }

    public QuestEntry withProgress(Progress next) {
        return new QuestEntry(quest, season, state, next);
    }
}
