package wiki.creeper.harbor.wildlife.sim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.random.RandomGenerator;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.wildlife.api.Medium;

/**
 * Natural spawn species selection ('17-3' 9.4 natural spawns). Candidates are the species of the
 * spot's region (fishingKey) that are enabled in species.yml and currently satisfy the species
 * YAML's time, weather, moon, sub-area and unlock flag conditions (so fish seen in the wild are the
 * ones actually catchable at that time, serving as a hint). Weight = grade weight × spawn_weight ×
 * boost. Legendary and mythic fish never spawn naturally. Pure math.
 */
public final class SpawnTable {

    /**
     * Conditions at the spot.
     *
     * @param time dawn, day, dusk, night (same boundaries as Fishing)
     * @param weather clear, rain, thunder
     * @param moon full, new, ""
     * @param areas sub-areas (World fishingKey)
     * @param mediums media possible at the spot (WATER for a water cell, plus FLOOR if the floor is
     *     sand)
     * @param depth depth below the surface (blocks, ignored for floor fish)
     */
    public record Conditions(
            RegionId region,
            String time,
            String weather,
            String moon,
            List<String> areas,
            Predicate<String> serverFlags,
            Set<Medium> mediums,
            double depth) {

        public Conditions {
            areas = List.copyOf(areas);
            mediums = Set.copyOf(mediums);
        }
    }

    private SpawnTable() {}

    public static boolean eligible(SpeciesProfile p, Conditions c) {
        FishDef fish = p.fish();
        if (!p.naturalSpawn()
                || !fish.region().equals(c.region())
                || !c.mediums().contains(p.medium())) {
            return false;
        }
        if (!fish.time().isEmpty() && !fish.time().contains(c.time())) {
            return false;
        }
        if (!fish.weather().isEmpty() && !weatherMatches(fish.weather(), c.weather())) {
            return false;
        }
        if (!fish.moon().isEmpty() && !fish.moon().contains(c.moon())) {
            return false;
        }
        if (!fish.area().isEmpty() && fish.area().stream().noneMatch(c.areas()::contains)) {
            return false;
        }
        String flag = fish.unlockFlag();
        if (flag != null && !c.serverFlags().test(flag)) {
            return false;
        }
        if (p.medium() == Medium.FLOOR || p.medium() == Medium.AIR) {
            return true;
        }
        return c.depth() >= p.depthMin() && (p.depthMax() <= 0 || c.depth() <= p.depthMax());
    }

    /**
     * Same rule as Fishing: a thunderstorm is also rainy weather, so it satisfies rain/storm
     * conditions too.
     */
    public static boolean weatherMatches(List<String> allowed, String weather) {
        if (allowed.contains(weather)) {
            return true;
        }
        return weather.equals("thunder") && (allowed.contains("rain") || allowed.contains("storm"));
    }

    public static double weight(
            SpeciesProfile p, ToDoubleFunction<Grade> gradeWeight, ToDoubleFunction<FishId> boost) {
        return gradeWeight.applyAsDouble(p.fish().grade())
                * p.spawnWeight()
                * boost.applyAsDouble(p.fish().id());
    }

    public static Optional<SpeciesProfile> pick(
            Collection<SpeciesProfile> all,
            Conditions c,
            ToDoubleFunction<Grade> gradeWeight,
            ToDoubleFunction<FishId> boost,
            RandomGenerator random) {
        List<SpeciesProfile> pool = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double total = 0;
        for (SpeciesProfile p : all) {
            if (!eligible(p, c)) {
                continue;
            }
            double w = weight(p, gradeWeight, boost);
            if (w <= 0) {
                continue;
            }
            pool.add(p);
            weights.add(w);
            total += w;
        }
        if (pool.isEmpty()) {
            return Optional.empty();
        }
        double r = random.nextDouble() * total;
        for (int i = 0; i < pool.size(); i++) {
            r -= weights.get(i);
            if (r < 0) {
                return Optional.of(pool.get(i));
            }
        }
        return Optional.of(pool.getLast());
    }

    /**
     * World time → time of day (same boundaries as Fishing FishingManager). The Nether and End are
     * judged by overworld time too.
     */
    public static String timeKey(long worldTime) {
        long t = Math.floorMod(worldTime, 24000L);
        if (t >= 23000 || t < 1000) {
            return "dawn";
        }
        return t < 12000 ? "day" : t < 13800 ? "dusk" : "night";
    }
}
