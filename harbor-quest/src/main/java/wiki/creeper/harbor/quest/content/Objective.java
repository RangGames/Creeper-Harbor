package wiki.creeper.harbor.quest.content;

import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Quest objective ('11. Data Design' 2.6 + 2.9 additional fields).
 *
 * @param key unique objective key. Used to carry progress over on reload (if absent, the order
 *     number "o<n>")
 * @param type objective type (catch · deliver · visit · talk · donate · collection · job_level ·
 *     job_tier · boss · chart_percent · use_item · flag · cutscene · affinity · reputation, or a
 *     type registered by another module)
 * @param count required amount. Completion rate (%) for collection and chart_percent; the value to
 *     reach for job_level, job_tier, affinity and reputation
 * @param qualityMin minimum quality (catch, deliver, donate)
 * @param sizeMin minimum size in cm (catch, deliver)
 * @param region catch region condition (catch)
 * @param npc deliver/talk target (end_npc if absent)
 * @param source whether catch includes traps (default rod)
 * @param waypoint tracking marker location
 * @param text objective text (empty means the type's default text)
 * @param scope collection scope (season · total)
 * @param node talk objective: the quest:talk action must run at this dialog node (empty means
 *     clicking the NPC)
 * @param server flag objective: whether it is a server flag
 */
public record Objective(
        String key,
        String type,
        Target target,
        int count,
        @Nullable Quality qualityMin,
        double sizeMin,
        @Nullable RegionId region,
        @Nullable NpcId npc,
        CatchFilter source,
        @Nullable Waypoint waypoint,
        @Nullable String text,
        String scope,
        @Nullable String node,
        boolean server) {

    /**
     * Tracking marker location. For an NPC, the NPC's current location (updated on every move for
     * wanderers); otherwise coordinates.
     *
     * @param world content world ID (empty means the player's current world)
     */
    public record Waypoint(
            @Nullable NpcId npc, @Nullable String world, double x, double y, double z) {}

    /** Built-in objective types. Other modules add more through ObjectiveTypeRegistry. */
    public static final Set<String> BUILTIN =
            Set.of(
                    "catch",
                    "deliver",
                    "visit",
                    "talk",
                    "donate",
                    "collection",
                    "job_level",
                    "job_tier",
                    "boss",
                    "chart_percent",
                    "use_item",
                    "flag",
                    "cutscene",
                    "affinity",
                    "reputation");

    /**
     * Types whose progress value is a "reached value" (overwritten with the current value instead
     * of counted).
     */
    public static final Set<String> GAUGES =
            Set.of(
                    "collection",
                    "job_level",
                    "job_tier",
                    "chart_percent",
                    "affinity",
                    "reputation");

    private static final Pattern KEY = Pattern.compile("[a-z0-9_]{1,32}");
    private static final Pattern TYPE = Pattern.compile("[a-z0-9_]{1,32}");
    private static final Pattern FLAG = Pattern.compile("[a-z0-9_]+(/[a-z0-9_]+)*");

    private static final Set<String> FIELDS =
            Set.of(
                    "key",
                    "type",
                    "target",
                    "count",
                    "quality_min",
                    "size_min",
                    "region",
                    "npc",
                    "source",
                    "waypoint",
                    "text",
                    "scope",
                    "node",
                    "server");

    /**
     * Whether this type counts a current amount (excluding deliver, which checks the inventory on
     * turn-in).
     */
    public boolean tracked() {
        return !type.equals("deliver");
    }

    public boolean gauge() {
        return GAUGES.contains(type);
    }

    /** Types whose value in the short form is an amount, not a target ({@code - job_level: 5}). */
    private static final Set<String> VALUE_IS_COUNT = Set.of("job_level", "job_tier", "reputation");

    /**
     * One objective line. It can be written two ways:
     *
     * <pre>{@code
     * - {key: mackerel, type: catch, target: fish/harbor_mackerel, count: 3}   # full form
     * - {catch: fish/harbor_mackerel, count: 3}                                # short form (proposed)
     * - {talk: npc/bawi}
     * - {job_level: 5}                                                         # type whose value is an amount
     * }</pre>
     *
     * The short form uses a built-in type name as the key and omits type and target. An empty key
     * is built from the type and target (catch_harbor_mackerel). In the full form an empty key is
     * the order number o&lt;n&gt; (kept unchanged to match existing progress).
     */
    static Objective parse(YamlNode n, int index, ParseContext ctx) {
        String shorthand = shorthandType(n);
        if (shorthand == null) {
            n.allowOnly(FIELDS);
        } else {
            Set<String> allowed = new java.util.HashSet<>(FIELDS);
            allowed.remove("type");
            allowed.add(shorthand);
            n.allowOnly(allowed);
        }
        boolean valueIsCount = shorthand != null && VALUE_IS_COUNT.contains(shorthand);
        String shortValue =
                shorthand == null
                        ? ""
                        : n.get(shorthand).map(YamlNode::asString).orElse("").strip();
        if (shorthand != null && !valueIsCount && n.has("target")) {
            n.error("축약형(" + shorthand + ": …)에는 target을 따로 쓰지 않습니다");
        }
        if (valueIsCount && n.has("count")) {
            n.error("축약형(" + shorthand + ": 수)에는 count를 따로 쓰지 않습니다");
        }
        String type = shorthand != null ? shorthand : n.string("type");
        if (!TYPE.matcher(type).matches()) {
            n.error("목표 type 형식이 틀렸습니다: " + type);
        }
        String rawTarget =
                shorthand != null && !valueIsCount
                        ? (shortValue.isEmpty() ? "any" : shortValue)
                        : n.string("target", "any");
        String key =
                n.string(
                        "key",
                        shorthand != null
                                ? autoKey(type, valueIsCount ? "" : rawTarget)
                                : "o" + (index + 1));
        if (!KEY.matcher(key).matches()) {
            n.error("목표 key는 소문자·숫자·밑줄 1~32자여야 합니다: " + key);
        }
        Target target;
        if (type.equals("flag")) {
            if (!FLAG.matcher(rawTarget).matches()) {
                n.error("flag 목표의 target은 플래그 이름이어야 합니다: " + rawTarget);
            }
            target = Target.name(rawTarget);
        } else {
            YamlNode targetNode = n.get(shorthand != null ? shorthand : "target").orElse(n);
            target = Target.parse(rawTarget, targetNode.pos(), ctx, targetNode::error);
        }
        checkTarget(n, type, target);
        int count =
                valueIsCount
                        ? n.integer(java.util.Objects.requireNonNull(shorthand), 1, 1, 1_000_000)
                        : n.integer("count", 1, 1, 1_000_000);
        if ("visit".equals(type) && count != 1) {
            // it goes up by 1 each time the player moves to another block inside the region, so 2
            // or
            // more would be filled just by entering and walking a few blocks
            n.error("visit 목표의 count는 1이어야 합니다 (방문 여부만 셉니다)");
        }
        Quality quality = null;
        if (n.has("quality_min")) {
            int stars = n.integer("quality_min", 1, Quality.MIN, Quality.MAX);
            if (stars >= Quality.MIN && stars <= Quality.MAX) {
                quality = Quality.of(stars);
            }
        }
        RegionId region =
                n.get("region")
                        .flatMap(r -> r.asId(RegionId.PREFIX))
                        .map(
                                id -> {
                                    ctx.ref("region", id, n.pos());
                                    return new RegionId(id);
                                })
                        .orElse(null);
        NpcId npc =
                n.get("npc")
                        .flatMap(r -> r.asId(NpcId.PREFIX))
                        .map(
                                id -> {
                                    ctx.ref("npc", id, n.pos());
                                    return new NpcId(id);
                                })
                        .orElse(null);
        CatchFilter source =
                n.has("source")
                        ? n.enumValue("source", CatchFilter.class).orElse(CatchFilter.ROD)
                        : CatchFilter.ROD;
        Waypoint waypoint = n.get("waypoint").map(w -> waypoint(w, ctx)).orElse(null);
        String scope = n.string("scope", "season");
        if (!scope.equals("season") && !scope.equals("total")) {
            n.error("scope는 season 또는 total이어야 합니다: " + scope);
        }
        return new Objective(
                key,
                type,
                target,
                count,
                quality,
                n.number("size_min", 0),
                region,
                npc,
                source,
                waypoint,
                n.optString("text").orElse(null),
                scope,
                n.optString("node").orElse(null),
                n.bool("server", false));
    }

    /** If there is no type and exactly one key is a built-in type name, that type (short form). */
    static @Nullable String shorthandType(YamlNode n) {
        if (!n.isMap() || n.has("type")) {
            return null;
        }
        String found = null;
        for (String k : n.entries().keySet()) {
            if (BUILTIN.contains(k)) {
                if (found != null) {
                    n.error("목표 하나에 유형을 두 개 쓸 수 없습니다: " + found + ", " + k);
                    return found;
                }
                found = k;
            }
        }
        return found;
    }

    /**
     * Default key for the short form: type_targetName (last part of the ID, letters, digits and
     * underscores only, up to 32 characters).
     */
    public static String autoKey(String type, String rawTarget) {
        String t = rawTarget.strip().toLowerCase(java.util.Locale.ROOT);
        int slash = t.lastIndexOf('/');
        if (slash >= 0) {
            t = t.substring(slash + 1);
        }
        t = t.replaceAll("[^a-z0-9_]+", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
        String key = t.isEmpty() || t.equals("any") ? type : type + "_" + t;
        return key.length() > 32 ? key.substring(0, 32).replaceAll("_$", "") : key;
    }

    private static void checkTarget(YamlNode n, String type, Target target) {
        switch (type) {
            case "catch", "donate" -> {
                if (target.kind() == Target.Kind.ID && !target.isId("fish")) {
                    n.error(type + " 목표의 target은 어종 ID·grade:·region:·any여야 합니다");
                }
            }
            case "deliver" -> {
                if (target.kind() == Target.Kind.ID
                        && !target.isId("fish")
                        && !target.isId("item")) {
                    n.error("deliver 목표의 target은 어종·아이템 ID·grade:·any여야 합니다");
                }
            }
            case "visit" -> {
                if (target.kind() != Target.Kind.REGION && target.kind() != Target.Kind.POINT) {
                    n.error("visit 목표의 target은 region:<ID> 또는 point:…여야 합니다");
                }
            }
            case "talk", "affinity" -> {
                if (target.kind() != Target.Kind.ANY && !target.isId("npc")) {
                    n.error(type + " 목표의 target은 NPC ID여야 합니다 (비우면 npc 필드·end_npc)");
                }
            }
            case "cutscene" -> {
                if (!target.isId("cutscene")) {
                    n.error("cutscene 목표의 target은 컷씬 ID여야 합니다");
                }
            }
            case "boss" -> {
                if (target.kind() != Target.Kind.ANY && !target.isId("boss")) {
                    n.error("boss 목표의 target은 보스 ID 또는 any여야 합니다");
                }
            }
            case "use_item" -> {
                if (!target.isId("item")) {
                    n.error("use_item 목표의 target은 아이템 ID여야 합니다");
                }
            }
            case "collection", "chart_percent" -> {
                if (target.kind() != Target.Kind.ANY && target.kind() != Target.Kind.REGION) {
                    n.error(type + " 목표의 target은 region:<ID> 또는 any여야 합니다");
                }
            }
            default -> {
                // job_level, job_tier, reputation, flag and other modules' types do not check the
                // target format
            }
        }
    }

    private static Waypoint waypoint(YamlNode w, ParseContext ctx) {
        if (w.isMap()) {
            w.allowOnly(Set.of("npc", "world", "x", "y", "z"));
            NpcId npc =
                    w.get("npc")
                            .flatMap(r -> r.asId(NpcId.PREFIX))
                            .map(
                                    id -> {
                                        ctx.ref("npc", id, w.pos());
                                        return new NpcId(id);
                                    })
                            .orElse(null);
            return new Waypoint(
                    npc,
                    w.optString("world").orElse(null),
                    w.number("x", 0),
                    w.number("y", 0),
                    w.number("z", 0));
        }
        String raw = w.asString().strip();
        if (raw.startsWith("npc/") || raw.startsWith("harbor:npc/")) {
            HarborId id = w.asId(NpcId.PREFIX).orElse(HarborId.parse("npc/invalid"));
            ctx.ref("npc", id, w.pos());
            return new Waypoint(new NpcId(id), null, 0, 0, 0);
        }
        String[] parts = raw.split(",", -1);
        try {
            if (parts.length == 3) {
                return new Waypoint(
                        null,
                        null,
                        Double.parseDouble(parts[0].strip()),
                        Double.parseDouble(parts[1].strip()),
                        Double.parseDouble(parts[2].strip()));
            }
            if (parts.length == 4) {
                return new Waypoint(
                        null,
                        parts[0].strip(),
                        Double.parseDouble(parts[1].strip()),
                        Double.parseDouble(parts[2].strip()),
                        Double.parseDouble(parts[3].strip()));
            }
        } catch (NumberFormatException e) {
            // error below
        }
        w.error("waypoint는 NPC ID, \"x,y,z\", \"월드,x,y,z\" 또는 {world, x, y, z}여야 합니다: " + raw);
        return new Waypoint(null, null, 0, 0, 0);
    }
}
