package wiki.creeper.harbor.world.api;

import java.util.List;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * Region summary (regions.yml + world/regions.yml).
 *
 * @param world content world name (harbor_main · harbor_nether · harbor_end)
 * @param order unlock order (1 harbor … 7 end)
 * @param hourlyGold gold per hour of manual fishing ('7. balance design' 3.2)
 * @param areas sub-area IDs
 * @param server ID of the server running this region's world (null = this server)
 */
public record RegionInfo(
        RegionId id,
        String name,
        String world,
        String biome,
        int order,
        int recLevel,
        int hourlyGold,
        List<String> areas,
        @Nullable String server) {

    public RegionInfo {
        areas = List.copyOf(areas);
    }
}
