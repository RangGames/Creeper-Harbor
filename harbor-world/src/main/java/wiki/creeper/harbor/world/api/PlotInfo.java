package wiki.creeper.harbor.world.api;

import java.util.Set;
import java.util.UUID;

/**
 * Housing plot (housing_plot). A square of size size inside one 40×40 cell of the harbor_homes
 * world (centered).
 *
 * @param plotId spiral order index
 * @param gridX cell coordinate
 * @param minX plot bound (blocks, inclusive)
 * @param maxX plot bound (blocks, inclusive)
 * @param members co-builders
 */
public record PlotInfo(
        long plotId,
        UUID owner,
        String ownerName,
        int gridX,
        int gridZ,
        int size,
        String theme,
        VisitPolicy visitPolicy,
        int likes,
        String world,
        int minX,
        int minZ,
        int maxX,
        int maxZ,
        double spawnX,
        double spawnY,
        double spawnZ,
        float spawnYaw,
        Set<UUID> members) {

    public PlotInfo {
        members = Set.copyOf(members);
    }

    public boolean contains(String worldName, double x, double z) {
        return world.equals(worldName) && x >= minX && x < maxX + 1 && z >= minZ && z < maxZ + 1;
    }

    public boolean isMember(UUID player) {
        return owner.equals(player) || members.contains(player);
    }
}
