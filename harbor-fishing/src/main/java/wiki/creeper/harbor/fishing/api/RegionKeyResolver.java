package wiki.creeper.harbor.fishing.api;

import java.util.List;
import java.util.Optional;
import org.bukkit.Location;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * Extension point mapping a bobber location to a fishing region key (docs 20 3.2, proposed). World
 * registers it via {@link FishingService#registerRegionResolver}. When not registered, Fishing
 * decides the region from regions.yml bounds only and does not check sub-area (species area)
 * conditions. Fishing does not call World. Called on the main thread on every cast, so keep it
 * light (target 0.05ms).
 */
@FunctionalInterface
public interface RegionKeyResolver {

    /**
     * Empty if the region cannot be determined (no fishing at that spot). To delegate, return
     * {@link Optional#empty()} instead of {@code null}.
     */
    Optional<RegionKey> resolve(Location bobber);

    /**
     * Fishing region key.
     *
     * @param region region (regions.yml)
     * @param biomeKey biome key (e.g. harbor:ash_trench, for atmosphere and statistics)
     * @param areas sub-area IDs (matched against species area conditions, e.g. lighthouse,
     *     cave_lake)
     * @param school over a fish school
     * @param spotSource modifier source of the fish school or hidden fishing spot (registered by
     *     World as spot:, null if none)
     */
    record RegionKey(
            RegionId region,
            String biomeKey,
            List<String> areas,
            boolean school,
            @Nullable String spotSource) {

        public RegionKey {
            areas = List.copyOf(areas);
        }
    }
}
