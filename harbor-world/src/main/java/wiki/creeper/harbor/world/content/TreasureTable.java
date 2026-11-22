package wiki.creeper.harbor.world.content;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Treasure map reward table (world/treasure.yml, docs 20 8.3 treasure.yml, proposed). No server
 * API.
 *
 * @param regionMult regional gold multiplier
 */
public record TreasureTable(Map<RegionId, Double> regionMult, List<Tier> tiers) {

    /** Reward item. */
    public record Loot(ItemId item, int weight, int minCount, int maxCount) {}

    /**
     * Tier.
     *
     * @param region the region if region-only (null = all regions)
     * @param weight weight for being drawn when making a map (0 = event only)
     * @param picks how many times items are drawn
     */
    public record Tier(
            String id,
            @Nullable RegionId region,
            int weight,
            long minGold,
            long maxGold,
            int picks,
            double hintChance,
            List<Loot> items) {}

    /** Roll result. */
    public record Roll(long gold, Map<ItemId, Integer> items, boolean hint) {}

    public static final TreasureTable EMPTY = new TreasureTable(Map.of(), List.of());

    public static final ContentType.Single<TreasureTable> TYPE =
            new ContentType.Single<>(
                    "world_treasure", "world/treasure.yml", false, TreasureTable::parse);

    public Optional<Tier> tier(String id, RegionId region) {
        Optional<Tier> exact =
                tiers.stream()
                        .filter(t -> t.id().equals(id) && region.equals(t.region()))
                        .findFirst();
        return exact.isPresent()
                ? exact
                : tiers.stream().filter(t -> t.id().equals(id) && t.region() == null).findFirst();
    }

    /**
     * Picks a tier when making a map (a region-only tier replaces the common tier with the same
     * ID).
     */
    public Optional<Tier> pickTier(RegionId region, RandomGenerator random) {
        Map<String, Tier> byId = new HashMap<>();
        for (Tier t : tiers) {
            if (t.weight() <= 0) {
                continue;
            }
            if (region.equals(t.region())) {
                byId.put(t.id(), t);
            } else if (t.region() == null) {
                byId.putIfAbsent(t.id(), t);
            }
        }
        List<Tier> pool = new ArrayList<>(byId.values());
        pool.sort(java.util.Comparator.comparing(Tier::id));
        int total = pool.stream().mapToInt(Tier::weight).sum();
        if (total <= 0) {
            return Optional.empty();
        }
        int roll = random.nextInt(total);
        for (Tier t : pool) {
            roll -= t.weight();
            if (roll < 0) {
                return Optional.of(t);
            }
        }
        return Optional.of(pool.getLast());
    }

    public double mult(RegionId region) {
        return regionMult.getOrDefault(region, 1.0);
    }

    /** Rolls the rewards (once on the main thread, before the claim transaction). */
    public Roll roll(Tier tier, RegionId region, RandomGenerator random) {
        long span = Math.max(0, tier.maxGold() - tier.minGold());
        long base = tier.minGold() + (span == 0 ? 0 : random.nextLong(span + 1));
        long gold = Math.round(base * mult(region));
        Map<ItemId, Integer> items = new HashMap<>();
        int total = tier.items().stream().mapToInt(Loot::weight).sum();
        for (int i = 0; i < tier.picks() && total > 0; i++) {
            int roll = random.nextInt(total);
            for (Loot loot : tier.items()) {
                roll -= loot.weight();
                if (roll < 0) {
                    int count =
                            loot.minCount()
                                    + (loot.maxCount() > loot.minCount()
                                            ? random.nextInt(loot.maxCount() - loot.minCount() + 1)
                                            : 0);
                    items.merge(loot.item(), count, Integer::sum);
                    break;
                }
            }
        }
        return new Roll(gold, Map.copyOf(items), random.nextDouble() < tier.hintChance());
    }

    static TreasureTable parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(Set.of("region_mult", "tiers"));
        Map<RegionId, Double> mult = new HashMap<>();
        node.get("region_mult")
                .ifPresent(
                        m ->
                                m.entries()
                                        .forEach(
                                                (k, v) -> {
                                                    Optional<HarborId> id = HarborId.tryParse(k);
                                                    if (id.isEmpty()
                                                            || !id.get()
                                                                    .hasPrefix(RegionId.PREFIX)) {
                                                        v.error("지역 ID 형식이 틀렸습니다: '" + k + "'");
                                                        return;
                                                    }
                                                    ctx.ref("region", id.get(), v.pos());
                                                    double value = v.asDouble();
                                                    if (value < 0) {
                                                        v.error("배율은 0 이상입니다");
                                                    }
                                                    mult.put(new RegionId(id.get()), value);
                                                }));
        List<Tier> tiers = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (YamlNode t : node.list("tiers")) {
            t.allowOnly(Set.of("id", "region", "weight", "gold", "picks", "hint_chance", "items"));
            String id = RegionExt.localId(t, "id");
            RegionId region =
                    t.get("region")
                            .flatMap(
                                    r ->
                                            r.asId(RegionId.PREFIX)
                                                    .map(
                                                            h -> {
                                                                ctx.ref("region", h, r.pos());
                                                                return new RegionId(h);
                                                            }))
                            .orElse(null);
            if (!seen.add(id + "@" + region)) {
                t.error("같은 지역에 등급 ID가 중복됩니다: " + id);
            }
            long minGold = 0;
            long maxGold = 0;
            List<YamlNode> gold = t.list("gold");
            if (gold.size() == 2) {
                minGold = gold.get(0).asLong();
                maxGold = gold.get(1).asLong();
                if (minGold < 0 || maxGold < minGold) {
                    t.error("gold는 [최소, 최대]이고 0 이상입니다");
                }
            } else {
                t.error("gold는 [최소, 최대]입니다");
            }
            List<Loot> items = new ArrayList<>();
            for (YamlNode i : t.list("items")) {
                i.allowOnly(Set.of("item", "weight", "count"));
                HarborId item =
                        i.require("item")
                                .flatMap(n -> n.asId(ItemId.PREFIX))
                                .orElse(HarborId.parse("item/invalid"));
                i.get("item").ifPresent(n -> ctx.ref("item", item, n.pos()));
                int min = 1;
                int max = 1;
                List<YamlNode> count = i.list("count");
                if (count.size() == 2) {
                    min = count.get(0).asInt();
                    max = count.get(1).asInt();
                    if (min < 1 || max < min) {
                        i.error("count는 [최소, 최대]이고 1 이상입니다");
                    }
                } else if (!count.isEmpty()) {
                    i.error("count는 [최소, 최대]입니다");
                }
                items.add(new Loot(new ItemId(item), i.integer("weight", 1, 0, 100_000), min, max));
            }
            double hint = t.number("hint_chance", 0);
            if (hint < 0 || hint > 1) {
                t.error("hint_chance는 0~1입니다");
            }
            tiers.add(
                    new Tier(
                            id,
                            region,
                            t.integer("weight", 0, 0, 100_000),
                            minGold,
                            maxGold,
                            t.integer("picks", 1, 0, 10),
                            hint,
                            List.copyOf(items)));
        }
        return new TreasureTable(Map.copyOf(mult), List.copyOf(tiers));
    }
}
