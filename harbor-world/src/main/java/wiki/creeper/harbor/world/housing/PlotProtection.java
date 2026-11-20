package wiki.creeper.harbor.world.housing;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.util.BoundingBox;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.PlotInfo;
import wiki.creeper.harbor.world.api.PlotPermission;
import wiki.creeper.harbor.world.housing.HousingManager.PlotState;

/**
 * 플롯 보호 (하우징 월드). 블록 설치·파괴·상호작용, 피스톤, 액체 흐름, 폭발, 엔티티 생성을 플롯 경계로 막는다. 엔티티 30개 · 레드스톤 부품 20개 제한 (플러그인
 * 장식 Display·Interaction은 제외). 레드스톤 수는 지연 쓰기로 저장한다. 메인 스레드 전용.
 */
public final class PlotProtection implements Listener {

    private static final Set<Material> REDSTONE =
            EnumSet.of(
                    Material.REDSTONE_WIRE,
                    Material.REPEATER,
                    Material.COMPARATOR,
                    Material.PISTON,
                    Material.STICKY_PISTON,
                    Material.OBSERVER,
                    Material.DISPENSER,
                    Material.DROPPER,
                    Material.HOPPER,
                    Material.REDSTONE_TORCH,
                    Material.REDSTONE_WALL_TORCH,
                    Material.REDSTONE_LAMP,
                    Material.DAYLIGHT_DETECTOR,
                    Material.TARGET,
                    Material.LEVER,
                    Material.TRIPWIRE_HOOK,
                    Material.SCULK_SENSOR);

    private final WorldKit kit;
    private final PlotStore store;

    PlotProtection(WorldKit kit, PlotStore store) {
        this.kit = kit;
        this.store = store;
    }

    private boolean allowedAt(Player player, Location at) {
        if (player.hasPermission("harbor.world.bypass")) {
            return true;
        }
        Optional<PlotState> s = store.stateAt(at);
        return s.isPresent() && PlotAccess.can(player, s.get().info, PlotPermission.BUILD);
    }

    private boolean housing(@Nullable World world) {
        return world != null && store.isHousingWorld(world);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (!housing(block.getWorld())) {
            return;
        }
        Player player = event.getPlayer();
        if (!allowedAt(player, block.getLocation())) {
            event.setCancelled(true);
            kit.actionBar(player, "world.housing.no_build");
            return;
        }
        if (REDSTONE.contains(block.getType())) {
            Optional<PlotState> s = store.stateAt(block.getLocation());
            if (s.isPresent()) {
                if (s.get().redstone >= kit.balance().housingRedstoneLimit()) {
                    event.setCancelled(true);
                    kit.actionBar(
                            player,
                            "world.housing.redstone_limit",
                            Placeholder.unparsed(
                                    "limit", String.valueOf(kit.balance().housingRedstoneLimit())));
                    return;
                }
                s.get().redstone++;
                saveRedstone(s.get());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!housing(block.getWorld())) {
            return;
        }
        if (!allowedAt(event.getPlayer(), block.getLocation())) {
            event.setCancelled(true);
            kit.actionBar(event.getPlayer(), "world.housing.no_build");
            return;
        }
        if (REDSTONE.contains(block.getType())) {
            store.stateAt(block.getLocation())
                    .ifPresent(
                            s -> {
                                s.redstone = Math.max(0, s.redstone - 1);
                                saveRedstone(s);
                            });
        }
    }

    private void saveRedstone(PlotState s) {
        long id = s.info.plotId();
        int count = s.redstone;
        kit.api()
                .writeBehind()
                .enqueue(
                        "world:plot:" + id + ":redstone",
                        c -> HousingRepository.updateRedstone(c, id, count));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null
                || !housing(block.getWorld())
                || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!allowedAt(event.getPlayer(), block.getLocation())) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (housing(event.getBlock().getWorld())
                && !allowedAt(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketFillEvent event) {
        if (housing(event.getBlock().getWorld())
                && !allowedAt(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        Player player = event.getPlayer();
        Location at = event.getBlock().getLocation();
        if (player == null || !housing(at.getWorld())) {
            return;
        }
        if (!allowedAt(player, at) || overEntityLimit(at)) {
            event.setCancelled(true);
            kit.actionBar(
                    player,
                    "world.housing.entity_limit",
                    Placeholder.unparsed(
                            "limit", String.valueOf(kit.balance().housingEntityLimit())));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHang(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        Location at = event.getBlock().getLocation();
        if (player == null || !housing(at.getWorld())) {
            return;
        }
        if (!allowedAt(player, at) || overEntityLimit(at)) {
            event.setCancelled(true);
            kit.actionBar(
                    player,
                    "world.housing.entity_limit",
                    Placeholder.unparsed(
                            "limit", String.valueOf(kit.balance().housingEntityLimit())));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangBreak(HangingBreakByEntityEvent event) {
        Location at = WorldKit.loc(event.getEntity());
        if (!housing(at.getWorld())) {
            return;
        }
        if (!(event.getRemover() instanceof Player player) || !allowedAt(player, at)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        Entity target = event.getRightClicked();
        Location at = WorldKit.loc(target);
        if (!housing(at.getWorld()) || target instanceof Interaction) {
            return; // 개인 어항·가구 클릭(Interaction)은 Collection·장식이 처리한다
        }
        if (!allowedAt(event.getPlayer(), at)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Location at = WorldKit.loc(event.getEntity());
        if (!housing(at.getWorld()) || event.getEntity() instanceof Player) {
            return;
        }
        if (!(event.getDamager() instanceof Player player) || !allowedAt(player, at)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        Location at = event.getLocation();
        if (!housing(at.getWorld())) {
            return;
        }
        switch (event.getSpawnReason()) {
            case CUSTOM, COMMAND, DEFAULT -> {
                // 플러그인 연출 (개인 어항 리그 등)
            }
            case SPAWNER_EGG -> {
                if (store.stateAt(at).isEmpty() || overEntityLimit(at)) {
                    event.setCancelled(true);
                }
            }
            default -> event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent event) {
        if (housing(event.getBlock().getWorld())
                && crosses(
                        event.getBlock(),
                        event.getBlocks(),
                        event.getDirection().getModX(),
                        event.getDirection().getModZ())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPiston(BlockPistonRetractEvent event) {
        if (housing(event.getBlock().getWorld())
                && crosses(
                        event.getBlock(),
                        event.getBlocks(),
                        event.getDirection().getModX(),
                        event.getDirection().getModZ())) {
            event.setCancelled(true);
        }
    }

    private boolean crosses(Block piston, List<Block> blocks, int dx, int dz) {
        Optional<PlotState> home = store.stateAt(piston.getLocation());
        if (home.isEmpty()) {
            return true;
        }
        PlotInfo plot = home.get().info;
        for (Block b : blocks) {
            if (!plot.contains(plot.world(), b.getX() + 0.5, b.getZ() + 0.5)
                    || !plot.contains(plot.world(), b.getX() + dx + 0.5, b.getZ() + dz + 0.5)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        Block from = event.getBlock();
        if (!housing(from.getWorld())) {
            return;
        }
        Optional<PlotState> a = store.stateAt(from.getLocation());
        Optional<PlotState> b = store.stateAt(event.getToBlock().getLocation());
        if (a.isEmpty() || b.isEmpty() || a.get() != b.get()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (housing(event.getLocation().getWorld())) {
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(BlockExplodeEvent event) {
        if (housing(event.getBlock().getWorld())) {
            event.blockList().clear();
        }
    }

    /** 플롯 안 엔티티 수 (플레이어·저장하지 않는 엔티티·플러그인 장식 제외). */
    private boolean overEntityLimit(Location at) {
        Optional<PlotState> s = store.stateAt(at);
        World world = at.getWorld();
        if (s.isEmpty() || world == null) {
            return true;
        }
        PlotInfo p = s.get().info;
        BoundingBox box =
                new BoundingBox(
                        p.minX(),
                        world.getMinHeight(),
                        p.minZ(),
                        p.maxX() + 1,
                        world.getMaxHeight(),
                        p.maxZ() + 1);
        int count = 0;
        for (Entity e : world.getNearbyEntities(box)) {
            if (e instanceof Player
                    || !e.isPersistent()
                    || e.getPersistentDataContainer().has(kit.key("decor"))) {
                continue;
            }
            count++;
        }
        return count >= kit.balance().housingEntityLimit();
    }
}
