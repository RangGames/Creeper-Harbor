package wiki.creeper.harbor.wildlife.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * Harbor-Wildlife public API ('17-3' 9.2 WildlifeService). Look it up via ServicesManager. Use on
 * the main thread.
 *
 * <p>Wild fish do not affect catch rolls (rolls are Fishing's alone, '17-3' 9.1 principle). Bite
 * hand-off goes only through the Fishing api's claimShadow. Query results are as of the last
 * simulation step (100ms).
 */
public interface WildlifeService {

    /** Fish within a radius of a location (nearest first). */
    List<WildFish> nearby(Location location, double radius);

    Optional<WildFish> fish(UUID id);

    /** Number of live fish (this server). */
    int count();

    /**
     * Spawns a fish for a cinematic. Legendary and mythic fish can be spawned too (they never spawn
     * naturally). Empty if the species is not in species.yml, has no rig, or WildFishSpawnEvent is
     * cancelled.
     */
    Optional<UUID> spawnScripted(FishId fish, Location at, ScriptedBehaviour behaviour);

    /**
     * Spawns a school that swims along a path (great migration cinematic etc.). On reaching the
     * last point it scatters into normal fish.
     *
     * @param path points to pass through (same world, spawned at the first point)
     * @param count number of fish (0 or less for the species' default school size)
     * @return school ID
     */
    Optional<UUID> spawnSchool(FishId fish, List<Location> path, int count);

    /**
     * Raises the density of target species in a region (great migration ×2 etc.). Calling again
     * with the same source replaces it.
     *
     * @param source source (e.g. event:great_migration_202611181930)
     * @param fish target species (empty for every species in the region)
     * @param multiplier multiplied into the per-player target count and target species' spawn
     *     weight
     */
    void boost(String source, RegionId region, Set<FishId> fish, double multiplier, Instant until);

    void clearBoost(String source);

    /**
     * Casts a ray from the origin in a direction and returns the first fish hit (body-radius
     * sphere).
     */
    Optional<WildHit> raycast(Location origin, Vector direction, double distance);

    /** Removes a fish (applied on the next simulation step). */
    void remove(UUID fish);
}
