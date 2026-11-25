package wiki.creeper.harbor.fishing.roll;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.fishing.content.FishingBalance;

/**
 * Spawn roll (docs 20 3.4 RollEngine). Uses no server API (simulation, unit tests).
 *
 * <ol>
 *   <li>Candidates: species from the region index that pass the time, weather, moon, bait, job tier
 *       and unlock conditions
 *   <li>Legendary: each candidate legendary species is rolled separately with its pity chance
 *   <li>Mythic: each mythic window of the sea events being participated in is rolled with the event
 *       chance + mythic_chance (FishingManager reserves the server count)
 *   <li>Grade: the base weights of the grades present in the region are renormalized to 100%, and
 *       rare-or-better are boosted (total multiplier capped at 1.6). Conditional bait bonuses
 *       ({@link RollModifiers.WeightBonus}) also boost a rare-or-better grade by the weight share
 *       of its matching candidates (bait category, inside the same cap)
 *   <li>Species: picked within the grade by weight × species multiplier (unregistered, event,
 *       conditional bait bonus)
 * </ol>
 *
 * Mythic species are excluded from normal candidates and only come from mythic windows.
 */
public final class RollEngine {

    /**
     * Roll result.
     *
     * @param legendCandidates legendary species that passed the conditions (pity counter increment
     *     targets)
     * @param mythic the window, if rolled from a mythic window (server count reservation target)
     */
    public record Outcome(
            FishDef fish,
            List<FishDef> legendCandidates,
            RollContext.@Nullable MythicWindow mythic) {

        public Outcome(FishDef fish, List<FishDef> legendCandidates) {
            this(fish, legendCandidates, null);
        }
    }

    private final ContentSnapshot snapshot;
    private final FishingBalance balance;
    private final Map<RegionId, Map<Grade, List<FishDef>>> index = new HashMap<>();

    public RollEngine(ContentSnapshot snapshot, FishingBalance balance) {
        this.snapshot = snapshot;
        this.balance = balance;
        for (FishDef fish : snapshot.all(FishDef.TYPE).values()) {
            index.computeIfAbsent(fish.region(), r -> new EnumMap<>(Grade.class))
                    .computeIfAbsent(fish.grade(), g -> new ArrayList<>())
                    .add(fish);
        }
    }

    /** The snapshot this engine was built from (rebuilt on reload). */
    public ContentSnapshot snapshot() {
        return snapshot;
    }

    public boolean hasRegion(RegionId region) {
        return index.containsKey(region);
    }

    /** Candidates that passed the conditions (per grade). */
    public Map<Grade, List<FishDef>> candidates(RollContext ctx) {
        Map<Grade, List<FishDef>> result = new EnumMap<>(Grade.class);
        for (Map.Entry<Grade, List<FishDef>> e :
                index.getOrDefault(ctx.region(), Map.of()).entrySet()) {
            List<FishDef> passing = e.getValue().stream().filter(f -> eligible(f, ctx)).toList();
            if (!passing.isEmpty()) {
                result.put(e.getKey(), passing);
            }
        }
        return result;
    }

    static boolean eligible(FishDef fish, RollContext ctx) {
        if (!fish.time().isEmpty() && !fish.time().contains(ctx.time())) {
            return false;
        }
        if (!fish.weather().isEmpty() && !weatherMatches(fish.weather(), ctx.weather())) {
            return false;
        }
        if (!fish.moon().isEmpty() && !fish.moon().contains(ctx.moon())) {
            return false;
        }
        if (!fish.bait().isEmpty() && (ctx.bait() == null || !fish.bait().contains(ctx.bait()))) {
            return false;
        }
        if (ctx.jobTier() < fish.jobTierMin()) {
            return false;
        }
        String flag = fish.unlockFlag();
        if (flag != null && !ctx.serverFlags().test(flag)) {
            return false;
        }
        // Sub-areas: checked only when World provides the region key
        if (ctx.areasKnown()
                && !fish.area().isEmpty()
                && fish.area().stream().noneMatch(ctx.areas()::contains)) {
            return false;
        }
        for (FishDef.Extra extra : fish.extra()) {
            boolean ok =
                    switch (extra.type()) {
                        case PLAYER_FLAG -> ctx.playerFlags().test(extra.value());
                        case WORLD_FLAG -> ctx.serverFlags().test(extra.value());
                        case EQUIPMENT_TAG -> ctx.equipment().contains(extra.value());
                    };
            if (!ok) {
                return false;
            }
        }
        // Mythics only from event mythic windows
        return fish.grade() != Grade.MYTHIC;
    }

    /**
     * Sum of the conditional bait/part bonuses that apply to a species (0 if none). Additive
     * (proposed).
     */
    static double weightBonus(FishDef fish, RollContext ctx, RollModifiers mods) {
        if (mods.weightBonus().isEmpty()) {
            return 0;
        }
        double sum = 0;
        Set<String> tokens = ctx.castTokens();
        for (RollModifiers.WeightBonus b : mods.weightBonus()) {
            if (b.matches(fish.tags(), tokens)) {
                sum += b.value();
            }
        }
        return sum;
    }

    /**
     * Grade-level share of the conditional bonuses: Σ weight × bonus / Σ weight over the grade's
     * candidates. A bonus that matches every candidate (ice worm in the fjord) gives its full
     * value; one that matches a few species gives a fraction.
     */
    public static double gradeShare(List<FishDef> fish, RollContext ctx, RollModifiers mods) {
        if (mods.weightBonus().isEmpty() || fish.isEmpty()) {
            return 0;
        }
        double total = 0;
        double boosted = 0;
        for (FishDef f : fish) {
            total += f.weight();
            boosted += f.weight() * weightBonus(f, ctx, mods);
        }
        return total <= 0 ? 0 : boosted / total;
    }

    /**
     * A thunderstorm (thunder, storm) is also rainy weather, so it satisfies rain conditions too.
     */
    static boolean weatherMatches(List<String> allowed, String weather) {
        if (allowed.contains(weather)) {
            return true;
        }
        boolean storm = weather.equals("thunder");
        return storm && (allowed.contains("rain") || allowed.contains("storm"));
    }

    /**
     * Rolls.
     *
     * @param pity pity counters per species
     */
    public Optional<Outcome> roll(
            RollContext ctx,
            RollModifiers mods,
            Map<FishId, Integer> pity,
            RandomGenerator random) {
        Map<Grade, List<FishDef>> candidates = candidates(ctx);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        List<FishDef> legends = candidates.getOrDefault(Grade.LEGENDARY, List.of());

        // Legendary: each species separately
        List<FishDef> legendHits = new ArrayList<>();
        for (FishDef legend : legends) {
            if (random.nextDouble() < legendChance(pity.getOrDefault(legend.id(), 0), mods)) {
                legendHits.add(legend);
            }
        }
        if (!legendHits.isEmpty()) {
            return Optional.of(new Outcome(pickSpecies(legendHits, ctx, mods, random), legends));
        }

        // Mythic: each open window separately (the first window that hits)
        for (RollContext.MythicWindow window : ctx.mythic()) {
            double chance =
                    (window.chance() > 0 ? window.chance() : balance.mythicChance())
                            + mods.mythicChanceBonus();
            if (random.nextDouble() < chance) {
                return Optional.of(new Outcome(window.fish(), legends, window));
            }
        }

        // Grade
        Map<Grade, Double> weights = new EnumMap<>(Grade.class);
        for (Grade grade : candidates.keySet()) {
            double base = balance.gradeWeight(grade);
            if (base <= 0) {
                continue;
            }
            double mult = 1;
            if (grade.isAtLeast(Grade.RARE)) {
                double bonus = mods.gradeBonus().getOrDefault(grade, 0.0);
                double share = gradeShare(candidates.getOrDefault(grade, List.of()), ctx, mods);
                mult = Math.min((1 + bonus) * (1 + share), 1 + balance.rareBonusCap());
            }
            weights.put(grade, base * mult);
        }
        if (weights.isEmpty()) {
            return Optional.empty();
        }
        Grade grade = weighted(weights, random);
        return Optional.of(
                new Outcome(
                        pickSpecies(candidates.getOrDefault(grade, List.of()), ctx, mods, random),
                        legends));
    }

    /**
     * Legendary chance: min(base + step × max(0, n − s), max) × legend_chance ("7. Balance design"
     * 4.3).
     */
    public double legendChance(int casts, RollModifiers mods) {
        int start = balance.legendPityStart() + mods.legendPityStartOffset();
        double p =
                Math.min(
                        balance.legendBase()
                                + balance.legendPityStep() * Math.max(0, casts - start),
                        balance.legendMax());
        return p * mods.legendChanceMult();
    }

    /**
     * Expected spawn ratios per region (for the Economy price S₀). Computed from the base weights
     * without modifiers; species that need bait or an unlock flag, and legendaries and mythics, are
     * 0. Time, weather and moon conditions are ignored.
     */
    public double expectedShare(RegionId region, FishId fish) {
        Map<Grade, List<FishDef>> pool = new EnumMap<>(Grade.class);
        for (Map.Entry<Grade, List<FishDef>> e : index.getOrDefault(region, Map.of()).entrySet()) {
            if (e.getKey().isAtLeast(Grade.LEGENDARY)) {
                continue;
            }
            List<FishDef> list =
                    e.getValue().stream()
                            .filter(
                                    f ->
                                            f.bait().isEmpty()
                                                    && f.unlockFlag() == null
                                                    && f.extra().isEmpty())
                            .toList();
            if (!list.isEmpty()) {
                pool.put(e.getKey(), list);
            }
        }
        double totalGrade = 0;
        for (Grade g : pool.keySet()) {
            totalGrade += balance.gradeWeight(g);
        }
        if (totalGrade <= 0) {
            return 0;
        }
        for (Map.Entry<Grade, List<FishDef>> e : pool.entrySet()) {
            double sum = e.getValue().stream().mapToDouble(FishDef::weight).sum();
            for (FishDef f : e.getValue()) {
                if (f.id().equals(fish)) {
                    return balance.gradeWeight(e.getKey()) / totalGrade * f.weight() / sum;
                }
            }
        }
        return 0;
    }

    /** Species of the region (per grade, ignoring conditions). Used for trap yields. */
    public Map<Grade, List<FishDef>> regionPool(RegionId region) {
        return index.getOrDefault(region, Map.of());
    }

    public FishingBalance balance() {
        return balance;
    }

    private static FishDef pickSpecies(
            List<FishDef> fish, RollContext ctx, RollModifiers mods, RandomGenerator random) {
        Map<FishDef, Double> weights = new HashMap<>();
        for (FishDef f : fish) {
            double w = f.weight();
            if (!ctx.caught().test(f.id())) {
                w *= 1 + mods.uncaughtWeight();
            }
            w *= mods.speciesMult().getOrDefault(f.id().toString(), 1.0);
            w *= Math.max(0, 1 + weightBonus(f, ctx, mods));
            weights.put(f, w);
        }
        return weighted(weights, random);
    }

    static <T> T weighted(Map<T, Double> weights, RandomGenerator random) {
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
            throw new IllegalArgumentException("가중치가 비었습니다");
        }
        return last;
    }
}
