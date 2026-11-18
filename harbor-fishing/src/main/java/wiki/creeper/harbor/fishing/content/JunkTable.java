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
 * 잡템 표 (fishing/junk.yml, 추가 제안, docs 20 3.4 '잡템'). 포획에 성공하면 지역 표의 항목마다 따로 굴려 맞은 것을 모두 추가 지급한다 (산호
 * 조각 5%·심해 결정 4%, '13. 제작 · 상점 가격' 4장). 보물 지도는 World가 TreasureDropHook을 등록했을 때만 판정한다.
 *
 * <p>지역 키는 맵 확정 전에 미리 적어 둘 수 있게 참조 검사를 하지 않는다 (regions.yml에 없는 지역은 판정에서 쓰이지 않을 뿐). 아이템은 검사한다.
 *
 * @param regions 지역별 잡템
 * @param treasureChance 보물 지도 기본 확률 (× (1 + treasure_map_chance))
 * @param treasureRegions 보물 지도가 나오는 지역 (비우면 모든 지역)
 */
public record JunkTable(
        Map<RegionId, List<Entry>> regions, double treasureChance, Set<RegionId> treasureRegions) {

    /** 잡템 하나. */
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
