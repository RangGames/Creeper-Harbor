package wiki.creeper.harbor.fishing.session;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.fishing.api.FishingService.SimulationResult;
import wiki.creeper.harbor.fishing.roll.RollContext;
import wiki.creeper.harbor.fishing.roll.RollEngine;
import wiki.creeper.harbor.fishing.roll.RollModifiers;

/**
 * Result verification (docs 20 3.4, /harbor fishing simulate). Rolls without modifiers under the
 * given conditions (default: day, clear, no bait, 3rd tier, all server flags) and reports grade
 * ratios; for legendaries it computes the average number of casts until the first catch from the
 * pity chance (target about 200).
 */
final class Simulation {

    private static final int LEGEND_TRIALS = 2000;

    /** Default number of casts (docs 20 3.4: 100,000). */
    static final int DEFAULT_COUNT = 100_000;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Set<String> TIMES = Set.of("dawn", "day", "dusk", "night");
    private static final Set<String> WEATHERS = Set.of("clear", "rain", "thunder");
    private static final Set<String> MOONS = Set.of("full", "new", "");

    /**
     * Simulation conditions ({@code /harbor fishing simulate <region> [count] [conditions]}).
     * Written as space-separated {@code key=value}: time (dawn·day·dusk·night), weather
     * (clear·rain·thunder), moon (full·new·none), bait (item ID), tier (1~3).
     */
    record Conditions(String time, String weather, String moon, @Nullable ItemId bait, int tier) {

        static final Conditions DEFAULT = new Conditions("day", "clear", "", null, 3);

        /**
         * Parses the condition text.
         *
         * @throws IllegalArgumentException with the offending token as message
         */
        static Conditions parse(String text) {
            Conditions c = DEFAULT;
            for (String token : WHITESPACE.split(text.trim(), -1)) {
                if (token.isEmpty()) {
                    continue;
                }
                int eq = token.indexOf('=');
                if (eq <= 0) {
                    throw new IllegalArgumentException(token);
                }
                String key = token.substring(0, eq).toLowerCase(Locale.ROOT);
                String value = token.substring(eq + 1).toLowerCase(Locale.ROOT);
                c =
                        switch (key) {
                            case "time" -> {
                                require(TIMES.contains(value), token);
                                yield new Conditions(value, c.weather, c.moon, c.bait, c.tier);
                            }
                            case "weather" -> {
                                require(WEATHERS.contains(value), token);
                                yield new Conditions(c.time, value, c.moon, c.bait, c.tier);
                            }
                            case "moon" -> {
                                String moon = value.equals("none") ? "" : value;
                                require(MOONS.contains(moon), token);
                                yield new Conditions(c.time, c.weather, moon, c.bait, c.tier);
                            }
                            case "bait" -> {
                                ItemId bait;
                                try {
                                    bait = ItemId.parse(value);
                                } catch (IllegalArgumentException e) {
                                    throw new IllegalArgumentException(token, e);
                                }
                                yield new Conditions(c.time, c.weather, c.moon, bait, c.tier);
                            }
                            case "tier" -> {
                                int tier;
                                try {
                                    tier = Integer.parseInt(value);
                                } catch (NumberFormatException e) {
                                    throw new IllegalArgumentException(token, e);
                                }
                                require(tier >= 1 && tier <= 3, token);
                                yield new Conditions(c.time, c.weather, c.moon, c.bait, tier);
                            }
                            default -> throw new IllegalArgumentException(token);
                        };
            }
            return c;
        }

        private static void require(boolean ok, String token) {
            if (!ok) {
                throw new IllegalArgumentException(token);
            }
        }

        /** Short summary for the result message. */
        String describe() {
            ItemId b = bait;
            return time
                    + " · "
                    + weather
                    + (moon.isEmpty() ? "" : " · " + moon)
                    + " · "
                    + (b == null ? "-" : b.toString())
                    + " · "
                    + tier;
        }
    }

    private Simulation() {}

    static SimulationResult run(RollEngine engine, RegionId region, int count) {
        return run(engine, region, count, Conditions.DEFAULT);
    }

    static SimulationResult run(
            RollEngine engine, RegionId region, int count, Conditions conditions) {
        SplittableRandom random = new SplittableRandom();
        RollContext ctx =
                new RollContext(
                        region,
                        conditions.time(),
                        conditions.weather(),
                        conditions.moon(),
                        conditions.bait(),
                        conditions.tier(),
                        flag -> true,
                        fish -> false,
                        List.of(),
                        false,
                        List.of(),
                        flag -> true,
                        java.util.Set.of());
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
