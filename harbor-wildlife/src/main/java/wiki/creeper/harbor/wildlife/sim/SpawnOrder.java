package wiki.creeper.harbor.wildlife.sim;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.wildlife.api.ScriptedBehaviour;

/**
 * Spawn order decided by the main thread and handed to the simulation thread. Fish IDs are created
 * up front by main (so spawnScripted can return them immediately).
 *
 * @param ids fish to create (the first is the school leader)
 * @param center school center (inside the medium). The rest are scattered over medium cells within
 *     1.5 blocks
 * @param school school ID (null if solitary)
 * @param scripted behaviour for a scripted fish, null for a natural one
 * @param path path school points (empty for none)
 * @param tag fish school spot ID (for a school at that spot)
 * @param region home region name (for boundary checks)
 * @param airBaseY island base height for air fish
 */
public record SpawnOrder(
        List<UUID> ids,
        SpeciesProfile profile,
        String world,
        Vec3 center,
        @Nullable UUID school,
        @Nullable ScriptedBehaviour scripted,
        List<Vec3> path,
        @Nullable String tag,
        @Nullable String region,
        double airBaseY) {

    public SpawnOrder {
        ids = List.copyOf(ids);
        path = List.copyOf(path);
    }
}
