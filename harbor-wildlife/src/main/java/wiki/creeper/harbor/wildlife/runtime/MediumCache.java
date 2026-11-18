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
 * 매질 격자 관리 ('17-3' 9.4). 서식지 지역 청크가 적재되면 ChunkSnapshot을 (메인 스레드에서 틱당 몇 개씩) 떠서 가상 스레드에서 섹션마다 물·용암·공기
 * 비트 배열을 만든다. 서식지 높이 범위의 섹션만 만든다. 블록 설치·파괴·액체 흐름이 서식지 안에서 일어나면 그 섹션만 다시 만든다 (1초에 한 번 묶어서).
 */
final class MediumCache implements Listener {

    /** 청크 언로드 알림 (그 안의 개체를 지운다). */
    interface UnloadHook {
        void unloaded(World world, int cx, int cz);
    }

    private record ChunkKey(String world, int cx, int cz) {}

    private final WildlifeKit kit;
    private final UnloadHook unload;
    private final Map<ChunkKey, Integer> queue = new LinkedHashMap<>();
    private final Map<ChunkKey, Set<Integer>> dirty = new HashMap<>();

    /** 청크마다 적재 세대. 언로드 뒤 늦게 끝난 빌드가 격자를 되살리지 않게 한다. */
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

    /** 적재된 청크를 모두 다시 본다 (시작·콘텐츠 리로드). 이미 있는 격자는 새로 만들 때까지 그대로 둬서 개체가 멈추지 않게 한다. */
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

    // ------------------------------------------------------------------ 적재·언로드

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

    /** 서식지 지역(어종이 있는 지역)에 걸친 청크면 줄에 넣는다. 가운데와 네 모서리 중 하나라도 지역 안이면 서식지로 본다. */
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

    /** 틱당 몇 개씩 스냅샷을 떠 가상 스레드로 넘긴다 (메인 스레드 복사 비용 제한). */
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

    /** 칸 하나의 매질. 물풀·켈프·거품 기둥은 물, 물에 잠긴 단단하지 않은 블록도 물로 본다. */
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

    // ------------------------------------------------------------------ 블록 변경

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

    /** 바뀐 섹션만 다시 만든다 (섹션당 초당 1회). */
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
