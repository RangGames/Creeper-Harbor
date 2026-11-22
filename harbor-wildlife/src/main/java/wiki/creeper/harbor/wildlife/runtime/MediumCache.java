package wiki.creeper.harbor.wildlife.runtime;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.wildlife.content.WildlifeBalance;
import wiki.creeper.harbor.wildlife.sim.ChunkCells;
import wiki.creeper.harbor.wildlife.sim.MediumField;
import wiki.creeper.harbor.wildlife.sim.MediumGrid;
import wiki.creeper.harbor.world.api.RegionInfo;

/**
 * Medium grid management ('17-3' 9.4). When habitat region chunks load, takes ChunkSnapshots (a few
 * per tick on the main thread) and builds per-section water/lava/air bit arrays on a virtual
 * thread. Only sections within the habitat height range are built. When block placement, breaking
 * or fluid flow happens inside a habitat, only that section is rebuilt (batched once per second).
 */
final class MediumCache implements Listener {

    /** Chunk unload notification (removes the fish inside). */
    interface UnloadHook {
        void unloaded(World world, int cx, int cz);
    }

    private record ChunkKey(String world, int cx, int cz) {}

    private final WildlifeKit kit;
    private final UnloadHook unload;
    private final Map<ChunkKey, Integer> queue = new LinkedHashMap<>();
    private final Map<ChunkKey, Set<Integer>> dirty = new HashMap<>();

    /**
     * Load generation per chunk. Keeps a build that finishes late after unload from reviving the
     * grid.
     */
    private final Map<ChunkKey, Long> generation = new ConcurrentHashMap<>();

    private final AtomicLong gen = new AtomicLong();
    private final Map<ChunkKey, Integer> masks = new HashMap<>();

    MediumCache(WildlifeKit kit, UnloadHook unload) {
        this.kit = kit;
        this.unload = unload;
    }

    MediumGrid grid() {
        return kit.grid;
    }

    void start() {
        var _ = kit.tasks.timer(1, 1, this::drain);
        var _ = kit.tasks.timer(20, 20, this::rebuildDirty);
        rescan();
    }

    /**
     * Rescans all loaded chunks (startup, content reload). Existing grids are kept until rebuilt so
     * fish don't freeze.
     */
    void rescan() {
        for (World w : Bukkit.getWorlds()) {
            for (Chunk c : w.getLoadedChunks()) {
                consider(w, c.getX(), c.getZ());
            }
        }
    }

    int queued() {
        return queue.size();
    }

    // ------------------------------------------------------------------ load and unload

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoad(ChunkLoadEvent event) {
        Chunk c = event.getChunk();
        consider(c.getWorld(), c.getX(), c.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnload(ChunkUnloadEvent event) {
        Chunk c = event.getChunk();
        ChunkKey key = new ChunkKey(c.getWorld().getName(), c.getX(), c.getZ());
        queue.remove(key);
        dirty.remove(key);
        masks.remove(key);
        generation.remove(key);
        kit.grid.remove(key.world(), key.cx(), key.cz());
        unload.unloaded(c.getWorld(), c.getX(), c.getZ());
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        String name = event.getWorld().getName();
        kit.grid.clearWorld(name);
        queue.keySet().removeIf(k -> k.world().equals(name));
        dirty.keySet().removeIf(k -> k.world().equals(name));
        masks.keySet().removeIf(k -> k.world().equals(name));
        generation.keySet().removeIf(k -> k.world().equals(name));
    }

    /**
     * Queues the chunk if it overlaps a habitat region (a region with species). It counts as
     * habitat if the center or any of the four corners is inside the region.
     */
    private void consider(World world, int cx, int cz) {
        int mask = maskAt(world, cx, cz);
        if (mask == 0) {
            return;
        }
        ChunkKey key = new ChunkKey(world.getName(), cx, cz);
        masks.put(key, mask);
        generation.put(key, gen.incrementAndGet());
        queue.put(key, mask);
    }

    private int maskAt(World world, int cx, int cz) {
        int mask = 0;
        int y = world.getSeaLevel();
        int[][] probes = {{8, 8}, {0, 0}, {15, 0}, {0, 15}, {15, 15}};
        Set<RegionId> seen = new HashSet<>();
        for (int[] o : probes) {
            Optional<RegionInfo> r =
                    kit.regions.regionAt(new Location(world, cx * 16 + o[0], y, cz * 16 + o[1]));
            if (r.isPresent() && seen.add(r.get().id())) {
                mask |= kit.index().gridMask(r.get().id());
            }
        }
        return mask;
    }

    /**
     * Takes a few snapshots per tick and hands them to a virtual thread (limits main-thread copy
     * cost).
     */
    private void drain() {
        int budget = kit.balance().gridChunksPerTick();
        Iterator<Map.Entry<ChunkKey, Integer>> it = queue.entrySet().iterator();
        while (budget > 0 && it.hasNext()) {
            Map.Entry<ChunkKey, Integer> e = it.next();
            it.remove();
            ChunkKey key = e.getKey();
            World world = Bukkit.getWorld(key.world());
            if (world == null || !world.isChunkLoaded(key.cx(), key.cz())) {
                continue;
            }
            budget--;
            ChunkSnapshot snap =
                    world.getChunkAt(key.cx(), key.cz()).getChunkSnapshot(false, false, false);
            String content = kit.contentWorld(world);
            WildlifeBalance bal = kit.balance();
            int minY = Math.max(world.getMinHeight(), bal.gridMinY(content));
            int maxY = Math.min(world.getMaxHeight() - 1, bal.gridMaxY(content));
            int mask = e.getValue();
            Long expected = generation.get(key);
            var _ =
                    kit.tasks.async(
                            () -> {
                                ChunkCells cells =
                                        ChunkCells.build(
                                                minY,
                                                maxY,
                                                mask,
                                                (x, yy, z) -> classify(snap, x, yy, z));
                                if (expected != null && expected.equals(generation.get(key))) {
                                    kit.grid.put(key.world(), key.cx(), key.cz(), cells);
                                }
                            });
        }
    }

    /**
     * Medium of one cell. Seagrass, kelp and bubble columns count as water, as do waterlogged
     * non-solid blocks.
     */
    static int classify(ChunkSnapshot snap, int x, int y, int z) {
        Material m = snap.getBlockType(x, y, z);
        switch (m) {
            case WATER, BUBBLE_COLUMN, KELP, KELP_PLANT, SEAGRASS, TALL_SEAGRASS -> {
                return MediumField.WATER;
            }
            case LAVA -> {
                return MediumField.LAVA;
            }
            default -> {}
        }
        if (m.isAir()) {
            return MediumField.AIR;
        }
        if (m.isSolid()) {
            return 0;
        }
        BlockData data = snap.getBlockData(x, y, z);
        return data instanceof Waterlogged w && w.isWaterlogged() ? MediumField.WATER : 0;
    }

    // ------------------------------------------------------------------ block changes

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        mark(event.getToBlock());
    }

    private void mark(Block block) {
        String world = block.getWorld().getName();
        int cx = block.getX() >> 4;
        int cz = block.getZ() >> 4;
        if (!kit.grid.has(world, cx, cz)) {
            return;
        }
        dirty.computeIfAbsent(new ChunkKey(world, cx, cz), k -> new HashSet<>())
                .add(block.getY() >> 4);
    }

    /** Rebuilds only changed sections (once per second per section). */
    private void rebuildDirty() {
        if (dirty.isEmpty()) {
            return;
        }
        Map<ChunkKey, Set<Integer>> batch = new HashMap<>(dirty);
        dirty.clear();
        batch.forEach(
                (key, sections) -> {
                    World world = Bukkit.getWorld(key.world());
                    Integer mask = masks.get(key);
                    if (world == null || mask == null || !world.isChunkLoaded(key.cx(), key.cz())) {
                        return;
                    }
                    ChunkSnapshot snap =
                            world.getChunkAt(key.cx(), key.cz())
                                    .getChunkSnapshot(false, false, false);
                    String content = kit.contentWorld(world);
                    WildlifeBalance bal = kit.balance();
                    int minY = Math.max(world.getMinHeight(), bal.gridMinY(content));
                    int maxY = Math.min(world.getMaxHeight() - 1, bal.gridMaxY(content));
                    Long expected = generation.get(key);
                    var _ =
                            kit.tasks.async(
                                    () -> {
                                        ChunkCells cells =
                                                kit.grid.get(key.world(), key.cx(), key.cz());
                                        if (cells == null) {
                                            return;
                                        }
                                        for (int s : sections) {
                                            cells =
                                                    cells.withSection(
                                                            s,
                                                            minY,
                                                            maxY,
                                                            mask,
                                                            (x, y, z) -> classify(snap, x, y, z));
                                        }
                                        if (expected != null
                                                && expected.equals(generation.get(key))) {
                                            kit.grid.put(key.world(), key.cx(), key.cz(), cells);
                                        }
                                    });
                });
    }
}
