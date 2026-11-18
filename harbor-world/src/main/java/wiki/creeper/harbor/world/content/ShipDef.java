package wiki.creeper.harbor.world.content;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.content.type.SoundTable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RigId;
import wiki.creeper.harbor.common.yaml.YamlNode;
import wiki.creeper.harbor.world.api.ShipFlag;
import wiki.creeper.harbor.world.api.UpgradePath;

/**
 * 선박 정의 (world/ships.yml, docs 20 8.3 ships.yml, 추가 제안). 서버 API 없음.
 *
 * @param price 조선소 가격 (0 = 팔지 않음)
 * @param speed 속도 보정 (+0.30 = +30%)
 * @param cargo 화물 칸 (9의 배수)
 * @param starter 시즌 첫 접속 때 지급 (나룻배)
 * @param upgrades 갈래별 단계 누적값 (speed: 속도 보정 더하기, cargo: 칸 더하기, hull: 내구도 배율 더하기)
 * @param engineSound 항해 반복 소리 (기본: 쇄빙선 harbor:ship.icebreaker_loop, 나머지 harbor:ship.engine_loop,
 *     '15. 사운드' 4.4, 추가 제안). 빈 문자열이면 소리 없음
 * @param enginePitch 항해 소리 피치 (원양 탐사선·나룻배는 같은 소리를 피치만 바꿔 쓴다)
 */
public record ShipDef(
        HarborId id,
        String name,
        ItemId item,
        boolean chest,
        String wood,
        long price,
        double speed,
        int cargo,
        int durability,
        Set<ShipFlag> flags,
        Requires requires,
        boolean starter,
        @Nullable RigId decor,
        @Nullable RigId decorDonor,
        Map<UpgradePath, List<Double>> upgrades,
        String engineSound,
        double enginePitch) {

    public static final String ENGINE_LOOP = "harbor:ship.engine_loop";
    public static final String ICEBREAKER_LOOP = "harbor:ship.icebreaker_loop";

    /** 구매 조건. */
    public record Requires(int level, int jobTier, List<String> flags) {
        public static final Requires NONE = new Requires(0, 1, List.of());
    }

    public static final String PREFIX = "ship/";

    /** 화물 칸 상한 (상자 6줄). */
    public static final int MAX_CARGO = 54;

    public static final Set<String> WOODS =
            Set.of(
                    "oak",
                    "spruce",
                    "birch",
                    "jungle",
                    "acacia",
                    "dark_oak",
                    "mangrove",
                    "cherry",
                    "pale_oak");

    public static final ContentType.Entries<ShipDef> TYPE =
            new ContentType.Entries<>(
                    "ship", List.of("world/ships.yml"), PREFIX, ShipDef::parse, ShipDef::id);

    private static final Set<String> FIELDS =
            Set.of(
                    "id",
                    "name",
                    "item",
                    "base",
                    "wood",
                    "price",
                    "speed",
                    "cargo",
                    "durability",
                    "flags",
                    "requires",
                    "starter",
                    "decor",
                    "decor_donor",
                    "upgrades",
                    "engine_sound",
                    "engine_pitch");

    public boolean has(ShipFlag flag) {
        return flags.contains(flag);
    }

    /** 단계별 업그레이드 값 (0단계 = 0). */
    public double upgradeValue(UpgradePath path, int level) {
        List<Double> values = upgrades.getOrDefault(path, List.of());
        if (level <= 0 || values.isEmpty()) {
            return 0;
        }
        return values.get(Math.min(level, values.size()) - 1);
    }

    public int maxUpgrade(UpgradePath path) {
        return upgrades.getOrDefault(path, List.of()).size();
    }

    /** 엔티티 종류 이름 (예: SPRUCE_CHEST_BOAT). */
    public String entityName() {
        return wood.toUpperCase(java.util.Locale.ROOT) + (chest ? "_CHEST_BOAT" : "_BOAT");
    }

    static ShipDef parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(FIELDS);
        HarborId id =
                node.require("id")
                        .flatMap(n -> n.asId(PREFIX))
                        .orElse(HarborId.parse("ship/invalid"));
        HarborId item =
                node.require("item")
                        .flatMap(n -> n.asId(ItemId.PREFIX))
                        .orElse(HarborId.parse("item/invalid"));
        node.get("item").ifPresent(n -> ctx.ref("item", item, n.pos()));

        String base = node.string("base", "boat");
        if (!base.equals("boat") && !base.equals("chest_boat")) {
            node.error("base는 boat 또는 chest_boat입니다: '" + base + "'");
        }
        String wood = node.string("wood", "oak");
        if (!WOODS.contains(wood)) {
            node.error(
                    "wood는 " + String.join(" · ", WOODS.stream().sorted().toList()) + " 중 하나입니다");
        }
        int cargo = node.integer("cargo", 0, 0, MAX_CARGO);
        if (cargo % 9 != 0) {
            node.error("cargo는 9의 배수여야 합니다: " + cargo);
        }

        java.util.EnumSet<ShipFlag> flags = java.util.EnumSet.noneOf(ShipFlag.class);
        for (YamlNode f : node.list("flags")) {
            f.asEnum(ShipFlag.class).ifPresent(flags::add);
        }

        Requires requires = Requires.NONE;
        if (node.has("requires")) {
            YamlNode r = node.get("requires").orElseThrow();
            r.allowOnly(Set.of("level", "job_tier", "flags"));
            requires =
                    new Requires(
                            r.integer("level", 0, 0, 1000),
                            r.integer("job_tier", 1, 1, 3),
                            r.strings("flags"));
        }

        Map<UpgradePath, List<Double>> upgrades = new EnumMap<>(UpgradePath.class);
        node.get("upgrades")
                .ifPresent(
                        u -> {
                            u.allowOnly(Set.of("speed", "cargo", "hull"));
                            for (UpgradePath path : UpgradePath.values()) {
                                List<Double> values = new ArrayList<>();
                                double last = 0;
                                for (YamlNode v : u.list(path.key())) {
                                    double value = v.asDouble();
                                    if (value < last) {
                                        v.error("업그레이드 값은 단계마다 커져야 합니다 (누적값)");
                                    }
                                    if (path == UpgradePath.CARGO && value % 9 != 0) {
                                        v.error("cargo 업그레이드는 9의 배수여야 합니다");
                                    }
                                    last = value;
                                    values.add(value);
                                }
                                if (!values.isEmpty()) {
                                    upgrades.put(path, List.copyOf(values));
                                }
                            }
                        });

        return new ShipDef(
                id,
                node.string("name"),
                new ItemId(item),
                base.equals("chest_boat"),
                wood,
                node.longValue("price", 0),
                node.number("speed", 0),
                cargo,
                node.integer("durability", 100, 1, 100_000),
                Set.copyOf(flags),
                requires,
                node.bool("starter", false),
                rig(node, "decor", ctx),
                rig(node, "decor_donor", ctx),
                Map.copyOf(upgrades),
                engineSound(node, flags),
                enginePitch(node));
    }

    private static String engineSound(YamlNode node, Set<ShipFlag> flags) {
        String fallback = flags.contains(ShipFlag.ICE_BREAK) ? ICEBREAKER_LOOP : ENGINE_LOOP;
        if (!node.has("engine_sound")) {
            return fallback;
        }
        YamlNode n = node.get("engine_sound").orElseThrow();
        String key = n.isNull() ? "" : n.asString();
        if (!key.isEmpty() && !SoundTable.KEY.matcher(key).matches()) {
            n.error("소리 키 형식이 아닙니다 (예: harbor:ship.engine_loop): " + key);
            return fallback;
        }
        return key;
    }

    private static double enginePitch(YamlNode node) {
        double pitch = node.number("engine_pitch", 1.0);
        if (pitch < 0.5 || pitch > 2.0) {
            node.get("engine_pitch").orElse(node).error("engine_pitch는 0.5~2.0이어야 합니다");
            return 1.0;
        }
        return pitch;
    }

    private static @Nullable RigId rig(YamlNode node, String key, ParseContext ctx) {
        return node.get(key)
                .flatMap(
                        n ->
                                n.asId(RigId.PREFIX)
                                        .map(
                                                id -> {
                                                    ctx.ref("rig", id, n.pos());
                                                    return new RigId(id);
                                                }))
                .orElse(null);
    }
}
