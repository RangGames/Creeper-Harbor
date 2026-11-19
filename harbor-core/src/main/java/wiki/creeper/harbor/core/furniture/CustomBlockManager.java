package wiki.creeper.harbor.core.furniture;

import io.papermc.paper.datacomponent.DataComponentTypes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
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
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.ItemModels;
import wiki.creeper.harbor.core.api.ItemService;
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
    private final ItemService items;
    private final Tasks tasks;
    private final Logger log;

    /** 블록 데이터 문자열 → 블록 (powered 두 상태 모두). 콘텐츠를 다시 읽으면 비운다. */
    private @Nullable Map<String, CustomBlockDef> byState;

    public CustomBlockManager(
            Supplier<ContentSnapshot> content,
            MessageService messages,
            ItemService items,
            Tasks tasks,
            Logger log) {
        this.content = content;
        this.messages = messages;
        this.items = items;
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
        for (CustomBlockDef def : content.get().all(CustomBlockDef.TYPE).values()) {
            try {
                for (int state : def.states()) {
                    map.put(noteBlock(state, false).getAsString(), def);
                    map.put(noteBlock(state, true).getAsString(), def);
                }
            } catch (IllegalArgumentException e) {
                log.error("커스텀 블록 {}의 노트블록 상태를 만들지 못했습니다: {}", def.id(), e.getMessage());
            }
        }
        Map<String, CustomBlockDef> result = Map.copyOf(map);
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

    /** 놓인 뒤(보호 구역 검사가 모두 끝난 뒤) 방향에 맞는 상태로 바꾼다. 나중에 취소되면 서버가 원래 블록으로 되돌린다. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Optional<CustomBlockDef> def = idOf(event.getItemInHand()).flatMap(this::def);
        if (def.isEmpty()) {
            return;
        }
        Block placed = event.getBlockPlaced();
        String orientation = orientation(def.get().rotation(), event);
        try {
            placed.setBlockData(noteBlock(def.get().stateOf(orientation), false), false);
        } catch (IllegalArgumentException e) {
            return;
        }
        sound(placed, def.get().placeSound());
    }

    /** 놓는 방향: axis = 댄 면의 축 (원목), horizontal = 놓는 사람 쪽 (화로), facing = 바라보는 반대쪽, 위·아래 포함 (발사기). */
    private static String orientation(CustomBlockDef.Rotation rotation, BlockPlaceEvent event) {
        Player player = event.getPlayer();
        BlockFace face = event.getBlockAgainst().getFace(event.getBlockPlaced());
        BlockFace toward = player.getFacing().getOppositeFace();
        return switch (rotation) {
            case NONE -> "none";
            case AXIS -> {
                BlockFace f = face != null && face != BlockFace.SELF ? face : BlockFace.UP;
                yield f == BlockFace.EAST || f == BlockFace.WEST
                        ? "x"
                        : f == BlockFace.NORTH || f == BlockFace.SOUTH ? "z" : "y";
            }
            case HORIZONTAL -> toward.name().toLowerCase(Locale.ROOT);
            case FACING -> {
                float pitch = feet(player).getPitch();
                yield pitch > 45
                        ? "up"
                        : pitch < -45 ? "down" : toward.name().toLowerCase(Locale.ROOT);
            }
        };
    }

    /** 부술 수 없는 블록 · 경험치 · 부수는 소리. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Optional<CustomBlockDef> def = at(event.getBlock());
        if (def.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        boolean creative = player.getGameMode() == GameMode.CREATIVE;
        if (def.get().breaking().unbreakable() && !creative) {
            event.setCancelled(true);
            return;
        }
        int min = def.get().expMin();
        int max = def.get().expMax();
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!creative && max > 0 && BlockBreakSpeed.canHarvest(def.get().breaking(), tool)) {
            event.setExpToDrop(min + ThreadLocalRandom.current().nextInt(max - min + 1));
        }
        sound(event.getBlock(), def.get().breakSound());
    }

    /** 노트블록 대신 정한 드롭 (없으면 블록 자신). 도구가 맞지 않으면(requires_tool) 아무것도 없다. */
    @EventHandler(ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent event) {
        Optional<CustomBlockDef> def = at(event.getBlockState());
        if (def.isEmpty()) {
            return;
        }
        int amount = 0;
        for (Item drop : List.copyOf(event.getItems())) {
            ItemStack stack = drop.getItemStack();
            if (stack.getType() == Material.NOTE_BLOCK) {
                amount += stack.getAmount();
                event.getItems().remove(drop);
            }
        }
        if (amount == 0) {
            return;
        }
        ItemStack tool = event.getPlayer().getInventory().getItemInMainHand();
        CustomBlockDef block = def.get();
        if (!BlockBreakSpeed.canHarvest(block.breaking(), tool)) {
            return;
        }
        Location at = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
        World world = event.getBlock().getWorld();
        boolean silk = block.silkTouch() && tool.containsEnchantment(Enchantment.SILK_TOUCH);
        if (block.drops().isEmpty() || silk) {
            world.dropItemNaturally(at, item(block, amount));
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (CustomBlockDef.Drop drop : block.drops()) {
            if (random.nextDouble() * 100 >= drop.chance()) {
                continue;
            }
            int n = drop.min() + random.nextInt(drop.max() - drop.min() + 1);
            if (n <= 0) {
                continue;
            }
            dropItem(drop.item(), n).ifPresent(stack -> world.dropItemNaturally(at, stack));
        }
    }

    private Optional<ItemStack> dropItem(String item, int amount) {
        if (item.startsWith("minecraft:")) {
            Material material = Material.matchMaterial(item);
            return material == null || !material.isItem()
                    ? Optional.empty()
                    : Optional.of(
                            ItemStack.of(material, Math.min(amount, material.getMaxStackSize())));
        }
        return HarborId.tryParse(item)
                .flatMap(id -> content.get().get(ItemDef.TYPE, id))
                .map(def -> items.create(def, amount));
    }

    private static void sound(Block block, @Nullable String key) {
        if (key == null || !Key.parseable(key)) {
            return;
        }
        block.getWorld()
                .playSound(
                        Sound.sound(Key.key(key), Sound.Source.BLOCK, 1f, 1f),
                        block.getX() + 0.5,
                        block.getY() + 0.5,
                        block.getZ() + 0.5);
    }

    private static Location feet(Player player) {
        return java.util.Objects.requireNonNull(player.getLocation());
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
