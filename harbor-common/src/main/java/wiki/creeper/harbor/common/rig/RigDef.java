package wiki.creeper.harbor.common.rig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RigId;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Display rig definition (rigs/*.yml, proposed format). The rig of '16. Plugin design' 5.10: a bone
 * hierarchy, display parts attached per bone (ItemDisplay item_model or BlockDisplay block), and
 * keyframe animations.
 *
 * <p>Rig JSON from fish model work (harbor_rig_v1) is converted to this format by {@code
 * harbor-tools rig-import} and placed in rigs/fish/&lt;name&gt;.yml (proposed). Units are blocks,
 * degrees and ticks.
 *
 * <p>Instead of a keyframe list, a channel can also be written as evenly spaced samples ({@code
 * {step: 2, v: [x0, y0, z0, x1, y1, z1, …]}}, one value every step ticks from tick 0, the last
 * value at length ticks). Samples are linearly interpolated. The converter uses this to copy the
 * resource pack's baked frames as-is. An animation's {@code events} emit named signals at set ticks
 * (e.g. ink, hiss sounds; Core {@code RigInstance.onEvent}).
 *
 * <pre>
 * - id: rig/fish/harbor_mackerel
 *   forward: -z                 # model head direction (prototype fish models face −Z)
 *   default_animation: swim
 *   bones:
 *     - name: body
 *       part: {item_model: harbor:rig/fish/harbor_mackerel/body}
 *     - name: tail
 *       parent: body
 *       pivot: [0, 0, 0.25]
 *       part: {item_model: harbor:rig/fish/harbor_mackerel/tail, offset: [0, 0, 0.12]}
 *   animations:
 *     swim:
 *       length: 20
 *       mode: loop
 *       bones:
 *         tail:
 *           rotation:
 *             - {t: 0, v: [0, -25, 0], ease: ease_in_out}
 *             - {t: 10, v: [0, 25, 0], ease: ease_in_out}
 *             - {t: 20, v: [0, -25, 0]}
 * </pre>
 *
 * @param scale overall rig scale factor
 * @param forwardNegZ whether the model head faces −Z. If true, yaw + 180° and the pitch sign is
 *     flipped when given a heading (prototype correction)
 * @param length body length (blocks, proposed). Used by Wildlife school size and spawn checks. 0
 *     means unspecified
 * @param viewRange Display view_range multiplier (default 1.0)
 * @param bones ordered so that a parent always precedes its children
 * @param defaultAnimation animation to return to right after spawn and after a one-shot finishes.
 *     Rest pose if absent
 */
public record RigDef(
        RigId id,
        double scale,
        boolean forwardNegZ,
        double length,
        double viewRange,
        List<Bone> bones,
        Map<String, Animation> animations,
        @Nullable String defaultAnimation) {

    /**
     * One bone. The rest pose is the pivot position, rotation (Euler, degrees) and scale in parent
     * space.
     *
     * @param parent parent bone index (−1 means the rig origin)
     */
    public record Bone(
            String name,
            int parent,
            Vec3 pivot,
            Vec3 rotation,
            Vec3 scale,
            @Nullable Part part,
            @Nullable Hitbox hitbox) {}

    /** Display part kind. */
    public enum PartKind {
        ITEM,
        BLOCK
    }

    /** ItemDisplay render mode. Names match Bukkit ItemDisplay.ItemDisplayTransform. */
    public enum ItemTransform implements Keyed {
        NONE,
        THIRDPERSON_LEFTHAND,
        THIRDPERSON_RIGHTHAND,
        FIRSTPERSON_LEFTHAND,
        FIRSTPERSON_RIGHTHAND,
        HEAD,
        GUI,
        GROUND,
        FIXED;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /**
     * Display part attached to a bone.
     *
     * @param model item_model key for ITEM (e.g. {@code harbor:rig/fish/x/body}), block state for
     *     BLOCK (e.g. {@code minecraft:kelp_plant})
     * @param offset part position/rotation/scale relative to the bone
     * @param flip whether to apply the item_display 180° Y-flip correction (default true for ITEM,
     *     always false for BLOCK)
     * @param brightness fixed brightness [block, sky] (0~15). Ambient brightness if absent (fixed
     *     for e.g. fish in lava, '17-3' 9.4)
     * @param glowColor glow outline color (0xRRGGBB). Default color used by {@code setPartGlow},
     *     independent of default glowing
     */
    public record Part(
            PartKind kind,
            String model,
            Xform offset,
            ItemTransform transform,
            boolean flip,
            @Nullable Brightness brightness,
            int glowColor) {}

    /** Fixed brightness (0~15). */
    public record Brightness(int block, int sky) {}

    /**
     * Part hitbox (per-part Boss Interaction, '8. System details' chapter 9). The box center sits
     * at the offset position relative to the bone.
     *
     * @param width horizontal width/depth (blocks)
     * @param height height (blocks)
     */
    public record Hitbox(double width, double height, Vec3 offset) {}

    /** Playback mode. */
    public enum LoopMode implements Keyed {
        /** Repeats from the start when finished. */
        LOOP,
        /** Plays once and returns to the default animation (default_animation). */
        ONCE,
        /** Plays once and holds the last pose. */
        HOLD;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /** One animation. length in ticks, tracks is bone index → channel, events in tick order. */
    public record Animation(
            String name,
            int length,
            LoopMode mode,
            Map<Integer, BoneTracks> tracks,
            List<Event> events) {

        public Animation {
            tracks = Map.copyOf(tracks);
            events = List.copyOf(events);
        }

        public Animation(String name, int length, LoopMode mode, Map<Integer, BoneTracks> tracks) {
            this(name, length, mode, tracks, List.of());
        }
    }

    /**
     * Signal emitted at tick (ticks since animation start) during an animation (proposed). E.g.
     * {@code ink}, {@code hiss}.
     */
    public record Event(int tick, String name) {}

    /**
     * Channel of one bone. position is added to the pivot, rotation is added to the base rotation
     * (Euler), scale multiplies the base scale.
     */
    public record BoneTracks(Track position, Track rotation, Track scale) {}

    public static final ContentType.Entries<RigDef> TYPE =
            new ContentType.Entries<>(
                    "rig",
                    List.of("rigs/*.yml", "rigs/fish/*.yml", "rigs/boss/*.yml"),
                    RigId.PREFIX,
                    RigDef::parse,
                    r -> r.id().id());

    private static final HarborId PLACEHOLDER = HarborId.parse("rig/invalid");
    private static final Set<String> FIELDS =
            Set.of(
                    "id",
                    "scale",
                    "forward",
                    "length",
                    "view_range",
                    "bones",
                    "animations",
                    "default_animation");
    private static final Set<String> BONE_FIELDS =
            Set.of("name", "parent", "pivot", "rotation", "scale", "part", "hitbox");
    private static final Set<String> PART_FIELDS =
            Set.of(
                    "item_model",
                    "block",
                    "offset",
                    "rotation",
                    "scale",
                    "transform",
                    "flip",
                    "brightness",
                    "glow_color");
    private static final Set<String> ANIM_FIELDS = Set.of("length", "mode", "bones", "events");
    private static final Set<String> SAMPLED_FIELDS = Set.of("step", "v");
    private static final Set<String> EVENT_FIELDS = Set.of("t", "name");
    private static final Set<String> CHANNELS = Set.of("position", "rotation", "scale");
    private static final Set<String> KEY_FIELDS = Set.of("t", "v", "ease");

    /** Number of bones with parts. */
    public int partCount() {
        int n = 0;
        for (Bone bone : bones) {
            if (bone.part() != null) {
                n++;
            }
        }
        return n;
    }

    public int hitboxCount() {
        int n = 0;
        for (Bone bone : bones) {
            if (bone.hitbox() != null) {
                n++;
            }
        }
        return n;
    }

    public Optional<Animation> animation(String name) {
        return Optional.ofNullable(animations.get(name));
    }

    // ------------------------------------------------------------------ Parsing

    static RigDef parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(FIELDS);
        HarborId rawId = node.require("id").flatMap(n -> n.asId(RigId.PREFIX)).orElse(PLACEHOLDER);

        double scale = node.number("scale", 1.0);
        if (scale <= 0) {
            node.error("scale은 0보다 커야 합니다");
        }
        String forward = node.string("forward", "-z");
        if (!forward.equals("-z") && !forward.equals("+z")) {
            node.error("forward는 -z 또는 +z여야 합니다: '" + forward + "'");
        }
        double length = node.number("length", 0);
        if (length < 0) {
            node.error("length는 0 이상이어야 합니다");
        }
        double viewRange = node.number("view_range", 1.0);
        if (viewRange <= 0) {
            node.error("view_range는 0보다 커야 합니다");
        }

        List<Bone> bones = new ArrayList<>();
        Map<String, Integer> boneIndex = new HashMap<>();
        List<YamlNode> boneNodes = node.list("bones");
        if (boneNodes.isEmpty()) {
            node.error("bones에 뼈가 하나 이상 있어야 합니다");
        }
        for (YamlNode b : boneNodes) {
            Bone bone = parseBone(b, boneIndex);
            if (boneIndex.containsKey(bone.name())) {
                b.error("뼈 이름 '" + bone.name() + "'가 중복됩니다");
                continue;
            }
            boneIndex.put(bone.name(), bones.size());
            bones.add(bone);
        }
        if (!bones.isEmpty() && bones.stream().allMatch(b -> b.part() == null)) {
            node.error("표시 부품(part)이 있는 뼈가 하나도 없습니다");
        }

        Map<String, Animation> animations = new LinkedHashMap<>();
        node.get("animations")
                .ifPresent(
                        anims ->
                                anims.entries()
                                        .forEach(
                                                (name, a) ->
                                                        animations.put(
                                                                name,
                                                                parseAnimation(
                                                                        name, a, boneIndex))));

        String defaultAnimation = node.optString("default_animation").orElse(null);
        if (defaultAnimation != null && !animations.containsKey(defaultAnimation)) {
            node.get("default_animation")
                    .orElseThrow()
                    .error("default_animation '" + defaultAnimation + "'가 animations에 없습니다");
        }

        return new RigDef(
                new RigId(rawId),
                scale,
                forward.equals("-z"),
                length,
                viewRange,
                List.copyOf(bones),
                Map.copyOf(animations),
                defaultAnimation);
    }

    private static Bone parseBone(YamlNode b, Map<String, Integer> known) {
        b.allowOnly(BONE_FIELDS);
        String name = b.string("name");
        int parent = -1;
        Optional<YamlNode> parentNode = b.get("parent");
        if (parentNode.isPresent()) {
            String parentName = parentNode.get().asString();
            Integer index = known.get(parentName);
            if (index == null) {
                parentNode.get().error("parent '" + parentName + "'는 이 뼈보다 앞에 정의된 뼈여야 합니다");
            } else {
                parent = index;
            }
        }
        Vec3 pivot = vec(b, "pivot", Vec3.ZERO);
        Vec3 rotation = vec(b, "rotation", Vec3.ZERO);
        Vec3 scale = scale(b, "scale");

        Part part = b.get("part").map(RigDef::parsePart).orElse(null);
        Hitbox hitbox = null;
        Optional<YamlNode> hb = b.get("hitbox");
        if (hb.isPresent()) {
            YamlNode h = hb.get();
            h.allowOnly(Set.of("width", "height", "offset"));
            double width = h.number("width");
            double height = h.number("height");
            if (width <= 0 || height <= 0) {
                h.error("hitbox width·height는 0보다 커야 합니다");
            }
            hitbox = new Hitbox(width, height, vec(h, "offset", Vec3.ZERO));
        }
        return new Bone(name, parent, pivot, rotation, scale, part, hitbox);
    }

    private static Part parsePart(YamlNode p) {
        p.allowOnly(PART_FIELDS);
        boolean hasItem = p.has("item_model");
        boolean hasBlock = p.has("block");
        if (hasItem == hasBlock) {
            p.error("part에는 item_model과 block 중 하나만 있어야 합니다");
        }
        PartKind kind = hasBlock ? PartKind.BLOCK : PartKind.ITEM;
        String model = hasBlock ? p.string("block") : p.string("item_model", "");
        Xform offset =
                new Xform(
                        vec(p, "offset", Vec3.ZERO),
                        Quat.euler(vec(p, "rotation", Vec3.ZERO)),
                        scale(p, "scale"));
        ItemTransform transform =
                p.get("transform")
                        .flatMap(t -> t.asEnum(ItemTransform.class))
                        .orElse(ItemTransform.NONE);
        boolean flip = p.bool("flip", kind == PartKind.ITEM);
        if (kind == PartKind.BLOCK && flip) {
            p.error("block 부품에는 flip을 쓸 수 없습니다 (item_display 보정 전용)");
        }
        Brightness brightness = null;
        Optional<YamlNode> br = p.get("brightness");
        if (br.isPresent()) {
            List<YamlNode> pair = br.get().asList();
            if (pair.size() != 2) {
                br.get().error("brightness는 [블록, 하늘] 두 값이어야 합니다");
            } else {
                brightness = new Brightness(pair.get(0).asInt(), pair.get(1).asInt());
                if (brightness.block() < 0
                        || brightness.block() > 15
                        || brightness.sky() < 0
                        || brightness.sky() > 15) {
                    br.get().error("brightness 값은 0~15여야 합니다");
                }
            }
        }
        int glow = 0xFFFFFF;
        Optional<YamlNode> gc = p.get("glow_color");
        if (gc.isPresent()) {
            String raw = gc.get().asString();
            String hex = raw.startsWith("#") ? raw.substring(1) : raw;
            try {
                glow = Integer.parseInt(hex, 16);
                if (hex.length() != 6) {
                    gc.get().error("glow_color는 #RRGGBB 형식이어야 합니다: '" + raw + "'");
                }
            } catch (NumberFormatException e) {
                gc.get().error("glow_color는 #RRGGBB 형식이어야 합니다: '" + raw + "'");
            }
        }
        return new Part(kind, model, offset, transform, flip, brightness, glow);
    }

    private static Animation parseAnimation(
            String name, YamlNode a, Map<String, Integer> boneIndex) {
        a.allowOnly(ANIM_FIELDS);
        int length = a.integer("length");
        if (length < 1) {
            a.error("length는 1틱 이상이어야 합니다");
        }
        LoopMode mode = a.get("mode").flatMap(m -> m.asEnum(LoopMode.class)).orElse(LoopMode.LOOP);
        Map<Integer, BoneTracks> tracks = new HashMap<>();
        a.get("bones")
                .ifPresent(
                        bonesNode ->
                                bonesNode
                                        .entries()
                                        .forEach(
                                                (boneName, channels) -> {
                                                    Integer index = boneIndex.get(boneName);
                                                    if (index == null) {
                                                        channels.error(
                                                                "동작 '"
                                                                        + name
                                                                        + "'의 뼈 '"
                                                                        + boneName
                                                                        + "'가 bones에 없습니다");
                                                        return;
                                                    }
                                                    channels.allowOnly(CHANNELS);
                                                    tracks.put(
                                                            index,
                                                            new BoneTracks(
                                                                    track(
                                                                            channels,
                                                                            "position",
                                                                            length),
                                                                    track(
                                                                            channels,
                                                                            "rotation",
                                                                            length),
                                                                    track(
                                                                            channels, "scale",
                                                                            length)));
                                                }));
        List<Event> events = new ArrayList<>();
        for (YamlNode e : a.list("events")) {
            e.allowOnly(EVENT_FIELDS);
            int t = e.integer("t");
            if (t < 0 || t > length) {
                e.error("이벤트 시각 t는 0~" + length + " 사이여야 합니다: " + t);
            }
            events.add(new Event(t, e.string("name")));
        }
        events.sort(Comparator.comparingInt(Event::tick));
        return new Animation(name, length, mode, tracks, events);
    }

    private static Track track(YamlNode channels, String key, int length) {
        Optional<YamlNode> sampled = channels.get(key).filter(YamlNode::isMap);
        if (sampled.isPresent()) {
            return sampledTrack(sampled.get(), length);
        }
        List<YamlNode> keyNodes = channels.list(key);
        if (keyNodes.isEmpty()) {
            return Track.EMPTY;
        }
        List<Track.Keyframe> keys = new ArrayList<>();
        double last = -1;
        for (YamlNode k : keyNodes) {
            k.allowOnly(KEY_FIELDS);
            double t = k.number("t");
            if (t < 0 || t > length) {
                k.error("키 시각 t는 0~" + length + " 사이여야 합니다: " + t);
            }
            if (t <= last) {
                k.error("키 시각 t는 앞 키보다 커야 합니다: " + t);
            }
            last = t;
            Vec3 value =
                    key.equals("scale")
                            ? scale(k, "v")
                            : k.get("v").map(RigDef::vec3).orElseGet(() -> missing(k, "v"));
            Easing ease = k.get("ease").flatMap(e -> e.asEnum(Easing.class)).orElse(Easing.LINEAR);
            keys.add(new Track.Keyframe(t, value, ease));
        }
        return new Track(keys);
    }

    /**
     * Evenly spaced sample channel: {@code {step: n, v: [x0, y0, z0, …]}}. Value count is (length /
     * step + 1) × 3.
     */
    private static Track sampledTrack(YamlNode node, int length) {
        node.allowOnly(SAMPLED_FIELDS);
        int step = node.integer("step", 1);
        if (step < 1 || length % step != 0) {
            node.error("step은 1 이상이고 length(" + length + ")의 약수여야 합니다: " + step);
            return Track.EMPTY;
        }
        List<YamlNode> values = node.list("v");
        int samples = length / step + 1;
        if (values.size() != samples * 3) {
            node.error(
                    "v에는 "
                            + samples
                            + "개 샘플 × 3 = "
                            + samples * 3
                            + "개 값이 있어야 합니다: "
                            + values.size());
            return Track.EMPTY;
        }
        List<Track.Keyframe> keys = new ArrayList<>(samples);
        for (int i = 0; i < samples; i++) {
            Vec3 v =
                    new Vec3(
                            values.get(i * 3).asDouble(),
                            values.get(i * 3 + 1).asDouble(),
                            values.get(i * 3 + 2).asDouble());
            keys.add(new Track.Keyframe(i * (double) step, v, Easing.LINEAR));
        }
        return new Track(keys);
    }

    private static Vec3 missing(YamlNode node, String key) {
        node.error("필수 필드 '" + key + "'가 없습니다");
        return Vec3.ZERO;
    }

    private static Vec3 vec(YamlNode node, String key, Vec3 fallback) {
        return node.get(key).map(RigDef::vec3).orElse(fallback);
    }

    /**
     * Scale: one number (uniform) or [x, y, z]. 1 if absent. A negative value mirrors that axis
     * (left/right fins, etc.).
     */
    private static Vec3 scale(YamlNode node, String key) {
        Optional<YamlNode> value = node.get(key);
        if (value.isEmpty()) {
            return Vec3.ONE;
        }
        YamlNode v = value.get();
        if (v.isScalar()) {
            double k = v.asDouble();
            return new Vec3(k, k, k);
        }
        return vec3(v);
    }

    private static Vec3 vec3(YamlNode node) {
        List<YamlNode> xyz = node.asList();
        if (xyz.size() != 3) {
            node.error("[x, y, z] 세 값이어야 합니다");
            return Vec3.ZERO;
        }
        return new Vec3(xyz.get(0).asDouble(), xyz.get(1).asDouble(), xyz.get(2).asDouble());
    }
}
