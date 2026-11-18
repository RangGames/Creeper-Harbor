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
 * wildlife/species.yml 항목 (추가 제안 형식, '17-3' 9.3). 어종별 이동 값. 비운 값(0·null)은 리그 몸길이와 behaviours.yml에서
 * 자동으로 채운다 ({@code SpeciesProfile.resolve}). 디자이너는 덮어쓸 값만 적는다.
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
 * @param special 고유 동작 (behaviours.yml actions 이름). 성향을 정하지 않으면 이 동작의 group이 성향이 된다
 * @param group 성향 덮어쓰기
 * @param cruiseSpeed 순항 속도 (블록/초, 0이면 몸길이로 계산)
 * @param turnRate 최대 회전 속도 (도/초, 0이면 템플릿 기본)
 * @param depthMin 수면 아래 최소 깊이 (블록, 물·용암)
 * @param depthMax 수면 아래 최대 깊이 (0이면 제한 없음)
 * @param schoolMin 무리 최소 마릿수 (0이면 몸길이로 계산)
 * @param spawnWeight 자연 스폰 가중치 (등급 가중치에 곱함)
 * @param threatRadius 위협 감지 반경 덮어쓰기 (0이면 성향 값)
 * @param rig 리그 (없으면 rig/fish/&lt;이름&gt;, 그것도 없으면 rig/wild/&lt;템플릿&gt;)
 * @param scale 리그 크기 배율
 * @param enabled 끄면 자연 스폰하지 않는다 (spawnScripted는 가능)
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
