package wiki.creeper.harbor.quest.api;

import java.time.Instant;
import wiki.creeper.harbor.common.id.QuestId;

/**
 * One stored quest row ({@link QuestService#storedQuests}).
 *
 * @param season season the row belongs to (earlier than the current one only for main quests)
 * @param main whether it is a main quest ({@code quest/main/…}, carried over between seasons)
 * @param state stored state (ACTIVE · READY · COMPLETED · FAILED)
 * @param updatedAt last write time
 */
public record QuestRecord(
        QuestId quest, int season, boolean main, QuestState state, Instant updatedAt) {}
