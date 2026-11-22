package wiki.creeper.harbor.boss.encounter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.plugin.Plugin;

/**
 * Plugin chunk tickets for arena chunks (prevents unloading, 10.4). Counted when several encounters
 * use the same chunk. Main thread only.
 */
final class ChunkTickets {

    private final Plugin plugin;
    private final Map<Long, Integer> chunkHolds = new HashMap<>();
    private final Map<UUID, Set<Long>> encounterChunks = new HashMap<>();

    ChunkTickets(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Adds plugin chunk tickets to arena chunks during an encounter (prevents unloading, 10.4).
     * Counted when several encounters use the same chunk.
     */
    void hold(Encounter e, boolean hold) {
        if (hold) {
            Set<Long> keys = new HashSet<>();
            int r = (int) Math.ceil(e.radius / 16.0);
            int cx = e.center.getBlockX() >> 4;
            int cz = e.center.getBlockZ() >> 4;
            for (int x = cx - r; x <= cx + r; x++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    long key = Chunk.getChunkKey(x, z) ^ ((long) e.world.getName().hashCode() << 1);
                    keys.add(key);
                    if (chunkHolds.merge(key, 1, Integer::sum) == 1) {
                        e.world.getChunkAt(x, z).addPluginChunkTicket(plugin);
                    }
                }
            }
            encounterChunks.put(e.id, keys);
            return;
        }
        Set<Long> keys = encounterChunks.remove(e.id);
        if (keys == null) {
            return;
        }
        int r = (int) Math.ceil(e.radius / 16.0);
        int cx = e.center.getBlockX() >> 4;
        int cz = e.center.getBlockZ() >> 4;
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                long key = Chunk.getChunkKey(x, z) ^ ((long) e.world.getName().hashCode() << 1);
                Integer left = chunkHolds.computeIfPresent(key, (k, v) -> v - 1);
                if (left != null && left <= 0) {
                    chunkHolds.remove(key);
                    e.world.getChunkAt(x, z).removePluginChunkTicket(plugin);
                }
            }
        }
    }
}
