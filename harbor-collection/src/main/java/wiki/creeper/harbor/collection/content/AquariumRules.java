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
 * Aquarium rules (aquarium.yml, proposed, docs 20 4.3·4.4). Points are used only for personal
 * contribution ranking; the stage is decided by the donation rate (distinct species ÷ donatable
 * species count). Tank display (tanks) and structures (structure) run only on the main server (the
 * server that has the world).
 *
 * @param qualityBonus quality bonus × (1 + qualityBonus × (★ − 1))
 * @param firstSpeciesMult point multiplier for a species donated to the server for the first time
 * @param perPlayerSpeciesCap max number of fish of the same species one player can earn points for
 *     per season
 * @param display tank display (none if absent)
 */
public record AquariumRules(
        Map<Grade, Integer> points,
        double qualityBonus,
        double firstSpeciesMult,
        int perPlayerSpeciesCap,
        List<Stage> stages,
        Display display) {

    /**
     * Stage: at donation rate {@code rate} or above, sets server flag {@code flag} and applies
     * server buff {@code modifiers}.
     *
     * @param structure structure template placed at this stage (NamespacedKey string; unchanged if
     *     absent)
     */
    public record Stage(
            int stage,
            double rate,
            String flag,
            List<StatBonus> modifiers,
            @Nullable String structure) {}

    /**
     * One tank (the space a rig swims in).
     *
     * @param box swim area (block coordinates)
     * @param slots max number of species placed in this tank
     * @param stage stage that opens it (stage 1 "add 2 tanks" is stage: 1)
     * @param deep deep-sea hall tank (deep_regions species only, stage 4)
     * @param label name tag Text Display position (above the box if absent)
     */
    public record Tank(
            String id, Box box, int slots, int stage, boolean deep, @Nullable Point label) {}

    /**
     * Tank display settings (proposed values).
     *
     * @param world aquarium world (main server)
     * @param view rigs are shown only to players inside this box
     * @param visibleMax number of rigs shown at once (the rest rotate every rotateMinutes)
     * @param lodRadius swim movement is skipped when no viewer is within this distance (animation
     *     LOD is done by Core)
     * @param swimSpeed swim speed (blocks/tick)
     * @param fallbackRig rig used when the species rig (rig/fish/<name>) is missing (if absent,
     *     that species is not shown)
     * @param baseStructure stage 0 structure (the look restored after a season reset)
     * @param structureOrigin reference coordinate where structures are placed
     * @param entrance entrance to move players in the area to just before placing a structure
     * @param structureBox area covered by the structure (for moving players)
     * @param deepRegions regions whose species go into deep-sea hall tanks
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

        /** Tanks opened at this stage. */
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

    /**
     * Structure for this stage: the highest stage at or below it that has a structure → otherwise
     * base_structure.
     */
    public Optional<String> structureFor(int stage) {
        String result = display.baseStructure();
        for (Stage s : stages) {
            if (s.stage() <= stage && s.structure() != null) {
                result = s.structure();
            }
        }
        return Optional.ofNullable(result);
    }

    /** Next stage (empty if none). */
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
