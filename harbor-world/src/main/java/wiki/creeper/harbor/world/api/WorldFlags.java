package wiki.creeper.harbor.world.api;

import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * Flag names World reads and writes (Core FlagService). Quest sets {@link #questDone} when a quest
 * is completed and story/ch&lt;N&gt;_done when the last quest of a chapter is finished (regions.yml
 * unlock.quest · world/regions.yml unlock.flags). World sets {@link #visited} on the first visit.
 */
public final class WorldFlags {

    private WorldFlags() {}

    /**
     * First-visit flag of a region {@code visited/<region name>} (season scope, set by World on the
     * first visit; read by Economy recipe and shop unlocks).
     */
    public static String visited(RegionId region) {
        return "visited/" + region.id().name();
    }

    /**
     * Quest completion flag = the quest ID path as is (e.g. quest/main/ch1_dark_lighthouse). Set by
     * Quest on completion (permanent for main quests, season scope for the rest).
     */
    public static String questDone(QuestId quest) {
        return quest.id().path();
    }
}
