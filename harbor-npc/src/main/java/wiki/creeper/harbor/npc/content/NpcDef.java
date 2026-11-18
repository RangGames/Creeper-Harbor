package wiki.creeper.harbor.npc.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.common.yaml.YamlNode;
import wiki.creeper.harbor.npc.api.AffinityLevel;
import wiki.creeper.harbor.npc.api.GiftReaction;
import wiki.creeper.harbor.npc.api.NpcRole;

/**
 * NPC 정의 (npcs.yml · npcs/*.yml, '11. 데이터 설계' 2.5 + docs 20 5.3 추가 제안 필드).
 *
 * @param name 기본 이름 (MiniMessage)
 * @param location 서 있는 곳. 떠돌이는 비우고 schedule 후보를 쓴다
 * @param pose Mannequin 자세 (standing·sneaking·sitting …, 서버에서 Mannequin.validPoses로 검사)
 * @param dialog 대화 트리 ID (dialogs/*.yml)
 * @param shop 상점 ID (Economy shops.yml)
 * @param gifts 선물 반응 표 {아이템·어종 ID: 호감도}. 없으면 기본 +1
 * @param giftLines 선물 반응 대사 키 (없으면 npc.gift.reaction.<반응>)
 * @param affinityLevels 단계표 (비우면 balance.yml npc.affinity.levels)
 * @param spawnFlag 이 서버 플래그가 서야 등장 (예: aquarium_stage_2)
 * @param hideFlag 이 서버 플래그가 서면 숨김
 * @param greet 하루 첫 대화 호감도를 줄지
 */
public record NpcDef(
        NpcId id,
        String name,
        NpcRole role,
        @Nullable Spot location,
        @Nullable Skin skin,
        @Nullable String pose,
        @Nullable HarborId dialog,
        @Nullable HarborId shop,
        Map<HarborId, Integer> gifts,
        Map<GiftReaction, String> giftLines,
        List<AffinityLevel> affinityLevels,
        double lookRadius,
        double interactRadius,
        @Nullable String spawnFlag,
        @Nullable String hideFlag,
        List<NameVariant> nameVariants,
        @Nullable Schedule schedule,
        boolean greet) {

    public static final ContentType.Entries<NpcDef> TYPE =
            new ContentType.Entries<>(
                    "npc",
                    List.of("npcs.yml", "npcs/*.yml"),
                    NpcId.PREFIX,
                    NpcDef::parse,
                    d -> d.id().id());

    /** 이름 변형: 플레이어 플래그가 서 있으면 이 이름 (위에서부터 첫 일치). */
    public record NameVariant(String playerFlag, String name) {}

    /**
     * 떠돌이 일정 (role: wanderer 필수).
     *
     * @param slotHours 칸 길이 (기본 balance.yml npc.wanderer.slot_hours, 제안값 3)
     * @param announce 도착 때 harbor:announce로 알릴지 (행상 true, 선장 false. 추가 제안)
     */
    public record Schedule(int slotHours, boolean announce, List<Candidate> candidates) {

        public Schedule {
            candidates = List.copyOf(candidates);
        }

        public java.util.Optional<Candidate> candidate(String id) {
            return candidates.stream().filter(c -> c.id().equals(id)).findFirst();
        }
    }

    /**
     * 떠돌이 후보 위치.
     *
     * @param time 출현 시간대 (비우면 상시)
     * @param weather 출현 날씨 (비우면 상시, storm = thunder)
     */
    public record Candidate(
            String id, Spot location, int weight, List<String> time, List<String> weather) {

        public Candidate {
            time = List.copyOf(time);
            weather = List.copyOf(weather);
        }

        /** 조건부 후보인지 (칸 안에서 1분마다 확인). */
        public boolean conditional() {
            return !time.isEmpty() || !weather.isEmpty();
        }
    }

    public NpcDef {
        gifts = Map.copyOf(gifts);
        giftLines = Map.copyOf(giftLines);
        affinityLevels = List.copyOf(affinityLevels);
        nameVariants = List.copyOf(nameVariants);
    }

    public boolean wanderer() {
        return role == NpcRole.WANDERER;
    }

    private static final Pattern FLAG = Pattern.compile("[a-z0-9_]+(/[a-z0-9_]+)*");

    private static final Pattern POSE = Pattern.compile("[a-z_]+");

    private static final Set<String> FIELDS =
            Set.of(
                    "id",
                    "name",
                    "role",
                    "location",
                    "skin",
                    "pose",
                    "dialog",
                    "shop",
                    "gifts",
                    "gift_lines",
                    "affinity_levels",
                    "look_radius",
                    "interact_radius",
                    "spawn_flag",
                    "hide_flag",
                    "name_variants",
                    "schedule",
                    "greet");

    static NpcDef parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(FIELDS);
        HarborId rawId =
                node.require("id")
                        .flatMap(n -> n.asId(NpcId.PREFIX))
                        .orElse(HarborId.parse("npc/invalid"));
        NpcRole role = node.enumValue("role", NpcRole.class).orElse(NpcRole.STORY);
        Spot location = node.get("location").flatMap(n -> Spot.parse(n, "")).orElse(null);
        Schedule schedule = node.get("schedule").map(NpcDef::parseSchedule).orElse(null);
        if (role == NpcRole.WANDERER) {
            if (schedule == null) {
                node.error("role: wanderer는 schedule이 필수입니다");
            }
        } else if (location == null) {
            node.error("필수 필드 'location'이 없습니다 (떠돌이만 schedule 후보로 대신함)");
        }
        HarborId dialog = null;
        if (node.has("dialog")) {
            YamlNode d = node.get("dialog").orElseThrow();
            dialog = d.asId(DialogDef.PREFIX).orElse(null);
            if (dialog != null) {
                ctx.ref("dialog", dialog, d.pos());
            }
        }
        HarborId shop = null;
        if (node.has("shop")) {
            YamlNode s = node.get("shop").orElseThrow();
            shop = s.asId("shop/").orElse(null);
            if (shop != null) {
                ctx.ref("shop", shop, s.pos());
            }
        }
        String pose = node.optString("pose").orElse(null);
        if (pose != null && !POSE.matcher(pose).matches()) {
            node.get("pose").ifPresent(p -> p.error("자세 이름 형식이 틀렸습니다: '" + p.asString() + "'"));
        }
        return new NpcDef(
                new NpcId(rawId),
                node.string("name"),
                role,
                location,
                node.get("skin").flatMap(Skin::parse).orElse(null),
                pose,
                dialog,
                shop,
                parseGifts(node, ctx),
                parseGiftLines(node),
                parseLevels(node),
                node.number("look_radius", 6),
                node.number("interact_radius", 4),
                flag(node, "spawn_flag"),
                flag(node, "hide_flag"),
                parseVariants(node),
                schedule,
                node.bool("greet", true));
    }

    private static @Nullable String flag(YamlNode node, String key) {
        String value = node.optString(key).orElse(null);
        if (value != null && !FLAG.matcher(value).matches()) {
            node.get(key).ifPresent(n -> n.error("플래그 이름 형식이 틀렸습니다: '" + n.asString() + "'"));
            return null;
        }
        return value;
    }

    private static Map<HarborId, Integer> parseGifts(YamlNode node, ParseContext ctx) {
        Map<HarborId, Integer> gifts = new LinkedHashMap<>();
        node.get("gifts")
                .ifPresent(
                        g ->
                                g.entries()
                                        .forEach(
                                                (raw, value) -> {
                                                    HarborId id =
                                                            HarborId.tryParse(raw).orElse(null);
                                                    if (id == null) {
                                                        value.error(
                                                                "선물 키는 아이템·어종 ID여야 합니다: '"
                                                                        + raw
                                                                        + "'");
                                                        return;
                                                    }
                                                    if (id.hasPrefix(FishId.PREFIX)) {
                                                        ctx.ref("fish", id, value.pos());
                                                    } else if (id.hasPrefix(ItemId.PREFIX)) {
                                                        ctx.ref("item", id, value.pos());
                                                    } else {
                                                        value.error(
                                                                "선물 키는 fish/ 또는 item/ ID여야 합니다: '"
                                                                        + raw
                                                                        + "'");
                                                        return;
                                                    }
                                                    gifts.put(id, value.asInt());
                                                }));
        return gifts;
    }

    private static Map<GiftReaction, String> parseGiftLines(YamlNode node) {
        Map<GiftReaction, String> lines = new LinkedHashMap<>();
        node.get("gift_lines")
                .ifPresent(
                        g ->
                                g.entries()
                                        .forEach(
                                                (raw, value) ->
                                                        wiki.creeper.harbor.common.model.Keyed
                                                                .fromKey(GiftReaction.class, raw)
                                                                .ifPresentOrElse(
                                                                        r ->
                                                                                lines.put(
                                                                                        r,
                                                                                        value
                                                                                                .asString()),
                                                                        () ->
                                                                                value.error(
                                                                                        "반응은 love·like·normal·dislike 중 하나여야 합니다: '"
                                                                                                + raw
                                                                                                + "'"))));
        return lines;
    }

    /** affinity_levels: [{points, name, discount, unlocks}] 순서대로 0단계부터. */
    static List<AffinityLevel> parseLevels(YamlNode node) {
        List<AffinityLevel> levels = new ArrayList<>();
        int previous = -1;
        for (YamlNode l : node.list("affinity_levels")) {
            l.allowOnly(Set.of("points", "name", "discount", "unlocks"));
            int points = l.integer("points");
            if (levels.isEmpty() && points != 0) {
                l.error("첫 단계의 points는 0이어야 합니다");
            }
            if (points <= previous) {
                l.error("단계별 points는 커져야 합니다: " + points);
            }
            previous = points;
            levels.add(
                    new AffinityLevel(
                            levels.size(),
                            points,
                            l.string("name"),
                            l.integer("discount", 0, 0, 90),
                            l.strings("unlocks")));
        }
        return levels;
    }

    private static List<NameVariant> parseVariants(YamlNode node) {
        List<NameVariant> variants = new ArrayList<>();
        for (YamlNode v : node.list("name_variants")) {
            v.allowOnly(Set.of("player_flag", "name"));
            String f = v.string("player_flag");
            if (!f.isEmpty() && !FLAG.matcher(f).matches()) {
                v.error("플래그 이름 형식이 틀렸습니다: '" + f + "'");
            }
            variants.add(new NameVariant(f, v.string("name")));
        }
        return variants;
    }

    private static Schedule parseSchedule(YamlNode node) {
        node.allowOnly(Set.of("slot_hours", "announce", "candidates"));
        int slotHours = node.integer("slot_hours", 0, 0, 24);
        if (slotHours != 0 && 24 % slotHours != 0) {
            node.error("slot_hours는 24의 약수여야 합니다 (0·3·6…시 칸 맞춤): " + slotHours);
        }
        List<Candidate> candidates = new ArrayList<>();
        Set<String> ids = new java.util.HashSet<>();
        for (YamlNode c : node.list("candidates")) {
            c.allowOnly(Set.of("id", "location", "weight", "time", "weather"));
            String id = c.string("id");
            if (!ids.add(id)) {
                c.error("후보 ID가 중복됩니다: '" + id + "'");
            }
            if (!FLAG.matcher(id).matches() || id.contains("/")) {
                c.error("후보 ID는 소문자·숫자·밑줄이어야 합니다: '" + id + "'");
            }
            List<String> time = c.strings("time");
            time.stream()
                    .filter(t -> !Conditions.TIMES.contains(t))
                    .forEach(t -> c.error("시간대는 dawn·day·dusk·night 중 하나여야 합니다: '" + t + "'"));
            List<String> weather = c.strings("weather");
            weather.stream()
                    .filter(w -> !Conditions.WEATHERS.contains(w))
                    .forEach(w -> c.error("날씨는 clear·rain·thunder·storm 중 하나여야 합니다: '" + w + "'"));
            Spot spot = c.require("location").flatMap(l -> Spot.parse(l, "")).orElse(null);
            if (spot != null) {
                candidates.add(
                        new Candidate(
                                id, spot, c.integer("weight", 100, 1, 1_000_000), time, weather));
            }
        }
        if (candidates.isEmpty()) {
            node.error("떠돌이 후보(candidates)가 하나 이상 있어야 합니다");
        }
        return new Schedule(slotHours, node.bool("announce", false), candidates);
    }
}
