package wiki.creeper.harbor.fishing.content;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * 요리 버프 (fishing/food.yml, 추가 제안, docs 20 0.1 '요리 버프는 Fishing'). 요리 아이템(items의 type: food)을 먹으면
 * 보정자를 출처 {@code food:<이름>}으로 등록한다. 요리 버프는 1종만 적용되어, 다른 요리를 먹으면 앞의 버프를 지우고 바꾼다 ('7. 밸런스 기획' 4.2).
 * 지속 시간은 food_buff_duration(요리사 +25%·랭크) 만큼 늘어난다. 제작 레시피는 Economy.
 *
 * @param buffs 요리 아이템 → 버프
 */
public record FoodBuffs(Map<ItemId, Buff> buffs) {

    /**
     * 요리 하나의 버프.
     *
     * @param shareable 연회 요리(feast_share)로 파티원에게 나눌 수 있는지
     */
    public record Buff(ItemId item, Duration duration, List<Effect> effects, boolean shareable) {}

    public static final FoodBuffs EMPTY = new FoodBuffs(Map.of());

    public static final ContentType.Single<FoodBuffs> TYPE =
            new ContentType.Single<>("fishing_food", "fishing/food.yml", false, FoodBuffs::parse);

    public Optional<Buff> of(ItemId item) {
        return Optional.ofNullable(buffs.get(item));
    }

    static FoodBuffs parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(Set.of("buffs"));
        Map<ItemId, Buff> buffs = new LinkedHashMap<>();
        for (YamlNode b : node.list("buffs")) {
            b.allowOnly(Set.of("item", "duration_s", "effects", "shareable"));
            HarborId item =
                    b.require("item")
                            .flatMap(n -> n.asId(ItemId.PREFIX))
                            .orElse(HarborId.parse("item/invalid"));
            b.get("item").ifPresent(n -> ctx.ref("item", item, n.pos()));
            List<Effect> effects = Effect.parseList(b.list("effects"));
            if (effects.isEmpty()) {
                b.error("effects가 비었습니다");
            }
            ItemId id = new ItemId(item);
            if (buffs.containsKey(id)) {
                b.error("같은 요리의 버프가 두 번 정의되었습니다: " + item);
            }
            buffs.put(
                    id,
                    new Buff(
                            id,
                            Duration.ofSeconds(b.integer("duration_s", 600, 1, 86_400)),
                            effects,
                            b.bool("shareable", true)));
        }
        return new FoodBuffs(Map.copyOf(buffs));
    }
}
