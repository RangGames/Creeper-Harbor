package wiki.creeper.harbor.npc.logic;

import java.util.List;
import wiki.creeper.harbor.npc.api.AffinityLevel;
import wiki.creeper.harbor.npc.api.GiftReaction;

/** 호감도 단계·선물 계산 (서버 API 없음). */
public final class AffinityMath {

    private AffinityMath() {}

    /** 누적 호감도의 단계 (levels는 points 오름차순, 0단계 0점). */
    public static AffinityLevel levelFor(int points, List<AffinityLevel> levels) {
        AffinityLevel current = levels.getFirst();
        for (AffinityLevel l : levels) {
            if (points >= l.points()) {
                current = l;
            }
        }
        return current;
    }

    /**
     * 선물 호감도. 어종이면 좋은 반응(양수)에만 × (1 + bonus × (★ − 1)), 반올림. 싫어하는 선물은 품질과 무관하다.
     *
     * @param stars 어종 품질 (일반 아이템은 0)
     */
    public static int giftPoints(int base, int stars, double bonus) {
        if (base <= 0 || stars <= 1) {
            return base;
        }
        return (int) Math.round(base * (1 + bonus * (stars - 1)));
    }

    /** 표에 적힌 값(품질 배율 전)으로 반응을 고른다. */
    public static GiftReaction reaction(int base, int love, int like) {
        if (base >= love) {
            return GiftReaction.LOVE;
        }
        if (base >= like) {
            return GiftReaction.LIKE;
        }
        return base > 0 ? GiftReaction.NORMAL : GiftReaction.DISLIKE;
    }

    /** 호감도는 0 아래로 내려가지 않는다. */
    public static int apply(int points, int delta) {
        return (int) Math.clamp((long) points + delta, 0L, Integer.MAX_VALUE);
    }
}
