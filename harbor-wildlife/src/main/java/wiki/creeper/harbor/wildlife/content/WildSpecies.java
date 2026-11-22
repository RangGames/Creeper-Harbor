package wiki.creeper.harbor.wildlife.content;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RigId;
import wiki.creeper.harbor.common.yaml.YamlNode;
import wiki.creeper.harbor.wildlife.api.Medium;

/**
 * wildlife/species.yml entry (proposed format, '17-3' 9.3). Per-species movement values. Empty
 * values (0/null) are filled automatically from the rig body length and behaviours.yml ({@code
 * SpeciesProfile.resolve}). Designers write only the values they want to override.
 *
 * <pre>
 * - id: wild/harbor_mackerel
 *   fish_id: fish/harbor_mackerel
 *   template: fish
 *   medium: water
 *   special: leap
 *   depth: [1, 6]
 * </pre>
 *
 * @param special special action (name from behaviours.yml actions). If no temperament is set, this
 *     action's group becomes the temperament
 * @param group temperament override
 * @param cruiseSpeed cruise speed (blocks/second, 0 computes it from body length)
 * @param turnRate max turn rate (degrees/second, 0 uses the template default)
 * @param depthMin minimum depth below the surface (blocks, water/lava)
 * @param depthMax maximum depth below the surface (0 means unlimited)
 * @param schoolMin minimum school size (0 computes it from body length)
 * @param spawnWeight natural spawn weight (multiplied into the grade weight)
 * @param threatRadius threat detection radius override (0 uses the temperament value)
 * @param rig rig (defaults to rig/fish/&lt;name&gt;, then rig/wild/&lt;template&gt;)
 * @param scale rig scale multiplier
 * @param enabled if off, no natural spawns (spawnScripted still works)
 */
public record WildSpecies(
        HarborId id,
        FishId fish,
        WildTemplate template,
        Medium medium,
        @Nullable String special,
        @Nullable WildGroup group,
        double cruiseSpeed,
        double turnRate,
        double depthMin,
        double depthMax,
        int schoolMin,
        int schoolMax,
        double spawnWeight,
        double threatRadius,
        @Nullable RigId rig,
        double scale,
        boolean enabled) {

    public static final String PREFIX = "wild/";

    public static final ContentType.Entries<WildSpecies> TYPE =
            new ContentType.Entries<>(
                    "wildlife_species",
                    List.of("wildlife/species.yml", "wildlife/species/*.yml"),
                    PREFIX,
                    WildSpecies::parse,
                    WildSpecies::id);

    private static final HarborId PLACEHOLDER_ID = HarborId.parse("wild/invalid");
    private static final FishId PLACEHOLDER_FISH = FishId.parse("fish/invalid");
    private static final Set<String> FIELDS =
            Set.of(
                    "id",
                    "fish_id",
                    "template",
                    "medium",
                    "special",
                    "group",
                    "cruise_speed",
                    "turn_rate",
                    "depth",
                    "school",
                    "spawn_weight",
                    "threat_radius",
                    "rig",
                    "scale",
                    "enabled");

    static WildSpecies parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(FIELDS);
        HarborId id = node.require("id").flatMap(n -> n.asId(PREFIX)).orElse(PLACEHOLDER_ID);
        FishId fish = PLACEHOLDER_FISH;
        Optional<YamlNode> fishNode = node.require("fish_id");
        if (fishNode.isPresent()) {
            Optional<HarborId> fid = fishNode.get().asId(FishId.PREFIX);
            if (fid.isPresent()) {
                fish = new FishId(fid.get());
                ctx.ref("fish", fid.get(), fishNode.get().pos());
            }
        }
        WildTemplate template =
                node.get("template")
                        .flatMap(n -> n.asEnum(WildTemplate.class))
                        .orElse(WildTemplate.FISH);
        Medium medium =
                node.get("medium")
                        .flatMap(n -> n.asEnum(Medium.class))
                        .orElse(template == WildTemplate.CLAM ? Medium.FLOOR : Medium.WATER);
        if (template == WildTemplate.CLAM && medium != Medium.FLOOR) {
            node.error("clam 템플릿은 medium: floor여야 합니다");
        }
        String special = node.optString("special").orElse(null);
        WildGroup group = node.get("group").flatMap(n -> n.asEnum(WildGroup.class)).orElse(null);

        double cruise = nonNegative(node, "cruise_speed");
        double turn = nonNegative(node, "turn_rate");
        double depthMin = 0;
        double depthMax = 0;
        Optional<YamlNode> depth = node.get("depth");
        if (depth.isPresent()) {
            List<YamlNode> pair = depth.get().asList();
            if (pair.size() != 2) {
                depth.get().error("depth는 [최소, 최대] 두 값이어야 합니다");
            } else {
                depthMin = pair.get(0).asDouble();
                depthMax = pair.get(1).asDouble();
                if (depthMin < 0 || depthMax < depthMin) {
                    depth.get().error("depth는 0 ≤ 최소 ≤ 최대여야 합니다");
                }
            }
        }
        int schoolMin = 0;
        int schoolMax = 0;
        Optional<YamlNode> school = node.get("school");
        if (school.isPresent()) {
            List<YamlNode> pair = school.get().asList();
            if (pair.size() != 2) {
                school.get().error("school은 [최소, 최대] 두 값이어야 합니다");
            } else {
                schoolMin = pair.get(0).asInt();
                schoolMax = pair.get(1).asInt();
                if (schoolMin < 1 || schoolMax < schoolMin || schoolMax > 32) {
                    school.get().error("school은 1 ≤ 최소 ≤ 최대 ≤ 32여야 합니다");
                }
            }
        }
        if (medium == Medium.FLOOR && schoolMax > 1) {
            node.error("바닥 개체(floor)는 무리를 짓지 않습니다 (school 최대 1)");
        }
        double spawnWeight = node.number("spawn_weight", 1.0);
        if (spawnWeight < 0) {
            node.error("spawn_weight는 0 이상이어야 합니다");
        }
        RigId rig = null;
        Optional<YamlNode> rigNode = node.get("rig");
        if (rigNode.isPresent()) {
            Optional<HarborId> rid = rigNode.get().asId(RigId.PREFIX);
            if (rid.isPresent()) {
                rig = new RigId(rid.get());
                ctx.ref("rig", rid.get(), rigNode.get().pos());
            }
        }
        double scale = node.number("scale", 1.0);
        if (scale <= 0) {
            node.error("scale은 0보다 커야 합니다");
        }
        return new WildSpecies(
                id,
                fish,
                template,
                medium,
                special,
                group,
                cruise,
                turn,
                depthMin,
                depthMax,
                schoolMin,
                schoolMax,
                spawnWeight,
                nonNegative(node, "threat_radius"),
                rig,
                scale,
                node.bool("enabled", true));
    }

    private static double nonNegative(YamlNode node, String key) {
        double v = node.number(key, 0);
        if (v < 0) {
            node.error("'" + key + "'는 0 이상이어야 합니다");
        }
        return v;
    }
}
