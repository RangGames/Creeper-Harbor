package wiki.creeper.harbor.world.housing;

import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.ItemModels;
import wiki.creeper.harbor.core.api.event.FurnitureRemoveEvent;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.DisplayAnchor;
import wiki.creeper.harbor.world.api.PlotPermission;
import wiki.creeper.harbor.world.api.event.DisplayAnchorChangedEvent;
import wiki.creeper.harbor.world.content.DecorDef;

/**
 * Placing and removing decor in the world: block decor (chunk PDC), tanks (display anchors) and
 * furniture (split out of {@link DecorManager}).
 */
public final class DecorPlacement implements Listener {

    private final WorldKit kit;
    private final HousingManager housing;
    private final DecorManager decor;

    DecorPlacement(WorldKit kit, HousingManager housing, DecorManager decor) {
        this.kit = kit;
        this.housing = housing;
        this.decor = decor;
    }

    private NamespacedKey tag() {
        return decor.tag();
    }

    private Optional<DecorDef> def(HarborId id) {
        return decor.def(id);
    }

    private Optional<DecorDef> def(@Nullable ItemStack stack) {
        return decor.def(stack);
    }

    private void addCount(UUID uuid, HarborId id, int amount) {
        decor.addCount(uuid, id, amount);
    }

    private void creditAfterWorldSave(World world, UUID owner, HarborId id, int amount) {
        decor.creditAfterWorldSave(world, owner, id, amount);
    }

    /** 플롯 안 Core 가구: 플롯 건축 권한이 있으면 회수를 허락한다. 장식으로 산 가구는 회수한 사람이 아니라 플롯 주인의 보관함 개수로 돌린다. */
    @EventHandler(ignoreCancelled = true)
    public void onFurnitureRemove(FurnitureRemoveEvent event) {
        Optional<HousingManager.PlotState> plot = housing.stateAt(event.location());
        if (plot.isEmpty()) {
            return;
        }
        if (housing.can(event.player(), plot.get().info, PlotPermission.BUILD)) {
            event.setAllowed(true);
        }
        Optional<DecorDef> d = def(event.item());
        if (d.isEmpty() || !event.allowed()) {
            return;
        }
        event.setReturnItem(false);
        World world = event.location().getWorld();
        if (world != null) {
            creditAfterWorldSave(world, plot.get().info.owner(), d.get().id(), 1);
        } else {
            addCount(plot.get().info.owner(), d.get().id(), 1);
        }
    }

    private NamespacedKey blockKey(Block block) {
        return kit.key("d_" + (block.getX() & 15) + "_" + block.getY() + "_" + (block.getZ() & 15));
    }

    private Optional<DecorDef> placedAt(Block block) {
        String raw =
                block.getChunk()
                        .getPersistentDataContainer()
                        .get(blockKey(block), PersistentDataType.STRING);
        return raw == null ? Optional.empty() : HarborId.tryParse(raw).flatMap(this::def);
    }

    private boolean tagged(Block block) {
        return block.getChunk().getPersistentDataContainer().has(blockKey(block));
    }

    /** 그 자리 블록이 아직 그 장식인지 (피스톤·액체 등으로 바뀌었으면 태그만 남은 것). */
    private boolean matches(Block block, DecorDef d) {
        HarborId custom = d.block();
        if (custom != null) {
            return kit.api().furniture().blockAt(block).map(custom::equals).orElse(false);
        }
        String raw = d.material();
        Material m = raw == null ? null : Material.matchMaterial(raw);
        // 벽 횃불처럼 놓으면 종류가 바뀌는 블록은 설치 아이템으로 비교한다
        return m != null
                && (block.getType() == m || block.getBlockData().getPlacementMaterial() == m);
    }

    /** 장식이 아닌 것이 놓인 자리의 남은 태그를 지운다 (수조면 어항 자리도). */
    private void clearStale(Block block) {
        Optional<DecorDef> d = placedAt(block);
        block.getChunk().getPersistentDataContainer().remove(blockKey(block));
        if (d.isPresent() && d.get().kind() == DecorDef.Kind.TANK) {
            housing.stateAt(block.getLocation()).ifPresent(p -> removeAnchorAt(p, block));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Optional<DecorDef> d = def(event.getItemInHand());
        Block block = event.getBlockPlaced();
        if (d.isEmpty()) {
            if (tagged(block)) {
                clearStale(block);
            }
            return;
        }
        Optional<HousingManager.PlotState> plot = housing.stateAt(block.getLocation());
        if (plot.isEmpty()
                || (d.get().kind() == DecorDef.Kind.FURNITURE && d.get().furniture() == null)) {
            event.setCancelled(true);
            return;
        }
        if (d.get().kind() == DecorDef.Kind.FURNITURE) {
            return; // Core 가구 설치 확인 (블록을 놓지 않는다)
        }
        if (d.get().kind() == DecorDef.Kind.TANK
                && plot.get().anchors.size() >= kit.balance().housingAnchorLimit()) {
            event.setCancelled(true);
            kit.actionBar(
                    event.getPlayer(),
                    "world.decor.tank_limit",
                    Placeholder.unparsed(
                            "limit", String.valueOf(kit.balance().housingAnchorLimit())));
            return;
        }
        block.getChunk()
                .getPersistentDataContainer()
                .set(blockKey(block), PersistentDataType.STRING, d.get().id().toString());
        if (d.get().kind() == DecorDef.Kind.TANK) {
            addAnchor(event.getPlayer(), plot.get(), d.get(), block);
        }
    }

    private void addAnchor(Player player, HousingManager.PlotState plot, DecorDef d, Block block) {
        int slot = 0;
        while (true) {
            int s = slot;
            if (plot.anchors.stream().noneMatch(a -> a.slot() == s)) {
                break;
            }
            slot++;
        }
        BlockFace face = player.getFacing().getOppositeFace();
        DisplayAnchor anchor =
                new DisplayAnchor(
                        plot.info.plotId(),
                        slot,
                        d.id(),
                        plot.info.world(),
                        block.getX(),
                        block.getY(),
                        block.getZ(),
                        face.name());
        housing.addAnchor(plot, anchor);
        var _ = kit.api().database().asyncVoid(c -> HousingRepository.addAnchor(c, anchor));
        Bukkit.getPluginManager().callEvent(new DisplayAnchorChangedEvent(plot.info, anchor, true));
        kit.actionBar(
                player,
                "world.decor.tank_placed",
                Placeholder.unparsed("slot", String.valueOf(slot + 1)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Optional<DecorDef> d = placedAt(block);
        if (d.isEmpty()) {
            return;
        }
        if (!matches(block, d.get())) {
            clearStale(block); // 장식은 이미 옮겨졌다: 회수하지 않고 평범하게 부순다
            return;
        }
        block.getChunk().getPersistentDataContainer().remove(blockKey(block));
        event.setDropItems(false);
        Optional<HousingManager.PlotState> plot = housing.stateAt(block.getLocation());
        UUID owner = plot.map(p -> p.info.owner()).orElse(event.getPlayer().getUniqueId());
        creditAfterWorldSave(block.getWorld(), owner, d.get().id(), 1);
        if (d.get().kind() == DecorDef.Kind.TANK && plot.isPresent()) {
            removeAnchorAt(plot.get(), block);
        }
    }

    private void removeAnchorAt(HousingManager.PlotState plot, Block block) {
        plot.anchors.stream()
                .filter(
                        a ->
                                a.x() == block.getX()
                                        && a.y() == block.getY()
                                        && a.z() == block.getZ())
                .findFirst()
                .ifPresent(
                        a -> {
                            housing.removeAnchor(plot, a.slot());
                            long id = a.plotId();
                            int slot = a.slot();
                            var _ =
                                    kit.api()
                                            .database()
                                            .asyncVoid(
                                                    c ->
                                                            HousingRepository.removeAnchor(
                                                                    c, id, slot));
                            Bukkit.getPluginManager()
                                    .callEvent(new DisplayAnchorChangedEvent(plot.info, a, false));
                        });
    }

    // 장식 태그는 위치에 붙어 있어 블록이 옮겨지면 태그만 남는다: 장식 블록을 밀거나 당기거나 액체로 쓸어 가지 못하게 한다
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent event) {
        if (!housing.isHousingWorld(event.getBlock().getWorld())) {
            return;
        }
        BlockFace dir = event.getDirection();
        if (tagged(event.getBlock().getRelative(dir))) {
            event.setCancelled(true); // 머리 자리의 부서지는 장식 (횃불 등)
            return;
        }
        for (Block b : event.getBlocks()) {
            if (tagged(b) || tagged(b.getRelative(dir))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPiston(BlockPistonRetractEvent event) {
        if (!housing.isHousingWorld(event.getBlock().getWorld())) {
            return;
        }
        for (Block b : event.getBlocks()) {
            if (tagged(b)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        Block to = event.getToBlock();
        if (housing.isHousingWorld(to.getWorld()) && tagged(to)) {
            event.setCancelled(true);
        }
    }

    /** 가구 설치: 가구 장식을 들고 플롯 안 블록을 우클릭. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        Optional<DecorDef> d = def(hand);
        Block clicked = event.getClickedBlock();
        if (d.isEmpty()
                || d.get().kind() != DecorDef.Kind.FURNITURE
                || d.get().furniture() != null
                || clicked == null) {
            return; // Core 가구(furniture:)는 Core가 놓는다
        }
        event.setCancelled(true);
        Block target = clicked.getRelative(event.getBlockFace());
        Optional<HousingManager.PlotState> plot = housing.stateAt(target.getLocation());
        if (plot.isEmpty()
                || !housing.can(player, plot.get().info, PlotPermission.BUILD)
                || !target.getType().isAir()) {
            kit.actionBar(player, "world.housing.no_build");
            return;
        }
        World world = target.getWorld();
        Location at = target.getLocation().add(0.5, 0, 0.5);
        at.setYaw(Math.round(WorldKit.loc(player).getYaw() / 90f) * 90f + 180f);
        String model = d.get().model();
        ItemStack look = ItemStack.of(Material.PAPER);
        ItemModels.apply(look, model);
        String decorId = d.get().id().toString();
        ItemDisplay display =
                world.spawn(
                        at.clone().add(0, 0.5, 0),
                        ItemDisplay.class,
                        e -> {
                            e.setItemStack(look);
                            e.setPersistent(true);
                            e.getPersistentDataContainer()
                                    .set(tag(), PersistentDataType.STRING, decorId);
                        });
        world.spawn(
                at,
                Interaction.class,
                e -> {
                    e.setInteractionWidth(0.9f);
                    e.setInteractionHeight(1.0f);
                    e.setPersistent(true);
                    e.getPersistentDataContainer().set(tag(), PersistentDataType.STRING, decorId);
                    e.getPersistentDataContainer()
                            .set(
                                    kit.key("decor_display"),
                                    PersistentDataType.STRING,
                                    display.getUniqueId().toString());
                });
        hand.setAmount(hand.getAmount() - 1);
    }

    /** 가구 회수: 웅크리고 우클릭 (건축 권한). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onFurniture(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction box)) {
            return;
        }
        String decorId = box.getPersistentDataContainer().get(tag(), PersistentDataType.STRING);
        if (decorId == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        Optional<HousingManager.PlotState> plot = housing.stateAt(WorldKit.loc(box));
        if (!player.isSneaking()
                || plot.isEmpty()
                || !housing.can(player, plot.get().info, PlotPermission.BUILD)) {
            return;
        }
        String displayId =
                box.getPersistentDataContainer()
                        .get(kit.key("decor_display"), PersistentDataType.STRING);
        if (displayId != null) {
            try {
                Entity display = Bukkit.getEntity(UUID.fromString(displayId));
                if (display != null) {
                    display.remove();
                }
            } catch (IllegalArgumentException e) {
                // 잘못된 UUID는 무시
            }
        }
        World world = box.getWorld();
        box.remove();
        HarborId.tryParse(decorId)
                .ifPresent(id -> creditAfterWorldSave(world, plot.get().info.owner(), id, 1));
        kit.actionBar(player, "world.decor.furniture_removed");
    }
}
