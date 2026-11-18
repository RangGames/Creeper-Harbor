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
 * 자연 스폰 어종 고르기 ('17-3' 9.4 자연 스폰). 후보는 그 자리 지역(fishingKey)의 어종 중 species.yml에서 켜져 있고 어종 YAML의
 * 시간·날씨·달·세부 구역·해금 플래그를 지금 만족하는 것 (야생에서 보이는 어종이 그 시간 실제로 낚이는 어종이 되어 힌트가 된다). 가중치 = 등급 가중치 ×
 * spawn_weight × boost. 전설·신화는 자연 스폰하지 않는다. 순수 계산.
 */
public final class SpawnTable {

    /**
     * 그 자리 조건.
     *
     * @param time dawn · day · dusk · night (Fishing과 같은 경계)
     * @param weather clear · rain · thunder
     * @param moon full · new · ""
     * @param areas 세부 구역 (World fishingKey)
     * @param mediums 그 자리에서 가능한 매질 (물 칸이면 WATER, 바닥이 모래면 FLOOR도)
     * @param depth 수면 아래 깊이 (블록, 바닥 개체는 보지 않음)
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

    /** Fishing과 같은 규칙: 뇌우는 비도 오는 날씨라 rain·storm 조건도 만족한다. */
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

    /** 월드 시간 → 시간대 (Fishing FishingManager와 같은 경계). 네더·엔드도 오버월드 시간으로 판정한다. */
    public static String timeKey(long worldTime) {
        long t = Math.floorMod(worldTime, 24000L);
        if (t >= 23000 || t < 1000) {
            return "dawn";
        }
        return t < 12000 ? "day" : t < 13800 ? "dusk" : "night";
    }
}
