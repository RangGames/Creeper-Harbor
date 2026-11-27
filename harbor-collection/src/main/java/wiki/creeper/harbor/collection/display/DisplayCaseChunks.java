package wiki.creeper.harbor.collection.display;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.bukkit.Chunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import wiki.creeper.harbor.collection.display.DisplayCaseManager.PlotView;
import wiki.creeper.harbor.world.api.DisplayAnchor;
import wiki.creeper.harbor.world.api.HousingService;
import wiki.creeper.harbor.world.api.PlotInfo;
import wiki.creeper.harbor.world.api.TankBounds;

/**
 * Redraws a plot when a chunk under one of its tanks loads (proposed). The tank entities (model,
 * floor, click boxes, rig) are not persistent: a tank whose chunk was not loaded when the plot was
 * drawn (the owner joined before the chunks around the plot loaded) is skipped, and a chunk that
 * unloads takes its entities with it while the plot stays loaded. Either way the barrier blocks of
 * the tank stayed invisible until the next full redraw. A load schedules a redraw a few ticks
 * later, restarted by each further load, so a burst of chunk loads draws the plot once.
 */
final class DisplayCaseChunks implements Listener {

    /** Ticks without another chunk load of the plot before it is redrawn. */
    static final int SETTLE_TICKS = 10;

    private final HousingService housing;
    private final Map<Long, PlotView> plots;
    private final Map<Long, Integer> waiting = new HashMap<>();

    DisplayCaseChunks(HousingService housing, Map<Long, PlotView> plots) {
        this.housing = housing;
        this.plots = plots;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        String world = chunk.getWorld().getName();
        int minX = chunk.getX() << 4;
        int minZ = chunk.getZ() << 4;
        for (PlotView view : plots.values()) {
            if (touches(view.plot, world, minX, minZ)) {
                waiting.put(view.plot.plotId(), SETTLE_TICKS);
            }
        }
    }

    /** Called every tick: the plots to redraw now. */
    List<Long> due() {
        if (waiting.isEmpty()) {
            return List.of();
        }
        List<Long> due = new ArrayList<>();
        Iterator<Map.Entry<Long, Integer>> it = waiting.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Integer> e = it.next();
            int left = e.getValue() - 1;
            if (left <= 0) {
                it.remove();
                due.add(e.getKey());
            } else {
                e.setValue(left);
            }
        }
        return due;
    }

    /** Forgets a plot that was unloaded or drawn anyway. */
    void forget(long plotId) {
        waiting.remove(plotId);
    }

    void clear() {
        waiting.clear();
    }

    /** Whether the 16×16 column at (minX, minZ) holds a block of one of the plot's tanks. */
    private boolean touches(PlotInfo plot, String world, int minX, int minZ) {
        int maxX = minX + 15;
        int maxZ = minZ + 15;
        if (!plot.world().equals(world)
                || plot.maxX() < minX
                || plot.minX() > maxX
                || plot.maxZ() < minZ
                || plot.minZ() > maxZ) {
            return false;
        }
        for (DisplayAnchor a : housing.displayAnchors(plot.plotId())) {
            if (!a.world().equals(world)) {
                continue;
            }
            TankBounds b = housing.tankOf(a);
            if (b.maxX() >= minX && b.minX() <= maxX && b.maxZ() >= minZ && b.minZ() <= maxZ) {
                return true;
            }
        }
        return false;
    }
}
