package wiki.creeper.harbor.world.data;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * Per-player World data (docs 20 8.3 PlayerComponent&lt;WorldState&gt;). Immutable: to change it,
 * create a new value with with… and call HarborPlayer.set. Chart bitmaps are kept as the raw bytes
 * read from the DB (since the mask changes with content), and the consumer (ChartManager) reads
 * them combined with the mask.
 *
 * @param visited regions visited this season (region_visit)
 * @param charts raw chart per region (sea_chart)
 * @param cold frostbite gauge (0–100)
 * @param oxygen oxygen gauge (seconds, −1 = full)
 * @param safe last safe position (carried over across server moves and rejoins)
 */
public record WorldState(
        Set<RegionId> visited,
        Map<RegionId, ChartRow> charts,
        double cold,
        double oxygen,
        @Nullable SafePos safe) {

    /** Chart row (the raw bytes are held as a copy and never change). */
    public static final class ChartRow {

        private final byte[] bits;
        private final int visited;
        private final int claimed;

        public ChartRow(byte[] bits, int visited, int claimed) {
            this.bits = bits.clone();
            this.visited = visited;
            this.claimed = claimed;
        }

        public byte[] bits() {
            return bits.clone();
        }

        public int visited() {
            return visited;
        }

        public int claimed() {
            return claimed;
        }
    }

    /** Safe position (by content world name). */
    public record SafePos(String world, double x, double y, double z) {}

    public static final WorldState EMPTY = new WorldState(Set.of(), Map.of(), 0, -1, null);

    public WorldState {
        visited = Set.copyOf(visited);
        charts = Map.copyOf(charts);
    }

    public WorldState withVisited(RegionId region) {
        Set<RegionId> next = new HashSet<>(visited);
        next.add(region);
        return new WorldState(next, charts, cold, oxygen, safe);
    }

    public WorldState withChart(RegionId region, ChartRow row) {
        Map<RegionId, ChartRow> next = new HashMap<>(charts);
        next.put(region, row);
        return new WorldState(visited, next, cold, oxygen, safe);
    }

    public WorldState withGauges(double newCold, double newOxygen) {
        return new WorldState(visited, charts, newCold, newOxygen, safe);
    }

    public WorldState withSafe(@Nullable SafePos pos) {
        return new WorldState(visited, charts, cold, oxygen, pos);
    }
}
