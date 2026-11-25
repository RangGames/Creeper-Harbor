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

    /**
     * Core furniture inside a plot: allows removal if the player has build permission on the plot.
     * Furniture bought as decor goes back to the plot owner's storage count, not the remover's.
     */
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

    /**
     * Whether the block there is still that decor (if a piston or liquid changed it, only the tag
     * is left).
     */
    private boolean matches(Block block, DecorDef d) {
        HarborId custom = d.block();
        if (custom != null) {
            return kit.api().furniture().blockAt(block).map(custom::equals).orElse(false);
        }
        String raw = d.material();
        Material m = raw == null ? null : Material.matchMaterial(raw);
        // Blocks whose type changes when placed (like wall torches) are compared by their placement
        // item
        return m != null
                && (block.getType() == m || block.getBlockData().getPlacementMaterial() == m);
    }

    /**
     * Clears a leftover tag where something other than decor was placed (and the aquarium slot for
     * a tank).
     */
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
            return; // Core furniture placement confirmation (no block is placed)
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
            clearStale(
                    block); // The decor was already moved: break it normally without recovering it
            return;
        }
        block.getChunk().getPersistentDataContainer().remove(blockKey(block));
        event.setDropItems(false);
        Optional<HousingManager.PlotState> plot = housing.stateAt(block.getLocation());
        Player breaker = event.getPlayer();
        UUID owner = plot.map(p -> p.info.owner()).orElse(breaker.getUniqueId());
        if (owner.equals(breaker.getUniqueId())) {
            // The owner gets the decor item back as a drop, in creative too (proposed). It is saved
            // with the chunk like the removal, and goes back to storage when leaving the housing
            // world. Anyone else breaking it returns it to the owner's storage
            block.getWorld()
                    .dropItemNaturally(
                            block.getLocation().add(0.5, 0.5, 0.5), decor.item(d.get(), 1));
        } else {
            creditAfterWorldSave(block.getWorld(), owner, d.get().id(), 1);
        }
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

    // Decor tags are tied to positions, so moving the block leaves only the tag behind: prevent
    // decor
    // blocks from being pushed, pulled or washed away by liquids
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent event) {
        if (!housing.isHousingWorld(event.getBlock().getWorld())) {
            return;
        }
        BlockFace dir = event.getDirection();
        if (tagged(event.getBlock().getRelative(dir))) {
            event.setCancelled(true); // Breakable decor in the piston head's spot (torches etc.)
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

    /** Furniture placement: right-click a block inside a plot while holding furniture decor. */
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
            return; // Core furniture (furniture:) is placed by Core
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

    /** Furniture recovery: sneak + right-click (build permission). */
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
                // Ignore an invalid UUID
            }
        }
        World world = box.getWorld();
        box.remove();
        HarborId.tryParse(decorId)
                .ifPresent(id -> creditAfterWorldSave(world, plot.get().info.owner(), id, 1));
        kit.actionBar(player, "world.decor.furniture_removed");
    }
}
