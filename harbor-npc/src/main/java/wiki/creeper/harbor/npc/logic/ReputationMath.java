package wiki.creeper.harbor.npc.logic;

import java.util.List;
import java.util.Optional;
import wiki.creeper.harbor.npc.api.ReputationTier;

/** 평판 등급·상한 계산 (서버 API 없음). */
public final class ReputationMath {

    private ReputationMath() {}

    /** 누적 평판의 등급. 등급표는 tier 오름차순이고 첫 등급은 0점이다. 비어 있으면 1. */
    public static int tierFor(int value, List<ReputationTier> tiers) {
        int tier = tiers.isEmpty() ? 1 : tiers.getFirst().tier();
        for (ReputationTier t : tiers) {
            if (value >= t.points()) {
                tier = t.tier();
            }
        }
        return tier;
    }

    public static Optional<ReputationTier> next(int value, List<ReputationTier> tiers) {
        for (ReputationTier t : tiers) {
            if (t.points() > value) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    /**
     * 하루 상한을 적용한 실제 증가량. 음수(관리자 차감)는 상한과 무관하다.
     *
     * @param usedToday 오늘 이미 받은 양
     * @param cap 0 이하면 상한 없음
     */
    public static int capped(int delta, int usedToday, int cap) {
        if (delta <= 0 || cap <= 0) {
            return delta;
        }
        return Math.max(0, Math.min(delta, cap - usedToday));
    }

    /** 보정자 배율 적용 (CONTRIBUTION 출처). 반올림, 배율은 0 이상. */
    public static int scaled(int delta, double multiplier) {
        if (delta <= 0) {
            return delta;
        }
        return (int) Math.round(delta * Math.max(0, multiplier));
    }

    /** 평판은 0 아래로 내려가지 않는다. */
    public static int apply(int value, int delta) {
        long next = (long) value + delta;
        return (int) Math.clamp(next, 0L, Integer.MAX_VALUE);
    }
}
