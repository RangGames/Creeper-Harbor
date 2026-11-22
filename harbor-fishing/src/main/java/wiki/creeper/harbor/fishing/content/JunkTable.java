package wiki.creeper.harbor.fishing.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Junk table (fishing/junk.yml, proposed, docs 20 3.4 "junk"). On a successful catch, every entry
 * of the region table is rolled separately and all hits are given in addition (coral fragment 5%,
 * deep sea crystal 4%, "13. Crafting & shop prices" chapter 4). Treasure maps are rolled only when
 * World has registered a TreasureDropHook.
 *
 * <p>Region keys are not reference-checked so they can be written before the map is finalized
 * (regions missing from regions.yml are simply never used in rolls). Items are checked.
 *
 * @param regions junk per region
 * @param treasureChance base treasure map chance (× (1 + treasure_map_chance))
 * @param treasureRegions regions where treasure maps drop (empty for all regions)
 */
public record JunkTable(
        Map<RegionId, List<Entry>> regions, double treasureChance, Set<RegionId> treasureRegions) {

    /** One junk entry. */
    public record Entry(ItemId item, double chance, int amount) {}

    public static final JunkTable EMPTY = new JunkTable(Map.of(), 0, Set.of());

    public static final ContentType.Single<JunkTable> TYPE =
            new ContentType.Single<>("fishing_junk", "fishing/junk.yml", false, JunkTable::parse);

    public List<Entry> entries(RegionId region) {
        return regions.getOrDefault(region, List.of());
    }

    public boolean treasureIn(RegionId region) {
        return treasureChance > 0
                && (treasureRegions.isEmpty() || treasureRegions.contains(region));
    }

    static JunkTable parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(Set.of("regions", "treasure"));
        Map<RegionId, List<Entry>> regions = new LinkedHashMap<>();
        node.get("regions")
                .ifPresent(
                        r ->
                                r.entries()
                                        .forEach(
                                                (key, list) -> {
                                                    RegionId region = region(key, list);
                                                    if (region != null) {
                                                        regions.put(region, entries(list, ctx));
                                                    }
                                                }));
        double treasure = 0;
        Set<RegionId> treasureRegions = Set.of();
        if (node.has("treasure")) {
            YamlNode t = node.get("treasure").orElseThrow();
            t.allowOnly(Set.of("chance", "regions"));
            treasure = chance(t, "chance");
            List<RegionId> list = new ArrayList<>();
            for (YamlNode r : t.list("regions")) {
                RegionId id = region(r.asString(), r);
                if (id != null) {
                    list.add(id);
                }
            }
            treasureRegions = Set.copyOf(list);
        }
        return new JunkTable(Map.copyOf(regions), treasure, treasureRegions);
    }

    private static List<Entry> entries(YamlNode list, ParseContext ctx) {
        List<Entry> result = new ArrayList<>();
        for (YamlNode e : list.asList()) {
            e.allowOnly(Set.of("item", "chance", "amount"));
            HarborId item =
                    e.require("item")
                            .flatMap(n -> n.asId(ItemId.PREFIX))
                            .orElse(HarborId.parse("item/invalid"));
            e.get("item").ifPresent(n -> ctx.ref("item", item, n.pos()));
            result.add(
                    new Entry(
                            new ItemId(item), chance(e, "chance"), e.integer("amount", 1, 1, 64)));
        }
        return List.copyOf(result);
    }

    static double chance(YamlNode node, String key) {
        double value = node.number(key, 0);
        if (value < 0 || value > 1) {
            node.error(key + "는 0~1 사이여야 합니다");
            return 0;
        }
        return value;
    }

    private static @Nullable RegionId region(String raw, YamlNode at) {
        try {
            return RegionId.parse(raw);
        } catch (IllegalArgumentException e) {
            at.error("지역 ID 형식이 틀렸습니다: '" + raw + "'");
            return null;
        }
    }
}
