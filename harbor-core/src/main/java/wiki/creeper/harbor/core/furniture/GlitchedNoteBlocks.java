package wiki.creeper.harbor.core.furniture;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.CustomBlockDef;
import wiki.creeper.harbor.core.api.event.ContentReloadedEvent;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * Repairs glitched note blocks (same purpose as ItemsAdder fix-glitched-blocks, proposed). When a
 * custom block is assigned a new state number, vanilla note blocks already in the world (with a
 * changed pitch) that happen to have that state look like the custom block. Such blocks are reset
 * to the vanilla default state (harp, 0).
 *
 * <p>Each chunk stores its "list of checked state numbers" in PDC. When a chunk loads, only
 * currently assigned numbers missing from that list (= numbers assigned after the check) are
 * searched. Blocks with already checked numbers are custom blocks placed afterward, so they are
 * left alone (WorldEdit copies and piston moves too). A checked chunk is not revisited until block
 * definitions grow.
 *
 * <p>Chunk snapshots are taken on the main thread and searched on a virtual thread, up to {@value
 * #PER_TICK} chunks per tick. Disabled with balance.yml furniture.fix_glitched_noteblocks (default
 * true).
 */
public final class GlitchedNoteBlocks implements Listener {

    @SuppressWarnings("deprecation") // Uses a fixed namespace without a plugin instance
    private static final NamespacedKey CHECKED = new NamespacedKey("harbor", "noteblock_states");

    private static final String SETTING = "furniture.fix_glitched_noteblocks";

    private static final int PER_TICK = 8;

    private final Supplier<ContentSnapshot> content;
    private final Tasks tasks;
    private final Logger log;
    private final Deque<Chunk> queue = new ArrayDeque<>();
    private final Set<ChunkRef> queued = new HashSet<>();

    /** Chunk being scanned → positions where blocks were placed meanwhile (not reverted). */
    private final Map<ChunkRef, Set<Long>> scanning = new HashMap<>();

    private record ChunkRef(UUID world, int x, int z) {}

    public GlitchedNoteBlocks(Supplier<ContentSnapshot> content, Tasks tasks, Logger log) {
        this.content = content;
        this.tasks = tasks;
        this.log = log;
    }

    public void start() {
        enqueueLoaded();
        var _ = tasks.timer(1, 1, this::drain);
    }

    private boolean enabled() {
        return content.get().single(Balance.TYPE).bool(SETTING, true);
    }

    /** Currently assigned state numbers (sorted). */
    private int[] assigned() {
        return content.get().all(CustomBlockDef.TYPE).values().stream()
                .flatMap(def -> def.states().stream())
                .mapToInt(Integer::intValue)
                .distinct()
                .sorted()
                .toArray();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoad(ChunkLoadEvent event) {
        enqueue(event.getChunk());
    }

    /** When block definitions change, rechecks loaded chunks (new numbers may exist). */
    @EventHandler
    public void onReload(ContentReloadedEvent event) {
        var _ = tasks.later(1, this::enqueueLoaded);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        Set<Long> placed = scanning.get(chunkKey(block.getChunk()));
        if (placed != null) {
            placed.add(pos(block.getX() & 15, block.getY(), block.getZ() & 15));
        }
    }

    private void enqueueLoaded() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                enqueue(chunk);
            }
        }
    }

    private void enqueue(Chunk chunk) {
        if (queued.add(chunkKey(chunk))) {
            queue.add(chunk);
        }
    }

    private void drain() {
        if (queue.isEmpty()) {
            return;
        }
        boolean on = enabled();
        int[] current = assigned();
        for (int i = 0; i < PER_TICK && !queue.isEmpty(); i++) {
            Chunk chunk = queue.poll();
            queued.remove(chunkKey(chunk));
            if (on && current.length > 0 && chunk.isLoaded()) {
                check(chunk, current);
            }
        }
    }

    private void check(Chunk chunk, int[] current) {
        int[] marked =
                chunk.getPersistentDataContainer().get(CHECKED, PersistentDataType.INTEGER_ARRAY);
        List<Integer> fresh = fresh(current, marked);
        if (fresh.isEmpty()) {
            if (marked == null || !Arrays.equals(marked, current)) {
                chunk.getPersistentDataContainer()
                        .set(CHECKED, PersistentDataType.INTEGER_ARRAY, current);
            }
            return;
        }
        ChunkRef key = chunkKey(chunk);
        if (scanning.containsKey(key)) {
            return;
        }
        Set<String> targets = new HashSet<>();
        try {
            for (int state : fresh) {
                targets.add(noteBlock(state, false).getAsString());
                targets.add(noteBlock(state, true).getAsString());
            }
        } catch (IllegalArgumentException e) {
            return; // CustomBlockManager already logs it
        }
        ChunkSnapshot snap = chunk.getChunkSnapshot(false, false, false);
        int minY = chunk.getWorld().getMinHeight();
        int maxY = chunk.getWorld().getMaxHeight();
        scanning.put(key, new HashSet<>());
        var _ =
                tasks.supplyAsync(() -> find(snap, targets, minY, maxY))
                        .thenAcceptAsync(
                                found -> apply(chunk, key, found, targets, current), tasks.main())
                        .exceptionally(
                                e -> {
                                    scanning.remove(key);
                                    log.warn("노트블록 확인 실패 ({}): {}", key, String.valueOf(e));
                                    return null;
                                });
    }

    /** Numbers never checked (in current but not in marked). */
    static List<Integer> fresh(int[] current, int @Nullable [] marked) {
        Set<Integer> done = new TreeSet<>();
        if (marked != null) {
            for (int m : marked) {
                done.add(m);
            }
        }
        List<Integer> result = new ArrayList<>();
        for (int c : current) {
            if (!done.contains(c)) {
                result.add(c);
            }
        }
        return result;
    }

    private static List<Long> find(ChunkSnapshot snap, Set<String> targets, int minY, int maxY) {
        List<Long> found = new ArrayList<>();
        for (int y = minY; y < maxY; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    if (snap.getBlockType(x, y, z) == Material.NOTE_BLOCK
                            && targets.contains(snap.getBlockData(x, y, z).getAsString())) {
                        found.add(pos(x, y, z));
                    }
                }
            }
        }
        return found;
    }

    private void apply(
            Chunk chunk, ChunkRef key, List<Long> found, Set<String> targets, int[] current) {
        Set<Long> placed = scanning.remove(key);
        if (!chunk.isLoaded()) {
            return; // Retry on next load
        }
        BlockData vanilla = noteBlock(0, false);
        int fixed = 0;
        for (long p : found) {
            if (placed != null && placed.contains(p)) {
                continue;
            }
            Block block = chunk.getBlock(x(p), y(p), z(p));
            if (block.getType() == Material.NOTE_BLOCK
                    && targets.contains(block.getBlockData().getAsString())) {
                block.setBlockData(vanilla, false);
                fixed++;
            }
        }
        chunk.getPersistentDataContainer().set(CHECKED, PersistentDataType.INTEGER_ARRAY, current);
        if (fixed > 0) {
            log.info(
                    "커스텀 블록 모양으로 보이던 기존 노트블록 {}개를 바닐라로 되돌렸습니다 ({} {},{})",
                    fixed,
                    chunk.getWorld().getName(),
                    chunk.getX(),
                    chunk.getZ());
        }
    }

    private static BlockData noteBlock(int state, boolean powered) {
        return Bukkit.createBlockData(
                "minecraft:note_block[" + CustomBlockDef.properties(state, powered) + "]");
    }

    private static ChunkRef chunkKey(Chunk chunk) {
        return new ChunkRef(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
    }

    static long pos(int x, int y, int z) {
        return ((long) (y + 4096) << 8) | ((long) x << 4) | z;
    }

    static int x(long p) {
        return (int) ((p >> 4) & 15);
    }

    static int z(long p) {
        return (int) (p & 15);
    }

    static int y(long p) {
        return (int) (p >> 8) - 4096;
    }
}
