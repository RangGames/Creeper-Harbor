package wiki.creeper.harbor.wildlife.sim;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Per-world medium grid ('17-3' 9.4). Holds per-chunk {@link ChunkCells}. Chunk objects are
 * immutable and only replaced, so the simulation thread reads without locking.
 */
public final class MediumGrid {

    private final Map<String, Map<Long, ChunkCells>> worlds = new ConcurrentHashMap<>();

    public static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public void put(String world, int cx, int cz, ChunkCells cells) {
        worlds.computeIfAbsent(world, w -> new ConcurrentHashMap<>()).put(key(cx, cz), cells);
    }

    public @Nullable ChunkCells get(String world, int cx, int cz) {
        Map<Long, ChunkCells> chunks = worlds.get(world);
        return chunks == null ? null : chunks.get(key(cx, cz));
    }

    public boolean has(String world, int cx, int cz) {
        return get(world, cx, cz) != null;
    }

    public void remove(String world, int cx, int cz) {
        Map<Long, ChunkCells> chunks = worlds.get(world);
        if (chunks != null) {
            chunks.remove(key(cx, cz));
        }
    }

    public void clear() {
        worlds.clear();
    }

    public void clearWorld(String world) {
        worlds.remove(world);
    }

    /** Lookup for one world. */
    public MediumField field(String world) {
        return (x, y, z) -> {
            Map<Long, ChunkCells> chunks = worlds.get(world);
            if (chunks == null) {
                return 0;
            }
            ChunkCells c = chunks.get(key(x >> 4, z >> 4));
            return c == null ? 0 : c.cell(x & 15, y, z & 15);
        };
    }

    public int chunks() {
        int n = 0;
        for (Map<Long, ChunkCells> m : worlds.values()) {
            n += m.size();
        }
        return n;
    }

    public long bytes() {
        long n = 0;
        for (Map<Long, ChunkCells> m : worlds.values()) {
            for (ChunkCells c : m.values()) {
                n += c.bytes();
            }
        }
        return n;
    }
}
