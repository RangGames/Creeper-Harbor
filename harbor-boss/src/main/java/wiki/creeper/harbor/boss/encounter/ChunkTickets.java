package wiki.creeper.harbor.boss.encounter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.plugin.Plugin;

/** 경기장 청크의 플러그인 청크 티켓 (언로드 방지, 10.4). 여러 조우가 같은 청크를 쓰면 센다. 메인 스레드 전용. */
final class ChunkTickets {

    private final Plugin plugin;
    private final Map<Long, Integer> chunkHolds = new HashMap<>();
    private final Map<UUID, Set<Long>> encounterChunks = new HashMap<>();

    ChunkTickets(Plugin plugin) {
        this.plugin = plugin;
    }

    /** 조우 중 경기장 청크에 플러그인 청크 티켓을 건다 (언로드 방지, 10.4). 여러 조우가 같은 청크를 쓰면 센다. */
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
