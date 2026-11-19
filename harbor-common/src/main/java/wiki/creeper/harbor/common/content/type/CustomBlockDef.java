package wiki.creeper.harbor.common.content.type;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * 커스텀 블록 (furniture/blocks.yml, 추가 제안). 노트블록의 상태(악기 × 음 높이) 하나에 모델 하나를 붙이는 방식이다. 리소스팩 자동 빌드가 {@code
 * minecraft:blockstates/note_block.json}과 블록·아이템 모델을 만들어 넣는다 ({@code FurnishingPack}).
 *
 * <p>state는 한 번 정하면 바꾸지 않는다 (이미 설치한 블록의 모양이 바뀐다). 1~{@value #MAX_STATE}. 0은 바닐라 노트블록 모양이다. powered
 * 상태는 쓰지 않는다 (레드스톤 신호로 모양이 바뀌지 않게 두 상태가 같은 모델을 쓴다).
 *
 * <pre>{@code
 * - {id: block/marble_tile, name: 대리석 타일, state: 1, texture: "harbor:block/marble_tile"}
 * - {id: block/crate, name: 나무 상자, state: 2, top: "harbor:block/crate_top", side: "harbor:block/crate_side"}
 * - {id: block/lantern_post, name: 등대 기둥, state: 3, model: "harbor:block/lantern_post"}
 * - {id: block/dice, name: 주사위, state: 4, textures: {down: "harbor:block/d1", up: "harbor:block/d6",
 *     north: "harbor:block/d2", south: "harbor:block/d5", east: "harbor:block/d3", west: "harbor:block/d4"}}
 * }</pre>
 *
 * @param state 노트블록 상태 번호 (1~399, 블록마다 달라야 한다)
 * @param model 직접 만든 블록 모델 키 (있으면 texture·top·side·bottom은 쓰지 않는다)
 * @param texture 여섯 면 같은 텍스처 키 (cube_all)
 * @param top 윗면 텍스처 (cube_bottom_top, side 필수)
 * @param side 옆면 텍스처
 * @param bottom 아랫면 텍스처 (없으면 top)
 * @param faces 면마다 다른 텍스처 (down·up·north·south·east·west 여섯 개 모두, minecraft:block/cube)
 */
public record CustomBlockDef(
        HarborId id,
        String name,
        int state,
        @Nullable String model,
        @Nullable String texture,
        @Nullable String top,
        @Nullable String side,
        @Nullable String bottom,
        Map<String, String> faces) {

    public static final String PREFIX = "block/";

    /** textures에 적는 면 이름 (블록 모델 cube의 텍스처 변수). */
    public static final List<String> FACES =
            List.of("down", "up", "north", "south", "east", "west");

    public CustomBlockDef {
        faces = Map.copyOf(faces);
    }

    /** 악기 16종 × 음 25개 - 바닐라 모양 1개. */
    public static final int MAX_STATE = 16 * 25 - 1;

    /** 바닐라 노트블록 악기 (블록 아래·위 블록과 무관한 16종, 블록 상태 이름). */
    public static final List<String> INSTRUMENTS =
            List.of(
                    "harp",
                    "basedrum",
                    "snare",
                    "hat",
                    "bass",
                    "flute",
                    "bell",
                    "guitar",
                    "chime",
                    "xylophone",
                    "iron_xylophone",
                    "cow_bell",
                    "didgeridoo",
                    "bit",
                    "banjo",
                    "pling");

    public static final int NOTES = 25;

    private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public static final ContentType.Entries<CustomBlockDef> TYPE =
            new ContentType.Entries<>(
                    "custom_block",
                    List.of("furniture/blocks.yml"),
                    PREFIX,
                    CustomBlockDef::parse,
                    CustomBlockDef::id);

    /** 상태 번호의 악기. */
    public static String instrument(int state) {
        return INSTRUMENTS.get(state / NOTES);
    }

    /** 상태 번호의 음 높이 (0~24). */
    public static int note(int state) {
        return state % NOTES;
    }

    /** 블록 상태 속성 (이름순): {@code instrument=bell,note=3,powered=false}. */
    public static String properties(int state, boolean powered) {
        return "instrument=" + instrument(state) + ",note=" + note(state) + ",powered=" + powered;
    }

    /** 블록 모델 키 (직접 만든 모델이 없으면 자동 생성 경로). */
    public String blockModel() {
        String m = model;
        return m != null ? m : generatedKey();
    }

    /** 아이템 모델(item_model) 키. 리소스팩 빌드가 이 경로에 아이템 정의를 만든다. */
    public String itemModel() {
        return generatedKey();
    }

    private String generatedKey() {
        return id.namespace() + ":generated/block/" + id.name();
    }

    static CustomBlockDef parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(
                Set.of(
                        "id",
                        "name",
                        "state",
                        "model",
                        "texture",
                        "top",
                        "side",
                        "bottom",
                        "textures"));
        HarborId id =
                node.require("id")
                        .flatMap(n -> n.asId(PREFIX))
                        .orElse(HarborId.parse(PREFIX + "invalid"));
        int state = node.integer("state", 0);
        if (state < 1 || state > MAX_STATE) {
            node.error("state는 1~" + MAX_STATE + " 사이여야 합니다 (0은 바닐라 노트블록): " + state);
        }
        String model = key(node, "model");
        String texture = key(node, "texture");
        String top = key(node, "top");
        String side = key(node, "side");
        String bottom = key(node, "bottom");
        Map<String, String> faces = new TreeMap<>();
        node.get("textures")
                .ifPresent(
                        t -> {
                            t.allowOnly(Set.copyOf(FACES));
                            for (String face : FACES) {
                                String raw = key(t, face);
                                if (raw == null) {
                                    t.error(
                                            "textures에는 "
                                                    + String.join(" · ", FACES)
                                                    + " 여섯 면이 모두 있어야 합니다");
                                    return;
                                }
                                faces.put(face, raw);
                            }
                        });
        int ways =
                (model != null ? 1 : 0)
                        + (texture != null ? 1 : 0)
                        + (side != null ? 1 : 0)
                        + (node.has("textures") ? 1 : 0);
        if (ways != 1) {
            node.error("커스텀 블록은 model · texture · (top·side) · textures 중 하나만 적습니다");
        }
        if ((top != null || bottom != null) && side == null) {
            node.error("top·bottom을 쓰면 side도 적어야 합니다");
        }
        if (side != null && top == null) {
            node.error("side를 쓰면 top도 적어야 합니다 (모든 면이 같으면 texture)");
        }
        return new CustomBlockDef(
                id, node.string("name"), state, model, texture, top, side, bottom, faces);
    }

    private static @Nullable String key(YamlNode node, String field) {
        String raw = node.optString(field).orElse(null);
        if (raw != null && !KEY.matcher(raw).matches()) {
            node.error(field + "는 'harbor:block/x' 같은 키여야 합니다: '" + raw + "'");
        }
        return raw;
    }
}
