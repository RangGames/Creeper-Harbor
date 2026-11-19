package wiki.creeper.harbor.world.content;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * 하우징 장식 (world/housing_decor.yml, docs 20 8.3 housing_decor.yml, 추가 제안). 서버 API 없음 (블록 이름 확인은
 * 플러그인의 ContentCheck).
 *
 * @param material 블록 (block · tank)
 * @param model 가구 외형 아이템 모델 (furniture · tank 표시용)
 * @param furniture Core 가구 정의 (furniture/furniture.yml, 추가 제안). 있으면 앉기·충돌·빛까지 Core 가구로 놓는다
 * @param block Core 커스텀 블록 정의 (furniture/blocks.yml, 추가 제안). block 장식에서 material 대신
 * @param amount 한 번 살 때 받는 개수
 * @param reputationMin 시즌 간 최고 평판 등급 조건 (0 = 없음)
 */
public record DecorDef(
        HarborId id,
        String name,
        Kind kind,
        @Nullable String material,
        @Nullable String model,
        @Nullable HarborId furniture,
        @Nullable HarborId block,
        int amount,
        long price,
        int reputationMin,
        String category) {

    /** 장식 종류. */
    public enum Kind {
        /** 블록 하나. */
        BLOCK,
        /** ItemDisplay 가구 (Interaction으로 회수). */
        FURNITURE,
        /** 개인 어항 수조 블록 (Collection 전시 칸 자리, DisplayAnchor). */
        TANK
    }

    public static final String PREFIX = "decor/";

    private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public static final ContentType.Entries<DecorDef> TYPE =
            new ContentType.Entries<>(
                    "housing_decor",
                    List.of("world/housing_decor.yml"),
                    PREFIX,
                    DecorDef::parse,
                    DecorDef::id);

    static DecorDef parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(
                Set.of(
                        "id",
                        "name",
                        "kind",
                        "material",
                        "model",
                        "furniture",
                        "block",
                        "amount",
                        "price",
                        "reputation_min",
                        "category"));
        HarborId id =
                node.require("id")
                        .flatMap(n -> n.asId(PREFIX))
                        .orElse(HarborId.parse("decor/invalid"));
        Kind kind = RegionExt.enumOf(node, "kind", Kind.class, Kind.BLOCK);
        String material = node.optString("material").orElse(null);
        String model = node.optString("model").orElse(null);
        if (material != null && !KEY.matcher(material).matches()) {
            node.error("material은 'minecraft:oak_planks' 같은 키여야 합니다: '" + material + "'");
        }
        if (model != null && !KEY.matcher(model).matches()) {
            node.error("model은 'harbor:decor/x' 같은 키여야 합니다: '" + model + "'");
        }
        HarborId furniture = ref(node, ctx, "furniture", "furniture/", "furniture");
        HarborId block = ref(node, ctx, "block", "block/", "custom_block");
        if (kind == Kind.TANK && material == null) {
            node.error("tank 장식에는 material이 필요합니다");
        }
        if (kind == Kind.BLOCK && (material == null) == (block == null)) {
            node.error("block 장식에는 material(바닐라 블록) 또는 block(커스텀 블록) 중 하나가 필요합니다");
        }
        if (kind == Kind.FURNITURE && model == null && furniture == null) {
            node.error("furniture 장식에는 furniture(Core 가구) 또는 model이 필요합니다");
        }
        if (furniture != null && kind != Kind.FURNITURE) {
            node.error("furniture는 kind: furniture 장식에만 씁니다");
        }
        if (block != null && kind != Kind.BLOCK) {
            node.error("block은 kind: block 장식에만 씁니다");
        }
        long price = node.longValue("price", 0);
        if (price <= 0) {
            node.error("price는 0보다 커야 합니다");
        }
        return new DecorDef(
                id,
                node.string("name"),
                kind,
                material,
                model,
                furniture,
                block,
                node.integer("amount", 1, 1, 64),
                price,
                node.integer("reputation_min", 0, 0, 10),
                node.string("category", "misc"));
    }

    private static @Nullable HarborId ref(
            YamlNode node, ParseContext ctx, String field, String prefix, String type) {
        return node.get(field)
                .flatMap(n -> n.asId(prefix))
                .map(
                        id -> {
                            ctx.ref(type, id, node.pos());
                            return id;
                        })
                .orElse(null);
    }
}
