package wiki.creeper.harbor.fishing.trap;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.fishing.content.FishingBalance;
import wiki.creeper.harbor.fishing.roll.QualityRoller;

/**
 * 통발 어종 판정 ('7. 밸런스 기획' 8장). 설치 지역 어종 중 통발 max_grade 이하 등급만, 보정 없는 기본 등급 가중치로 고른다. 전설·신화, 미끼가 필요한
 * 어종, release_only, 해금 플래그를 못 맞춘 어종은 나오지 않는다. 크기·품질은 주인의 직업 상한을 적용하고 행운은 0. 서버 API 없음.
 */
public final class TrapRoller implements TrapMath.Roller {

    private final Map<Grade, List<FishDef>> pool = new EnumMap<>(Grade.class);
    private final Map<Grade, Double> gradeWeights = new EnumMap<>(Grade.class);
    private final FishingBalance balance;
    private final Quality cap;
    private final RandomGenerator random;

    public TrapRoller(
            Map<Grade, List<FishDef>> regionPool,
            Grade maxGrade,
            Predicate<String> serverFlags,
            FishingBalance balance,
            Quality cap,
            RandomGenerator random) {
        this.balance = balance;
        this.cap = cap;
        this.random = random;
        for (Map.Entry<Grade, List<FishDef>> e : regionPool.entrySet()) {
            Grade grade = e.getKey();
            boolean aboveMax = grade != maxGrade && grade.isAtLeast(maxGrade);
            if (aboveMax || grade.isAtLeast(Grade.LEGENDARY)) {
                continue;
            }
            List<FishDef> list =
                    e.getValue().stream()
                            .filter(
                                    f ->
                                            f.bait().isEmpty()
                                                    && !f.releaseOnly()
                                                    && (f.unlockFlag() == null
                                                            || serverFlags.test(f.unlockFlag())))
                            .toList();
            double weight = balance.gradeWeight(grade);
            if (!list.isEmpty() && weight > 0) {
                pool.put(grade, list);
                gradeWeights.put(grade, weight);
            }
        }
    }

    public boolean isEmpty() {
        return pool.isEmpty();
    }

    @Override
    public TrapMath.Rolled next() {
        Grade grade = pick(gradeWeights);
        Map<FishDef, Double> species = new HashMap<>();
        for (FishDef f : pool.getOrDefault(grade, List.of())) {
            species.put(f, (double) f.weight());
        }
        FishDef fish = pick(species);
        QualityRoller.Rolled rolled =
                QualityRoller.roll(fish, 0, cap, balance.qualityBounds(), random);
        double value = fish.basePrice() * balance.qualityMult(rolled.quality().stars());
        return new TrapMath.Rolled(fish, rolled.sizeCm(), rolled.quality(), value);
    }

    private <T> T pick(Map<T, Double> weights) {
        double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        double r = random.nextDouble() * total;
        @Nullable T last = null;
        for (Map.Entry<T, Double> e : weights.entrySet()) {
            last = e.getKey();
            r -= e.getValue();
            if (r < 0) {
                return e.getKey();
            }
        }
        if (last == null) {
            throw new IllegalStateException("통발 어종 후보가 없습니다");
        }
        return last;
    }
}
