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
 * Catch effect cosmetic preset (shop/catch_fx.yml, proposed, docs 20 12.4 'Catch effects').
 * Fishing's effect presets (fishing/presets.yml) only define intensity per grade, so the colors and
 * shapes a cosmetic changes live here. Intensity (pillar height, fireworks, announcements) uses the
 * grade values as-is ('the grade must be visible').
 *
 * @param pillarParticle light pillar particle (for dust, cycles through pillarColors)
 * @param fireworkType firework shape (ball · ball_large · star · burst · creeper)
 * @param sound sound key (empty means the grade's default sound)
 * @param splashParticle splash particle (instead of the grade preset's particle)
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
