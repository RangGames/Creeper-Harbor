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
 * Region extension (world/regions.yml, docs 20 8.3 regions.yml extra fields, all proposed). Core
 * RegionDef fixes the fields of regions.yml, so World-only fields live in this file under the same
 * region ID. No server API (harbor-tools checks it with the same code).
 *
 * @param order unlock order (1 harbor … 7 end)
 * @param hourlyGold gold per hour of manual fishing ('7. balance design' 3.2)
 * @param server ID of the server running this region's world (null = this server)
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

    /** 3D point. yaw is used only by stations and safe points. */
    public record Point(double x, double y, double z, float yaw) {}

    /**
     * Conditions added to RegionDef.unlock.
     *
     * @param flags player flags (entry flags set by Quest as chapter rewards, Core FlagService)
     * @param jobTier required job tier (job advancement quest, 1 = no condition)
     * @param vehicle ship ID that must be ridden (empty = no condition)
     * @param firstEntryOnly apply the vehicle condition only on the first visit
     */
    public record Unlock(
            List<String> flags, int jobTier, List<HarborId> vehicle, boolean firstEntryOnly) {
        public static final Unlock NONE = new Unlock(List.of(), 1, List.of(), false);
    }

    /**
     * Sub-area (matches the fish species area).
     *
     * @param minY height range (none = full height)
     */
    public record Area(String id, List<RegionDef.Point> bounds, int minY, int maxY) {}

    /** Station kind. */
    public enum DockKind {
        AIRSHIP,
        DIVE,
        PIER
    }

    public record Dock(String id, DockKind kind, Point at) {}

    /** Facility kind. */
    public enum StationType {
        SHIPYARD,
        HOUSING,
        DECOR_SHOP
    }

    public record Station(String id, StationType type, Point at) {}

    public record TreasureSite(String id, Point at, String type) {}

    public record HiddenSpot(String id, Point at) {}

    /** Environment debuff kind. */
    public enum EnvType {
        COLD,
        OXYGEN,
        HEAT
    }

    /**
     * Environment zone.
     *
     * @param area sub-area ID (null = whole region)
     * @param surfaceY water surface height (oxygen)
     * @param depth oxygen drains from this depth below the surface (oxygen)
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
