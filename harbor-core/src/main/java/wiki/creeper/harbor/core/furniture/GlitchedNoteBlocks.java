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
 * 깨진 노트블록 복구 (ItemsAdder fix-glitched-blocks와 같은 목적, 추가 제안). 커스텀 블록에 상태 번호를 새로 배정하면, 그 전부터 월드에 있던
 * 바닐라 노트블록(음을 바꿔 둔 것)이 마침 그 상태면 커스텀 블록 모양으로 보인다. 그런 블록을 바닐라 기본 상태(harp·0)로 되돌린다.
 *
 * <p>청크마다 "확인을 마친 상태 번호 목록"을 PDC에 남긴다. 청크가 로드될 때 지금 배정된 번호 중 목록에 없는 번호(= 확인 뒤 새로 배정된 번호)만 찾는다. 확인을
 * 마친 번호의 블록은 그 뒤에 놓은 커스텀 블록이므로 건드리지 않는다 (WorldEdit 복사·피스톤 이동도 그대로). 한 번 확인한 청크는 블록 정의가 늘기 전까지 다시 보지
 * 않는다.
 *
 * <p>청크 스냅숏은 메인 스레드에서 뜨고 찾기는 가상 스레드에서 한다. 한 틱에 {@value #PER_TICK}청크까지. balance.yml
 * furniture.fix_glitched_noteblocks (기본 true)로 끈다.
 */
public final class GlitchedNoteBlocks implements Listener {

    @SuppressWarnings("deprecation") // 플러그인 인스턴스 없이 고정 네임스페이스를 쓴다
    private static final NamespacedKey CHECKED = new NamespacedKey("harbor", "noteblock_states");

    private static final String SETTING = "furniture.fix_glitched_noteblocks";

    private static final int PER_TICK = 8;

    private final Supplier<ContentSnapshot> content;
    private final Tasks tasks;
    private final Logger log;
    private final Deque<Chunk> queue = new ArrayDeque<>();
    private final Set<ChunkRef> queued = new HashSet<>();

    /** 스캔 중인 청크 → 그 사이에 블록을 놓은 위치 (되돌리지 않는다). */
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

    /** 지금 배정된 상태 번호 (정렬). */
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

    /** 블록 정의가 바뀌면 로드된 청크를 다시 본다 (새 번호가 생겼을 수 있다). */
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
            return; // CustomBlockManager가 이미 기록한다
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

    /** 확인한 적 없는 번호 (marked에 없는 current). */
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
            return; // 다음 로드 때 다시
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
