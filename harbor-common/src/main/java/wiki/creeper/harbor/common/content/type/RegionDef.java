package wiki.creeper.harbor.common.content.type;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.yaml.YamlNode;

/** Region definition (regions.yml). Fields follow '11. Data design' 2.4. */
public record RegionDef(
        RegionId id,
        String name,
        String world,
        List<Point> bounds,
        String biome,
        Unlock unlock,
        int fishingSpots,
        // Upper bound of the recommended level (proposed, '7. Balance design' 3.2). Basis for
        // regional XP decay. 0 means no decay
        int recLevel,
        List<Point> trapZone,
        @Nullable String music) {

    /** Horizontal coordinates (x, z). */
    public record Point(double x, double z) {}

    /** Entry requirement. level 0 = no requirement. */
    public record Unlock(int level, @Nullable QuestId quest, List<ItemId> items) {
        public static final Unlock NONE = new Unlock(0, null, List.of());
    }

    public static final Set<String> WORLDS = Set.of("harbor_main", "harbor_nether", "harbor_end");

    private static final Set<String> FIELDS =
            Set.of(
                    "id",
                    "name",
                    "world",
                    "bounds",
                    "biome",
                    "unlock",
                    "fishing_spots",
                    "trap_zone",
                    "music",
                    "rec_level");

    public static final ContentType.Entries<RegionDef> TYPE =
            new ContentType.Entries<>(
                    "region",
                    List.of("regions.yml"),
                    RegionId.PREFIX,
                    RegionDef::parse,
                    r -> r.id().id());

    /** Whether a point is inside the boundary polygon (ray casting). */
    public boolean contains(double x, double z) {
        boolean inside = false;
        for (int i = 0, j = bounds.size() - 1; i < bounds.size(); j = i++) {
            Point a = bounds.get(i);
            Point b = bounds.get(j);
            if ((a.z() > z) != (b.z() > z)
                    && x < (b.x() - a.x()) * (z - a.z()) / (b.z() - a.z()) + a.x()) {
                inside = !inside;
            }
        }
        return inside;
    }

    static RegionDef parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(FIELDS);
        HarborId rawId =
                node.require("id").flatMap(n -> n.asId(RegionId.PREFIX)).orElse(Placeholder.REGION);

        String world = node.string("world");
        if (!world.isEmpty() && !WORLDS.contains(world)) {
            node.error(
                    "world는 "
                            + String.join(" · ", WORLDS.stream().sorted().toList())
                            + " 중 하나여야 합니다");
        }

        List<Point> bounds = points(node.list("bounds"));
        if (bounds.size() < 3) {
            node.error("bounds는 꼭짓점이 3개 이상인 다각형이어야 합니다");
        }

        Unlock unlock = Unlock.NONE;
        if (node.has("unlock")) {
            YamlNode u = node.get("unlock").orElseThrow();
            u.allowOnly(Set.of("level", "quest", "items"));
            unlock =
                    new Unlock(
                            u.integer("level", 0, 0, 1000),
                            u.get("quest").map(q -> Refs.quest(q, ctx)).orElse(null),
                            Refs.items(u.list("items"), ctx));
        }

        return new RegionDef(
                new RegionId(rawId),
                node.string("name"),
                world,
                bounds,
                node.string("biome"),
                unlock,
                node.integer("fishing_spots", 0, 0, 1000),
                node.integer("rec_level", 0, 0, 100),
                points(node.list("trap_zone")),
                node.optString("music").orElse(null));
    }

    private static List<Point> points(List<YamlNode> nodes) {
        List<Point> result = new ArrayList<>();
        for (YamlNode n : nodes) {
            List<YamlNode> xz = n.asList();
            if (xz.size() != 2) {
                n.error("좌표는 [x, z] 두 값이어야 합니다");
                continue;
            }
            result.add(new Point(xz.get(0).asDouble(), xz.get(1).asDouble()));
        }
        return List.copyOf(result);
    }
}
