package wiki.creeper.harbor.fishing.content;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * 등급별 낚시 연출 프리셋 (fishing/presets.yml, docs 20 3.3 FishingPreset, '4. 낚시 연출 & 보스전' 1장). 연출 강도(0 최소 ·
 * 1 보통 · 2 최대, /낚시 설정)마다 생략할 항목을 {@code intensity}에 적는다. 파일이 없으면 연출 없이(문구만) 진행한다.
 *
 * <p>생략 항목 이름: darkness(전설 입질 어둠), title(입질 타이틀), shake(화면 흔들림 대신 짧은 흔들림 사운드), pillar(빛기둥),
 * firework(폭죽), dialog(포획 결과 Dialog), nearby(근처 플레이어 알림), jump_viewers(근처 플레이어에게 점프 장면),
 * shadow(그림자).
 */
public record FishingPresets(Map<Grade, GradePreset> grades, Map<Integer, Set<String>> skip) {

    /**
     * 한 등급의 연출.
     *
     * @param shadowRig 어종 리그(rig/fish/<이름>)가 없을 때 쓰는 등급 기본 그림자 리그 (예: rig/fish/shadow_small, 비우면
     *     없음)
     * @param shadowModel 리그를 쓸 수 없을 때 ItemDisplay 하나로 그리는 그림자 아이템 모델 (예: harbor:shadow/rare)
     * @param shadowScale 그림자 크기 배율 (전설은 배보다 크게)
     * @param glowColor 그림자 감별 글로우 색 (#RRGGBB, 비우면 없음)
     */
    public record GradePreset(
            @Nullable String shadowRig,
            String shadowModel,
            double shadowScale,
            @Nullable String glowColor,
            Stage bite,
            Stage caught) {}

    /**
     * 입질·포획 단계 연출.
     *
     * @param sound 효과음 키 (예: minecraft:entity.fishing_bobber.splash, 비우면 없음)
     * @param particle 파티클 이름 (org.bukkit.Particle, 예: splash)
     * @param particleCount 파티클 수
     * @param flags darkness · title · shake · pillar · firework · dialog · nearby
     */
    public record Stage(
            @Nullable String sound,
            @Nullable String particle,
            int particleCount,
            Set<String> flags) {

        public static final Stage NONE = new Stage(null, null, 0, Set.of());

        public boolean has(String flag) {
            return flags.contains(flag);
        }
    }

    public static final GradePreset NONE =
            new GradePreset(null, "minecraft:cod", 1.0, null, Stage.NONE, Stage.NONE);

    public static final FishingPresets EMPTY = new FishingPresets(Map.of(), Map.of());

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
                    "shadow");

    public GradePreset of(Grade grade) {
        return grades.getOrDefault(grade, NONE);
    }

    /** 이 연출 강도에서 그 항목을 보여 주는지. */
    public boolean shows(int intensity, String flag) {
        return !skip.getOrDefault(intensity, Set.of()).contains(flag);
    }

    static FishingPresets parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(Set.of("grades", "intensity"));
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
        return new FishingPresets(Map.copyOf(grades), Map.copyOf(skip));
    }

    private static GradePreset preset(YamlNode node, ParseContext ctx) {
        node.allowOnly(
                Set.of("shadow_rig", "shadow_model", "shadow_scale", "glow", "bite", "catch"));
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
                node.get("catch").map(FishingPresets::stage).orElse(Stage.NONE));
    }

    private static Stage stage(YamlNode node) {
        node.allowOnly(Set.of("sound", "particle", "count", "flags"));
        return new Stage(
                node.optString("sound").orElse(null),
                node.optString("particle").orElse(null),
                node.integer("count", 12, 0, 500),
                flags(node.list("flags")));
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
