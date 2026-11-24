package wiki.creeper.harbor.fishing.content;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.content.type.SoundTable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Per-grade fishing presentation presets (fishing/presets.yml, docs 20 3.3 FishingPreset, "4.
 * Fishing presentation & boss fights" chapter 1). For each presentation intensity (0 minimum · 1
 * normal · 2 maximum, /낚시 설정) the items to skip are listed in {@code intensity}. Without the file
 * it proceeds with no presentation (text only).
 *
 * <p>Skippable item names: darkness (legendary bite darkness), title (bite title), shake (a short
 * shake sound instead of screen shake), pillar (light pillar), firework (fireworks), dialog (catch
 * result Dialog), nearby (nearby player notice), jump_viewers (jump scene for nearby players),
 * shadow (shadow), flop (species rig flopping in the hand on catch), trail (cast line trail), mark
 * (bite "!" Text Display above the bobber), tape (legendary measuring animation, falls back to a
 * title), ambience (waiting rumble/particles), rod_bend (rod model bending with tension), screen
 * (post effect and camera overlay of a stage).
 *
 * <p>Proposed additions ("4. Fishing presentation" 1.3): {@code cast_trail} (#RRGGBB dust trail of
 * the bobber in flight, empty for none), {@code hook_swing} (forced swing when hooking, {@code
 * type} whack/stab/none and {@code ticks}), {@code rod_bend_steps} (number of rod bend levels sent
 * as custom_model_data float 0, 0 for off), and per grade {@code mark}/{@code mark_color} (bite
 * mark text and color), {@code measure} (actionbar/title/tape) and {@code wait} (waiting ambience
 * stage played every {@link #WAIT_INTERVAL_TICKS} ticks).
 *
 * <p>{@code sounds}: fishing step sounds independent of grade ("15. Sound" 4.1, proposed). Keys are
 * {@link #SOUND_STEPS}, values are sound keys (harbor:… or vanilla). Omitted steps use {@link
 * #DEFAULT_SOUNDS}; an empty string plays no sound.
 */
public record FishingPresets(
        Map<Grade, GradePreset> grades,
        Map<Integer, Set<String>> skip,
        Map<String, String> sounds,
        @Nullable String castTrail,
        HookSwing hookSwing,
        int rodBendSteps) {

    /** Waiting ambience interval (ticks). */
    public static final int WAIT_INTERVAL_TICKS = 40;

    /** How the size is revealed on catch ("4. Fishing presentation" chapter 1, measure step). */
    public enum Measure {
        ACTIONBAR,
        TITLE,
        TAPE
    }

    /**
     * Forced hand swing on hooking (26.3 swing animation, proposed).
     *
     * @param type whack, stab or none
     * @param ticks swing duration in ticks
     */
    public record HookSwing(String type, int ticks) {
        public static final HookSwing DEFAULT = new HookSwing("whack", 6);

        public boolean enabled() {
            return !type.equals("none") && ticks > 0;
        }
    }

    /** Default step sounds ("15. Sound" 4.1). Fallback vanilla sounds are in sounds.yml. */
    public static final Map<String, String> DEFAULT_SOUNDS =
            Map.ofEntries(
                    Map.entry("cast", "harbor:fishing.cast"),
                    Map.entry("land", "harbor:fishing.bobber_land"),
                    Map.entry("land_lava", "harbor:fishing.lava_cast"),
                    Map.entry("land_ice", "harbor:fishing.ice_hole"),
                    Map.entry("hook", "harbor:fishing.hook"),
                    Map.entry("reel_loop", "harbor:fishing.reel_loop"),
                    Map.entry("tension_warn", "harbor:fishing.tension_warn"),
                    Map.entry("line_snap", "harbor:fishing.line_snap"),
                    Map.entry("reel_in", "harbor:fishing.reel_in"),
                    Map.entry("flop", "harbor:fishing.flop"),
                    Map.entry("escape", ""),
                    Map.entry("escape_big", "harbor:fishing.miss_big"));

    public static final Set<String> SOUND_STEPS = DEFAULT_SOUNDS.keySet();

    public FishingPresets {
        grades = Map.copyOf(grades);
        skip = Map.copyOf(skip);
        sounds = Map.copyOf(sounds);
    }

    public FishingPresets(
            Map<Grade, GradePreset> grades,
            Map<Integer, Set<String>> skip,
            Map<String, String> sounds) {
        this(grades, skip, sounds, null, HookSwing.DEFAULT, 0);
    }

    /** Step sound key. Null if none (no sound). */
    public @Nullable String sound(String step) {
        String key = sounds.getOrDefault(step, DEFAULT_SOUNDS.get(step));
        return key == null || key.isBlank() ? null : key;
    }

    /**
     * Presentation for one grade.
     *
     * @param shadowRig grade default shadow rig used when there is no species rig (rig/fish/<name>)
     *     (e.g. rig/fish/shadow_small, empty for none)
     * @param shadowModel shadow item model drawn as a single ItemDisplay when no rig can be used
     *     (e.g. harbor:shadow/rare)
     * @param shadowScale shadow size multiplier (legendaries are bigger than the boat)
     * @param glowColor shadow identification glow color (#RRGGBB, empty for none)
     * @param mark bite mark text shown above the bobber (empty for none)
     * @param markColor bite mark color (#RRGGBB)
     * @param measure how the size is revealed on catch
     * @param waiting waiting ambience (legendary rumble, darkened water), NONE for none
     */
    public record GradePreset(
            @Nullable String shadowRig,
            String shadowModel,
            double shadowScale,
            @Nullable String glowColor,
            Stage bite,
            Stage caught,
            String mark,
            String markColor,
            Measure measure,
            Stage waiting) {

        public GradePreset(
                @Nullable String shadowRig,
                String shadowModel,
                double shadowScale,
                @Nullable String glowColor,
                Stage bite,
                Stage caught) {
            this(
                    shadowRig,
                    shadowModel,
                    shadowScale,
                    glowColor,
                    bite,
                    caught,
                    "!",
                    "#ffffff",
                    Measure.ACTIONBAR,
                    Stage.NONE);
        }
    }

    /**
     * Presentation for the bite or catch step.
     *
     * @param sound sound key (e.g. harbor:fishing.bite, empty for none). For harbor sounds, Core
     *     HarborSounds picks based on whether the pack is in use
     * @param soundFallback vanilla sound played when the pack sound can't be used (empty means the
     *     fallback in sounds.yml, proposed)
     * @param particle particle name (org.bukkit.Particle, e.g. splash)
     * @param particleCount particle count
     * @param flags darkness · title · shake · pillar · firework · dialog · nearby
     * @param postEffect resource pack post effect shown to this player only (26.3
     *     Player#postEffects, e.g. harbor:legend_bite; null for none, proposed)
     * @param cameraOverlay camera overlay texture shown through a client-only head item (e.g.
     *     harbor:overlay/legend_bite; null for none, proposed)
     * @param effectTicks how long the post effect and camera overlay stay
     */
    public record Stage(
            @Nullable String sound,
            @Nullable String soundFallback,
            @Nullable String particle,
            int particleCount,
            Set<String> flags,
            @Nullable String postEffect,
            @Nullable String cameraOverlay,
            int effectTicks) {

        public static final Stage NONE = new Stage(null, null, null, 0, Set.of(), null, null, 0);

        public boolean has(String flag) {
            return flags.contains(flag);
        }
    }

    public static final GradePreset NONE =
            new GradePreset(null, "minecraft:cod", 1.0, null, Stage.NONE, Stage.NONE);

    public static final FishingPresets EMPTY = new FishingPresets(Map.of(), Map.of(), Map.of());

    public static final ContentType.Single<FishingPresets> TYPE =
            new ContentType.Single<>(
                    "fishing_presets", "fishing/presets.yml", false, FishingPresets::parse);

    private static final Set<String> FLAGS =
            Set.of(
                    "darkness",
                    "title",
                    "shake",
                    "pillar",
                    "firework",
                    "dialog",
                    "nearby",
                    "jump_viewers",
                    "shadow",
                    "flop",
                    "trail",
                    "mark",
                    "tape",
                    "ambience",
                    "rod_bend",
                    "screen");

    public GradePreset of(Grade grade) {
        return grades.getOrDefault(grade, NONE);
    }

    /** Whether that item is shown at this presentation intensity. */
    public boolean shows(int intensity, String flag) {
        return !skip.getOrDefault(intensity, Set.of()).contains(flag);
    }

    static FishingPresets parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(
                Set.of(
                        "grades",
                        "intensity",
                        "sounds",
                        "cast_trail",
                        "hook_swing",
                        "rod_bend_steps"));
        Map<Grade, GradePreset> grades = new EnumMap<>(Grade.class);
        node.get("grades")
                .ifPresent(
                        g ->
                                g.entries()
                                        .forEach(
                                                (key, value) -> {
                                                    Grade grade = grade(key);
                                                    if (grade == null) {
                                                        value.error("알 수 없는 등급: " + key);
                                                    } else {
                                                        grades.put(grade, preset(value, ctx));
                                                    }
                                                }));
        Map<Integer, Set<String>> skip = new HashMap<>();
        node.get("intensity")
                .ifPresent(
                        i ->
                                i.entries()
                                        .forEach(
                                                (key, value) -> {
                                                    int level;
                                                    try {
                                                        level = Integer.parseInt(key);
                                                    } catch (NumberFormatException e) {
                                                        value.error("연출 강도는 0·1·2입니다: " + key);
                                                        return;
                                                    }
                                                    skip.put(level, flags(value.asList()));
                                                }));
        Map<String, String> sounds = new HashMap<>();
        node.get("sounds")
                .ifPresent(
                        m ->
                                m.entries()
                                        .forEach(
                                                (key, value) -> {
                                                    if (!SOUND_STEPS.contains(key)) {
                                                        value.error(
                                                                "알 수 없는 낚시 단계 '"
                                                                        + key
                                                                        + "' ("
                                                                        + String.join(
                                                                                ", ",
                                                                                new java.util
                                                                                        .TreeSet<>(
                                                                                        SOUND_STEPS))
                                                                        + ")");
                                                    } else {
                                                        sounds.put(key, soundKey(value));
                                                    }
                                                }));
        String trail = color(node, "cast_trail", "#dfefff");
        HookSwing swing =
                node.get("hook_swing").map(FishingPresets::swing).orElse(HookSwing.DEFAULT);
        int bend = node.integer("rod_bend_steps", 4, 0, 16);
        return new FishingPresets(grades, skip, sounds, trail, swing, bend);
    }

    private static HookSwing swing(YamlNode node) {
        node.allowOnly(Set.of("type", "ticks"));
        String type = node.string("type", "whack");
        if (!Set.of("whack", "stab", "none").contains(type)) {
            node.error("hook_swing.type은 whack·stab·none 중 하나입니다: " + type);
            type = "whack";
        }
        return new HookSwing(type, node.integer("ticks", 6, 0, 100));
    }

    /** #RRGGBB color (empty string = none). On a bad format, records an error and uses none. */
    private static @Nullable String color(YamlNode node, String key, @Nullable String fallback) {
        if (node.get(key).isEmpty()) {
            return fallback;
        }
        String value = node.optString(key).orElse("");
        if (value.isEmpty()) {
            return null;
        }
        if (!value.matches("#[0-9a-fA-F]{6}")) {
            node.error(key + "는 #RRGGBB 형식이어야 합니다");
            return null;
        }
        return value;
    }

    private static GradePreset preset(YamlNode node, ParseContext ctx) {
        node.allowOnly(
                Set.of(
                        "shadow_rig",
                        "shadow_model",
                        "shadow_scale",
                        "glow",
                        "bite",
                        "catch",
                        "mark",
                        "mark_color",
                        "measure",
                        "wait"));
        String glow = node.optString("glow").orElse(null);
        if (glow != null && !glow.matches("#[0-9a-fA-F]{6}")) {
            node.error("glow는 #RRGGBB 형식이어야 합니다");
            glow = null;
        }
        String rig = node.optString("shadow_rig").orElse(null);
        if (rig != null) {
            HarborId.tryParse(rig)
                    .ifPresentOrElse(
                            id -> ctx.ref("rig", id, node.pos()),
                            () -> node.error("shadow_rig ID 형식이 틀렸습니다: " + rig));
        }
        return new GradePreset(
                rig,
                node.string("shadow_model", "minecraft:cod"),
                node.number("shadow_scale", 1.0),
                glow,
                node.get("bite").map(FishingPresets::stage).orElse(Stage.NONE),
                node.get("catch").map(FishingPresets::stage).orElse(Stage.NONE),
                node.string("mark", "!"),
                Objects.requireNonNullElse(color(node, "mark_color", "#ffffff"), "#ffffff"),
                measure(node),
                node.get("wait").map(FishingPresets::stage).orElse(Stage.NONE));
    }

    private static Measure measure(YamlNode node) {
        String value = node.string("measure", "actionbar");
        for (Measure m : Measure.values()) {
            if (m.name().toLowerCase(java.util.Locale.ROOT).equals(value)) {
                return m;
            }
        }
        node.error("measure는 actionbar·title·tape 중 하나입니다: " + value);
        return Measure.ACTIONBAR;
    }

    private static Stage stage(YamlNode node) {
        node.allowOnly(
                Set.of(
                        "sound",
                        "sound_fallback",
                        "particle",
                        "count",
                        "flags",
                        "post_effect",
                        "camera_overlay",
                        "effect_ticks"));
        return new Stage(
                node.get("sound")
                        .map(FishingPresets::soundKey)
                        .filter(k -> !k.isEmpty())
                        .orElse(null),
                node.get("sound_fallback")
                        .map(FishingPresets::soundKey)
                        .filter(k -> !k.isEmpty())
                        .orElse(null),
                node.optString("particle").orElse(null),
                node.integer("count", 12, 0, 500),
                flags(node.list("flags")),
                resourceKey(node, "post_effect"),
                resourceKey(node, "camera_overlay"),
                node.integer("effect_ticks", 60, 1, 1200));
    }

    /** namespace:path resource key (null if absent). On a bad format, records an error. */
    private static @Nullable String resourceKey(YamlNode node, String key) {
        String value = node.optString(key).orElse("");
        if (value.isEmpty()) {
            return null;
        }
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            node.error(key + " 형식이 아닙니다 (예: harbor:legend_bite): " + value);
            return null;
        }
        return value;
    }

    /** Sound key (empty string = no sound). On a bad format, records an error and returns "". */
    private static String soundKey(YamlNode node) {
        String key = node.isNull() ? "" : node.asString();
        if (!key.isEmpty() && !SoundTable.KEY.matcher(key).matches()) {
            node.error("소리 키 형식이 아닙니다 (예: harbor:fishing.bite): " + key);
            return "";
        }
        return key;
    }

    private static Set<String> flags(List<YamlNode> nodes) {
        Set<String> result = new java.util.HashSet<>();
        for (YamlNode n : nodes) {
            String flag = n.asString();
            if (!FLAGS.contains(flag)) {
                n.error("알 수 없는 연출 항목 '" + flag + "' (" + String.join(", ", FLAGS) + ")");
            } else {
                result.add(flag);
            }
        }
        return Set.copyOf(result);
    }

    private static @Nullable Grade grade(String key) {
        for (Grade g : Grade.values()) {
            if (g.key().equals(key)) {
                return g;
            }
        }
        return null;
    }
}
