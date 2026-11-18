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
 * 해상 이벤트의 낚시 효과 정의 (fishing/sea_events.yml, 추가 제안). 신화 어종이 나오는 이벤트(첫 물고기의 귀환, 유령선, 물고기 대이동)의
 * fish_effects를 미리 적어 둔다. World 스케줄러는 이 정의를 그대로 harbor:event 메시지에 실어 보내도 되고({@code FishingService}와
 * 무관하게 메시지에 직접 적어도 된다), 운영자는 /harbor fishing event start로 시험한다. 일정은 World의 events.yml.
 *
 * @param events 이벤트 ID → 정의
 */
public record SeaEventPresets(Map<String, Preset> events) {

    /**
     * @param region 이벤트 해역 (null이면 서버 전체)
     * @param duration 기본 지속 시간
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
