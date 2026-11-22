package wiki.creeper.harbor.boss.content;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.BossId;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.id.RigId;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Boss definition (bosses/*.yml, docs 20 10.3). Empty values (0 or −1) take the type default from
 * balance.yml {@code boss.types.<type>}. A running encounter keeps the definition from its start
 * (reloads do not change it, 10.6).
 *
 * <pre>
 * - id: boss/swamp_catfish
 *   name: "&lt;dark_green&gt;Lord of the Swamp 'Gnome'"
 *   type: intro
 *   goal: capture
 *   region: region/harbor
 *   arena: {mode: field, world: harbor_main, slots: [{center: [180, 62, -220], radius: 20}],
 *           deck: {spawn: [0, 1, 0], boss: [0, -1, -12], wheel: [0, 1, 3], repair: [[-2, 1, 0]]}}
 *   party: {min: 1, max: 2, base: 1}
 *   hp: {base: 2000}
 *   rig: rig/boss/gnome
 *   parts: [{id: head, bone: head, weakpoint: true}, {id: tail, bone: tail_2, armor: true}]
 *   hull: {max: 100, repair_per_action: 10}
 *   phases: [{id: hook}, {id: counter, patterns: [mud_spit]}, {id: final_tug, enter: {hp_below: 0.2}}]
 *   patterns: [{id: mud_spit, telegraph: {shape: circle, size: 4}, effect: {hull_damage: 8}}]
 *   fish: fish/swamp_catfish
 *   loot: {drops: [{item: item/material/boss_scale, chance: 1.0, count: 2}], gold: {min: 300, max: 300}}
 *   summon: {item: item/boss/summon_swamp_catfish, cooldown: 600}
 * </pre>
 *
 * @param story story fight (proposed: no loot, records or weekly count; 10.1 Lord of the Trench
 *     calming fight)
 * @param timeLimitSeconds 0 means the type default
 * @param rigScale rig size multiplier (proposed)
 * @param fish linked boss fish (guaranteed catch; collection only if it is a release_only fish)
 */
public record BossDef(
        BossId id,
        String name,
        BossType type,
        Goal goal,
        RegionId region,
        boolean story,
        Arena arena,
        PartyRule party,
        Hp hp,
        int timeLimitSeconds,
        RigId rig,
        double rigScale,
        List<Part> parts,
        Hull hull,
        List<PhaseDef> phases,
        List<PatternDef> patterns,
        @Nullable FishId fish,
        Loot loot,
        @Nullable Summon summon,
        Presentation presentation) {

    /** Hit part cap (proposed value, docs 20 10.3). */
    public static final int MAX_HIT_PARTS = 8;

    /** Rig part cap ('8. System details' chapter 12). */
    public static final int MAX_RIG_PARTS = 30;

    /**
     * Goal: capture or calm (Lord of the Trench and Verdigris Thunderfish use a rage gauge instead
     * of HP).
     */
    public enum Goal implements Keyed {
        CAPTURE,
        CALM;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /** Arena mode (docs 20 10.4). */
    public enum ArenaMode implements Keyed {
        /**
         * Fixed deck slot inside a main server region. The boss is visible to party members only.
         */
        FIELD,
        /** Arena cell on an instance server (raids). */
        INSTANCE,
        /** Fixed location on the main server, visible to everyone (world boss). */
        WORLD;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /** One arena slot. center is in world coordinates, radius is the boundary radius (blocks). */
    public record Slot(Vec3 center, double radius) {}

    /**
     * Fixed deck structure (coordinates relative to the slot center).
     *
     * @param spawn deck position where party members stand
     * @param boss boss spawn position
     * @param wheel helm Interaction position (captain dodging; no dodge input if absent)
     * @param repair damaged spots (mechanic repairs)
     */
    public record Deck(Vec3 spawn, Vec3 boss, @Nullable Vec3 wheel, List<Vec3> repair) {}

    public record Arena(ArenaMode mode, String world, List<Slot> slots, Deck deck) {}

    /**
     * @param base balance reference party size (reference value for HP scaling)
     */
    public record PartyRule(int min, int max, int base) {}

    /**
     * @param perPlayerRate HP increase per player. Negative means the type default
     */
    public record Hp(double base, double perPlayerRate) {}

    /**
     * Hit part. The hitbox size comes from the rig bone's hitbox (so the rig and boss definition
     * stay in sync).
     *
     * @param weakpoint part that glows during weak point exposure and takes a 3.0 damage multiplier
     * @param armor hard part (damage multiplier 0.2)
     */
    public record Part(String id, String bone, boolean weakpoint, boolean armor) {}

    /**
     * Ship durability.
     *
     * @param repairPerAction amount restored per repair kit use
     */
    public record Hull(int max, int repairPerAction) {}

    /** Phase kind (docs 20 10.4 state machine). */
    public enum PhaseKind implements Keyed {
        HOOK,
        COUNTER,
        FINAL_TUG;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /**
     * Final tug-of-war settings. 0 means the balance.yml boss.qte default.
     *
     * @param rounds number of rounds (default 3)
     * @param inputs inputs per round (default 4)
     * @param windowTicks time limit per input (default 20 ticks = 1.0s)
     * @param pass round success ratio (default 0.6)
     */
    public record Qte(int rounds, int inputs, int windowTicks, double pass) {
        public static final Qte DEFAULT = new Qte(0, 0, 0, 0);
    }

    /**
     * One phase.
     *
     * @param hpBelow entry condition (final_tug: HP ratio at or below this value). 0 means no
     *     condition
     * @param patterns pattern IDs chosen in this phase
     * @param patternGapTicks minimum gap between patterns (0 means the type default)
     * @param weakIntervalTicks weak point exposure interval (counter, 0 means the default)
     * @param weakTicks weak point exposure duration (counter, 0 means the default)
     * @param hookTicks safe-zone hold time to fill the hook gauge from 0 to 100% (hook, 0 means the
     *     default)
     */
    public record PhaseDef(
            PhaseKind kind,
            double hpBelow,
            List<String> patterns,
            int patternGapTicks,
            int weakIntervalTicks,
            int weakTicks,
            int hookTicks,
            Qte qte) {}

    /** Telegraph area shape (docs 20 10.3). */
    public enum Shape implements Keyed {
        CIRCLE,
        LINE,
        CONE,
        RING;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /** Telegraph area center (proposed). */
    public enum Anchor implements Keyed {
        /** At the feet of a random party member. */
        MEMBER,
        /** Deck center. */
        DECK,
        /** From the boss position toward the deck (line/cone direction). */
        BOSS;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /**
     * Telegraph. size is the circle/cone radius, line width, or ring outer radius.
     *
     * @param length line length (blocks)
     * @param angle full cone angle (degrees)
     * @param inner ring inner radius
     * @param ticks telegraph time (0 means the type default; ping compensation is added at runtime)
     */
    public record Telegraph(
            Shape shape,
            double size,
            double length,
            double angle,
            double inner,
            int ticks,
            Anchor at) {}

    /** Debuff (personal effect, shown on the action bar). */
    public enum Debuff implements Keyed {
        NONE,
        /** Mud splash (blocks vision). */
        BLIND,
        /** Ink (blindness). */
        DARKNESS,
        SLOW,
        /** Gravity reversal (levitation). */
        LEVITATE,
        /**
         * Control reversal (Void Nebula Fish nebula burst). QTE left/right and forward/back are
         * flipped.
         */
        INVERT;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /**
     * Pattern effect. Knockback for players in range and ship durability loss; debuffs are personal
     * effects.
     *
     * @param tension value added to anchor tension (proposed, 0-1)
     */
    public record Effect(
            int hullDamage, double knockback, Debuff debuff, int debuffTicks, double tension) {}

    /**
     * One pattern.
     *
     * @param name name shown on the action bar (MiniMessage)
     * @param animation one-shot rig animation (not played if absent)
     * @param charge charge pattern (dodged when the captain steers the opposite way at the helm)
     */
    public record PatternDef(
            String id,
            String name,
            @Nullable String animation,
            Telegraph telegraph,
            Effect effect,
            int cooldownTicks,
            int weight,
            boolean charge) {}

    /**
     * One drop.
     *
     * @param pity pity target (guaranteed after missing it loot.pity_after times in a row)
     */
    public record Drop(ItemId item, double chance, int countMin, int countMax, boolean pity) {}

    /**
     * Loot.
     *
     * @param goldMin clear gold (−1 means the type default)
     * @param topBonus extra rewards for the top 20% by contribution
     * @param pityAfter raid pity (0 means none)
     * @param participation participation reward on failure (1 boss scale)
     * @param title clear title (null if none)
     */
    public record Loot(
            List<Drop> drops,
            long goldMin,
            long goldMax,
            List<Drop> topBonus,
            int pityAfter,
            @Nullable Drop participation,
            @Nullable HarborId title) {}

    /**
     * Summon bait.
     *
     * @param cooldownSeconds resummon cooldown for the same party leader
     */
    public record Summon(ItemId item, int cooldownSeconds) {}

    /**
     * Presentation.
     *
     * @param intro one-shot rig animation on spawn
     * @param outro cutscene ID after a clear (only when the NPC module is present)
     * @param music sound key played at the start
     * @param roar roar sound key (played at the boss position on spawn, counter and final
     *     tug-of-war; '15. Sound' 4.3, proposed)
     * @param announce server announcement for kills and new records
     */
    public record Presentation(
            @Nullable String intro,
            @Nullable HarborId outro,
            @Nullable String music,
            @Nullable String roar,
            boolean announce) {}

    public static final ContentType.Entries<BossDef> TYPE =
            new ContentType.Entries<>(
                    "boss",
                    List.of("bosses/*.yml"),
                    BossId.PREFIX,
                    BossDef::parse,
                    b -> b.id().id());

    private static final HarborId PLACEHOLDER = HarborId.parse("boss/invalid");
    private static final Set<String> FIELDS =
            Set.of(
                    "id",
                    "name",
                    "type",
                    "goal",
                    "region",
                    "story",
                    "arena",
                    "party",
                    "hp",
                    "time_limit",
                    "rig",
                    "rig_scale",
                    "parts",
                    "hull",
                    "phases",
                    "patterns",
                    "fish",
                    "loot",
                    "summon",
                    "presentation");

    // ------------------------------------------------------------------ Lookup

    public Optional<PhaseDef> phase(PhaseKind kind) {
        return phases.stream().filter(p -> p.kind() == kind).findFirst();
    }

    public Optional<PatternDef> pattern(String patternId) {
        return patterns.stream().filter(p -> p.id().equals(patternId)).findFirst();
    }

    public Optional<Part> partByBone(String bone) {
        return parts.stream().filter(p -> p.bone().equals(bone)).findFirst();
    }

    /**
     * HP ratio for entering the final tug-of-war (if absent, definition checks reject it rather
     * than using a 0.2 proposed value).
     */
    public double finalTugBelow() {
        return phase(PhaseKind.FINAL_TUG).map(PhaseDef::hpBelow).orElse(0.0);
    }

    public boolean isWorld() {
        return arena.mode() == ArenaMode.WORLD;
    }

    // ------------------------------------------------------------------ Parsing

    static BossDef parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(FIELDS);
        HarborId rawId = node.require("id").flatMap(n -> n.asId(BossId.PREFIX)).orElse(PLACEHOLDER);
        BossType type = node.enumValue("type", BossType.class).orElse(BossType.INTRO);
        Goal goal = node.get("goal").flatMap(n -> n.asEnum(Goal.class)).orElse(Goal.CAPTURE);
        RegionId region = new RegionId(HarborId.parse("region/harbor"));
        Optional<YamlNode> regionNode = node.require("region");
        if (regionNode.isPresent()) {
            Optional<HarborId> r = regionNode.get().asId(RegionId.PREFIX);
            if (r.isPresent()) {
                region = new RegionId(r.get());
                ctx.ref("region", r.get(), regionNode.get().pos());
            }
        }
        Arena arena = parseArena(node, type);
        PartyRule party = parseParty(node, type);
        Hp hp = parseHp(node);
        int timeLimit = node.integer("time_limit", 0, 0, 7200);
        RigId rig = new RigId(HarborId.parse("rig/invalid"));
        Optional<YamlNode> rigNode = node.require("rig");
        if (rigNode.isPresent()) {
            Optional<HarborId> r = rigNode.get().asId(RigId.PREFIX);
            if (r.isPresent()) {
                rig = new RigId(r.get());
                ctx.ref("rig", r.get(), rigNode.get().pos());
            }
        }
        double rigScale = node.number("rig_scale", 1.0);
        if (rigScale <= 0) {
            node.error("rig_scale은 0보다 커야 합니다");
            rigScale = 1.0;
        }
        List<Part> parts = parseParts(node);
        Hull hull = parseHull(node);
        List<PatternDef> patterns = parsePatterns(node);
        List<PhaseDef> phases = parsePhases(node, patterns);
        FishId fish = null;
        Optional<YamlNode> fishNode = node.get("fish");
        if (fishNode.isPresent()) {
            Optional<HarborId> f = fishNode.get().asId(FishId.PREFIX);
            if (f.isPresent()) {
                fish = new FishId(f.get());
                ctx.ref("fish", f.get(), fishNode.get().pos());
            }
        }
        boolean story = node.bool("story", false);
        Loot loot = parseLoot(node, ctx);
        Summon summon = parseSummon(node, ctx);
        Presentation presentation = parsePresentation(node, ctx);
        if (arena.mode() == ArenaMode.WORLD && type != BossType.WORLD) {
            node.error("arena.mode: world는 type: world 보스만 쓸 수 있습니다");
        }
        if (type == BossType.WORLD && arena.mode() != ArenaMode.WORLD) {
            node.error("월드 보스는 arena.mode: world여야 합니다");
        }
        return new BossDef(
                new BossId(rawId),
                node.string("name"),
                type,
                goal,
                region,
                story,
                arena,
                party,
                hp,
                timeLimit,
                rig,
                rigScale,
                parts,
                hull,
                phases,
                patterns,
                fish,
                loot,
                summon,
                presentation);
    }

    private static Arena parseArena(YamlNode node, BossType type) {
        Optional<YamlNode> a = node.require("arena");
        if (a.isEmpty()) {
            return new Arena(
                    ArenaMode.FIELD,
                    "harbor_main",
                    List.of(new Slot(Vec3.ZERO, 16)),
                    new Deck(Vec3.ZERO, Vec3.ZERO, null, List.of()));
        }
        YamlNode arena = a.get();
        arena.allowOnly(Set.of("mode", "world", "slots", "deck"));
        ArenaMode mode = arena.enumValue("mode", ArenaMode.class).orElse(ArenaMode.FIELD);
        List<Slot> slots = new ArrayList<>();
        for (YamlNode s : arena.list("slots")) {
            s.allowOnly(Set.of("center", "radius"));
            double radius = s.number("radius", 16);
            if (radius < 4 || radius > 256) {
                s.error("radius는 4~256이어야 합니다: " + radius);
            }
            slots.add(
                    new Slot(
                            s.get("center").map(BossDef::vec).orElseGet(() -> required(s)),
                            radius));
        }
        if (slots.isEmpty()) {
            arena.error("slots가 하나 이상 있어야 합니다");
            slots.add(new Slot(Vec3.ZERO, 16));
        }
        if (mode == ArenaMode.WORLD && slots.size() != 1) {
            arena.error("월드 경기장은 슬롯이 하나여야 합니다");
        }
        Deck deck = new Deck(new Vec3(0, 1, 0), new Vec3(0, 0, -12), null, List.of());
        Optional<YamlNode> d = arena.get("deck");
        if (d.isPresent()) {
            YamlNode dn = d.get();
            dn.allowOnly(Set.of("spawn", "boss", "wheel", "repair"));
            List<Vec3> repair = new ArrayList<>();
            for (YamlNode r : dn.list("repair")) {
                repair.add(vec(r));
            }
            deck =
                    new Deck(
                            dn.get("spawn").map(BossDef::vec).orElse(new Vec3(0, 1, 0)),
                            dn.get("boss").map(BossDef::vec).orElse(new Vec3(0, 0, -12)),
                            dn.get("wheel").map(BossDef::vec).orElse(null),
                            List.copyOf(repair));
        }
        if (type == BossType.WORLD && mode != ArenaMode.WORLD) {
            arena.error("월드 보스 경기장은 mode: world");
        }
        return new Arena(mode, arena.string("world", "harbor_main"), List.copyOf(slots), deck);
    }

    private static PartyRule parseParty(YamlNode node, BossType type) {
        Optional<YamlNode> p = node.get("party");
        if (p.isEmpty()) {
            return type == BossType.WORLD ? new PartyRule(1, 1000, 10) : new PartyRule(1, 10, 1);
        }
        YamlNode party = p.get();
        party.allowOnly(Set.of("min", "max", "base"));
        int limit = type == BossType.WORLD ? 1000 : 10;
        int min = party.integer("min", 1, 1, limit);
        int max = party.integer("max", min, 1, limit);
        int base = party.integer("base", min, 1, limit);
        if (min > max) {
            party.error("party.min이 max보다 큽니다");
        }
        if (base < min || base > max) {
            party.error("party.base는 min~max 사이여야 합니다");
        }
        return new PartyRule(min, max, base);
    }

    private static Hp parseHp(YamlNode node) {
        Optional<YamlNode> h = node.require("hp");
        if (h.isEmpty()) {
            return new Hp(1, -1);
        }
        YamlNode hp = h.get();
        hp.allowOnly(Set.of("base", "per_player_rate"));
        double base = hp.number("base");
        if (base <= 0) {
            hp.error("hp.base는 0보다 커야 합니다");
            base = 1;
        }
        double rate = hp.number("per_player_rate", -1);
        if (rate > 5) {
            hp.error("per_player_rate가 너무 큽니다: " + rate);
        }
        return new Hp(base, rate);
    }

    private static List<Part> parseParts(YamlNode node) {
        List<Part> parts = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (YamlNode p : node.list("parts")) {
            p.allowOnly(Set.of("id", "bone", "weakpoint", "armor"));
            String id = p.string("id");
            if (!ids.add(id)) {
                p.error("부위 ID가 겹칩니다: " + id);
            }
            boolean weak = p.bool("weakpoint", false);
            boolean armor = p.bool("armor", false);
            if (weak && armor) {
                p.error("weakpoint와 armor를 같이 쓸 수 없습니다");
            }
            parts.add(new Part(id, p.string("bone", id), weak, armor));
        }
        if (parts.isEmpty()) {
            node.error("parts(판정 부위)가 하나 이상 있어야 합니다");
        }
        if (parts.size() > MAX_HIT_PARTS) {
            node.error("판정 부위는 " + MAX_HIT_PARTS + "개 이하여야 합니다: " + parts.size());
        }
        return List.copyOf(parts);
    }

    private static Hull parseHull(YamlNode node) {
        Optional<YamlNode> h = node.get("hull");
        if (h.isEmpty()) {
            return new Hull(100, 10);
        }
        YamlNode hull = h.get();
        hull.allowOnly(Set.of("max", "repair_per_action"));
        return new Hull(
                hull.integer("max", 100, 1, 100_000),
                hull.integer("repair_per_action", 10, 1, 100_000));
    }

    private static List<PatternDef> parsePatterns(YamlNode node) {
        List<PatternDef> patterns = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (YamlNode p : node.list("patterns")) {
            p.allowOnly(
                    Set.of(
                            "id",
                            "name",
                            "animation",
                            "telegraph",
                            "effect",
                            "cooldown",
                            "weight",
                            "charge"));
            String id = p.string("id");
            if (!ids.add(id)) {
                p.error("패턴 ID가 겹칩니다: " + id);
            }
            Telegraph telegraph = new Telegraph(Shape.CIRCLE, 3, 0, 0, 0, 0, Anchor.MEMBER);
            Optional<YamlNode> t = p.require("telegraph");
            if (t.isPresent()) {
                YamlNode tn = t.get();
                tn.allowOnly(Set.of("shape", "size", "length", "angle", "inner", "ticks", "at"));
                Shape shape = tn.enumValue("shape", Shape.class).orElse(Shape.CIRCLE);
                double size = tn.number("size");
                if (size <= 0 || size > 64) {
                    tn.error("size는 0~64여야 합니다");
                }
                double length = tn.number("length", shape == Shape.LINE ? 12 : 0);
                double angle = tn.number("angle", shape == Shape.CONE ? 60 : 0);
                double inner = tn.number("inner", shape == Shape.RING ? size / 2 : 0);
                if (shape == Shape.RING && inner >= size) {
                    tn.error("ring의 inner는 size보다 작아야 합니다");
                }
                if (shape == Shape.CONE && (angle <= 0 || angle > 360)) {
                    tn.error("cone angle은 0~360도여야 합니다");
                }
                Anchor at =
                        tn.get("at")
                                .flatMap(n -> n.asEnum(Anchor.class))
                                .orElse(
                                        shape == Shape.LINE || shape == Shape.CONE
                                                ? Anchor.BOSS
                                                : Anchor.MEMBER);
                telegraph =
                        new Telegraph(
                                shape,
                                size,
                                length,
                                angle,
                                inner,
                                tn.integer("ticks", 0, 0, 200),
                                at);
            }
            Effect effect = new Effect(0, 0, Debuff.NONE, 0, 0);
            Optional<YamlNode> e = p.get("effect");
            if (e.isPresent()) {
                YamlNode en = e.get();
                en.allowOnly(
                        Set.of("hull_damage", "knockback", "debuff", "debuff_seconds", "tension"));
                Debuff debuff =
                        en.get("debuff").flatMap(n -> n.asEnum(Debuff.class)).orElse(Debuff.NONE);
                double tension = en.number("tension", 0);
                if (tension < -1 || tension > 1) {
                    en.error("tension은 −1~1이어야 합니다");
                }
                effect =
                        new Effect(
                                en.integer("hull_damage", 0, 0, 100_000),
                                en.number("knockback", 0),
                                debuff,
                                (int) Math.round(en.number("debuff_seconds", 3) * 20),
                                tension);
            }
            patterns.add(
                    new PatternDef(
                            id,
                            p.string("name", id),
                            p.optString("animation").orElse(null),
                            telegraph,
                            effect,
                            (int) Math.round(p.number("cooldown", 6) * 20),
                            p.integer("weight", 1, 1, 1000),
                            p.bool("charge", false)));
        }
        return List.copyOf(patterns);
    }

    private static List<PhaseDef> parsePhases(YamlNode node, List<PatternDef> patterns) {
        Set<String> patternIds = new HashSet<>();
        patterns.forEach(p -> patternIds.add(p.id()));
        Set<String> used = new HashSet<>();
        List<PhaseDef> phases = new ArrayList<>();
        for (YamlNode p : node.list("phases")) {
            p.allowOnly(
                    Set.of(
                            "id",
                            "enter",
                            "patterns",
                            "pattern_gap",
                            "weak_interval",
                            "weak_seconds",
                            "hook_seconds",
                            "qte"));
            PhaseKind kind = p.enumValue("id", PhaseKind.class).orElse(PhaseKind.COUNTER);
            double hpBelow = 0;
            Optional<YamlNode> enter = p.get("enter");
            if (enter.isPresent()) {
                enter.get().allowOnly(Set.of("hp_below"));
                hpBelow = enter.get().number("hp_below", 0);
                if (hpBelow < 0 || hpBelow >= 1) {
                    enter.get().error("hp_below는 0~1 사이여야 합니다");
                }
            }
            List<String> ids = p.strings("patterns");
            for (int i = 0; i < ids.size(); i++) {
                String id = ids.get(i);
                if (!patternIds.contains(id)) {
                    p.list("patterns").get(i).error("patterns에 없는 패턴입니다: " + id);
                }
                used.add(id);
            }
            Qte qte = Qte.DEFAULT;
            Optional<YamlNode> q = p.get("qte");
            if (q.isPresent()) {
                YamlNode qn = q.get();
                qn.allowOnly(Set.of("rounds", "inputs", "window_seconds", "pass"));
                double pass = qn.number("pass", 0);
                if (pass < 0 || pass > 1) {
                    qn.error("pass는 0~1이어야 합니다");
                }
                qte =
                        new Qte(
                                qn.integer("rounds", 0, 0, 9),
                                qn.integer("inputs", 0, 0, 12),
                                (int) Math.round(qn.number("window_seconds", 0) * 20),
                                pass);
            }
            phases.add(
                    new PhaseDef(
                            kind,
                            hpBelow,
                            List.copyOf(ids),
                            (int) Math.round(p.number("pattern_gap", 0) * 20),
                            (int) Math.round(p.number("weak_interval", 0) * 20),
                            (int) Math.round(p.number("weak_seconds", 0) * 20),
                            (int) Math.round(p.number("hook_seconds", 0) * 20),
                            qte));
        }
        List<PhaseKind> order = phases.stream().map(PhaseDef::kind).toList();
        if (!order.equals(List.of(PhaseKind.HOOK, PhaseKind.COUNTER, PhaseKind.FINAL_TUG))) {
            node.error("phases는 hook, counter, final_tug 순서로 하나씩 있어야 합니다: " + order);
        } else if (phases.get(2).hpBelow() <= 0) {
            node.error("final_tug에는 enter.hp_below가 있어야 합니다");
        }
        for (PatternDef pattern : patterns) {
            if (!used.contains(pattern.id())) {
                node.error("어느 페이즈에서도 쓰지 않는 패턴입니다: " + pattern.id());
            }
        }
        return List.copyOf(phases);
    }

    private static Loot parseLoot(YamlNode node, ParseContext ctx) {
        Optional<YamlNode> l = node.get("loot");
        if (l.isEmpty()) {
            return new Loot(List.of(), 0, 0, List.of(), 0, null, null);
        }
        YamlNode loot = l.get();
        loot.allowOnly(
                Set.of("drops", "gold", "top_bonus", "pity_after", "participation", "title"));
        List<Drop> drops = new ArrayList<>();
        for (YamlNode d : loot.list("drops")) {
            drops.add(parseDrop(d, ctx));
        }
        List<Drop> top = new ArrayList<>();
        for (YamlNode d : loot.list("top_bonus")) {
            top.add(parseDrop(d, ctx));
        }
        long goldMin = -1;
        long goldMax = -1;
        Optional<YamlNode> g = loot.get("gold");
        if (g.isPresent()) {
            g.get().allowOnly(Set.of("min", "max"));
            goldMin = g.get().longValue("min", 0);
            goldMax = g.get().longValue("max", goldMin);
            if (goldMin < 0 || goldMax < goldMin) {
                g.get().error("gold는 0 ≤ min ≤ max여야 합니다");
            }
        }
        Drop participation = loot.get("participation").map(d -> parseDrop(d, ctx)).orElse(null);
        HarborId title = null;
        Optional<YamlNode> t = loot.get("title");
        if (t.isPresent()) {
            Optional<HarborId> id = t.get().asId("title/");
            if (id.isPresent()) {
                title = id.get();
                ctx.ref("title", id.get(), t.get().pos());
            }
        }
        int pityAfter = loot.integer("pity_after", 0, 0, 1000);
        if (pityAfter > 0 && drops.stream().noneMatch(Drop::pity)) {
            loot.error("pity_after가 있으면 pity: true인 드롭이 하나 이상 있어야 합니다");
        }
        return new Loot(
                List.copyOf(drops),
                goldMin,
                goldMax,
                List.copyOf(top),
                pityAfter,
                participation,
                title);
    }

    private static Drop parseDrop(YamlNode d, ParseContext ctx) {
        d.allowOnly(Set.of("item", "chance", "count", "pity"));
        ItemId item = new ItemId(HarborId.parse("item/invalid"));
        Optional<YamlNode> itemNode = d.require("item");
        if (itemNode.isPresent()) {
            Optional<HarborId> id = itemNode.get().asId(ItemId.PREFIX);
            if (id.isPresent()) {
                item = new ItemId(id.get());
                ctx.ref("item", id.get(), itemNode.get().pos());
            }
        }
        double chance = d.number("chance", 1.0);
        if (chance <= 0 || chance > 1) {
            d.error("chance는 0~1 사이여야 합니다");
        }
        int min = 1;
        int max = 1;
        Optional<YamlNode> count = d.get("count");
        if (count.isPresent()) {
            if (count.get().isList()) {
                List<YamlNode> range = count.get().asList();
                if (range.size() != 2) {
                    count.get().error("count는 숫자 또는 [최소, 최대]");
                } else {
                    min = range.get(0).asInt();
                    max = range.get(1).asInt();
                }
            } else {
                min = count.get().asInt();
                max = min;
            }
        }
        if (min < 1 || max < min || max > 64) {
            d.error("count는 1 ≤ 최소 ≤ 최대 ≤ 64여야 합니다");
            min = 1;
            max = Math.max(1, Math.min(64, max));
        }
        return new Drop(item, chance, min, max, d.bool("pity", false));
    }

    private static @Nullable Summon parseSummon(YamlNode node, ParseContext ctx) {
        Optional<YamlNode> s = node.get("summon");
        if (s.isEmpty()) {
            return null;
        }
        YamlNode summon = s.get();
        summon.allowOnly(Set.of("item", "cooldown"));
        Optional<YamlNode> itemNode = summon.require("item");
        if (itemNode.isEmpty()) {
            return null;
        }
        Optional<HarborId> id = itemNode.get().asId(ItemId.PREFIX);
        if (id.isEmpty()) {
            return null;
        }
        ctx.ref("item", id.get(), itemNode.get().pos());
        return new Summon(new ItemId(id.get()), summon.integer("cooldown", 600, 0, 86_400));
    }

    private static Presentation parsePresentation(YamlNode node, ParseContext ctx) {
        Optional<YamlNode> p = node.get("presentation");
        if (p.isEmpty()) {
            return new Presentation(null, null, null, null, true);
        }
        YamlNode pres = p.get();
        pres.allowOnly(Set.of("intro", "outro", "music", "roar", "announce"));
        HarborId outro = null;
        Optional<YamlNode> o = pres.get("outro");
        if (o.isPresent()) {
            Optional<HarborId> id = o.get().asId("cutscene/");
            if (id.isPresent()) {
                outro = id.get();
                ctx.ref("cutscene", id.get(), o.get().pos());
            }
        }
        return new Presentation(
                pres.optString("intro").orElse(null),
                outro,
                pres.optString("music").orElse(null),
                pres.optString("roar").orElse(null),
                pres.bool("announce", true));
    }

    private static Vec3 vec(YamlNode node) {
        List<YamlNode> xyz = node.asList();
        if (xyz.size() != 3) {
            node.error("[x, y, z] 세 값이어야 합니다");
            return Vec3.ZERO;
        }
        return new Vec3(xyz.get(0).asDouble(), xyz.get(1).asDouble(), xyz.get(2).asDouble());
    }

    private static Vec3 required(YamlNode node) {
        node.error("필수 필드 'center'가 없습니다");
        return Vec3.ZERO;
    }
}
