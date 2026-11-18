package wiki.creeper.harbor.shop.content;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * 포획 연출 코스메틱 프리셋 (shop/catch_fx.yml, 추가 제안, docs 20 12.4 '포획 연출'). Fishing의 연출
 * 프리셋(fishing/presets.yml)은 등급별 강도만 정하므로, 코스메틱이 바꾸는 색·모양은 여기에 둔다. 강도(빛기둥 높이·폭죽 여부·공지)는 등급 값을 그대로 쓴다
 * ('등급이 눈에 보여야 한다').
 *
 * @param pillarParticle 빛기둥 파티클 (dust면 pillarColors 색을 돌아가며 쓴다)
 * @param fireworkType 폭죽 모양 (ball · ball_large · star · burst · creeper)
 * @param sound 효과음 키 (비우면 등급 기본 효과음)
 * @param splashParticle 물보라 파티클 (등급 프리셋의 particle 대신)
 */
public record CatchFxPreset(
        HarborId id,
        String pillarParticle,
        List<String> pillarColors,
        String fireworkType,
        List<String> fireworkColors,
        @Nullable String sound,
        String splashParticle) {

    public static final String PREFIX = "catch_fx/";

    public static final ContentType.Entries<CatchFxPreset> TYPE =
            new ContentType.Entries<>(
                    "catch_fx",
                    List.of("shop/catch_fx.yml"),
                    PREFIX,
                    CatchFxPreset::parse,
                    CatchFxPreset::id);

    private static final Set<String> FIREWORKS =
            Set.of("ball", "ball_large", "star", "burst", "creeper");

    static CatchFxPreset parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(
                Set.of(
                        "id",
                        "pillar_particle",
                        "pillar_colors",
                        "firework",
                        "firework_colors",
                        "sound",
                        "splash_particle"));
        HarborId id =
                node.require("id")
                        .flatMap(n -> n.asId(PREFIX))
                        .orElse(HarborId.parse("catch_fx/invalid"));
        String firework = node.string("firework", "ball").toLowerCase(Locale.ROOT);
        if (!FIREWORKS.contains(firework)) {
            node.error("firework는 " + FIREWORKS + " 중 하나여야 합니다: " + firework);
        }
        List<String> pillarColors = colors(node, "pillar_colors");
        List<String> fireworkColors = colors(node, "firework_colors");
        if (fireworkColors.isEmpty()) {
            fireworkColors = pillarColors.isEmpty() ? List.of("#ffffff") : pillarColors;
        }
        return new CatchFxPreset(
                id,
                node.string("pillar_particle", "dust").toLowerCase(Locale.ROOT),
                pillarColors.isEmpty() ? List.of("#ffffff") : pillarColors,
                firework,
                fireworkColors,
                node.optString("sound").orElse(null),
                node.string("splash_particle", "splash").toLowerCase(Locale.ROOT));
    }

    private static List<String> colors(YamlNode node, String key) {
        List<String> colors = node.strings(key);
        for (String c : colors) {
            if (!c.matches("#[0-9a-fA-F]{6}")) {
                node.error(key + " 색은 #rrggbb 형식이어야 합니다: " + c);
            }
        }
        return List.copyOf(colors);
    }
}
