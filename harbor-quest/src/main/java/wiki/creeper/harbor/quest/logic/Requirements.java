package wiki.creeper.harbor.quest.logic;

import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.quest.content.Requires;

/** 시작 조건 평가 (docs 20 6.4). 순수 계산: 플레이어 상태는 {@link Facts}로 받는다. */
public final class Requirements {

    private Requirements() {}

    /** 평가에 쓰는 플레이어 상태 (메인 스레드 메모리 조회). */
    public interface Facts {
        boolean completed(QuestId quest);

        int level();

        int jobTier();

        boolean playerFlag(String flag);

        boolean serverFlag(String flag);

        /** 시즌 주차 (Season SeasonClock). */
        int week();

        int affinityLevel(NpcId npc);

        int reputationTier();
    }

    /**
     * 평가 결과.
     *
     * @param lockedWeek 주차만 남았을 때 그 주차 (대화에 "N주차에 열립니다", 회색 ?). 0이면 주차 잠금 아님
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
