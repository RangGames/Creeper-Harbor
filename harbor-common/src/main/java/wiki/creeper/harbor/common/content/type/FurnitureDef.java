package wiki.creeper.harbor.common.content.type;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Furniture (furniture/furniture.yml, proposed). Placed as an ItemDisplay (model) + Interaction
 * (click hitbox). The model is an item model from the resource pack.
 *
 * <pre>{@code
 * - {id: furniture/fishing_chair, name: Fishing Chair, model: "harbor:decor/fishing_chair", seat: 0.45, solid: true}
 * - {id: furniture/table_lamp, name: Table Lamp, model: "harbor:furniture/table_lamp", width: 0.5, height: 0.8, light: 12}
 * }</pre>
 *
 * @param model item model key (looks like base_item if missing from the pack)
 * @param baseItem vanilla item shown when there is no model
 * @param width click hitbox width (blocks)
 * @param height click hitbox height (blocks)
 * @param seat sitting height (feet position, from the block floor). null means it cannot be sat on
 * @param solid whether it collides (fills barrier blocks up to the hitbox height; one block wide)
 * @param light brightness 0~15 (light block; if solid, the block right above the furniture)
 * @param rotation rotation steps (4 = 90 degrees, 8 = 45 degrees, 16 = 22.5 degrees)
 * @param scale model scale factor
 * @param offsetY model height (from the block floor; the center of the item model sits at this
 *     height)
 */
public record FurnitureDef(
        HarborId id,
        String name,
        String model,
        String baseItem,
        double width,
        double height,
        @Nullable Double seat,
        boolean solid,
        int light,
        int rotation,
        double scale,
        double offsetY) {

    public static final String PREFIX = "furniture/";

    private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private static final Set<Integer> ROTATIONS = Set.of(4, 8, 16);

    public static final ContentType.Entries<FurnitureDef> TYPE =
            new ContentType.Entries<>(
                    "furniture",
                    List.of("furniture/furniture.yml"),
                    PREFIX,
                    FurnitureDef::parse,
                    FurnitureDef::id);

    /** Number of blocks filled with barriers when solid (hitbox height rounded up, at least 1). */
    public int solidCells() {
        return solid ? Math.max(1, (int) Math.ceil(height - 1e-6)) : 0;
    }

    static FurnitureDef parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(
                Set.of(
                        "id",
                        "name",
                        "model",
                        "base_item",
                        "width",
                        "height",
                        "seat",
                        "solid",
                        "light",
                        "rotation",
                        "scale",
                        "offset_y"));
        HarborId id =
                node.require("id")
                        .flatMap(n -> n.asId(PREFIX))
                        .orElse(HarborId.parse(PREFIX + "invalid"));
        String model = node.string("model");
        if (!KEY.matcher(model).matches()) {
            node.error("model은 'harbor:furniture/x' 같은 키여야 합니다: '" + model + "'");
        }
        String baseItem = node.string("base_item", "minecraft:paper");
        if (!KEY.matcher(baseItem).matches()) {
            node.error("base_item은 'minecraft:paper' 같은 키여야 합니다: '" + baseItem + "'");
        }
        double width = range(node, "width", 1.0, 0.1, 4.0);
        double height = range(node, "height", 1.0, 0.1, 4.0);
        Double seat = null;
        if (node.has("seat")) {
            seat = range(node, "seat", 0.5, -0.5, 3.0);
        }
        int rotation = node.integer("rotation", 8);
        if (!ROTATIONS.contains(rotation)) {
            node.error("rotation은 4 · 8 · 16 중 하나여야 합니다: " + rotation);
        }
        return new FurnitureDef(
                id,
                node.string("name"),
                model,
                baseItem,
                width,
                height,
                seat,
                node.bool("solid", false),
                node.integer("light", 0, 0, 15),
                rotation,
                range(node, "scale", 1.0, 0.1, 4.0),
                range(node, "offset_y", 0.5, -1.0, 4.0));
    }

    private static double range(
            YamlNode node, String key, double fallback, double min, double max) {
        double v = node.number(key, fallback);
        if (v < min || v > max) {
            node.error(key + "는 " + min + "~" + max + " 사이여야 합니다: " + v);
            return fallback;
        }
        return v;
    }
}
