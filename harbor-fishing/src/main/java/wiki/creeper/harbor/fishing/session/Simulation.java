package wiki.creeper.harbor.fishing.session;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.fishing.api.FishingService.SimulationResult;
import wiki.creeper.harbor.fishing.roll.RollContext;
import wiki.creeper.harbor.fishing.roll.RollEngine;
import wiki.creeper.harbor.fishing.roll.RollModifiers;

/**
 * 결과 검산 (docs 20 3.4, /harbor fishing simulate). 보정 없는 기본 조건(낮·맑음·미끼 없음·3차·모든 서버 플래그)으로 판정해 등급 비율을
 * 보고, 전설은 천장 확률로 첫 포획까지 평균 캐스팅 수를 계산한다 (목표 약 200회).
 */
final class Simulation {

    private static final int LEGEND_TRIALS = 2000;

    private Simulation() {}

    static SimulationResult run(RollEngine engine, RegionId region, int count) {
        SplittableRandom random = new SplittableRandom();
        RollContext ctx =
                new RollContext(
                        region,
                        "day",
                        "clear",
                        "",
                        null,
                        3,
                        flag -> true,
                        fish -> false,
                        List.of(),
                        false,
                        List.of());
        Map<Grade, Integer> grades = new EnumMap<>(Grade.class);
        Map<FishId, Integer> species = new HashMap<>();
        Map<FishId, Integer> pity = new HashMap<>();
        for (int i = 0; i < count; i++) {
            Optional<RollEngine.Outcome> outcome =
                    engine.roll(ctx, RollModifiers.NONE, pity, random);
            if (outcome.isEmpty()) {
                continue;
            }
            FishDef fish = outcome.get().fish();
            grades.merge(fish.grade(), 1, Integer::sum);
            species.merge(fish.id(), 1, Integer::sum);
        }
        List<FishDef> legends = engine.candidates(ctx).getOrDefault(Grade.LEGENDARY, List.of());
        double legendAverage = legends.isEmpty() ? 0 : averageCastsToLegend(engine, random);
        return new SimulationResult(count, grades, species, legendAverage);
    }

    /** 어종 하나 기준: 조건을 만족한 캐스팅마다 천장 확률로 판정해 첫 성공까지 평균. */
    private static double averageCastsToLegend(RollEngine engine, SplittableRandom random) {
        long total = 0;
        for (int trial = 0; trial < LEGEND_TRIALS; trial++) {
            int casts = 0;
            while (true) {
                double p = engine.legendChance(casts, RollModifiers.NONE);
                casts++;
                if (random.nextDouble() < p || casts > 100_000) {
                    break;
                }
            }
            total += casts;
        }
        return (double) total / LEGEND_TRIALS;
    }
}
