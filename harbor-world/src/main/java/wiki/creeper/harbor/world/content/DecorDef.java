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
 * Housing decor (world/housing_decor.yml, docs 20 8.3 housing_decor.yml, proposed). No server API
 * (block names are checked by the plugin's ContentCheck).
 *
 * @param material block (block · tank)
 * @param model furniture appearance item model (for furniture · tank display)
 * @param furniture Core furniture definition (furniture/furniture.yml, proposed). If set, placed as
 *     Core furniture including sitting, collision and light
 * @param block Core custom block definition (furniture/blocks.yml, proposed). Used instead of
 *     material for block decor
 * @param amount number received per purchase
 * @param reputationMin best cross-season reputation tier required (0 = none)
 * @param size tank size [width, height, depth] in blocks (tank only, proposed; width runs to the
 *     placer's right, depth away from them)
 * @param fish fish a tank holds (tank only, proposed; one display slot each)
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
        String category,
        List<Integer> size,
        int fish) {

    public DecorDef {
        size = List.copyOf(size);
    }

    /** Blocks in a tank (1 for 1×1). */
    public int volume() {
        return size.get(0) * size.get(1) * size.get(2);
    }

    /** Decor kind. */
    public enum Kind {
        /** A single block. */
        BLOCK,
        /** ItemDisplay furniture (recovered via Interaction). */
        FURNITURE,
        /** Personal aquarium tank block (a Collection display slot, DisplayAnchor). */
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
                        "category",
                        "size",
                        "fish"));
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
        List<Integer> size = List.of(1, 1, 1);
        List<YamlNode> rawSize = node.list("size");
        if (!rawSize.isEmpty()) {
            if (kind != Kind.TANK) {
                node.error("size는 kind: tank 장식에만 씁니다");
            }
            List<Integer> s = rawSize.stream().map(YamlNode::asInt).toList();
            if (s.size() != 3 || s.stream().anyMatch(v -> v < 1 || v > 8)) {
                node.error("size는 [가로, 높이, 깊이] (각 1~8)이어야 합니다");
            } else {
                size = s;
            }
        }
        int volume = size.get(0) * size.get(1) * size.get(2);
        int fish = node.integer("fish", 1, 1, 64);
        if (fish > volume) {
            node.error("fish(" + fish + ")가 수조 칸 수(" + volume + ")보다 많습니다");
            fish = volume;
        }
        if (node.has("fish") && kind != Kind.TANK) {
            node.error("fish는 kind: tank 장식에만 씁니다");
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
                node.string("category", "misc"),
                size,
                fish);
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
