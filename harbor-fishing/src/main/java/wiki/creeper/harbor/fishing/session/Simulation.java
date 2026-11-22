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
 * Result verification (docs 20 3.4, /harbor fishing simulate). Rolls under the base conditions
 * without modifiers (day, clear, no bait, 3rd tier, all server flags) and reports grade ratios; for
 * legendaries it computes the average number of casts until the first catch from the pity chance
 * (target about 200).
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

    /**
     * Per species: rolls the pity chance on every cast that meets the conditions and averages until
     * the first success.
     */
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
