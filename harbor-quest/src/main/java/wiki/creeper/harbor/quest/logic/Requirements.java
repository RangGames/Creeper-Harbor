package wiki.creeper.harbor.quest.logic;

import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.quest.content.Requires;

/**
 * Start requirement evaluation (docs 20 6.4). Pure computation: player state comes in through
 * {@link Facts}.
 */
public final class Requirements {

    private Requirements() {}

    /** Player state used in the evaluation (main thread in-memory lookups). */
    public interface Facts {
        boolean completed(QuestId quest);

        int level();

        int jobTier();

        boolean playerFlag(String flag);

        boolean serverFlag(String flag);

        /** Season week (Season SeasonClock). */
        int week();

        int affinityLevel(NpcId npc);

        int reputationTier();
    }

    /**
     * Evaluation result.
     *
     * @param lockedWeek the week when only the week remains (dialog "opens in week N", gray ?). 0
     *     means no week lock
     */
    public record Outcome(boolean met, int lockedWeek) {
        public static final Outcome MET = new Outcome(true, 0);
        public static final Outcome LOCKED = new Outcome(false, 0);
    }

    public static Outcome evaluate(Requires r, Facts f) {
        for (QuestId q : r.quests()) {
            if (!f.completed(q)) {
                return Outcome.LOCKED;
            }
        }
        if (f.level() < r.level() || f.jobTier() < r.jobTier()) {
            return Outcome.LOCKED;
        }
        for (Requires.FlagCond flag : r.flags()) {
            boolean on = flag.server() ? f.serverFlag(flag.flag()) : f.playerFlag(flag.flag());
            if (on == flag.negate()) {
                return Outcome.LOCKED;
            }
        }
        Requires.AffinityReq affinity = r.affinity();
        if (affinity != null && f.affinityLevel(affinity.npc()) < affinity.level()) {
            return Outcome.LOCKED;
        }
        if (r.reputation() > 0 && f.reputationTier() < r.reputation()) {
            return Outcome.LOCKED;
        }
        if (r.week() > 0 && f.week() < r.week()) {
            return new Outcome(false, r.week());
        }
        return Outcome.MET;
    }
}
