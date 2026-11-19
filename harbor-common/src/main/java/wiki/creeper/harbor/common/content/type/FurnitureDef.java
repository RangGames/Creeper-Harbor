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
 * 가구 (furniture/furniture.yml, 추가 제안). ItemDisplay(모델) + Interaction(클릭 판정)으로 놓는다. 모델은 리소스팩의 아이템
 * 모델이다.
 *
 * <pre>{@code
 * - {id: furniture/fishing_chair, name: 낚시 의자, model: "harbor:decor/fishing_chair", seat: 0.45, solid: true}
 * - {id: furniture/table_lamp, name: 탁상 등, model: "harbor:furniture/table_lamp", width: 0.5, height: 0.8, light: 12}
 * }</pre>
 *
 * @param model 아이템 모델 키 (팩에 없으면 base_item 모양)
 * @param baseItem 모델이 없을 때 보일 바닐라 아이템
 * @param width 클릭 판정 너비 (블록)
 * @param height 클릭 판정 높이 (블록)
 * @param seat 앉는 높이 (발 위치, 블록 바닥 기준). null이면 앉을 수 없다
 * @param solid 부딪히는지 (판정 높이만큼 방벽 블록을 채운다. 한 칸 너비)
 * @param light 밝기 0~15 (빛 블록. solid면 가구 바로 위 칸)
 * @param rotation 회전 단계 (4 = 90도, 8 = 45도, 16 = 22.5도)
 * @param scale 모델 크기 배율
 * @param offsetY 모델 높이 (블록 바닥 기준, 아이템 모델 가운데가 이 높이에 온다)
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

    /** solid일 때 방벽으로 채우는 칸 수 (판정 높이 올림, 최소 1). */
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
