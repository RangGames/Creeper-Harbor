package wiki.creeper.harbor.world.api;

import java.time.Instant;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * Fish school point (docs 20 8.4). Moves to another candidate every 10–20 minutes. If hidden, it is
 * a hidden fishing spot (visible only with exploration tier 2 'hidden spot detection').
 *
 * @param id ID also used as the modifier source (spot:<region>_<number> or spot:<hidden spot ID>)
 * @param world content world name
 */
public record FishingSpot(
        String id,
        RegionId region,
        String world,
        double x,
        double y,
        double z,
        boolean hidden,
        Instant movesAt) {

    public double distanceSquared(double px, double pz) {
        double dx = px - x;
        double dz = pz - z;
        return dx * dx + dz * dz;
    }
}
