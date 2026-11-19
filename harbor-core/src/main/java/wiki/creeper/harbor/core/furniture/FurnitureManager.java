package wiki.creeper.harbor.core.furniture;

import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.FurnitureDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.ItemModels;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.api.Ui;
import wiki.creeper.harbor.core.api.event.FurnitureRemoveEvent;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * 가구 ({@link FurnitureDef}, 추가 제안). 한 가구 = ItemDisplay(모델) + Interaction(클릭 판정) + 선택: 방벽
 * 블록(solid)·빛 블록(light).
 *
 * <ul>
 *   <li>설치: 가구 아이템을 들고 블록을 우클릭. 보호 구역은 그 칸에 대한 BlockPlaceEvent로 확인한다 (하우징·WorldGuard 등이 그대로 막는다)
 *   <li>앉기: seat가 있는 가구를 우클릭. 보이지 않는 좌석 엔티티(저장하지 않음)에 태우고, 내리면 지운다
 *   <li>회수: 웅크리고 우클릭. 그 칸에 대한 BlockBreakEvent로 보호 구역을 확인하고, 설치한 사람·{@value #MANAGE} 권한자만 회수한다 (하우징은
 *       플롯 건축 권한, {@link FurnitureRemoveEvent#setAllowed}). 설치에 쓴 아이템을 돌려준다
 * </ul>
 *
 * Interaction에 가구 ID, 모델 엔티티 UUID, 설치 아이템(직렬화)을 PDC로 남겨서 서버가 다시 켜져도 그대로 회수할 수 있다.
 */
public final class FurnitureManager implements Listener {

    @SuppressWarnings("deprecation") // 플러그인 인스턴스 없이 고정 네임스페이스를 쓴다
    static final NamespacedKey ID = new NamespacedKey("harbor", "furniture");

    @SuppressWarnings("deprecation")
    private static final NamespacedKey DISPLAY = new NamespacedKey("harbor", "furniture_display");

    @SuppressWarnings("deprecation")
    private static final NamespacedKey ITEM = new NamespacedKey("harbor", "furniture_item");

    @SuppressWarnings("deprecation")
    private static final NamespacedKey SEAT = new NamespacedKey("harbor", "furniture_seat");

    @SuppressWarnings("deprecation")
    private static final NamespacedKey OWNER = new NamespacedKey("harbor", "furniture_owner");

    /** 남이 놓은 가구도 회수할 수 있는 권한. */
    static final String MANAGE = "harbor.furniture.manage";

    /** 같은 칸에 다른 가구가 있는지 볼 반경. */
    private static final double OCCUPIED_RADIUS = 0.45;

    private final Supplier<ContentSnapshot> content;
    private final MessageService messages;
    private final Ui ui;
    private final Tasks tasks;

    /** 가구(Interaction) UUID → 좌석 엔티티 UUID. */
    private final Map<UUID, UUID> seats = new HashMap<>();

    public FurnitureManager(
            Supplier<ContentSnapshot> content, MessageService messages, Ui ui, Tasks tasks) {
        this.content = content;
        this.messages = messages;
        this.ui = ui;
        this.tasks = tasks;
    }

    private void bar(Player player, String key, TagResolver... resolvers) {
        ui.actionBar(player, messages.get(key, resolvers));
    }

    public Optional<FurnitureDef> def(HarborId id) {
        return content.get().get(FurnitureDef.TYPE, id);
    }

    public List<FurnitureDef> all() {
        return content.get().all(FurnitureDef.TYPE).values().stream()
                .sorted(java.util.Comparator.comparing(FurnitureDef::id))
                .toList();
    }

    public ItemStack item(FurnitureDef def) {
        ItemStack stack = look(def);
        stack.setData(
                DataComponentTypes.ITEM_NAME,
                messages.get("furniture.item_name", Placeholder.unparsed("name", def.name())));
        stack.setData(
                DataComponentTypes.LORE,
                io.papermc.paper.datacomponent.item.ItemLore.lore(
                        List.of(
                                messages.get(
                                        def.seat() != null
                                                ? "furniture.lore_seat"
                                                : "furniture.lore"))));
        String id = def.id().toString();
        stack.editPersistentDataContainer(pdc -> pdc.set(ID, PersistentDataType.STRING, id));
        return stack;
    }

    /** 모델만 입힌 아이템 (ItemDisplay 표시용). */
    private ItemStack look(FurnitureDef def) {
        Material base = Material.matchMaterial(def.baseItem());
        ItemStack stack = ItemStack.of(base != null && base.isItem() ? base : Material.PAPER);
        ItemModels.apply(stack, def.model(), content.get());
        return stack;
    }

    public Optional<HarborId> idOf(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        String raw = stack.getPersistentDataContainer().get(ID, PersistentDataType.STRING);
        return raw == null ? Optional.empty() : HarborId.tryParse(raw);
    }

    // ------------------------------------------------------------------ 설치

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        Optional<FurnitureDef> def = idOf(hand).flatMap(this::def);
        Block clicked = event.getClickedBlock();
        if (def.isEmpty() || clicked == null) {
            return;
        }
        event.setCancelled(true);
        Block target =
                clicked.isReplaceable() ? clicked : clicked.getRelative(event.getBlockFace());
        place(player, hand, def.get(), clicked, target);
    }

    private void place(
            Player player, ItemStack hand, FurnitureDef def, Block against, Block target) {
        List<Block> cells = new ArrayList<>();
        for (int i = 0; i < Math.max(1, def.solidCells()); i++) {
            cells.add(target.getRelative(BlockFace.UP, i));
        }
        Block light = def.light() > 0 ? target.getRelative(BlockFace.UP, def.solidCells()) : null;
        List<Block> needed = new ArrayList<>(cells);
        if (light != null && !needed.contains(light)) {
            needed.add(light);
        }
        for (Block b : needed) {
            if (!b.isReplaceable()) {
                bar(player, "furniture.no_space");
                return;
            }
        }
        Location base = target.getLocation().add(0.5, 0, 0.5);
        World world = target.getWorld();
        if (!world.getNearbyEntities(
                        base.clone().add(0, 0.5, 0),
                        OCCUPIED_RADIUS,
                        0.5,
                        OCCUPIED_RADIUS,
                        e -> e instanceof Interaction && isFurniture(e))
                .isEmpty()) {
            bar(player, "furniture.occupied");
            return;
        }
        BlockPlaceEvent check =
                new BlockPlaceEvent(
                        target,
                        target.getState(),
                        against,
                        hand.clone(),
                        player,
                        true,
                        EquipmentSlot.HAND);
        if (!check.callEvent() || !check.canBuild()) {
            bar(player, "furniture.no_place");
            return;
        }
        float yaw = snap(feet(player).getYaw() + 180f, def.rotation());
        base.setYaw(yaw);
        ItemStack look = look(def);
        String id = def.id().toString();
        float scale = (float) def.scale();
        ItemDisplay display =
                world.spawn(
                        base.clone().add(0, def.offsetY(), 0),
                        ItemDisplay.class,
                        e -> {
                            e.setItemStack(look);
                            e.setPersistent(true);
                            if (scale != 1f) {
                                e.setTransformation(
                                        new Transformation(
                                                new Vector3f(),
                                                new AxisAngle4f(),
                                                new Vector3f(scale, scale, scale),
                                                new AxisAngle4f()));
                            }
                            e.getPersistentDataContainer().set(ID, PersistentDataType.STRING, id);
                        });
        ItemStack stored = hand.clone();
        stored.setAmount(1);
        byte[] bytes = stored.serializeAsBytes();
        String owner = player.getUniqueId().toString();
        world.spawn(
                base,
                Interaction.class,
                e -> {
                    e.setInteractionWidth((float) def.width());
                    e.setInteractionHeight((float) def.height());
                    e.setResponsive(true);
                    e.setPersistent(true);
                    PersistentDataContainer pdc = e.getPersistentDataContainer();
                    pdc.set(ID, PersistentDataType.STRING, id);
                    pdc.set(DISPLAY, PersistentDataType.STRING, display.getUniqueId().toString());
                    pdc.set(ITEM, PersistentDataType.BYTE_ARRAY, bytes);
                    pdc.set(OWNER, PersistentDataType.STRING, owner);
                });
        for (int i = 0; i < def.solidCells(); i++) {
            cells.get(i).setType(Material.BARRIER, false);
        }
        if (light != null) {
            light.setType(Material.LIGHT, false);
            if (light.getBlockData() instanceof Levelled level) {
                level.setLevel(def.light());
                light.setBlockData(level, false);
            }
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            hand.setAmount(hand.getAmount() - 1);
        }
        world.playSound(base, Sound.BLOCK_WOOD_PLACE, 1f, 1f);
    }

    /** 플레이어 방향을 회전 단계에 맞춘다 (rotation 4 = 90도). */
    static float snap(float yaw, int steps) {
        float step = 360f / steps;
        float snapped = Math.round(yaw / step) * step;
        return ((snapped % 360f) + 360f) % 360f;
    }

    // ------------------------------------------------------------------ 앉기 · 회수

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction box) || !isFurniture(box)) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        Optional<FurnitureDef> def = furnitureOf(box).flatMap(this::def);
        if (player.isSneaking()) {
            remove(player, box);
        } else if (def.isPresent() && def.get().seat() != null) {
            sit(player, box, def.get());
        }
    }

    private void sit(Player player, Interaction box, FurnitureDef def) {
        if (player.isInsideVehicle()) {
            return;
        }
        UUID seatId = seats.get(box.getUniqueId());
        Entity existing = seatId == null ? null : Bukkit.getEntity(seatId);
        if (existing != null && existing.isValid() && !existing.getPassengers().isEmpty()) {
            bar(player, "furniture.seat_taken");
            return;
        }
        if (existing != null) {
            existing.remove();
        }
        Double height = def.seat();
        Location at = feet(box).add(0, height == null ? 0 : height, 0);
        at.setYaw(feet(box).getYaw());
        String boxId = box.getUniqueId().toString();
        ItemDisplay seat =
                box.getWorld()
                        .spawn(
                                at,
                                ItemDisplay.class,
                                e -> {
                                    e.setPersistent(false);
                                    e.getPersistentDataContainer()
                                            .set(SEAT, PersistentDataType.STRING, boxId);
                                });
        seats.put(box.getUniqueId(), seat.getUniqueId());
        seat.addPassenger(player);
    }

    /** 좌석에서 내리면 좌석을 지우고 가구 위로 올린다 (방벽 안에 끼지 않게). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        Entity seat = event.getDismounted();
        String boxId = seat.getPersistentDataContainer().get(SEAT, PersistentDataType.STRING);
        if (boxId == null) {
            return;
        }
        seats.values().remove(seat.getUniqueId());
        Location seatAt = feet(seat);
        tasks.sync(
                () -> {
                    seat.remove();
                    if (event.getEntity() instanceof Player player && player.isOnline()) {
                        Location up = feet(player);
                        up.setY(
                                Math.max(
                                        up.getY(), Math.floor(seatAt.getY()) + standHeight(boxId)));
                        var _ = player.teleportAsync(up);
                    }
                });
    }

    /** 일어설 높이: solid 가구면 방벽 위, 아니면 그 자리. */
    private double standHeight(String boxId) {
        try {
            Entity box = Bukkit.getEntity(UUID.fromString(boxId));
            if (box instanceof Interaction i) {
                return furnitureOf(i).flatMap(this::def).map(FurnitureDef::solidCells).orElse(0);
            }
        } catch (IllegalArgumentException e) {
            // 잘못된 UUID
        }
        return 0;
    }

    private void remove(Player player, Interaction box) {
        Location base = feet(box);
        Block block = base.getBlock();
        BlockBreakEvent check = new BlockBreakEvent(block, player);
        check.setDropItems(false);
        check.setExpToDrop(0);
        if (!check.callEvent()) {
            bar(player, "furniture.no_remove");
            return;
        }
        HarborId id = furnitureOf(box).orElse(null);
        if (id == null) {
            return;
        }
        Optional<FurnitureDef> def = def(id);
        ItemStack item = storedItem(box).orElseGet(() -> def.map(this::item).orElse(null));
        if (item == null) {
            item = ItemStack.of(Material.PAPER);
        }
        String owner = box.getPersistentDataContainer().get(OWNER, PersistentDataType.STRING);
        boolean mine = player.getUniqueId().toString().equals(owner);
        FurnitureRemoveEvent removing =
                new FurnitureRemoveEvent(
                        player, base, id, item, mine || player.hasPermission(MANAGE));
        if (!removing.callEvent()) {
            return;
        }
        if (!removing.allowed()) {
            bar(player, "furniture.no_remove");
            return;
        }
        String displayId = box.getPersistentDataContainer().get(DISPLAY, PersistentDataType.STRING);
        entity(displayId).ifPresent(Entity::remove);
        UUID seatId = seats.remove(box.getUniqueId());
        if (seatId != null) {
            Entity seat = Bukkit.getEntity(seatId);
            if (seat != null) {
                seat.eject();
                seat.remove();
            }
        }
        int solid = def.map(FurnitureDef::solidCells).orElse(0);
        for (int i = 0; i < Math.max(solid, 1); i++) {
            Block cell = block.getRelative(BlockFace.UP, i);
            if (cell.getType() == Material.BARRIER) {
                cell.setType(Material.AIR, false);
            }
        }
        if (def.map(FurnitureDef::light).orElse(0) > 0) {
            Block light = block.getRelative(BlockFace.UP, solid);
            if (light.getType() == Material.LIGHT) {
                light.setType(Material.AIR, false);
            }
        }
        box.remove();
        if (removing.returnItem()) {
            for (ItemStack left : player.getInventory().addItem(item).values()) {
                player.getWorld().dropItem(feet(player), left);
            }
        }
        box.getWorld().playSound(base, Sound.BLOCK_WOOD_BREAK, 1f, 1f);
        bar(
                player,
                "furniture.removed",
                Placeholder.unparsed("name", def.map(FurnitureDef::name).orElse(id.toString())));
    }

    /** 꺼질 때 좌석을 치운다 (좌석은 저장하지 않지만 앉은 사람을 내려 둔다). */
    public void shutdown() {
        for (UUID seatId : List.copyOf(seats.values())) {
            Entity seat = Bukkit.getEntity(seatId);
            if (seat != null) {
                seat.eject();
                seat.remove();
            }
        }
        seats.clear();
    }

    // ------------------------------------------------------------------ 도우미

    private static boolean isFurniture(Entity e) {
        return e.getPersistentDataContainer().has(ID, PersistentDataType.STRING);
    }

    private static Optional<HarborId> furnitureOf(Entity e) {
        String raw = e.getPersistentDataContainer().get(ID, PersistentDataType.STRING);
        return raw == null ? Optional.empty() : HarborId.tryParse(raw);
    }

    private static Optional<ItemStack> storedItem(Entity box) {
        byte[] bytes = box.getPersistentDataContainer().get(ITEM, PersistentDataType.BYTE_ARRAY);
        if (bytes == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(ItemStack.deserializeBytes(bytes));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<Entity> entity(@Nullable String uuid) {
        if (uuid == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(Bukkit.getEntity(UUID.fromString(uuid)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Location feet(Entity entity) {
        return Objects.requireNonNull(entity.getLocation());
    }
}
