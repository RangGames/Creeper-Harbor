package wiki.creeper.harbor.collection.content;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.id.RigId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.common.rig.RigDef;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * 수족관 규칙 (aquarium.yml, 추가 제안, docs 20 4.3·4.4). 점수는 개인 기여 순위에만 쓰고, 단계는 기부율(서로 다른 어종 수 ÷ 기부 가능 어종
 * 수)로 판정한다. 수조 연출(tanks)·구조물(structure)은 본 서버(world가 있는 서버)에서만 돈다.
 *
 * @param qualityBonus 품질 보너스 × (1 + qualityBonus × (★ − 1))
 * @param firstSpeciesMult 서버에 처음 기부되는 어종 점수 배율
 * @param perPlayerSpeciesCap 같은 어종을 한 사람이 시즌에 점수 인정받는 최대 마리 수
 * @param display 수조 연출 (없으면 연출 없음)
 */
public record AquariumRules(
        Map<Grade, Integer> points,
        double qualityBonus,
        double firstSpeciesMult,
        int perPlayerSpeciesCap,
        List<Stage> stages,
        Display display) {

    /**
     * 단계: 기부율 rate 이상이면 서버 플래그 flag를 켜고 서버 버프 modifiers를 건다.
     *
     * @param structure 이 단계에서 붙일 구조물 템플릿 (NamespacedKey 문자열, 없으면 그대로)
     */
    public record Stage(
            int stage,
            double rate,
            String flag,
            List<StatBonus> modifiers,
            @Nullable String structure) {}

    /**
     * 수조 하나 (리그가 헤엄치는 공간).
     *
     * @param box 헤엄 범위 (블록 좌표)
     * @param slots 이 수조에 넣을 최대 어종 수
     * @param stage 열리는 단계 (1단계 '수조 2개 추가'는 stage: 1)
     * @param deep 심해관 수조 (deep_regions 어종만, 4단계)
     * @param label 이름표 Text Display 위치 (없으면 상자 위)
     */
    public record Tank(
            String id, Box box, int slots, int stage, boolean deep, @Nullable Point label) {}

    /**
     * 수조 연출 설정 (제안값).
     *
     * @param world 수족관 월드 (본 서버)
     * @param view 이 상자 안 플레이어에게만 리그를 보여준다
     * @param visibleMax 동시에 띄우는 리그 수 (나머지는 rotateMinutes마다 교대)
     * @param lodRadius 이 거리 안에 시청자가 없으면 헤엄 이동을 생략한다 (애니메이션 LOD는 Core가 한다)
     * @param swimSpeed 헤엄 속도 (블록/틱)
     * @param fallbackRig 어종 리그(rig/fish/<이름>)가 없을 때 쓰는 리그 (없으면 그 어종은 표시하지 않음)
     * @param baseStructure 0단계 구조물 (시즌 초기화 뒤 되돌릴 모습)
     * @param structureOrigin 구조물을 붙이는 기준 좌표
     * @param entrance 구조물을 붙이기 직전 그 영역의 플레이어를 옮길 입구
     * @param structureBox 구조물이 덮는 영역 (플레이어 이동 판정)
     * @param deepRegions 심해관 수조에 넣을 어종의 지역
     */
    public record Display(
            String world,
            Box view,
            List<Tank> tanks,
            int visibleMax,
            int rotateMinutes,
            double lodRadius,
            double swimSpeed,
            @Nullable RigId fallbackRig,
            @Nullable String baseStructure,
            @Nullable Point structureOrigin,
            @Nullable Point entrance,
            Box structureBox,
            Set<RegionId> deepRegions) {

        public static final Display NONE =
                new Display(
                        "", Box.EMPTY, List.of(), 40, 10, 32, 0.08, null, null, null, null,
                        Box.EMPTY, Set.of());

        public boolean enabled() {
            return !world.isEmpty() && !tanks.isEmpty();
        }

        /** 단계에서 열린 수조. */
        public List<Tank> openTanks(int stage) {
            return tanks.stream().filter(t -> t.stage() <= stage).toList();
        }
    }

    public static final ContentType.Single<AquariumRules> TYPE =
            new ContentType.Single<>("aquarium", "aquarium.yml", false, AquariumRules::parse);

    public int pointsFor(Grade grade, int stars, boolean firstSpecies) {
        double base = points.getOrDefault(grade, 0) * (1 + qualityBonus * (stars - 1));
        return (int) Math.round(firstSpecies ? base * firstSpeciesMult : base);
    }

    /** 이 단계에 붙일 구조물: 그 단계 이하에서 structure가 있는 가장 높은 단계 → 없으면 base_structure. */
    public Optional<String> structureFor(int stage) {
        String result = display.baseStructure();
        for (Stage s : stages) {
            if (s.stage() <= stage && s.structure() != null) {
                result = s.structure();
            }
        }
        return Optional.ofNullable(result);
    }

    /** 다음 단계 (없으면 빈 값). */
    public Optional<Stage> next(int stage) {
        return stages.stream().filter(s -> s.stage() > stage).findFirst();
    }

    static AquariumRules parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(
                Set.of(
                        "points",
                        "quality_bonus",
                        "first_species_mult",
                        "per_player_species_cap",
                        "stages",
                        "display"));
        Map<Grade, Integer> points = new EnumMap<>(Grade.class);
        node.get("points")
                .ifPresent(
                        p ->
                                p.entries()
                                        .forEach(
                                                (k, v) ->
                                                        Keyed.fromKey(Grade.class, k)
                                                                .ifPresentOrElse(
                                                                        g ->
                                                                                points.put(
                                                                                        g,
                                                                                        v.asInt()),
                                                                        () ->
                                                                                v.error(
                                                                                        "등급 이름이 아닙니다: "
                                                                                                + k))));
        List<Stage> stages = new ArrayList<>();
        for (YamlNode s : node.list("stages")) {
            s.allowOnly(Set.of("stage", "rate", "flag", "modifiers", "structure"));
            double rate = s.number("rate");
            if (rate <= 0 || rate > 1) {
                s.error("rate는 0~1 사이여야 합니다");
            }
            stages.add(
                    new Stage(
                            s.integer("stage", 1, 1, 100),
                            rate,
                            s.string("flag"),
                            StatBonus.parseList(s.list("modifiers")),
                            s.optString("structure").orElse(null)));
        }
        stages.sort(Comparator.comparingInt(Stage::stage));
        Display display = node.get("display").map(d -> display(d, ctx)).orElse(Display.NONE);
        return new AquariumRules(
                Map.copyOf(points),
                node.number("quality_bonus", 0.1),
                node.number("first_species_mult", 3),
                node.integer("per_player_species_cap", 5, 1, 1000),
                List.copyOf(stages),
                display);
    }

    private static Display display(YamlNode d, ParseContext ctx) {
        d.allowOnly(
                Set.of(
                        "world",
                        "view",
                        "tanks",
                        "visible_max",
                        "rotate_minutes",
                        "lod_radius",
                        "swim_speed",
                        "fallback_rig",
                        "base_structure",
                        "structure_origin",
                        "entrance",
                        "structure_box",
                        "deep_regions"));
        List<Tank> tanks = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (YamlNode t : d.list("tanks")) {
            t.allowOnly(Set.of("id", "box", "slots", "stage", "deep", "label"));
            String id = t.string("id");
            if (!ids.add(id)) {
                t.error("수조 id가 겹칩니다: " + id);
            }
            tanks.add(
                    new Tank(
                            id,
                            t.get("box").map(Box::parse).orElse(Box.EMPTY),
                            t.integer("slots", 4, 1, 64),
                            t.integer("stage", 0, 0, 100),
                            t.bool("deep", false),
                            t.get("label").flatMap(Point::parse).orElse(null)));
        }
        RigId fallback = null;
        Optional<YamlNode> fb = d.get("fallback_rig");
        if (fb.isPresent()) {
            Optional<HarborId> id = fb.get().asId(RigId.PREFIX);
            if (id.isPresent()) {
                fallback = new RigId(id.get());
                ctx.ref(RigDef.TYPE.name(), id.get(), fb.get().pos());
            }
        }
        Set<RegionId> deep = new HashSet<>();
        for (YamlNode r : d.list("deep_regions")) {
            r.asId(RegionId.PREFIX).ifPresent(id -> deep.add(new RegionId(id)));
        }
        return new Display(
                d.string("world", "harbor_main"),
                d.get("view").map(Box::parse).orElse(Box.EMPTY),
                List.copyOf(tanks),
                d.integer("visible_max", 40, 1, 500),
                d.integer("rotate_minutes", 10, 1, 1440),
                d.number("lod_radius", 32),
                d.number("swim_speed", 0.08),
                fallback,
                d.optString("base_structure").orElse(null),
                d.get("structure_origin").flatMap(Point::parse).orElse(null),
                d.get("entrance").flatMap(Point::parse).orElse(null),
                d.get("structure_box").map(Box::parse).orElse(Box.EMPTY),
                Set.copyOf(deep));
    }
}
