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

    /**
     * 다음 시간 예측값 next_coef (docs 20 7.4 5단계): 다음 시간에 창 밖으로 빠질 판매량을 빼고, 최근 1시간 판매 속도가 이어진다고 본다.
     *
     * @param leaving 다음 정각에 24시간 창에서 빠지는 시간의 판매량
     * @param lastHour 최근 1시간 판매량
     */
    public static double forecast(
            long sales, long leaving, long lastHour, double s0, EconomyBalance balance) {
        return coefficient(Math.max(0, sales - leaving + lastHour), s0, balance);
    }

    /**
     * 일괄 판매 미끄러짐 (docs 20 7.4, 제안): 같은 어종을 기준 수보다 많이 팔면 M 대신 "이 판매가 반영됐을 때의 M"과 현재 M의 평균을 쓴다. 경제 계열
     * '대량 거래'(bulk_sell_slip −0.15/랭크)는 하락폭을 줄인다.
     *
     * @param sales 지금 24시간 판매량 S
     * @param count 이번 묶음 마릿수
     * @param slip bulk_sell_slip 보정 합 (기본 0, 음수일수록 하락폭이 작음)
     */
    public static double bulkCoefficient(
            double current, long sales, int count, double s0, double slip, EconomyBalance balance) {
        if (count <= balance.bulkThreshold() || s0 <= 0) {
            return current;
        }
        double after = coefficient(sales + count, s0, balance);
        if (after >= current) {
            return current;
        }
        double average = (current + after) / 2;
        double factor = Math.clamp(1 + slip, 0.0, 1.0);
        return current - (current - average) * factor;
    }
}
