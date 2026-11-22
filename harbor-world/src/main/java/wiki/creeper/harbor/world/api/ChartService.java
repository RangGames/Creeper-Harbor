package wiki.creeper.harbor.world.api;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * Sea chart (docs 20 8.4 'sea chart'). When the chunk changes inside a region it is marked on the
 * bitmap, and rewards are given at 25 · 50 · 75 · 95% completion. Quest's chart_percent objective
 * is updated through {@link wiki.creeper.harbor.world.api.event.ChartProgressEvent}. Main thread
 * only.
 */
public interface ChartService {

    /** Regional sea chart progress of an online player. */
    Optional<ChartProgress> progress(UUID player, RegionId region);

    Map<RegionId, ChartProgress> progress(UUID player);

    /** Regional sea chart map item (one MapView shared per region, fog drawn per player). */
    Optional<ItemStack> chartItem(Player player, RegionId region);

    /**
     * @param visited visited navigable cells
     * @param total navigable cells (mask size)
     * @param claimedSteps number of reward steps received
     */
    record ChartProgress(RegionId region, int visited, int total, int claimedSteps) {

        /** 0~1. */
        public double ratio() {
            return total <= 0 ? 0 : Math.min(1.0, (double) visited / total);
        }
    }
}
