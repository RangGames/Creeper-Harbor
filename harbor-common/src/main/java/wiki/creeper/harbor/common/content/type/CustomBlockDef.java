package wiki.creeper.harbor.common.content.type;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
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
 * - id: block/coral_ore
 *   name: 산호 광석
 *   state: 10
 *   texture: "harbor:block/coral_ore"
 *   hardness: 3.0           # 캐는 시간 (바닐라 단단함, 노트블록 0.8 · 돌 1.5 · 철광석 3 · 흑요석 50, -1 = 부술 수 없음)
 *   tool: pickaxe           # 잘 캐지는 도구 (none · axe · pickaxe · shovel · hoe · sword, 기본 axe)
 *   tier: stone             # 곡괭이 등급 (wood · stone · iron · diamond, 이 등급 이상)
 *   requires_tool: true     # 맞는 도구가 아니면 느리고 아무것도 떨어지지 않음
 *   drops:                  # 없으면 블록 자신
 *     - {item: minecraft:prismarine_shard, min: 1, max: 3}
 *     - {item: item/material/coral_piece, chance: 20}
 *   silk_touch: true        # 섬세한 손길이면 drops 대신 블록 자신
 *   exp: {min: 1, max: 3}
 *   sounds: {place: "minecraft:block.stone.place", break: "minecraft:block.stone.break"}
 * - {id: block/driftwood_log, name: 유목, state: 20, top: "...", side: "...", rotation: axis}   # 20·21·22번을 쓴다
 * }</pre>
 *
 * <p>rotation: 놓는 방향에 따라 돌아가는 블록. 방향마다 상태 번호를 하나씩 더 쓴다 (state부터 연속): axis(원목처럼 3방향: y·x·z),
 * horizontal(화로처럼 놓는 사람을 보는 4방향: north·east·south·west), facing(발사기처럼 6방향: + up·down). 모델은
 * north(axis는 세로)를 기준으로 만들고 돌린다.
 *
 * @param state 노트블록 상태 번호 (1~399, 블록마다 달라야 한다)
 * @param model 직접 만든 블록 모델 키 (있으면 texture·top·side·bottom은 쓰지 않는다)
 * @param texture 여섯 면 같은 텍스처 키 (cube_all)
 * @param top 윗면 텍스처 (cube_bottom_top, side 필수)
 * @param side 옆면 텍스처
 * @param bottom 아랫면 텍스처 (없으면 top)
 * @param faces 면마다 다른 텍스처 (down·up·north·south·east·west 여섯 개 모두, minecraft:block/cube)
 * @param rotation 방향 종류
 * @param breaking 캐는 속도·도구
 * @param drops 떨어뜨릴 아이템 (비면 블록 자신)
 * @param silkTouch 섬세한 손길이면 drops 대신 블록 자신
 * @param expMin 경험치 최소
 * @param expMax 경험치 최대
 * @param placeSound 놓을 때 소리 키 (서버가 낸다)
 * @param breakSound 부술 때 소리 키
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
        Map<String, String> faces,
        Rotation rotation,
        Breaking breaking,
        List<Drop> drops,
        boolean silkTouch,
        int expMin,
        int expMax,
        @Nullable String placeSound,
        @Nullable String breakSound) {

    public static final String PREFIX = "block/";

    /** 방향 종류. 방향마다 상태 번호 하나. */
    public enum Rotation {
        NONE("none"),
        AXIS("y,x,z"),
        HORIZONTAL("north,east,south,west"),
        FACING("north,east,south,west,up,down");

        /** 쉼표로 이은 방향 이름 (enum은 변경 불가 필드만 둔다). */
        private final String names;

        Rotation(String names) {
            this.names = names;
        }

        /** 방향 이름 (상태 번호 순서). */
        public List<String> orientations() {
            return List.of(names.split(","));
        }

        /** 블록 상태 파일의 모델 회전 [x, y] (도). */
        public static int[] modelRotation(String orientation) {
            return switch (orientation) {
                case "x" -> new int[] {90, 90};
                case "z", "down" -> new int[] {90, 0};
                case "east" -> new int[] {0, 90};
                case "south" -> new int[] {0, 180};
                case "west" -> new int[] {0, 270};
                case "up" -> new int[] {270, 0};
                default -> new int[] {0, 0};
            };
        }
    }

    /** 잘 캐지는 도구. */
    public enum Tool {
        NONE,
        AXE,
        PICKAXE,
        SHOVEL,
        HOE,
        SWORD
    }

    /** 곡괭이 등급 (이 등급 이상이어야 캐진다). */
    public enum Tier {
        WOOD,
        STONE,
        IRON,
        DIAMOND
    }

    /**
     * 캐는 설정. 기본값(단단함 0.8, 도끼, 도구 없어도 됨)은 바닐라 노트블록과 같아서 속도를 바꾸지 않는다.
     *
     * @param hardness 바닐라 단단함 (0 = 바로 부서짐, 음수 = 부술 수 없음)
     * @param tier 곡괭이 등급 (tool이 pickaxe일 때만)
     */
    public record Breaking(double hardness, Tool tool, @Nullable Tier tier, boolean requiresTool) {

        public static final Breaking NOTE_BLOCK = new Breaking(0.8, Tool.AXE, null, false);

        public boolean vanilla() {
            return equals(NOTE_BLOCK);
        }

        public boolean unbreakable() {
            return hardness < 0;
        }
    }

    /**
     * 떨어뜨릴 아이템 하나.
     *
     * @param item 바닐라 아이템 키 (minecraft:x) 또는 Harbor 아이템 ID (harbor:item/x)
     * @param chance 확률 (%)
     */
    public record Drop(String item, int min, int max, double chance) {

        public boolean vanilla() {
            return item.startsWith("minecraft:");
        }
    }

    /** textures에 적는 면 이름 (블록 모델 cube의 텍스처 변수). */
    public static final List<String> FACES =
            List.of("down", "up", "north", "south", "east", "west");

    public CustomBlockDef {
        faces = Map.copyOf(faces);
        drops = List.copyOf(drops);
    }

    /** 이 블록이 쓰는 상태 번호 (state부터 방향 수만큼). */
    public List<Integer> states() {
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < rotation.orientations().size(); i++) {
            list.add(state + i);
        }
        return list;
    }

    /** 방향 이름의 상태 번호 (없는 이름이면 기본 방향). */
    public int stateOf(String orientation) {
        int i = rotation.orientations().indexOf(orientation);
        return state + Math.max(0, i);
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
                        "textures",
                        "rotation",
                        "hardness",
                        "tool",
                        "tier",
                        "requires_tool",
                        "drops",
                        "silk_touch",
                        "exp",
                        "sounds"));
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
        Rotation rotation = choice(node, "rotation", Rotation.class, Rotation.NONE);
        if (state + rotation.orientations().size() - 1 > MAX_STATE) {
            node.error(
                    "rotation "
                            + rotation.name().toLowerCase(Locale.ROOT)
                            + "은 상태 번호를 "
                            + rotation.orientations().size()
                            + "개 써서 state가 "
                            + MAX_STATE
                            + "를 넘습니다");
        }
        Tool tool = choice(node, "tool", Tool.class, Tool.AXE);
        Tier tier = node.has("tier") ? choice(node, "tier", Tier.class, Tier.WOOD) : null;
        if (tier != null && tool != Tool.PICKAXE) {
            node.error("tier는 tool: pickaxe일 때만 씁니다");
        }
        double hardness = node.number("hardness", 0.8);
        if (hardness > 100) {
            node.error("hardness는 100 이하여야 합니다 (음수 = 부술 수 없음): " + hardness);
        }
        Breaking breaking = new Breaking(hardness, tool, tier, node.bool("requires_tool", false));
        List<Drop> drops = new ArrayList<>();
        for (YamlNode d : node.list("drops")) {
            Drop drop = drop(d, ctx);
            if (drop != null) {
                drops.add(drop);
            }
        }
        int expMin = 0;
        int expMax = 0;
        Optional<YamlNode> exp = node.get("exp");
        if (exp.isPresent()) {
            if (exp.get().isMap()) {
                exp.get().allowOnly(Set.of("min", "max"));
                expMin = exp.get().integer("min", 0, 0, 1000);
                expMax = exp.get().integer("max", expMin, 0, 1000);
            } else {
                expMin = expMax = node.integer("exp", 0, 0, 1000);
            }
            if (expMax < expMin) {
                node.error("exp max는 min 이상이어야 합니다");
            }
        }
        String placeSound = null;
        String breakSound = null;
        Optional<YamlNode> sounds = node.get("sounds");
        if (sounds.isPresent()) {
            sounds.get().allowOnly(Set.of("place", "break"));
            placeSound = key(sounds.get(), "place");
            breakSound = key(sounds.get(), "break");
        }
        return new CustomBlockDef(
                id,
                node.string("name"),
                state,
                model,
                texture,
                top,
                side,
                bottom,
                faces,
                rotation,
                breaking,
                drops,
                node.bool("silk_touch", false),
                expMin,
                expMax,
                placeSound,
                breakSound);
    }

    private static @Nullable Drop drop(YamlNode d, ParseContext ctx) {
        d.allowOnly(Set.of("item", "amount", "min", "max", "chance"));
        String raw = d.string("item");
        String item;
        if (raw.startsWith("minecraft:")) {
            if (!KEY.matcher(raw).matches()) {
                d.error("item은 'minecraft:x' 또는 Harbor 아이템 ID여야 합니다: '" + raw + "'");
                return null;
            }
            item = raw;
        } else {
            Optional<HarborId> id = d.require("item").flatMap(n -> n.asId(ItemId.PREFIX));
            if (id.isEmpty()) {
                return null;
            }
            ctx.ref(ItemDef.TYPE.name(), id.get(), d.pos());
            item = id.get().toString();
        }
        int amount = d.integer("amount", 1, 1, 64);
        int min = d.integer("min", amount, 0, 64);
        int max = d.integer("max", Math.max(min, amount), 0, 64);
        if (max < min) {
            d.error("max는 min 이상이어야 합니다");
        }
        double chance = d.number("chance", 100);
        if (chance <= 0 || chance > 100) {
            d.error("chance는 0보다 크고 100 이하여야 합니다 (%): " + chance);
        }
        return new Drop(item, min, max, chance);
    }

    private static <E extends Enum<E>> E choice(
            YamlNode node, String field, Class<E> type, E fallback) {
        String raw = node.optString(field).orElse(null);
        if (raw == null) {
            return fallback;
        }
        for (E e : type.getEnumConstants()) {
            if (e.name().equalsIgnoreCase(raw)) {
                return e;
            }
        }
        StringBuilder names = new StringBuilder();
        for (E e : type.getEnumConstants()) {
            names.append(names.isEmpty() ? "" : " · ").append(e.name().toLowerCase(Locale.ROOT));
        }
        node.error(field + "는 " + names + " 중 하나여야 합니다: '" + raw + "'");
        return fallback;
    }

    private static @Nullable String key(YamlNode node, String field) {
        String raw = node.optString(field).orElse(null);
        if (raw != null && !KEY.matcher(raw).matches()) {
            node.error(field + "는 'harbor:block/x' 같은 키여야 합니다: '" + raw + "'");
        }
        return raw;
    }
}
