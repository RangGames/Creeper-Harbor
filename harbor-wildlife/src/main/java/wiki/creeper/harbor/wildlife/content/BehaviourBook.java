package wiki.creeper.harbor.wildlife.content;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * wildlife/behaviours.yml (proposed format, '17-3' 9.3). Carries over the prototype GROUPS/BEHAVE
 * tables as-is: per-temperament 1-second dice (special action / rest chance), rest length, base
 * speed multiplier, threat detection radius, bait reaction chance, and per-action speed multiplier,
 * repetitions, impulse and particle.
 *
 * @param groups temperament table. Temperaments missing from the file use the documented values
 *     ({@link #DEFAULT_GROUPS})
 * @param actions action name → definition
 */
public record BehaviourBook(Map<WildGroup, GroupDef> groups, Map<String, ActionDef> actions) {

    /**
     * One temperament.
     *
     * @param specialChance chance the 1-second dice yields a special action (0~1, % in the file)
     * @param idleChance chance of resting (0~1)
     * @param idleSeconds rest length
     * @param speedBase base speed multiplier delta (active +0.25, lazy −0.30)
     * @param threatRadius threat detection radius (blocks, proposed value)
     * @param baitChance chance to approach when a bobber lands (0~1, proposed value)
     */
    public record GroupDef(
            double specialChance,
            double idleChance,
            double idleSeconds,
            double speedBase,
            double threatRadius,
            double baitChance) {}

    /**
     * One action.
     *
     * @param group default temperament of species that have this as their special action
     * @param speedDelta speed multiplier delta (dart 1.6 means ×(1 + 1.6), bask −0.85 means ×0.15)
     * @param reps number of repetitions
     * @param impulse impulse added when the action starts (blocks/second, leap [0, 0.65, 0])
     * @param particle particle (vanilla particle key, null if none)
     * @param teleportMin minimum teleport distance (blocks, 0 means no teleport, proposed field)
     * @param teleportMax maximum teleport distance
     * @param threat whether this is a startle action played first when threatened (startle, ink,
     *     dart; proposed field)
     */
    public record ActionDef(
            String name,
            WildGroup group,
            double speedDelta,
            int reps,
            Vec3 impulse,
            @Nullable String particle,
            double teleportMin,
            double teleportMax,
            boolean threat) {

        public boolean teleports() {
            return teleportMax > 0;
        }
    }

    /**
     * '17-3' 9.3 prototype values (only when a temperament is missing from the file). Threat radius
     * and bait chance are 9.4 proposed values.
     */
    public static final Map<WildGroup, GroupDef> DEFAULT_GROUPS =
            Map.of(
                    WildGroup.ACTIVE, new GroupDef(0.09, 0.02, 2.0, 0.25, 10, 0.6),
                    WildGroup.NORMAL, new GroupDef(0.06, 0.04, 3.5, 0.0, 8, 0.4),
                    WildGroup.LAZY, new GroupDef(0.05, 0.08, 6.0, -0.30, 6, 0.2));

    public static final ContentType.Single<BehaviourBook> TYPE =
            new ContentType.Single<>(
                    "wildlife_behaviours", "wildlife/behaviours.yml", false, BehaviourBook::parse);

    private static final Set<String> FIELDS = Set.of("groups", "actions");
    private static final Set<String> GROUP_FIELDS =
            Set.of(
                    "special_pct",
                    "idle_pct",
                    "idle_sec",
                    "speed_base",
                    "threat_radius",
                    "bait_chance");
    private static final Set<String> ACTION_FIELDS =
            Set.of("group", "speed_delta", "reps", "impulse", "particle", "teleport", "threat");

    public GroupDef group(WildGroup group) {
        GroupDef def = groups.get(group);
        return def != null ? def : Objects.requireNonNull(DEFAULT_GROUPS.get(group));
    }

    public Optional<ActionDef> action(@Nullable String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(actions.get(name));
    }

    static BehaviourBook parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(FIELDS);
        Map<WildGroup, GroupDef> groups = new EnumMap<>(WildGroup.class);
        node.get("groups")
                .ifPresent(
                        g ->
                                g.entries()
                                        .forEach(
                                                (name, value) -> {
                                                    Optional<WildGroup> group =
                                                            Keyed.fromKey(WildGroup.class, name);
                                                    if (group.isEmpty()) {
                                                        value.error(
                                                                "성향은 active · normal · lazy 중 하나여야 합니다: '"
                                                                        + name
                                                                        + "'");
                                                        return;
                                                    }
                                                    groups.put(group.get(), parseGroup(value));
                                                }));
        Map<String, ActionDef> actions = new LinkedHashMap<>();
        node.get("actions")
                .ifPresent(
                        a ->
                                a.entries()
                                        .forEach(
                                                (name, value) -> {
                                                    if (!name.matches("[a-z0-9_]+")) {
                                                        value.error(
                                                                "동작 이름은 소문자·숫자·밑줄만: '"
                                                                        + name
                                                                        + "'");
                                                        return;
                                                    }
                                                    actions.put(name, parseAction(name, value));
                                                }));
        return new BehaviourBook(Map.copyOf(groups), Map.copyOf(actions));
    }

    private static GroupDef parseGroup(YamlNode g) {
        g.allowOnly(GROUP_FIELDS);
        double special = percent(g, "special_pct");
        double idle = percent(g, "idle_pct");
        if (special + idle > 1) {
            g.error("special_pct + idle_pct가 100을 넘습니다");
        }
        double idleSec = g.number("idle_sec");
        if (idleSec <= 0) {
            g.error("idle_sec는 0보다 커야 합니다");
        }
        double speedBase = g.number("speed_base", 0);
        if (speedBase <= -1) {
            g.error("speed_base는 −1보다 커야 합니다 (속도 배율 = 1 + speed_base)");
        }
        double threat = g.number("threat_radius");
        if (threat < 0) {
            g.error("threat_radius는 0 이상이어야 합니다");
        }
        double bait = percent(g, "bait_chance");
        return new GroupDef(special, idle, idleSec, speedBase, threat, bait);
    }

    private static double percent(YamlNode g, String key) {
        double v = g.number(key);
        if (v < 0 || v > 100) {
            g.error("'" + key + "'는 0~100 (%)이어야 합니다: " + v);
        }
        return v / 100.0;
    }

    private static ActionDef parseAction(String name, YamlNode a) {
        a.allowOnly(ACTION_FIELDS);
        WildGroup group =
                a.get("group").flatMap(n -> n.asEnum(WildGroup.class)).orElse(WildGroup.NORMAL);
        double speedDelta = a.number("speed_delta", 0);
        if (speedDelta <= -1) {
            a.error("speed_delta는 −1보다 커야 합니다");
        }
        int reps = a.integer("reps", 1, 1, 10);
        Vec3 impulse = Vec3.ZERO;
        Optional<YamlNode> imp = a.get("impulse");
        if (imp.isPresent()) {
            List<YamlNode> xyz = imp.get().asList();
            if (xyz.size() != 3) {
                imp.get().error("impulse는 [x, y, z] 세 값이어야 합니다");
            } else {
                impulse =
                        new Vec3(
                                xyz.get(0).asDouble(),
                                xyz.get(1).asDouble(),
                                xyz.get(2).asDouble());
            }
        }
        String particle = a.optString("particle").orElse(null);
        if (particle != null && !particle.matches("[a-z0-9_]+")) {
            a.error("particle은 바닐라 파티클 키여야 합니다 (예: squid_ink): '" + particle + "'");
        }
        double tMin = 0;
        double tMax = 0;
        Optional<YamlNode> tp = a.get("teleport");
        if (tp.isPresent()) {
            List<YamlNode> pair = tp.get().asList();
            if (pair.size() != 2) {
                tp.get().error("teleport는 [최소, 최대] 두 값이어야 합니다");
            } else {
                tMin = pair.get(0).asDouble();
                tMax = pair.get(1).asDouble();
                if (tMin <= 0 || tMin > tMax) {
                    tp.get().error("teleport는 0 < 최소 ≤ 최대여야 합니다");
                }
            }
        }
        return new ActionDef(
                name,
                group,
                speedDelta,
                reps,
                impulse,
                particle,
                tMin,
                tMax,
                a.bool("threat", false));
    }
}
