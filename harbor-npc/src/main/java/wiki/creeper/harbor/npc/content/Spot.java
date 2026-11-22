package wiki.creeper.harbor.npc.content;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Content coordinates {@code {world, x, y, z, yaw, pitch}}. world is the same world ID as in
 * regions.yml (harbor_main etc.; dev servers map via balance.yml world_alias).
 */
public record Spot(String world, double x, double y, double z, float yaw, float pitch) {

    private static final Set<String> FIELDS = Set.of("world", "x", "y", "z", "yaw", "pitch");

    /** Map-form coordinates. If world is absent, defaultWorld (error if that is empty). */
    public static Optional<Spot> parse(YamlNode node, String defaultWorld) {
        if (!node.isMap()) {
            node.error("좌표는 {world, x, y, z, yaw, pitch} 형식이어야 합니다");
            return Optional.empty();
        }
        node.allowOnly(FIELDS);
        String world = node.string("world", defaultWorld);
        if (world.isEmpty()) {
            node.error("필수 필드 'world'가 없습니다");
        }
        return Optional.of(
                new Spot(
                        world,
                        node.number("x"),
                        node.number("y"),
                        node.number("z"),
                        (float) node.number("yaw", 0),
                        (float) node.number("pitch", 0)));
    }

    /**
     * List-form coordinates {@code [x, y, z]} or {@code [x, y, z, yaw, pitch]} (cutscene camera and
     * paths).
     */
    public static Optional<Spot> parseList(YamlNode node, String world) {
        List<YamlNode> values = node.asList();
        if (values.size() != 3 && values.size() != 5) {
            node.error("좌표는 [x, y, z] 또는 [x, y, z, yaw, pitch]여야 합니다");
            return Optional.empty();
        }
        float yaw = values.size() == 5 ? (float) values.get(3).asDouble() : 0;
        float pitch = values.size() == 5 ? (float) values.get(4).asDouble() : 0;
        return Optional.of(
                new Spot(
                        world,
                        values.get(0).asDouble(),
                        values.get(1).asDouble(),
                        values.get(2).asDouble(),
                        yaw,
                        pitch));
    }

    public Spot withRotation(float newYaw, float newPitch) {
        return new Spot(world, x, y, z, newYaw, newPitch);
    }

    public double distanceSquared(Spot other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
