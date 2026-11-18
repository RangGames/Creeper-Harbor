package wiki.creeper.harbor.world.content;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.content.type.RegionDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * 지역 확장 (world/regions.yml, docs 20 8.3 regions.yml 추가 필드, 모두 추가 제안). Core RegionDef가 regions.yml의
 * 필드를 고정하므로 World만 쓰는 필드는 같은 지역 ID로 이 파일에 둔다. 서버 API 없음 (harbor-tools도 같은 코드로 검사).
 *
 * @param order 해금 순서 (1 항구 … 7 엔드)
 * @param hourlyGold 수동 낚시 시간당 골드 ('7. 밸런스 기획' 3.2)
 * @param server 이 지역 월드를 돌리는 서버 ID (null = 지금 서버)
 */
public record RegionExt(
        RegionId id,
        int order,
        int hourlyGold,
        @Nullable String server,
        @Nullable Point spawn,
        Unlock unlock,
        List<Area> areas,
        List<Dock> docks,
        List<Station> stations,
        List<TreasureSite> treasureSites,
        List<Point> spotCandidates,
        List<HiddenSpot> hiddenSpots,
        List<Env> env,
        List<Point> airPockets,
        @Nullable String iceArea) {

    /** 3차원 점. yaw는 정류장·안전 지점만 쓴다. */
    public record Point(double x, double y, double z, float yaw) {}

    /**
     * RegionDef.unlock에 더하는 조건.
     *
     * @param flags 플레이어 플래그 (Quest가 장 보상으로 세운 진입 플래그, Core FlagService)
     * @param jobTier 필요한 직업 단계 (전직 퀘스트, 1 = 조건 없음)
     * @param vehicle 탑승해야 하는 선박 ID (비우면 조건 없음)
     * @param firstEntryOnly 탈것 조건을 첫 방문에만 적용
     */
    public record Unlock(
            List<String> flags, int jobTier, List<HarborId> vehicle, boolean firstEntryOnly) {
        public static final Unlock NONE = new Unlock(List.of(), 1, List.of(), false);
    }

    /**
     * 세부 구역 (어종 area와 맞춘다).
     *
     * @param minY 높이 범위 (없으면 전체)
     */
    public record Area(String id, List<RegionDef.Point> bounds, int minY, int maxY) {}

    /** 정류장 종류. */
    public enum DockKind {
        AIRSHIP,
        DIVE,
        PIER
    }

    public record Dock(String id, DockKind kind, Point at) {}

    /** 시설 종류. */
    public enum StationType {
        SHIPYARD,
        HOUSING,
        DECOR_SHOP
    }

    public record Station(String id, StationType type, Point at) {}

    public record TreasureSite(String id, Point at, String type) {}

    public record HiddenSpot(String id, Point at) {}

    /** 환경 디버프 종류. */
    public enum EnvType {
        COLD,
        OXYGEN,
        HEAT
    }

    /**
     * 환경 구역.
     *
     * @param area 세부 구역 ID (null = 지역 전체)
     * @param surfaceY 수면 높이 (산소)
     * @param depth 수면 아래 이 깊이부터 산소 소모 (산소)
     */
    public record Env(EnvType type, @Nullable String area, int surfaceY, int depth) {}

    public static final ContentType.Entries<RegionExt> TYPE =
            new ContentType.Entries<>(
                    "world_region",
                    List.of("world/regions.yml"),
                    RegionId.PREFIX,
                    RegionExt::parse,
                    r -> r.id().id());

    private static final Pattern LOCAL_ID = Pattern.compile("[a-z0-9_]+");
    private static final Pattern SERVER_ID = Pattern.compile("[a-z0-9_-]{1,32}");

    private static final Set<String> FIELDS =
            Set.of(
                    "id",
                    "order",
                    "hourly_gold",
                    "server",
                    "spawn",
                    "unlock",
                    "areas",
                    "docks",
                    "stations",
                    "treasure_sites",
                    "spot_candidates",
                    "hidden_spots",
                    "env",
                    "air_pockets",
                    "ice_area");

    public Optional<Area> area(String areaId) {
        return areas.stream().filter(a -> a.id().equals(areaId)).findFirst();
    }

    public Optional<Dock> dock(DockKind kind) {
        return docks.stream().filter(d -> d.kind() == kind).findFirst();
    }

    public Optional<Station> station(StationType type) {
        return stations.stream().filter(s -> s.type() == type).findFirst();
    }

    public boolean hasEnv(EnvType type) {
        return env.stream().anyMatch(e -> e.type() == type);
    }

    public Optional<Env> env(EnvType type) {
        return env.stream().filter(e -> e.type() == type).findFirst();
    }

    static RegionExt parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(FIELDS);
        HarborId rawId =
                node.require("id")
                        .flatMap(n -> n.asId(RegionId.PREFIX))
                        .orElse(HarborId.parse("region/invalid"));
        node.get("id").ifPresent(n -> ctx.ref("region", rawId, n.pos()));

        String server = node.optString("server").orElse(null);
        if (server != null && !SERVER_ID.matcher(server).matches()) {
            node.error("server는 서버 ID(소문자·숫자·밑줄·하이픈)여야 합니다: '" + server + "'");
        }

        Unlock unlock = Unlock.NONE;
        if (node.has("unlock")) {
            YamlNode u = node.get("unlock").orElseThrow();
            u.allowOnly(Set.of("flags", "job_tier", "vehicle", "first_entry_only"));
            List<String> flags = new ArrayList<>();
            for (YamlNode f : u.list("flags")) {
                String flag = f.asString();
                if (!flag.matches("[a-z0-9_]+(/[a-z0-9_]+)*")) {
                    f.error("플래그 이름 형식이 틀렸습니다: '" + flag + "'");
                }
                flags.add(flag);
            }
            List<HarborId> vehicle = new ArrayList<>();
            for (YamlNode v : u.list("vehicle")) {
                v.asId(ShipDef.PREFIX)
                        .ifPresent(
                                id -> {
                                    vehicle.add(id);
                                    ctx.ref(ShipDef.TYPE.name(), id, v.pos());
                                });
            }
            unlock =
                    new Unlock(
                            List.copyOf(flags),
                            u.integer("job_tier", 1, 1, 3),
                            List.copyOf(vehicle),
                            u.bool("first_entry_only", false));
        }

        Set<String> areaIds = new HashSet<>();
        List<Area> areas = new ArrayList<>();
        for (YamlNode a : node.list("areas")) {
            a.allowOnly(Set.of("id", "bounds", "y_range"));
            String id = localId(a, "id");
            if (!areaIds.add(id)) {
                a.error("세부 구역 ID가 중복됩니다: " + id);
            }
            List<RegionDef.Point> bounds = points2(a.list("bounds"));
            if (bounds.size() < 3) {
                a.error("bounds는 꼭짓점이 3개 이상인 다각형이어야 합니다");
            }
            int minY = -2048;
            int maxY = 4096;
            List<YamlNode> range = a.list("y_range");
            if (!range.isEmpty()) {
                if (range.size() != 2) {
                    a.error("y_range는 [최소, 최대]입니다");
                } else {
                    minY = range.get(0).asInt();
                    maxY = range.get(1).asInt();
                    if (minY > maxY) {
                        a.error("y_range 최소가 최대보다 큽니다");
                    }
                }
            }
            areas.add(new Area(id, bounds, minY, maxY));
        }

        Set<String> localIds = new HashSet<>();
        List<Dock> docks = new ArrayList<>();
        for (YamlNode d : node.list("docks")) {
            d.allowOnly(Set.of("id", "kind", "x", "y", "z", "yaw"));
            String id = unique(d, localIds);
            DockKind kind = enumOf(d, "kind", DockKind.class, DockKind.PIER);
            docks.add(new Dock(id, kind, point(d)));
        }
        List<Station> stations = new ArrayList<>();
        for (YamlNode s : node.list("stations")) {
            s.allowOnly(Set.of("id", "type", "x", "y", "z", "yaw"));
            String id = unique(s, localIds);
            StationType type = enumOf(s, "type", StationType.class, StationType.SHIPYARD);
            stations.add(new Station(id, type, point(s)));
        }
        List<TreasureSite> sites = new ArrayList<>();
        for (YamlNode t : node.list("treasure_sites")) {
            t.allowOnly(Set.of("id", "x", "y", "z", "type"));
            String id = unique(t, localIds);
            sites.add(new TreasureSite(id, point(t), t.string("type", "beach")));
        }
        List<HiddenSpot> hidden = new ArrayList<>();
        for (YamlNode h : node.list("hidden_spots")) {
            h.allowOnly(Set.of("id", "x", "y", "z"));
            String id = unique(h, localIds);
            hidden.add(new HiddenSpot(id, point(h)));
        }

        List<Env> env = new ArrayList<>();
        for (YamlNode e : node.list("env")) {
            e.allowOnly(Set.of("type", "area", "surface_y", "depth"));
            EnvType type = enumOf(e, "type", EnvType.class, EnvType.COLD);
            String area = e.optString("area").orElse(null);
            if (area != null && !areaIds.contains(area)) {
                e.error("env.area '" + area + "'는 이 지역의 세부 구역이 아닙니다");
            }
            env.add(
                    new Env(
                            type,
                            area,
                            e.integer("surface_y", 62, -64, 320),
                            e.integer("depth", 20, 0, 384)));
        }

        String ice = node.optString("ice_area").orElse(null);
        if (ice != null && !areaIds.contains(ice)) {
            node.error("ice_area '" + ice + "'는 이 지역의 세부 구역이 아닙니다");
        }

        Point spawn = node.get("spawn").map(RegionExt::point).orElse(null);
        return new RegionExt(
                new RegionId(rawId),
                node.integer("order", 1, 1, 100),
                node.integer("hourly_gold", 0, 0, 1_000_000),
                server,
                spawn,
                unlock,
                List.copyOf(areas),
                List.copyOf(docks),
                List.copyOf(stations),
                List.copyOf(sites),
                points3(node.list("spot_candidates")),
                List.copyOf(hidden),
                List.copyOf(env),
                points3(node.list("air_pockets")),
                ice);
    }

    private static String unique(YamlNode n, Set<String> seen) {
        String id = localId(n, "id");
        if (!seen.add(id)) {
            n.error("ID가 이 지역 안에서 중복됩니다: " + id);
        }
        return id;
    }

    static String localId(YamlNode n, String key) {
        String id = n.string(key);
        if (!LOCAL_ID.matcher(id).matches()) {
            n.error(key + "는 소문자·숫자·밑줄입니다: '" + id + "'");
        }
        return id;
    }

    static <E extends Enum<E>> E enumOf(YamlNode n, String key, Class<E> type, E fallback) {
        String raw = n.string(key).toUpperCase(Locale.ROOT);
        for (E e : type.getEnumConstants()) {
            if (e.name().equals(raw)) {
                return e;
            }
        }
        List<String> names = new ArrayList<>();
        for (E e : type.getEnumConstants()) {
            names.add(e.name().toLowerCase(Locale.ROOT));
        }
        n.error(key + "는 " + String.join(" · ", names) + " 중 하나입니다: '" + raw + "'");
        return fallback;
    }

    static Point point(YamlNode n) {
        return new Point(n.number("x"), n.number("y"), n.number("z"), (float) n.number("yaw", 0));
    }

    static List<RegionDef.Point> points2(List<YamlNode> nodes) {
        List<RegionDef.Point> result = new ArrayList<>();
        for (YamlNode n : nodes) {
            List<YamlNode> xz = n.asList();
            if (xz.size() != 2) {
                n.error("좌표는 [x, z] 두 값이어야 합니다");
                continue;
            }
            result.add(new RegionDef.Point(xz.get(0).asDouble(), xz.get(1).asDouble()));
        }
        return List.copyOf(result);
    }

    static List<Point> points3(List<YamlNode> nodes) {
        List<Point> result = new ArrayList<>();
        for (YamlNode n : nodes) {
            List<YamlNode> xyz = n.asList();
            if (xyz.size() != 3) {
                n.error("좌표는 [x, y, z] 세 값이어야 합니다");
                continue;
            }
            result.add(
                    new Point(
                            xyz.get(0).asDouble(),
                            xyz.get(1).asDouble(),
                            xyz.get(2).asDouble(),
                            0));
        }
        return List.copyOf(result);
    }
}
