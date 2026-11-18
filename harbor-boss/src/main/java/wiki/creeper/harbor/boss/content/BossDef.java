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
 * 보스 정의 (bosses/*.yml, docs 20 10.3). 수치가 비어 있으면(0 또는 −1) 유형 기본값을 balance.yml {@code
 * boss.types.<유형>}에서 가져온다. 진행 중 조우는 시작 시점 정의를 그대로 쓴다 (리로드해도 바뀌지 않음, 10.6).
 *
 * <pre>
 * - id: boss/swamp_catfish
 *   name: "&lt;dark_green&gt;늪지의 주인 '그놈'"
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
 * @param story 스토리 전투 (추가 제안: 전리품·기록·주간 횟수 없음, 10.1 해구의 군주 진정 전투)
 * @param timeLimitSeconds 0이면 유형 기본값
 * @param rigScale 리그 크기 배율 (추가 제안)
 * @param fish 연결된 보스 어종 (확정 포획. release_only 어종이면 도감만)
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

    /** 판정 부위 상한 (제안값, docs 20 10.3). */
    public static final int MAX_HIT_PARTS = 8;

    /** 리그 부위 상한 ('8. 시스템 상세 기획' 12장). */
    public static final int MAX_RIG_PARTS = 30;

    /** 목표: 포획 또는 진정 (해구의 군주·녹빛 폭뢰어는 체력 대신 분노 게이지). */
    public enum Goal implements Keyed {
        CAPTURE,
        CALM;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /** 경기장 방식 (docs 20 10.4). */
    public enum ArenaMode implements Keyed {
        /** 본 서버 지역 안 고정 갑판 슬롯. 보스는 파티원에게만 보인다. */
        FIELD,
        /** 인스턴스 서버의 경기장 칸 (레이드). */
        INSTANCE,
        /** 본 서버 고정 위치, 모두에게 보인다 (월드 보스). */
        WORLD;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /** 경기장 슬롯 하나. center는 월드 좌표, radius는 경계 반경 (블록). */
    public record Slot(Vec3 center, double radius) {}

    /**
     * 고정 갑판 구조물 (슬롯 중심 기준 상대 좌표).
     *
     * @param spawn 파티원이 서는 갑판 위치
     * @param boss 보스 등장 위치
     * @param wheel 조타륜 Interaction 위치 (선장 회피, 없으면 회피 입력 없음)
     * @param repair 파손 지점 (정비사 수리)
     */
    public record Deck(Vec3 spawn, Vec3 boss, @Nullable Vec3 wheel, List<Vec3> repair) {}

    public record Arena(ArenaMode mode, String world, List<Slot> slots, Deck deck) {}

    /**
     * @param base 밸런스 기준 인원 (체력 조정 참고값)
     */
    public record PartyRule(int min, int max, int base) {}

    /**
     * @param perPlayerRate 인원당 체력 증가율. 음수면 유형 기본값
     */
    public record Hp(double base, double perPlayerRate) {}

    /**
     * 판정 부위. 판정 상자 크기는 리그 뼈의 hitbox를 쓴다 (리그와 보스 정의가 어긋나지 않게).
     *
     * @param weakpoint 약점 노출 주기에 발광하고 피해 배율 3.0을 받는 부위
     * @param armor 단단한 부위 (피해 배율 0.2)
     */
    public record Part(String id, String bone, boolean weakpoint, boolean armor) {}

    /**
     * 배 내구도.
     *
     * @param repairPerAction 수리 키트 1회 복구량
     */
    public record Hull(int max, int repairPerAction) {}

    /** 페이즈 종류 (docs 20 10.4 상태 기계). */
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
     * 최후의 줄다리기 설정. 0이면 balance.yml boss.qte 기본값.
     *
     * @param rounds 라운드 수 (기본 3)
     * @param inputs 라운드당 입력 수 (기본 4)
     * @param windowTicks 입력 하나 제한 시간 (기본 20틱 = 1.0초)
     * @param pass 라운드 성공 비율 (기본 0.6)
     */
    public record Qte(int rounds, int inputs, int windowTicks, double pass) {
        public static final Qte DEFAULT = new Qte(0, 0, 0, 0);
    }

    /**
     * 페이즈 하나.
     *
     * @param hpBelow 들어가는 조건 (final_tug: 체력 비율이 이 값 이하). 0이면 조건 없음
     * @param patterns 이 페이즈에서 고르는 패턴 ID
     * @param patternGapTicks 패턴 사이 최소 간격 (0이면 유형 기본값)
     * @param weakIntervalTicks 약점 노출 주기 (counter, 0이면 기본값)
     * @param weakTicks 약점 노출 시간 (counter, 0이면 기본값)
     * @param hookTicks 걸기 게이지를 0 → 100%로 채우는 안전 구간 유지 시간 (hook, 0이면 기본값)
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

    /** 예고 범위 모양 (docs 20 10.3). */
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

    /** 예고 범위 중심 (추가 제안). */
    public enum Anchor implements Keyed {
        /** 무작위 파티원 발밑. */
        MEMBER,
        /** 갑판 중심. */
        DECK,
        /** 보스 위치에서 갑판 쪽으로 (line·cone 방향). */
        BOSS;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /**
     * 예고. size는 circle·cone 반경, line 폭, ring 바깥 반경.
     *
     * @param length line 길이 (블록)
     * @param angle cone 전체 각도 (도)
     * @param inner ring 안쪽 반경
     * @param ticks 예고 시간 (0이면 유형 기본값, 핑 보정은 실행 중 더함)
     */
    public record Telegraph(
            Shape shape,
            double size,
            double length,
            double angle,
            double inner,
            int ticks,
            Anchor at) {}

    /** 디버프 (개인 효과, 액션바 표시). */
    public enum Debuff implements Keyed {
        NONE,
        /** 진흙 뿌리기(시야 가림). */
        BLIND,
        /** 먹물 (실명). */
        DARKNESS,
        SLOW,
        /** 중력 반전 (공중 부양). */
        LEVITATE,
        /** 조작 반전 (공허 성운어 성운 폭발). QTE 좌우·앞뒤가 뒤집힌다. */
        INVERT;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /**
     * 패턴 효과. 범위 안 플레이어 넉백·배 내구도 감소, 디버프는 개인 효과.
     *
     * @param tension 앵커 텐션에 더하는 값 (추가 제안, 0~1)
     */
    public record Effect(
            int hullDamage, double knockback, Debuff debuff, int debuffTicks, double tension) {}

    /**
     * 패턴 하나.
     *
     * @param name 액션바에 보여줄 이름 (MiniMessage)
     * @param animation 리그 한 번 재생 동작 (없으면 재생 안 함)
     * @param charge 돌진 패턴 (선장이 조타륜에서 반대 방향을 입력하면 피함)
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
     * 드롭 하나.
     *
     * @param pity 천장 대상 (loot.pity_after회 연속 못 얻으면 확정)
     */
    public record Drop(ItemId item, double chance, int countMin, int countMax, boolean pity) {}

    /**
     * 전리품.
     *
     * @param goldMin 클리어 골드 (−1이면 유형 기본값)
     * @param topBonus 기여도 상위 20%에게 더 주는 것
     * @param pityAfter 레이드 천장 (0이면 없음)
     * @param participation 실패 참여 보상 (보스 비늘 1개)
     * @param title 클리어 칭호 (없으면 null)
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
     * 소환 미끼.
     *
     * @param cooldownSeconds 같은 파티장의 재소환 대기
     */
    public record Summon(ItemId item, int cooldownSeconds) {}

    /**
     * 연출.
     *
     * @param intro 등장 때 리그 한 번 재생 동작
     * @param outro 클리어 뒤 컷씬 ID (NPC 모듈이 있을 때만)
     * @param music 시작 때 재생할 사운드 키
     * @param announce 처치·신기록 서버 공지
     */
    public record Presentation(
            @Nullable String intro,
            @Nullable HarborId outro,
            @Nullable String music,
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

    // ------------------------------------------------------------------ 조회

    public Optional<PhaseDef> phase(PhaseKind kind) {
        return phases.stream().filter(p -> p.kind() == kind).findFirst();
    }

    public Optional<PatternDef> pattern(String patternId) {
        return patterns.stream().filter(p -> p.id().equals(patternId)).findFirst();
    }

    public Optional<Part> partByBone(String bone) {
        return parts.stream().filter(p -> p.bone().equals(bone)).findFirst();
    }

    /** 최후의 줄다리기 진입 체력 비율 (없으면 0.2 제안값이 아니라 정의 검사에서 막는다). */
    public double finalTugBelow() {
        return phase(PhaseKind.FINAL_TUG).map(PhaseDef::hpBelow).orElse(0.0);
    }

    public boolean isWorld() {
        return arena.mode() == ArenaMode.WORLD;
    }

    // ------------------------------------------------------------------ 파싱

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
            return new Presentation(null, null, null, true);
        }
        YamlNode pres = p.get();
        pres.allowOnly(Set.of("intro", "outro", "music", "announce"));
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
