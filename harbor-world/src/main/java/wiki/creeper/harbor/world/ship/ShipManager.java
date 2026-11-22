package wiki.creeper.harbor.world.ship;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.core.api.HandoffBlocker;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.SeaEventService;
import wiki.creeper.harbor.world.api.ShipInfo;
import wiki.creeper.harbor.world.api.ShipService;
import wiki.creeper.harbor.world.api.ShipState;
import wiki.creeper.harbor.world.api.UpgradePath;
import wiki.creeper.harbor.world.bridge.Payments;
import wiki.creeper.harbor.world.content.ShipDef;
import wiki.creeper.harbor.world.content.WorldBalance;
import wiki.creeper.harbor.world.region.RegionManager;

/**
 * Ships (docs 20 8.4 'ships'). Vanilla boat + decoration rig (Core RigInstance). Entities are not
 * saved (setPersistent false); they are recalled on quit, server shutdown, unload of a chunk
 * without riders and server moves, saving durability and cargo. Boats left with a PDC tag are
 * removed at startup and on chunk load.
 *
 * <ul>
 *   <li>Speed: while the driver presses forward, adds forward acceleration × (multiplier − 1) each
 *       tick (periodic speed correction, Phase 1 verification item). Multiplier = ship speed ×
 *       speed upgrade × boat_speed stat × storm
 *   <li>Decoration: on a regular boat the decoration root rides in the second passenger seat; when
 *       a player boards, it dismounts and remounts to keep the order. A chest boat (1 seat) follows
 *       every tick
 *   <li>Storm: in storm waters, durability −damage × boat_damage every 5 seconds and speed ×0.7
 *       (none with storm sailing rank 1). Only the ocean explorer (storm) may enter storm waters.
 *       At durability 0 passengers are dismounted and the ship becomes WRECKED
 *   <li>Icebreaking: the icebreaker turns the ice ahead into water in the fjord ice zone and
 *       restores it after 60 seconds (nothing outside the zone is touched)
 *   <li>Cargo: the cargo hold opens as a chest GUI (opening a chest boat or /선박 화물) and is saved
 *       write-behind on close
 * </ul>
 *
 * <p>State is handled by {@link ShipFleet}, summoning by {@link ShipSummoner}, sailing, storms and
 * icebreaking by {@link ShipNavigator}, cargo by {@link ShipCargo}, repair kits by {@link
 * ShipRepairKit} and shipyard trades by {@link ShipyardService}; this class handles ShipService,
 * join/quit and event wiring.
 */
public final class ShipManager implements ShipService, Listener {

    private final WorldKit kit;
    private final ShipFleet fleet;
    private final ShipSummoner summoner;
    private final ShipNavigator navigator;
    private final ShipCargo cargo;
    private final ShipRepairKit repairKit;
    private final ShipyardService shipyard;
    private final ShipyardMenu yard;

    public ShipManager(WorldKit kit, RegionManager regions, Payments payments) {
        this.kit = kit;
        this.fleet = new ShipFleet(kit);
        this.summoner = new ShipSummoner(kit, fleet);
        this.navigator = new ShipNavigator(kit, regions, fleet);
        this.cargo = new ShipCargo(kit, fleet);
        this.repairKit = new ShipRepairKit(kit, fleet);
        this.shipyard = new ShipyardService(kit, fleet, payments);
        this.yard = new ShipyardMenu(kit, this);
    }

    public void seaEvents(SeaEventService service) {
        navigator.seaEvents(service);
    }

    public void start() {
        var _ = kit.tasks().timer(1, 1, navigator::tick);
        var _ = kit.tasks().timer(20, 20, navigator::slowTick);
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (isTagged(e)) {
                    e.remove();
                }
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            load(p);
        }
    }

    /** Server shutdown: recalls everything and waits for the saves (up to 3 seconds). */
    public void shutdown() {
        List<CompletableFuture<Void>> saves = new ArrayList<>();
        for (Afloat a : fleet.afloatList()) {
            saves.add(fleet.recall(a, ShipState.STORED));
        }
        navigator.restoreAll();
        try {
            CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new))
                    .get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            kit.log().warn("선박 저장을 기다리지 못했습니다: {}", String.valueOf(e));
        }
    }

    // ------------------------------------------------------------------ lookup

    public Optional<ShipDef> def(HarborId type) {
        return fleet.def(type);
    }

    ShipInfo info(ShipRepository.Row row) {
        return fleet.info(row);
    }

    List<ShipRepository.Row> rows(UUID owner) {
        return fleet.rows(owner);
    }

    @Override
    public List<ShipInfo> ships(UUID owner) {
        return rows(owner).stream().map(this::info).toList();
    }

    @Override
    public Optional<ShipInfo> afloat(UUID owner) {
        return fleet.afloatList().stream()
                .filter(a -> a.row.owner().equals(owner))
                .findFirst()
                .map(a -> info(a.row));
    }

    @Override
    public Optional<ShipInfo> aboard(Player player) {
        return fleet.aboardAfloat(player).map(a -> info(a.row));
    }

    @Override
    public Optional<ShipInfo> byEntity(Entity boat) {
        return fleet.byEntity(boat).map(a -> info(a.row));
    }

    /** Type of the ship currently ridden (region vehicle condition). */
    public @Nullable HarborId shipType(Player player) {
        return fleet.aboardAfloat(player).map(a -> a.row.type()).orElse(null);
    }

    @Override
    public double speedMultiplier(Player driver) {
        Optional<Afloat> a = fleet.aboardAfloat(driver);
        if (a.isEmpty()) {
            return 1;
        }
        return navigator.speed(driver, a.get());
    }

    // ------------------------------------------------------------------ loading and join

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        load(event.getPlayer());
    }

    private void load(Player player) {
        UUID uuid = player.getUniqueId();
        var _ = summoner.donorTier(player);
        int season = kit.season();
        WorldBalance b = kit.balance();
        HarborId starterId =
                HarborId.tryParse(b.starterShip()).orElse(HarborId.parse("ship/rowboat"));
        Optional<ShipDef> starter = def(starterId).filter(ShipDef::starter);
        var _ =
                kit.api()
                        .database()
                        .async(
                                c -> {
                                    List<ShipRepository.Row> rows =
                                            ShipRepository.load(c, uuid, season);
                                    boolean granted = false;
                                    if (rows.isEmpty() && starter.isPresent()) {
                                        granted =
                                                ShipRepository.insertStarter(
                                                        c,
                                                        uuid,
                                                        season,
                                                        starter.get().id(),
                                                        starter.get().durability());
                                        rows = ShipRepository.load(c, uuid, season);
                                    }
                                    return new Object[] {rows, granted};
                                })
                        .whenCompleteAsync(
                                (result, error) -> {
                                    if (error != null || result == null) {
                                        kit.log()
                                                .warn(
                                                        "선박 적재 실패 {}: {}",
                                                        uuid,
                                                        String.valueOf(error));
                                        return;
                                    }
                                    @SuppressWarnings("unchecked")
                                    List<ShipRepository.Row> rows =
                                            (List<ShipRepository.Row>) result[0];
                                    fleet.setRows(uuid, rows);
                                    if (Boolean.TRUE.equals(result[1])
                                            && player.isOnline()
                                            && starter.isPresent()) {
                                        rows.stream()
                                                .filter(r -> r.type().equals(starter.get().id()))
                                                .findFirst()
                                                .ifPresent(
                                                        r ->
                                                                giveDeed(
                                                                        player,
                                                                        starter.get(),
                                                                        r.shipId()));
                                        kit.send(
                                                player,
                                                "world.ship.starter",
                                                Placeholder.unparsed("name", starter.get().name()));
                                    }
                                },
                                kit.tasks().main());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        for (Afloat a : fleet.afloatList()) {
            if (a.row.owner().equals(uuid)) {
                var _ = fleet.recall(a, ShipState.STORED);
            } else if (a.boat.getPassengers().contains(event.getPlayer())) {
                a.boat.removePassenger(event.getPlayer());
            }
        }
        fleet.forget(uuid);
        summoner.forgetCooldown(uuid);
        repairKit.forget(uuid);
        summoner.forgetDonor(uuid);
    }

    /**
     * Before a server move: recalls the launched boat (ships and rides are recalled before moving,
     * docs 20 8.4 'server moves').
     */
    public HandoffBlocker handoffBlocker() {
        return (player, destination) -> {
            UUID uuid = player.getUniqueId();
            List<CompletableFuture<Void>> saves = new ArrayList<>();
            for (Afloat a : fleet.afloatList()) {
                if (a.row.owner().equals(uuid)) {
                    saves.add(fleet.recall(a, ShipState.STORED));
                }
            }
            return saves.isEmpty()
                    ? HandoffBlocker.ALLOW
                    : new HandoffBlocker.Delay(
                            CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new)));
        };
    }

    // ------------------------------------------------------------------ summon and recall

    /**
     * Launches a ship. If shipId is 0 or less, the last ship that is not wrecked (the most recently
     * bought).
     *
     * @return true if launched
     */
    public boolean summon(Player player, long shipId) {
        return summoner.summon(player, shipId);
    }

    @Override
    public boolean summon(Player player, long shipId, boolean ignoreCooldown) {
        if (ignoreCooldown) {
            summoner.forgetCooldown(player.getUniqueId());
        }
        return summoner.summon(player, shipId);
    }

    @Override
    public boolean recall(UUID owner) {
        return recallOwned(owner);
    }

    @Override
    public boolean setDurability(UUID owner, long shipId, int durability) {
        return shipyard.adminSetDurability(owner, shipId, durability);
    }

    /** Recalls the owner's launched boat (/선박 회수). */
    public boolean recallOwned(UUID owner) {
        boolean any = false;
        for (Afloat a : fleet.afloatList()) {
            if (a.row.owner().equals(owner)) {
                var _ = fleet.recall(a, ShipState.STORED);
                any = true;
            }
        }
        return any;
    }

    // ------------------------------------------------------------------ events

    /** Blocks entry into storm waters (unless it is an ocean explorer). */
    @EventHandler(ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent event) {
        navigator.onVehicleMove(event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBoard(VehicleEnterEvent event) {
        navigator.onBoard(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnter(VehicleEnterEvent event) {
        navigator.onEnter(event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(VehicleDamageEvent event) {
        if (fleet.isShipBoat(event.getVehicle().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDestroy(VehicleDestroyEvent event) {
        if (fleet.isShipBoat(event.getVehicle().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (Entity e : event.getChunk().getEntities()) {
            Long id = fleet.shipIdOf(e.getUniqueId());
            Afloat a = id == null ? null : fleet.afloat(id);
            if (a != null) {
                var _ = fleet.recall(a, ShipState.STORED);
            }
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) {
            if (isTagged(e) && !fleet.isShipBoat(e.getUniqueId())) {
                e.remove();
            }
        }
    }

    private boolean isTagged(Entity e) {
        return e.getPersistentDataContainer().has(kit.key("ship"), PersistentDataType.LONG);
    }

    /** Opening a chest boat opens the cargo GUI. */
    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        cargo.onOpen(event);
    }

    /** /선박 화물: your own boat that you ride or that is within 8 blocks. */
    public void openCargo(Player player) {
        cargo.open(player);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        cargo.onClose(event);
    }

    /** Using a deed or repair kit. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR
                        && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        Optional<ItemId> id = kit.itemId(hand);
        if (id.isEmpty()) {
            return;
        }
        if (id.get().id().equals(ShipRepairKit.REPAIR_KIT)) {
            event.setCancelled(true);
            repairKit.use(player);
            return;
        }
        Optional<ShipDef> def =
                kit.api().content().snapshot().all(ShipDef.TYPE).values().stream()
                        .filter(d -> d.item().equals(id.get()))
                        .findFirst();
        if (def.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        Long tagged =
                hand.getPersistentDataContainer().get(kit.key("ship_id"), PersistentDataType.LONG);
        long shipId =
                tagged != null
                        ? tagged
                        : rows(player.getUniqueId()).stream()
                                .filter(r -> r.type().equals(def.get().id()))
                                .mapToLong(ShipRepository.Row::shipId)
                                .findFirst()
                                .orElse(-1);
        if (shipId < 0
                || rows(player.getUniqueId()).stream().noneMatch(r -> r.shipId() == shipId)) {
            kit.send(player, "world.ship.not_owner");
            return;
        }
        summon(player, shipId);
    }

    // ------------------------------------------------------------------ shipyard (purchase,
    // upgrade, repair)

    public void openYard(Player player) {
        yard.open(player);
    }

    void giveDeed(Player player, ShipDef def, long shipId) {
        shipyard.giveDeed(player, def, shipId);
    }

    /**
     * Purchase conditions (level, job tier, flags; D3 ocean explorer = Lv50 after the 2nd job
     * advancement). Returns a message key if unmet.
     */
    @Nullable String requirement(Player player, ShipDef def) {
        return shipyard.requirement(player, def);
    }

    void buy(Player player, ShipDef def) {
        shipyard.buy(player, def);
    }

    void upgrade(Player player, ShipRepository.Row row, UpgradePath path) {
        shipyard.upgrade(player, row, path);
    }

    long repairQuote(Player player, ShipRepository.Row row) {
        return shipyard.repairQuote(player, row);
    }

    void repair(Player player, ShipRepository.Row row) {
        shipyard.repair(player, row);
    }

    /** Admin command: give a ship (same row as a shipyard purchase, no payment). */
    public CompletableFuture<Boolean> adminGive(Player player, ShipDef def) {
        return shipyard.adminGive(player, def);
    }

    /** Admin command: fully repair all ships. */
    public void adminRepair(Player player) {
        shipyard.adminRepair(player);
    }

    public int afloatCount() {
        return fleet.afloatCount();
    }

    Component name(ShipDef def) {
        return Component.text(def.name());
    }
}
