package wiki.creeper.harbor.fishing.content;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.yaml.YamlNode;
import wiki.creeper.harbor.fishing.api.SeaEventMessage.FishEffect;

/**
 * Fishing effect definitions of sea events (fishing/sea_events.yml, proposed). Pre-declares the
 * fish_effects of events that bring mythic species (return of the first fish, ghost ship, great
 * migration). The World scheduler may send these definitions as is in the harbor:event message (or
 * write them directly into the message, independent of {@code FishingService}), and operators test
 * with /harbor fishing event start. The schedule is World's events.yml.
 *
 * @param events event ID → definition
 */
public record SeaEventPresets(Map<String, Preset> events) {

    /**
     * @param region event sea area (null for the whole server)
     * @param duration default duration
     */
    public record Preset(
            String id, @Nullable String region, Duration duration, List<FishEffect> fishEffects) {}

    public static final SeaEventPresets EMPTY = new SeaEventPresets(Map.of());

    public static final ContentType.Single<SeaEventPresets> TYPE =
            new ContentType.Single<>(
                    "fishing_sea_events", "fishing/sea_events.yml", false, SeaEventPresets::parse);

    public Optional<Preset> get(String id) {
        return Optional.ofNullable(events.get(id));
    }

    static SeaEventPresets parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(Set.of("events"));
        Map<String, Preset> events = new LinkedHashMap<>();
        for (YamlNode e : node.list("events")) {
            e.allowOnly(Set.of("id", "region", "duration_min", "fish_effects"));
            String id = e.string("id");
            if (!id.matches("[a-z0-9_]+")) {
                e.error("이벤트 ID는 소문자·숫자·밑줄입니다: '" + id + "'");
            }
            String region = e.optString("region").orElse(null);
            if (region != null) {
                HarborId.tryParse(region)
                        .ifPresentOrElse(
                                r -> ctx.ref("region", r, e.pos()),
                                () -> e.error("지역 ID 형식이 틀렸습니다: " + region));
            }
            List<FishEffect> effects = new ArrayList<>();
            for (YamlNode f : e.list("fish_effects")) {
                f.allowOnly(Set.of("type", "fish", "mult", "chance", "cap"));
                String type = f.string("type");
                HarborId fish =
                        f.require("fish")
                                .flatMap(n -> n.asId(FishId.PREFIX))
                                .orElse(HarborId.parse("fish/invalid"));
                f.get("fish").ifPresent(n -> ctx.ref("fish", fish, n.pos()));
                switch (type) {
                    case FishEffect.SPECIES_WEIGHT -> {
                        double mult = f.number("mult", 1);
                        if (mult <= 0) {
                            f.error("mult는 0보다 커야 합니다");
                        }
                        effects.add(FishEffect.speciesWeight(fish.toString(), mult));
                    }
                    case FishEffect.MYTHIC ->
                            effects.add(
                                    FishEffect.mythic(
                                            fish.toString(),
                                            JunkTable.chance(f, "chance"),
                                            f.integer("cap", 1, 1, 10_000)));
                    default -> f.error("type은 species_weight 또는 mythic입니다: '" + type + "'");
                }
            }
            if (events.containsKey(id)) {
                e.error("이벤트 ID가 중복됩니다: " + id);
            }
            events.put(
                    id,
                    new Preset(
                            id,
                            region,
                            Duration.ofMinutes(e.integer("duration_min", 60, 1, 7 * 24 * 60)),
                            List.copyOf(effects)));
        }
        return new SeaEventPresets(Map.copyOf(events));
    }
}
