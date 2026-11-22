package wiki.creeper.harbor.wildlife.runtime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import wiki.creeper.harbor.wildlife.sim.FishOut;
import wiki.creeper.harbor.wildlife.sim.Frame;
import wiki.creeper.harbor.world.api.RegionInfo;

/**
 * Region fence: tells the simulation thread to turn back fish that left their region. Main thread
 * only.
 */
final class RegionFence {

    private final WildlifeKit kit;
    private final SimEngine engine;
    private final Map<UUID, Long> turnedBack = new HashMap<>();
    private int regionCursor;

    RegionFence(WildlifeKit kit, SimEngine engine) {
        this.kit = kit;
        this.engine = engine;
    }

    /**
     * Turns fish that crossed the region boundary around (checks a portion per tick in rotation).
     */
    void check(Frame applied, long tick) {
        List<FishOut> list = applied.fish();
        int size = list.size();
        if (size == 0) {
            return;
        }
        int n = Math.min(100, size);
        for (int i = 0; i < n; i++) {
            FishOut o = list.get((regionCursor + i) % size);
            String home = o.region();
            if (home == null || o.hidden()) {
                continue;
            }
            Long last = turnedBack.get(o.id());
            if (last != null && tick - last < 40) {
                continue;
            }
            World w = Bukkit.getWorld(o.world());
            if (w == null) {
                continue;
            }
            Optional<RegionInfo> r = kit.regions.regionAt(new Location(w, o.x(), o.y(), o.z()));
            if (r.isEmpty() || !r.get().id().toString().equals(home)) {
                turnedBack.put(o.id(), tick);
                UUID id = o.id();
                engine.submit(world -> world.turnBack(id));
            }
        }
        regionCursor = (regionCursor + n) % size;
    }

    /** Clears turn-back records older than 10 seconds. */
    void prune(long tick) {
        turnedBack.values().removeIf(t -> tick - t > 200);
    }

    void forget(UUID id) {
        turnedBack.remove(id);
    }
}
