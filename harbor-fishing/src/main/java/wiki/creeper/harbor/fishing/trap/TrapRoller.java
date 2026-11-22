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
 * Trap species roll ("7. Balance design" chapter 8). Picks only species of the placement region at
 * or below the trap max_grade, using the base grade weights without modifiers. Legendaries,
 * mythics, species needing bait, release_only and species whose unlock flag is not met never
 * appear. Size and quality apply the owner's job cap, with luck 0. No server API.
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
