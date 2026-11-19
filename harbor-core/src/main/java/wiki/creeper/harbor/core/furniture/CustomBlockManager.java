package wiki.creeper.harbor.core.furniture;

import io.papermc.paper.datacomponent.DataComponentTypes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Item;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.NotePlayEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.CustomBlockDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.ItemModels;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.api.event.ContentReloadedEvent;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * 커스텀 블록 (노트블록 상태, {@link CustomBlockDef}, 추가 제안).
 *
 * <ul>
 *   <li>아이템은 노트블록 + item_model + {@code harbor:block} 태그. 바닐라처럼 놓이고(보호 구역 검사도 그대로), 놓인 뒤 상태를 바꾼다
 *   <li>부수면 노트블록 대신 그 블록 아이템이 떨어진다. 폭발에는 부서지지 않는다
 *   <li>커스텀 블록이 하나라도 있으면 모든 노트블록의 음 바꾸기(우클릭)·소리(NotePlayEvent)를 막는다 (상태가 곧 모양이라서)
 *   <li>아래 블록이 바뀌면 바닐라가 악기 상태를 바꿔 모양이 변하므로 paper-global.yml {@code
 *       block-updates.disable-noteblock-updates: true}가 필요하다. 꺼져 있으면 시작할 때 경고한다
 * </ul>
 */
public final class CustomBlockManager implements Listener {

    @SuppressWarnings("deprecation") // 플러그인 인스턴스 없이 고정 네임스페이스를 쓴다
    static final NamespacedKey KEY = new NamespacedKey("harbor", "block");

    private static final Pattern NOTEBLOCK_UPDATES_OFF =
            Pattern.compile("disable-noteblock-updates:\\s*true");

    private final Supplier<ContentSnapshot> content;
    private final MessageService messages;
    private final Tasks tasks;
    private final Logger log;

    /** 블록 데이터 문자열 → 블록 (powered 두 상태 모두). 콘텐츠를 다시 읽으면 비운다. */
    private @Nullable Map<String, CustomBlockDef> byState;

    private Map<HarborId, BlockData> dataById = Map.of();

    public CustomBlockManager(
            Supplier<ContentSnapshot> content, MessageService messages, Tasks tasks, Logger log) {
        this.content = content;
        this.messages = messages;
        this.tasks = tasks;
        this.log = log;
    }

    /** 커스텀 블록이 있으면 Paper 노트블록 갱신 설정을 확인한다 (파일 읽기는 가상 스레드). */
    public void start() {
        if (states().isEmpty()) {
            return;
        }
        Path config = Path.of("config", "paper-global.yml");
        var _ =
                tasks.supplyAsync(
                                () -> {
                                    try {
                                        return NOTEBLOCK_UPDATES_OFF
                                                .matcher(
                                                        Files.readString(
                                                                config, StandardCharsets.UTF_8))
                                                .find();
                                    } catch (IOException e) {
                                        return false;
                                    }
                                })
                        .thenAcceptAsync(
                                off -> {
                                    if (!off) {
                                        log.warn(
                                                "커스텀 블록이 있는데 paper-global.yml block-updates.disable-noteblock-updates가 true가 아닙니다."
                                                        + " 커스텀 블록 아래 블록이 바뀌면 모양이 바뀝니다");
                                    }
                                },
                                tasks.main());
    }

    @EventHandler
    public void onReload(ContentReloadedEvent event) {
        byState = null;
    }

    private Map<String, CustomBlockDef> states() {
        Map<String, CustomBlockDef> cached = byState;
        if (cached != null) {
            return cached;
        }
        Map<String, CustomBlockDef> map = new HashMap<>();
        Map<HarborId, BlockData> data = new HashMap<>();
        for (CustomBlockDef def : content.get().all(CustomBlockDef.TYPE).values()) {
            try {
                BlockData unpowered = noteBlock(def.state(), false);
                map.put(unpowered.getAsString(), def);
                map.put(noteBlock(def.state(), true).getAsString(), def);
                data.put(def.id(), unpowered);
            } catch (IllegalArgumentException e) {
                log.error("커스텀 블록 {}의 노트블록 상태를 만들지 못했습니다: {}", def.id(), e.getMessage());
            }
        }
        Map<String, CustomBlockDef> result = Map.copyOf(map);
        dataById = Map.copyOf(data);
        byState = result;
        return result;
    }

    private static BlockData noteBlock(int state, boolean powered) {
        return Bukkit.createBlockData(
                "minecraft:note_block[" + CustomBlockDef.properties(state, powered) + "]");
    }

    public Optional<CustomBlockDef> def(HarborId id) {
        return content.get().get(CustomBlockDef.TYPE, id);
    }

    public List<CustomBlockDef> all() {
        return content.get().all(CustomBlockDef.TYPE).values().stream()
                .sorted(java.util.Comparator.comparingInt(CustomBlockDef::state))
                .toList();
    }

    /** 놓인 커스텀 블록. */
    public Optional<CustomBlockDef> at(Block block) {
        if (block.getType() != Material.NOTE_BLOCK) {
            return Optional.empty();
        }
        return Optional.ofNullable(states().get(block.getBlockData().getAsString()));
    }

    private Optional<CustomBlockDef> at(BlockState state) {
        if (state.getType() != Material.NOTE_BLOCK) {
            return Optional.empty();
        }
        return Optional.ofNullable(states().get(state.getBlockData().getAsString()));
    }

    public ItemStack item(CustomBlockDef def, int amount) {
        ContentSnapshot snapshot = content.get();
        ItemStack stack = ItemStack.of(Material.NOTE_BLOCK, Math.max(1, amount));
        stack.setData(
                DataComponentTypes.ITEM_NAME,
                messages.get("furniture.block_name", Placeholder.unparsed("name", def.name())));
        ItemModels.apply(stack, def.itemModel(), snapshot);
        String id = def.id().toString();
        stack.editPersistentDataContainer(pdc -> pdc.set(KEY, PersistentDataType.STRING, id));
        return stack;
    }

    public Optional<HarborId> idOf(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getType() != Material.NOTE_BLOCK) {
            return Optional.empty();
        }
        String raw = stack.getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
        return raw == null ? Optional.empty() : HarborId.tryParse(raw);
    }

    // ------------------------------------------------------------------ 이벤트

    /** 놓인 뒤(보호 구역 검사가 모두 끝난 뒤) 상태를 바꾼다. 나중에 취소되면 서버가 원래 블록으로 되돌린다. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Optional<HarborId> id = idOf(event.getItemInHand());
        if (id.isEmpty()) {
            return;
        }
        states();
        BlockData data = dataById.get(id.get());
        if (data != null) {
            event.getBlockPlaced().setBlockData(data, false);
        }
    }

    /** 노트블록 대신 커스텀 블록 아이템을 떨어뜨린다. */
    @EventHandler(ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent event) {
        Optional<CustomBlockDef> def = at(event.getBlockState());
        if (def.isEmpty()) {
            return;
        }
        for (Item drop : event.getItems()) {
            ItemStack stack = drop.getItemStack();
            if (stack.getType() == Material.NOTE_BLOCK) {
                drop.setItemStack(item(def.get(), stack.getAmount()));
            }
        }
    }

    /** 노트블록 음 바꾸기를 막는다 (블록을 대고 놓기는 그대로). */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || block == null
                || block.getType() != Material.NOTE_BLOCK
                || states().isEmpty()) {
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
    }

    @EventHandler(ignoreCancelled = true)
    public void onNote(NotePlayEvent event) {
        if (!states().isEmpty()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (!states().isEmpty()) {
            event.blockList().removeIf(b -> at(b).isPresent());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(BlockExplodeEvent event) {
        if (!states().isEmpty()) {
            event.blockList().removeIf(b -> at(b).isPresent());
        }
    }
}
