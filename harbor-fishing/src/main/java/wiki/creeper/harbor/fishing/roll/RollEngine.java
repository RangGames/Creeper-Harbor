package wiki.creeper.harbor.fishing.roll;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.fishing.content.FishingBalance;

/**
 * 출현 판정 (docs 20 3.4 RollEngine). 서버 API를 쓰지 않는다 (시뮬레이션·단위 테스트).
 *
 * <ol>
 *   <li>후보: 지역 인덱스에서 시간·날씨·달·미끼·직업 단계·해금 조건을 통과한 어종
 *   <li>전설: 후보 전설 어종마다 천장 확률로 따로 판정
 *   <li>등급: 지역에 있는 등급의 기본 가중치를 다시 100%로 맞추고, 희귀 이상에 보정 (합계 배율 1.6 상한)
 *   <li>어종: 등급 안에서 weight × 어종 배율(미등록, 이벤트)로 고른다
 * </ol>
 *
 * 신화는 이벤트 모듈이 신화 창을 열 때만 나오므로 여기서는 일반 판정에서 뺀다.
 */
public final class RollEngine {

    /** 판정 결과. legendCandidates는 조건을 통과한 전설 어종 (천장 카운터 증가 대상). */
    public record Outcome(FishDef fish, List<FishDef> legendCandidates) {}

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

    /** 이 엔진이 만들어진 스냅샷 (리로드되면 새로 만든다). */
    public ContentSnapshot snapshot() {
        return snapshot;
    }

    public boolean hasRegion(RegionId region) {
        return index.containsKey(region);
    }

    /** 조건을 통과한 후보 (등급별). */
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
        // 신화는 이벤트 신화 창에서만
        return fish.grade() != Grade.MYTHIC;
    }

    /** 뇌우(thunder·storm)는 비도 오는 날씨라 rain 조건도 만족한다. */
    static boolean weatherMatches(List<String> allowed, String weather) {
        if (allowed.contains(weather)) {
            return true;
        }
        boolean storm = weather.equals("thunder");
        return storm && (allowed.contains("rain") || allowed.contains("storm"));
    }

    /**
     * 판정한다.
     *
     * @param pity 어종별 천장 카운터
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

        // 전설: 어종마다 따로
        List<FishDef> legendHits = new ArrayList<>();
        for (FishDef legend : legends) {
            if (random.nextDouble() < legendChance(pity.getOrDefault(legend.id(), 0), mods)) {
                legendHits.add(legend);
            }
        }
        if (!legendHits.isEmpty()) {
            return Optional.of(new Outcome(pickSpecies(legendHits, ctx, mods, random), legends));
        }

        // 등급
        Map<Grade, Double> weights = new EnumMap<>(Grade.class);
        for (Grade grade : candidates.keySet()) {
            double base = balance.gradeWeight(grade);
            if (base <= 0) {
                continue;
            }
            double bonus =
                    grade.isAtLeast(Grade.RARE) ? mods.gradeBonus().getOrDefault(grade, 0.0) : 0;
            weights.put(grade, base * Math.min(1 + bonus, 1 + balance.rareBonusCap()));
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

    /** 전설 확률: min(base + step × max(0, n − s), max) × legend_chance ('7. 밸런스 기획' 4.3). */
    public double legendChance(int casts, RollModifiers mods) {
        int start = balance.legendPityStart() + mods.legendPityStartOffset();
        double p =
                Math.min(
                        balance.legendBase()
                                + balance.legendPityStep() * Math.max(0, casts - start),
                        balance.legendMax());
        return p * mods.legendChanceMult();
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
            weights.put(f, w);
        }
        return weighted(weights, random);
    }

    private static <T> T weighted(Map<T, Double> weights, RandomGenerator random) {
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
