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
 * Custom block (furniture/blocks.yml, proposed). Attaches one model to one note block state
 * (instrument × pitch). The automatic resource pack build generates {@code
 * minecraft:blockstates/note_block.json} and the block/item models ({@code FurnishingPack}).
 *
 * <p>Once set, state never changes (blocks already placed would change appearance). 1~{@value
 * #MAX_STATE}. 0 is the vanilla note block look. The powered state is not used (both states share
 * the same model so redstone signals do not change the look).
 *
 * <pre>{@code
 * - {id: block/marble_tile, name: Marble Tile, state: 1, texture: "harbor:block/marble_tile"}
 * - {id: block/crate, name: Wooden Crate, state: 2, top: "harbor:block/crate_top", side: "harbor:block/crate_side"}
 * - {id: block/lantern_post, name: Lighthouse Post, state: 3, model: "harbor:block/lantern_post"}
 * - {id: block/dice, name: Dice, state: 4, textures: {down: "harbor:block/d1", up: "harbor:block/d6",
 *     north: "harbor:block/d2", south: "harbor:block/d5", east: "harbor:block/d3", west: "harbor:block/d4"}}
 * - id: block/coral_ore
 *   name: Coral Ore
 *   state: 10
 *   texture: "harbor:block/coral_ore"
 *   hardness: 3.0           # mining time (vanilla hardness: note block 0.8, stone 1.5, iron ore 3, obsidian 50, -1 = unbreakable)
 *   tool: pickaxe           # preferred tool (none, axe, pickaxe, shovel, hoe, sword; default axe)
 *   tier: stone             # pickaxe tier (wood, stone, iron, diamond; this tier or higher)
 *   requires_tool: true     # with the wrong tool it is slow and drops nothing
 *   drops:                  # if absent, the block itself
 *     - {item: minecraft:prismarine_shard, min: 1, max: 3}
 *     - {item: item/material/coral_piece, chance: 20}
 *   silk_touch: true        # with Silk Touch, the block itself instead of drops
 *   exp: {min: 1, max: 3}
 *   sounds: {place: "minecraft:block.stone.place", break: "minecraft:block.stone.break"}
 * - {id: block/driftwood_log, name: Driftwood, state: 20, top: "...", side: "...", rotation: axis}   # uses 20, 21, 22
 * }</pre>
 *
 * <p>rotation: a block that rotates with the placement direction. Each direction uses one more
 * state number (consecutive from state): axis (3 directions like logs: y, x, z), horizontal (4
 * directions facing the placer like furnaces: north, east, south, west), facing (6 directions like
 * dispensers: + up, down). Models are built facing north (vertical for axis) and rotated.
 *
 * @param state note block state number (1~399, must be unique per block)
 * @param model custom block model key (if set, texture/top/side/bottom are not used)
 * @param texture texture key for all six faces (cube_all)
 * @param top top texture (cube_bottom_top, side required)
 * @param side side texture
 * @param bottom bottom texture (top if absent)
 * @param faces a different texture per face (all six of down, up, north, south, east, west;
 *     minecraft:block/cube)
 * @param rotation rotation kind
 * @param breaking mining speed and tool
 * @param drops items to drop (the block itself if empty)
 * @param silkTouch with Silk Touch, the block itself instead of drops
 * @param expMin minimum experience
 * @param expMax maximum experience
 * @param placeSound sound key on placement (played by the server)
 * @param breakSound sound key on breaking
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

    /** Rotation kind. One state number per direction. */
    public enum Rotation {
        NONE("none"),
        AXIS("y,x,z"),
        HORIZONTAL("north,east,south,west"),
        FACING("north,east,south,west,up,down");

        /** Comma-joined direction names (enums keep only immutable fields). */
        private final String names;

        Rotation(String names) {
            this.names = names;
        }

        /** Direction names (in state number order). */
        public List<String> orientations() {
            return List.of(names.split(","));
        }

        /** Model rotation [x, y] in the blockstate file (degrees). */
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

    /** Preferred tool. */
    public enum Tool {
        NONE,
        AXE,
        PICKAXE,
        SHOVEL,
        HOE,
        SWORD
    }

    /** Pickaxe tier (this tier or higher is needed to mine). */
    public enum Tier {
        WOOD,
        STONE,
        IRON,
        DIAMOND
    }

    /**
     * Mining settings. The defaults (hardness 0.8, axe, no tool required) match the vanilla note
     * block, so the speed is unchanged.
     *
     * @param hardness vanilla hardness (0 = breaks instantly, negative = unbreakable)
     * @param tier pickaxe tier (only when tool is pickaxe)
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
     * One item to drop.
     *
     * @param item vanilla item key (minecraft:x) or Harbor item ID (harbor:item/x)
     * @param chance probability (%)
     */
    public record Drop(String item, int min, int max, double chance) {

        public boolean vanilla() {
            return item.startsWith("minecraft:");
        }
    }

    /** Face names used in textures (texture variables of the cube block model). */
    public static final List<String> FACES =
            List.of("down", "up", "north", "south", "east", "west");

    public CustomBlockDef {
        faces = Map.copyOf(faces);
        drops = List.copyOf(drops);
    }

    /** State numbers this block uses (from state, one per direction). */
    public List<Integer> states() {
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < rotation.orientations().size(); i++) {
            list.add(state + i);
        }
        return list;
    }

    /** State number for a direction name (default direction for unknown names). */
    public int stateOf(String orientation) {
        int i = rotation.orientations().indexOf(orientation);
        return state + Math.max(0, i);
    }

    /** 16 instruments × 25 notes - 1 vanilla look. */
    public static final int MAX_STATE = 16 * 25 - 1;

    /**
     * Vanilla note block instruments (the 16 not tied to the blocks below/above, by blockstate
     * name).
     */
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

    /** Instrument of a state number. */
    public static String instrument(int state) {
        return INSTRUMENTS.get(state / NOTES);
    }

    /** Pitch of a state number (0~24). */
    public static int note(int state) {
        return state % NOTES;
    }

    /** Blockstate properties (sorted by name): {@code instrument=bell,note=3,powered=false}. */
    public static String properties(int state, boolean powered) {
        return "instrument=" + instrument(state) + ",note=" + note(state) + ",powered=" + powered;
    }

    /** Block model key (auto-generated path when there is no custom model). */
    public String blockModel() {
        String m = model;
        return m != null ? m : generatedKey();
    }

    /**
     * Item model (item_model) key. The resource pack build creates the item definition at this
     * path.
     */
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
