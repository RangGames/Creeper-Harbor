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
 * Food buffs (fishing/food.yml, proposed, docs 20 0.1 "food buffs belong to Fishing"). Eating a
 * food item (items with type: food) registers its modifiers under the source {@code food:<name>}.
 * Only one food buff applies at a time; eating another food removes the previous buff and replaces
 * it ("7. Balance design" 4.2). The duration is extended by food_buff_duration (cook +25%, rank).
 * Crafting recipes belong to Economy.
 *
 * @param buffs food item → buff
 */
public record FoodBuffs(Map<ItemId, Buff> buffs) {

    /**
     * Buff of one food.
     *
     * @param shareable whether it can be shared with party members as a feast dish (feast_share)
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
