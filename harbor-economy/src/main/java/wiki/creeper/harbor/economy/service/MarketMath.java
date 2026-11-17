package wiki.creeper.harbor.economy.service;

import java.util.Map;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.economy.content.EconomyBalance;

/** 시세 공식 (서버 API 없음, 단위 테스트 대상). */
public final class MarketMath {

    private MarketMath() {}

    /** M = clamp(1 − k × (S / S₀ − 1), min, max). 판매가 없으면 max ('7. 밸런스 기획' 6.2). */
    public static double coefficient(long sales, double s0, EconomyBalance balance) {
        if (sales <= 0) {
            return balance.marketMax();
        }
        double m = 1 - balance.marketK() * (sales / Math.max(1e-9, s0) - 1);
        return Math.clamp(m, balance.marketMin(), balance.marketMax());
    }

    /**
     * 기대 출현 비율: 그 지역의 등급 기본 가중치 비중 × 등급 안 어종 가중치 비중. 전설·신화는 0 (일반 판정에 없음).
     *
     * @param gradeWeights 등급 기본 가중치 (60 · 25 · 10 · 4)
     */
    public static double expectedShare(
            FishDef fish, Iterable<FishDef> regionFish, Map<Grade, Double> gradeWeights) {
        double gradeTotal = 0;
        double sameGradeWeight = 0;
        java.util.EnumSet<Grade> present = java.util.EnumSet.noneOf(Grade.class);
        for (FishDef f : regionFish) {
            present.add(f.grade());
            if (f.grade() == fish.grade()) {
                sameGradeWeight += f.weight();
            }
        }
        for (Grade g : present) {
            gradeTotal += gradeWeights.getOrDefault(g, 0.0);
        }
        double gradeShare =
                gradeTotal <= 0 ? 0 : gradeWeights.getOrDefault(fish.grade(), 0.0) / gradeTotal;
        return sameGradeWeight <= 0 ? 0 : gradeShare * fish.weight() / sameGradeWeight;
    }
}
