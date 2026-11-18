package wiki.creeper.harbor.world.ship;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.ChestBoat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.donor.DonorTier;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RigId;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.core.api.HandoffBlocker;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.RigInstance;
import wiki.creeper.harbor.core.api.RigOptions;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.SeaEventService;
import wiki.creeper.harbor.world.api.ShipFlag;
import wiki.creeper.harbor.world.api.ShipInfo;
import wiki.creeper.harbor.world.api.ShipService;
import wiki.creeper.harbor.world.api.ShipSkinProvider;
import wiki.creeper.harbor.world.api.ShipState;
import wiki.creeper.harbor.world.api.UpgradePath;
import wiki.creeper.harbor.world.api.event.ShipSummonEvent;
import wiki.creeper.harbor.world.api.event.ShipWreckedEvent;
import wiki.creeper.harbor.world.bridge.Payments;
import wiki.creeper.harbor.world.content.SeaEventDef;
import wiki.creeper.harbor.world.content.ShipDef;
import wiki.creeper.harbor.world.content.WorldBalance;
import wiki.creeper.harbor.world.region.RegionManager;
import wiki.creeper.harbor.world.region.WorldIndex;

/**
 * 선박 (docs 20 8.4 '선박'). 바닐라 보트 + 장식 리그(Core RigInstance). 엔티티는 저장하지 않고(setPersistent false) 접속
 * 종료·서버 종료·탑승자 없는 청크 언로드·서버 이동 때 회수해 내구도와 적재물을 저장한다. 시작할 때와 청크 적재 때 PDC 태그가 남은 보트를 지운다.
 *
 * <ul>
 *   <li>속도: 조종자가 앞으로 누르는 동안 틱마다 앞으로 가속 × (배율 − 1)을 더한다 (주기적 속도 보정, Phase 1 검증 항목). 배율 = 선박 속도 × 속도
 *       업그레이드 × boat_speed 스탯 × 폭풍
 *   <li>장식: 일반 보트는 장식 루트가 두 번째 승객 자리에 타고, 플레이어가 타면 내렸다가 다시 태워 순서를 맞춘다. 상자 보트(자리 1개)는 틱마다 따라간다
 *   <li>폭풍: 폭풍 해역에서 5초마다 내구도 −damage × boat_damage, 속도 ×0.7 (폭풍 항해 1랭크면 없음). 원양 탐사선(storm)이 아니면 폭풍
 *       해역에 들어갈 수 없다. 내구도 0이면 승객을 내리고 WRECKED
 *   <li>쇄빙: 쇄빙선이 협만 얼음 구역에서 앞쪽 얼음을 물로 바꾸고 60초 뒤 되돌린다 (구역 밖은 건드리지 않음)
 *   <li>적재: 화물 칸은 상자 GUI로 열고(상자 보트를 열거나 /선박 화물), 닫을 때 지연 쓰기로 저장한다
 * </ul>
 */
public final class ShipManager implements ShipService, Listener {

    private static final Set<Material> ICE =
            Set.of(Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE, Material.FROSTED_ICE);

    /** 띄운 배. */
    private static final class Afloat {
        ShipRepository.Row row;
        final Boat boat;
        @Nullable RigInstance rig;
        final boolean follow;
        double damage;
        int ticks;

        Afloat(ShipRepository.Row row, Boat boat, @Nullable RigInstance rig, boolean follow) {
            this.row = row;
            this.boat = boat;
            this.rig = rig;
            this.follow = follow;
        }
    }

    /** 화물 GUI. */
    private static final class Cargo implements InventoryHolder {
        final long shipId;
        final UUID owner;
        @Nullable Inventory inventory;

        Cargo(long shipId, UUID owner) {
            this.shipId = shipId;
            this.owner = owner;
        }

        @Override
        public Inventory getInventory() {
            Inventory inv = inventory;
            if (inv == null) {
                throw new IllegalStateException("화물 GUI가 아직 없습니다");
            }
            return inv;
        }
    }

    private record Melt(Location at, Material type, long restoreAt) {}

    private final WorldKit kit;
    private final RegionManager regions;
    private final Payments payments;
    private @Nullable SeaEventService seaEvents;
    private final Map<UUID, List<ShipRepository.Row>> owned = new HashMap<>();
    private final Map<Long, Afloat> afloat = new HashMap<>();
    private final Map<UUID, Long> byBoat = new HashMap<>();
    private final Map<UUID, Instant> cooldown = new HashMap<>();
    private final Map<UUID, Integer> repairing = new HashMap<>();
    private final Deque<Melt> melted = new ArrayDeque<>();
    private final ShipyardMenu yard;

    public ShipManager(WorldKit kit, RegionManager regions, Payments payments) {
        this.kit = kit;
        this.regions = regions;
        this.payments = payments;
        this.yard = new ShipyardMenu(kit, this);
    }

    public void seaEvents(SeaEventService service) {
        this.seaEvents = service;
    }

    public void start() {
        var _ = kit.tasks().timer(1, 1, this::tick);
        var _ = kit.tasks().timer(20, 20, this::slowTick);
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

    /** 서버 종료: 모두 회수하고 저장을 기다린다 (최대 3초). */
    public void shutdown() {
        List<CompletableFuture<Void>> saves = new ArrayList<>();
        for (Afloat a : new ArrayList<>(afloat.values())) {
            saves.add(recall(a, ShipState.STORED));
        }
        while (!melted.isEmpty()) {
            Melt m = melted.poll();
            m.at().getBlock().setType(m.type());
        }
        try {
            CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new))
                    .get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            kit.log().warn("선박 저장을 기다리지 못했습니다: {}", String.valueOf(e));
        }
    }

    // ------------------------------------------------------------------ 조회

    public Optional<ShipDef> def(HarborId type) {
        return kit.api().content().snapshot().get(ShipDef.TYPE, type);
    }

    ShipInfo info(ShipRepository.Row row) {
        Optional<ShipDef> def = def(row.type());
        int max = def.map(d -> ShipMath.maxDurability(d, row.upgrades())).orElse(row.durability());
        ShipState state = afloat.containsKey(row.shipId()) ? ShipState.AFLOAT : row.state();
        return new ShipInfo(
                row.shipId(),
                row.owner(),
                row.type(),
                def.map(ShipDef::name).orElse(row.type().name()),
                row.upgrades(),
                row.durability(),
                max,
                state,
                def.map(ShipDef::flags).orElse(Set.of()));
    }

    List<ShipRepository.Row> rows(UUID owner) {
        return owned.getOrDefault(owner, List.of());
    }

    @Override
    public List<ShipInfo> ships(UUID owner) {
        return rows(owner).stream().map(this::info).toList();
    }

    @Override
    public Optional<ShipInfo> afloat(UUID owner) {
        return afloat.values().stream()
                .filter(a -> a.row.owner().equals(owner))
                .findFirst()
                .map(a -> info(a.row));
    }

    @Override
    public Optional<ShipInfo> aboard(Player player) {
        return aboardAfloat(player).map(a -> info(a.row));
    }

    private Optional<Afloat> aboardAfloat(Player player) {
        Entity vehicle = player.getVehicle();
        if (vehicle == null) {
            return Optional.empty();
        }
        Long id = byBoat.get(vehicle.getUniqueId());
        return id == null ? Optional.empty() : Optional.ofNullable(afloat.get(id));
    }

    @Override
    public Optional<ShipInfo> byEntity(Entity boat) {
        Long id = byBoat.get(boat.getUniqueId());
        return id == null
                ? Optional.empty()
                : Optional.ofNullable(afloat.get(id)).map(a -> info(a.row));
    }

    /** 지금 타고 있는 선박 종류 (지역 탈것 조건). */
    public @Nullable HarborId shipType(Player player) {
        return aboardAfloat(player).map(a -> a.row.type()).orElse(null);
    }

    @Override
    public double speedMultiplier(Player driver) {
        Optional<Afloat> a = aboardAfloat(driver);
        if (a.isEmpty()) {
            return 1;
        }
        return speed(driver, a.get());
    }

    private double speed(Player driver, Afloat a) {
        Optional<ShipDef> def = def(a.row.type());
        double base = def.map(d -> ShipMath.baseSpeed(d, a.row.upgrades())).orElse(1.0);
        double storm = 1;
        Optional<SeaEventDef.Storm> s = storm(WorldKit.loc(a.boat));
        if (s.isPresent() && kit.stat(driver, "feature_storm_sailing", 0) < 1) {
            storm = s.get().speedMult();
        }
        return ShipMath.speed(base, kit.stat(driver, "boat_speed", 1.0), storm);
    }

    private Optional<SeaEventDef.Storm> storm(Location at) {
        SeaEventService events = seaEvents;
        if (events == null) {
            return Optional.empty();
        }
        return events.stormAt(at)
                .flatMap(e -> kit.api().content().snapshot().get(SeaEventDef.TYPE, e.eventId()))
                .map(SeaEventDef::storm);
    }

    // ------------------------------------------------------------------ 적재·접속

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        load(event.getPlayer());
    }

    private void load(Player player) {
        UUID uuid = player.getUniqueId();
        var _ = donorTier(player);
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
                                    owned.put(uuid, new ArrayList<>(rows));
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
        for (Afloat a : new ArrayList<>(afloat.values())) {
            if (a.row.owner().equals(uuid)) {
                var _ = recall(a, ShipState.STORED);
            } else if (a.boat.getPassengers().contains(event.getPlayer())) {
                a.boat.removePassenger(event.getPlayer());
            }
        }
        owned.remove(uuid);
        cooldown.remove(uuid);
        repairing.remove(uuid);
        donors.remove(uuid);
    }

    /** 서버 이동 전: 띄운 배를 회수한다 (선박·탈것은 이동 전에 회수, docs 20 8.4 '서버 이동'). */
    public HandoffBlocker handoffBlocker() {
        return (player, destination) -> {
            UUID uuid = player.getUniqueId();
            List<CompletableFuture<Void>> saves = new ArrayList<>();
            for (Afloat a : new ArrayList<>(afloat.values())) {
                if (a.row.owner().equals(uuid)) {
                    saves.add(recall(a, ShipState.STORED));
                }
            }
            return saves.isEmpty()
                    ? HandoffBlocker.ALLOW
                    : new HandoffBlocker.Delay(
                            CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new)));
        };
    }

    // ------------------------------------------------------------------ 소환·회수

    /**
     * 띄운다. shipId가 0 이하면 파손되지 않은 마지막 선박 (가장 늦게 산 배).
     *
     * @return 띄웠으면 true
     */
    public boolean summon(Player player, long shipId) {
        UUID uuid = player.getUniqueId();
        Instant now = Instant.now();
        Instant ready = cooldown.get(uuid);
        if (ready != null && now.isBefore(ready)) {
            kit.send(
                    player,
                    "world.ship.cooldown",
                    Placeholder.unparsed(
                            "seconds",
                            String.valueOf(ready.getEpochSecond() - now.getEpochSecond() + 1)));
            return false;
        }
        List<ShipRepository.Row> mine = rows(uuid);
        Optional<ShipRepository.Row> pick =
                shipId > 0
                        ? mine.stream().filter(r -> r.shipId() == shipId).findFirst()
                        : mine.stream()
                                .filter(r -> r.state() != ShipState.WRECKED)
                                .reduce((a, b) -> b);
        if (pick.isEmpty()) {
            kit.send(player, "world.ship.none");
            return false;
        }
        ShipRepository.Row row = pick.get();
        if (row.state() == ShipState.WRECKED) {
            kit.send(player, "world.ship.wrecked_cannot");
            return false;
        }
        Optional<ShipDef> def = def(row.type());
        if (def.isEmpty()) {
            kit.send(player, "world.ship.none");
            return false;
        }
        Location water = findWater(WorldKit.loc(player), kit.balance().summonWaterRadius());
        if (water == null) {
            kit.send(player, "world.ship.no_water");
            return false;
        }
        ShipSummonEvent summonEvent = new ShipSummonEvent(player, info(row));
        Bukkit.getPluginManager().callEvent(summonEvent);
        if (summonEvent.isCancelled()) {
            return false;
        }
        for (Afloat a : new ArrayList<>(afloat.values())) {
            if (a.row.owner().equals(uuid)) {
                var _ = recall(a, ShipState.STORED);
            }
        }
        cooldown.put(uuid, now.plusSeconds(kit.balance().summonCooldownSeconds()));
        water.setYaw(WorldKit.loc(player).getYaw());
        EntityType type;
        try {
            type = EntityType.valueOf(def.get().entityName());
        } catch (IllegalArgumentException e) {
            type = def.get().chest() ? EntityType.OAK_CHEST_BOAT : EntityType.OAK_BOAT;
        }
        World world = water.getWorld();
        if (world == null || !(world.spawnEntity(water, type) instanceof Boat boat)) {
            kit.send(player, "world.ship.no_water");
            return false;
        }
        boat.setPersistent(false);
        boat.getPersistentDataContainer()
                .set(kit.key("ship"), PersistentDataType.LONG, row.shipId());
        RigInstance rig = spawnDecor(player, def.get(), boat);
        boolean follow = boat instanceof ChestBoat;
        if (rig != null && !follow) {
            rig.mount(boat);
        }
        Afloat a =
                new Afloat(
                        row.withDurability(row.durability(), ShipState.AFLOAT), boat, rig, follow);
        afloat.put(row.shipId(), a);
        byBoat.put(boat.getUniqueId(), row.shipId());
        replace(a.row);
        long id = row.shipId();
        int durability = row.durability();
        var _ =
                kit.api()
                        .database()
                        .asyncVoid(
                                c ->
                                        ShipRepository.updateState(
                                                c, id, durability, ShipState.AFLOAT));
        kit.send(player, "world.ship.summoned", Placeholder.unparsed("name", def.get().name()));
        return true;
    }

    private @Nullable RigInstance spawnDecor(Player owner, ShipDef def, Boat boat) {
        ShipSkinProvider skins = Bukkit.getServicesManager().load(ShipSkinProvider.class);
        if (skins != null && skins.hasBoatSkin(owner.getUniqueId())) {
            return null; // 선박 스킨은 Shop이 탑승 때 태운다
        }
        RigId rigId = def.decor();
        RigId donor = def.decorDonor();
        if (donor != null && DonorTier.FLEET.equals(donorTier(owner))) {
            rigId = donor;
        }
        if (rigId == null || !kit.api().rigs().ids().contains(rigId)) {
            return null;
        }
        try {
            return kit.api()
                    .rigs()
                    .spawn(rigId, WorldKit.loc(boat), RigOptions.publicRig().withMoveTicks(2));
        } catch (RuntimeException e) {
            kit.log().warn("선박 장식 리그를 띄우지 못했습니다 {}: {}", rigId, String.valueOf(e));
            return null;
        }
    }

    /** 후원 등급 캐시는 Shop이 Redis에 둔다. 메인 스레드에서 Redis를 부르지 않으려고 접속 때 읽어 둔 값을 쓴다. */
    private final Map<UUID, DonorTier> donors = new HashMap<>();

    private @Nullable DonorTier donorTier(Player player) {
        UUID uuid = player.getUniqueId();
        DonorTier cached = donors.get(uuid);
        if (cached == null) {
            var _ =
                    kit.tasks()
                            .supplyAsync(() -> DonorTier.lookup(kit.api().redis(), uuid))
                            .thenAcceptAsync(t -> donors.put(uuid, t), kit.tasks().main());
        }
        return cached;
    }

    private static @Nullable Location findWater(Location center, double radius) {
        World world = center.getWorld();
        if (world == null) {
            return null;
        }
        int r = (int) Math.ceil(radius);
        Location best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -2; dy <= 1; dy++) {
                    Block b =
                            world.getBlockAt(
                                    center.getBlockX() + dx,
                                    center.getBlockY() + dy,
                                    center.getBlockZ() + dz);
                    if (b.getType() != Material.WATER
                            || !b.getRelative(0, 1, 0).getType().isAir()) {
                        continue;
                    }
                    double d = dx * dx + dz * dz + dy * dy;
                    if (d < bestD && d <= radius * radius + 4) {
                        bestD = d;
                        best = b.getLocation().add(0.5, 1.0, 0.5);
                    }
                }
            }
        }
        return best;
    }

    /** 회수: 승객을 내리고 엔티티를 지운 뒤 내구도·상태를 저장한다. */
    private CompletableFuture<Void> recall(Afloat a, ShipState state) {
        afloat.remove(a.row.shipId());
        byBoat.remove(a.boat.getUniqueId());
        RigInstance rig = a.rig;
        if (rig != null) {
            rig.remove();
        }
        if (a.boat.isValid()) {
            a.boat.eject();
            a.boat.remove();
        }
        ShipRepository.Row row = a.row.withDurability(a.row.durability(), state);
        replace(row);
        long id = row.shipId();
        int durability = row.durability();
        return kit.api()
                .database()
                .asyncVoid(c -> ShipRepository.updateState(c, id, durability, state));
    }

    /** 주인의 띄운 배를 회수한다 (/선박 회수). */
    public boolean recallOwned(UUID owner) {
        boolean any = false;
        for (Afloat a : new ArrayList<>(afloat.values())) {
            if (a.row.owner().equals(owner)) {
                var _ = recall(a, ShipState.STORED);
                any = true;
            }
        }
        return any;
    }

    private void replace(ShipRepository.Row row) {
        List<ShipRepository.Row> list = owned.get(row.owner());
        if (list == null) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).shipId() == row.shipId()) {
                list.set(i, row);
                return;
            }
        }
        list.add(row);
    }

    // ------------------------------------------------------------------ 틱

    private void tick() {
        double accel = kit.balance().boatAcceleration();
        for (Afloat a : new ArrayList<>(afloat.values())) {
            if (!a.boat.isValid()) {
                var _ =
                        recall(
                                a,
                                a.row.state() == ShipState.WRECKED
                                        ? ShipState.WRECKED
                                        : ShipState.STORED);
                continue;
            }
            a.ticks++;
            RigInstance rig = a.rig;
            if (rig != null && a.follow) {
                Location at = WorldKit.loc(a.boat);
                rig.moveTo(at, 1);
                rig.setHeading(at.getYaw(), 0);
            }
            List<Entity> passengers = a.boat.getPassengers();
            if (passengers.isEmpty() || !(passengers.getFirst() instanceof Player driver)) {
                continue;
            }
            if (driver.getCurrentInput().isForward()) {
                double mult = speed(driver, a);
                double impulse = ShipMath.impulse(accel, mult);
                Vector v = a.boat.getVelocity();
                double h = Math.hypot(v.getX(), v.getZ());
                if (Math.abs(impulse) > 1e-4 && (impulse > 0 || h > Math.abs(impulse))) {
                    Location at = WorldKit.loc(a.boat);
                    Vector forward = at.getDirection().setY(0);
                    if (forward.lengthSquared() > 1e-6) {
                        a.boat.setVelocity(v.add(forward.normalize().multiply(impulse)));
                    }
                }
            }
            if (a.ticks % 2 == 0) {
                breakIce(a);
            }
        }
        long now = System.currentTimeMillis();
        while (!melted.isEmpty() && melted.peek().restoreAt() <= now) {
            Melt m = melted.poll();
            Block block = m.at().getBlock();
            if (block.getType() == Material.WATER && !hasBoatAt(m.at())) {
                block.setType(m.type());
            } else {
                melted.add(new Melt(m.at(), m.type(), now + 5_000));
            }
        }
    }

    private boolean hasBoatAt(Location at) {
        World world = at.getWorld();
        return world != null
                && !world.getNearbyEntitiesByType(Boat.class, at.clone().add(0.5, 0.5, 0.5), 1.5)
                        .isEmpty();
    }

    private void breakIce(Afloat a) {
        Optional<ShipDef> def = def(a.row.type());
        if (def.isEmpty() || !def.get().has(ShipFlag.ICE_BREAK)) {
            return;
        }
        Location at = WorldKit.loc(a.boat);
        Optional<WorldIndex.Entry> region = regions.entryAt(at);
        if (region.isEmpty()) {
            return;
        }
        String iceArea = region.get().ext().iceArea();
        if (iceArea == null) {
            return;
        }
        Vector forward = at.getDirection().setY(0);
        if (forward.lengthSquared() < 1e-6) {
            return;
        }
        forward.normalize();
        Vector side = new Vector(-forward.getZ(), 0, forward.getX());
        long restore = System.currentTimeMillis() + kit.balance().iceRestoreSeconds() * 1000L;
        World world = a.boat.getWorld();
        for (double ahead = 1.0; ahead <= 2.0; ahead += 1.0) {
            for (int s = -1; s <= 1; s++) {
                Location probe =
                        at.clone()
                                .add(forward.clone().multiply(ahead))
                                .add(side.clone().multiply(s));
                if (!region.get().inArea(iceArea, probe.getX(), probe.getY(), probe.getZ())) {
                    continue;
                }
                for (int dy = -1; dy <= 0; dy++) {
                    Block block =
                            world.getBlockAt(
                                    probe.getBlockX(), probe.getBlockY() + dy, probe.getBlockZ());
                    if (ICE.contains(block.getType())) {
                        Material type = block.getType();
                        block.setType(Material.WATER);
                        melted.add(new Melt(block.getLocation(), type, restore));
                    }
                }
            }
        }
    }

    /** 1초마다: 폭풍 내구도 피해. */
    private void slowTick() {
        for (Afloat a : new ArrayList<>(afloat.values())) {
            if (!a.boat.isValid()) {
                continue;
            }
            Optional<SeaEventDef.Storm> storm = storm(WorldKit.loc(a.boat));
            if (storm.isEmpty() || a.ticks / 20 % Math.max(1, storm.get().intervalSeconds()) != 0) {
                continue;
            }
            Player owner = Bukkit.getPlayer(a.row.owner());
            double mult = owner == null ? 1.0 : kit.stat(owner, "boat_damage", 1.0);
            a.damage += ShipMath.stormDamage(storm.get().damage(), mult);
            int whole = (int) Math.floor(a.damage);
            if (whole <= 0) {
                continue;
            }
            a.damage -= whole;
            int next = Math.max(0, a.row.durability() - whole);
            a.row = a.row.withDurability(next, ShipState.AFLOAT);
            replace(a.row);
            if (next <= 0) {
                wreck(a);
            } else if (owner != null && a.boat.getPassengers().contains(owner)) {
                kit.actionBar(
                        owner,
                        "world.ship.storm_damage",
                        Placeholder.unparsed("durability", String.valueOf(next)),
                        Placeholder.unparsed("max", String.valueOf(info(a.row).maxDurability())));
            }
        }
    }

    private void wreck(Afloat a) {
        for (Entity p : a.boat.getPassengers()) {
            if (p instanceof Player player) {
                kit.send(player, "world.ship.wrecked");
            }
        }
        var _ = recall(a, ShipState.WRECKED);
        Player owner = Bukkit.getPlayer(a.row.owner());
        if (owner != null) {
            Bukkit.getPluginManager()
                    .callEvent(
                            new ShipWreckedEvent(
                                    owner, info(a.row.withDurability(0, ShipState.WRECKED))));
        }
    }

    // ------------------------------------------------------------------ 이벤트

    /** 폭풍 해역 진입 차단 (원양 탐사선이 아니면). */
    @EventHandler(ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent event) {
        Long id = byBoat.get(event.getVehicle().getUniqueId());
        if (id == null) {
            return;
        }
        Afloat a = afloat.get(id);
        if (a == null || def(a.row.type()).map(d -> d.has(ShipFlag.STORM)).orElse(false)) {
            return;
        }
        if (storm(event.getTo()).isPresent() && storm(event.getFrom()).isEmpty()) {
            Location back = event.getFrom().clone();
            var _ = a.boat.teleport(back);
            var _ = kit.tasks().later(1, () -> a.boat.setVelocity(new Vector()));
            for (Entity p : a.boat.getPassengers()) {
                if (p instanceof Player player) {
                    kit.actionBar(player, "world.ship.storm_blocked");
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnter(VehicleEnterEvent event) {
        Long id = byBoat.get(event.getVehicle().getUniqueId());
        if (id == null || !(event.getEntered() instanceof Player)) {
            return;
        }
        Afloat a = afloat.get(id);
        RigInstance rig = a == null ? null : a.rig;
        if (a == null || rig == null || a.follow) {
            return;
        }
        // 첫 승객이 조종자라서 장식을 내렸다가 다시 태워 두 번째 자리로 보낸다
        rig.dismount();
        var _ =
                kit.tasks()
                        .later(
                                1,
                                () -> {
                                    if (rig.isValid() && a.boat.isValid()) {
                                        rig.mount(a.boat);
                                    }
                                });
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(VehicleDamageEvent event) {
        if (byBoat.containsKey(event.getVehicle().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDestroy(VehicleDestroyEvent event) {
        if (byBoat.containsKey(event.getVehicle().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (Entity e : event.getChunk().getEntities()) {
            Long id = byBoat.get(e.getUniqueId());
            Afloat a = id == null ? null : afloat.get(id);
            if (a != null) {
                var _ = recall(a, ShipState.STORED);
            }
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) {
            if (isTagged(e) && !byBoat.containsKey(e.getUniqueId())) {
                e.remove();
            }
        }
    }

    private boolean isTagged(Entity e) {
        return e.getPersistentDataContainer().has(kit.key("ship"), PersistentDataType.LONG);
    }

    /** 상자 보트를 열면 화물 GUI. */
    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getInventory().getHolder(false) instanceof ChestBoat boat
                && byBoat.containsKey(boat.getUniqueId())
                && event.getPlayer() instanceof Player player) {
            event.setCancelled(true);
            Long id = byBoat.get(boat.getUniqueId());
            Afloat a = id == null ? null : afloat.get(id);
            if (a != null) {
                var _ = kit.tasks().sync(() -> openCargo(player, a));
            }
        }
    }

    /** /선박 화물: 타고 있거나 8블록 안에 있는 자기 배. */
    public void openCargo(Player player) {
        Optional<Afloat> a = aboardAfloat(player);
        if (a.isEmpty()) {
            a =
                    afloat.values().stream()
                            .filter(x -> x.row.owner().equals(player.getUniqueId()))
                            .filter(
                                    x ->
                                            x.boat.getWorld().equals(player.getWorld())
                                                    && WorldKit.loc(x.boat)
                                                                    .distanceSquared(
                                                                            WorldKit.loc(player))
                                                            < 64)
                            .findFirst();
        }
        if (a.isEmpty()) {
            kit.send(player, "world.ship.cargo_far");
            return;
        }
        openCargo(player, a.get());
    }

    private void openCargo(Player player, Afloat a) {
        if (!a.row.owner().equals(player.getUniqueId())) {
            kit.send(player, "world.ship.not_owner");
            return;
        }
        Optional<ShipDef> def = def(a.row.type());
        int slots = def.map(d -> ShipMath.cargo(d, a.row.upgrades())).orElse(0);
        if (slots <= 0) {
            kit.send(player, "world.ship.no_cargo");
            return;
        }
        Cargo holder = new Cargo(a.row.shipId(), a.row.owner());
        Inventory inv =
                Bukkit.createInventory(
                        holder,
                        slots,
                        kit.api()
                                .messages()
                                .get(
                                        "world.ship.cargo_title",
                                        Placeholder.unparsed(
                                                "name", def.map(ShipDef::name).orElse(""))));
        holder.inventory = inv;
        byte[] raw = a.row.cargo();
        if (raw != null && raw.length > 0) {
            try {
                ItemStack[] items = ItemStack.deserializeItemsFromBytes(raw);
                for (int i = 0; i < Math.min(items.length, slots); i++) {
                    inv.setItem(i, items[i]);
                }
            } catch (RuntimeException e) {
                kit.log().warn("화물을 읽지 못했습니다 {}: {}", a.row.shipId(), String.valueOf(e));
            }
        }
        player.openInventory(inv);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Cargo cargo)) {
            return;
        }
        byte[] bytes = ItemStack.serializeItemsAsBytes(event.getInventory().getContents());
        Afloat a = afloat.get(cargo.shipId);
        if (a != null) {
            a.row = a.row.withCargo(bytes);
            replace(a.row);
        } else {
            rows(cargo.owner).stream()
                    .filter(r -> r.shipId() == cargo.shipId)
                    .findFirst()
                    .ifPresent(r -> replace(r.withCargo(bytes)));
        }
        long id = cargo.shipId;
        kit.api()
                .writeBehind()
                .enqueue(
                        "player:" + cargo.owner + ":world:cargo:" + id,
                        c -> ShipRepository.updateCargo(c, id, bytes));
    }

    /** 증서·수리 키트 사용. */
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
        if (id.get().id().equals(HarborId.parse("item/boat/repair_kit"))) {
            event.setCancelled(true);
            useRepairKit(player);
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

    private void useRepairKit(Player player) {
        Optional<Afloat> aboard = aboardAfloat(player);
        if (aboard.isEmpty()) {
            kit.send(player, "world.ship.kit_not_aboard");
            return;
        }
        UUID uuid = player.getUniqueId();
        if (repairing.containsKey(uuid)) {
            return;
        }
        Afloat a = aboard.get();
        int total = kit.balance().repairKitTicks();
        repairing.put(uuid, 0);
        kit.send(
                player,
                "world.ship.kit_start",
                Placeholder.unparsed("seconds", String.valueOf(total / 20)));
        repairStep(player, a, total);
    }

    private void repairStep(Player player, Afloat a, int total) {
        UUID uuid = player.getUniqueId();
        var _ =
                kit.tasks()
                        .later(
                                20,
                                () -> {
                                    Integer done = repairing.get(uuid);
                                    if (done == null) {
                                        return;
                                    }
                                    if (!player.isOnline()
                                            || aboardAfloat(player).filter(x -> x == a).isEmpty()) {
                                        repairing.remove(uuid);
                                        kit.send(player, "world.ship.kit_cancel");
                                        return;
                                    }
                                    int next = done + 20;
                                    if (next < total) {
                                        repairing.put(uuid, next);
                                        repairStep(player, a, total);
                                        return;
                                    }
                                    repairing.remove(uuid);
                                    ItemStack hand = player.getInventory().getItemInMainHand();
                                    if (kit.itemId(hand)
                                            .filter(
                                                    i ->
                                                            i.id().equals(
                                                                            HarborId.parse(
                                                                                    "item/boat/repair_kit")))
                                            .isEmpty()) {
                                        kit.send(player, "world.ship.kit_cancel");
                                        return;
                                    }
                                    hand.setAmount(hand.getAmount() - 1);
                                    int max = info(a.row).maxDurability();
                                    int value =
                                            Math.min(
                                                    max,
                                                    a.row.durability()
                                                            + (int)
                                                                    Math.ceil(
                                                                            max
                                                                                    * kit.balance()
                                                                                            .repairKitRatio()));
                                    a.row = a.row.withDurability(value, ShipState.AFLOAT);
                                    replace(a.row);
                                    long id = a.row.shipId();
                                    var _ =
                                            kit.api()
                                                    .database()
                                                    .asyncVoid(
                                                            c ->
                                                                    ShipRepository.updateState(
                                                                            c,
                                                                            id,
                                                                            value,
                                                                            ShipState.AFLOAT));
                                    kit.send(
                                            player,
                                            "world.ship.kit_done",
                                            Placeholder.unparsed(
                                                    "durability", String.valueOf(value)),
                                            Placeholder.unparsed("max", String.valueOf(max)));
                                });
    }

    // ------------------------------------------------------------------ 조선소 (구매·업그레이드·수리)

    public void openYard(Player player) {
        yard.open(player);
    }

    void giveDeed(Player player, ShipDef def, long shipId) {
        Optional<ItemDef> item = kit.api().content().snapshot().get(ItemDef.TYPE, def.item().id());
        if (item.isEmpty()) {
            return;
        }
        ItemStack deed = kit.api().items().create(item.get(), 1);
        deed.editPersistentDataContainer(
                pdc -> pdc.set(kit.key("ship_id"), PersistentDataType.LONG, shipId));
        kit.give(player, deed);
    }

    /** 구매 조건 (레벨·직업 단계·플래그, D3 원양 탐사선 = 2차 전직 후 Lv50). 못 채우면 메시지 키. */
    @Nullable String requirement(Player player, ShipDef def) {
        HarborPlayer hp = kit.api().players().get(player);
        ShipDef.Requires r = def.requires();
        if (hp.level() < r.level() || hp.jobTier().level() < r.jobTier()) {
            return "world.yard.req_level";
        }
        for (String flag : r.flags()) {
            if (!kit.api().flags().hasPlayer(player.getUniqueId(), flag)) {
                return "world.yard.req_story";
            }
        }
        return null;
    }

    void buy(Player player, ShipDef def) {
        UUID uuid = player.getUniqueId();
        if (rows(uuid).stream().anyMatch(r -> r.type().equals(def.id()))) {
            kit.send(player, "world.yard.owned");
            return;
        }
        String req = requirement(player, def);
        if (req != null) {
            kit.send(
                    player,
                    req,
                    Placeholder.unparsed("level", String.valueOf(def.requires().level())),
                    Placeholder.unparsed("tier", String.valueOf(def.requires().jobTier())));
            return;
        }
        int season = kit.season();
        long[] newId = {-1};
        var _ =
                payments.charge(
                                player,
                                def.price(),
                                LedgerReason.SHIP_PURCHASE,
                                "ship:s" + season + ":" + def.id().name(),
                                c ->
                                        newId[0] =
                                                ShipRepository.insert(
                                                        c,
                                                        uuid,
                                                        season,
                                                        def.id(),
                                                        def.durability()))
                        .thenAccept(
                                result -> {
                                    if (!result.isOk()) {
                                        kit.send(player, Payments.failKey(result));
                                        return;
                                    }
                                    owned.computeIfAbsent(uuid, u -> new ArrayList<>())
                                            .add(
                                                    new ShipRepository.Row(
                                                            newId[0],
                                                            uuid,
                                                            def.id(),
                                                            Map.of(),
                                                            def.durability(),
                                                            ShipState.STORED,
                                                            null));
                                    if (player.isOnline()) {
                                        giveDeed(player, def, newId[0]);
                                    }
                                    kit.send(
                                            player,
                                            "world.yard.bought",
                                            Placeholder.unparsed("name", def.name()),
                                            Placeholder.unparsed(
                                                    "price", String.valueOf(def.price())));
                                });
    }

    void upgrade(Player player, ShipRepository.Row row, UpgradePath path) {
        Optional<ShipDef> def = def(row.type());
        if (def.isEmpty() || def.get().price() <= 0) {
            kit.send(player, "world.yard.no_upgrade");
            return;
        }
        if (afloat.containsKey(row.shipId())) {
            kit.send(player, "world.yard.recall_first");
            return;
        }
        int level = row.upgrades().getOrDefault(path, 0);
        if (level >= def.get().maxUpgrade(path)) {
            kit.send(player, "world.yard.max_upgrade");
            return;
        }
        int nextLevel = level + 1;
        long cost = ShipMath.upgradeCost(def.get(), kit.balance().upgradeCost(nextLevel));
        Map<UpgradePath, Integer> after = new EnumMap<>(UpgradePath.class);
        after.putAll(row.upgrades());
        after.put(path, nextLevel);
        int oldMax = ShipMath.maxDurability(def.get(), row.upgrades());
        int newMax = ShipMath.maxDurability(def.get(), after);
        int durability =
                row.state() == ShipState.WRECKED
                        ? row.durability()
                        : Math.min(newMax, row.durability() + (newMax - oldMax));
        Map<UpgradePath, Integer> before = row.upgrades();
        long id = row.shipId();
        var _ =
                payments.charge(
                                player,
                                cost,
                                LedgerReason.SHIP_UPGRADE,
                                "shipup:" + id + ":" + path.key() + ":" + nextLevel,
                                c -> ShipRepository.upgrade(c, id, before, after, durability))
                        .thenAccept(
                                result -> {
                                    if (!result.isOk()) {
                                        kit.send(player, Payments.failKey(result));
                                        return;
                                    }
                                    replace(row.withUpgrades(after, durability));
                                    kit.send(
                                            player,
                                            "world.yard.upgraded",
                                            Placeholder.unparsed(
                                                    "path",
                                                    kit.api()
                                                                    .messages()
                                                                    .has(
                                                                            "world.yard.path."
                                                                                    + path.key())
                                                            ? plain("world.yard.path." + path.key())
                                                            : path.key()),
                                            Placeholder.unparsed(
                                                    "level", String.valueOf(nextLevel)),
                                            Placeholder.unparsed("price", String.valueOf(cost)));
                                });
    }

    long repairQuote(Player player, ShipRepository.Row row) {
        Optional<ShipDef> def = def(row.type());
        if (def.isEmpty()) {
            return 0;
        }
        return payments.repairQuote(
                player.getUniqueId(), def.get().price(), row.state() == ShipState.WRECKED);
    }

    void repair(Player player, ShipRepository.Row row) {
        Optional<ShipDef> def = def(row.type());
        if (def.isEmpty()) {
            return;
        }
        int max = ShipMath.maxDurability(def.get(), row.upgrades());
        if (row.durability() >= max && row.state() != ShipState.WRECKED) {
            kit.send(player, "world.yard.no_repair");
            return;
        }
        if (afloat.containsKey(row.shipId())) {
            kit.send(player, "world.yard.recall_first");
            return;
        }
        long cost = repairQuote(player, row);
        long id = row.shipId();
        var _ =
                payments.repair(
                                player,
                                cost,
                                "shiprepair:" + id + ":" + System.currentTimeMillis(),
                                c -> ShipRepository.updateState(c, id, max, ShipState.STORED))
                        .thenAccept(
                                result -> {
                                    if (!result.isOk()) {
                                        kit.send(player, Payments.failKey(result));
                                        return;
                                    }
                                    replace(row.withDurability(max, ShipState.STORED));
                                    kit.send(
                                            player,
                                            "world.yard.repaired",
                                            Placeholder.unparsed("price", String.valueOf(cost)));
                                });
    }

    private String plain(String key) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(kit.api().messages().get(key));
    }

    /** 관리 명령: 선박 지급 (조선소 구매와 같은 행, 결제 없음). */
    public CompletableFuture<Boolean> adminGive(Player player, ShipDef def) {
        UUID uuid = player.getUniqueId();
        int season = kit.season();
        return kit.api()
                .database()
                .async(
                        c ->
                                ShipRepository.insertStarter(
                                        c, uuid, season, def.id(), def.durability()))
                .thenCompose(
                        ok -> kit.api().database().async(c -> ShipRepository.load(c, uuid, season)))
                .thenApplyAsync(
                        rows -> {
                            owned.put(uuid, new ArrayList<>(rows));
                            rows.stream()
                                    .filter(r -> r.type().equals(def.id()))
                                    .findFirst()
                                    .ifPresent(r -> giveDeed(player, def, r.shipId()));
                            return true;
                        },
                        kit.tasks().main());
    }

    /** 관리 명령: 모든 선박 완전 수리. */
    public void adminRepair(Player player) {
        for (ShipRepository.Row row : new ArrayList<>(rows(player.getUniqueId()))) {
            Optional<ShipDef> def = def(row.type());
            if (def.isEmpty()) {
                continue;
            }
            int max = ShipMath.maxDurability(def.get(), row.upgrades());
            ShipState state =
                    afloat.containsKey(row.shipId()) ? ShipState.AFLOAT : ShipState.STORED;
            replace(row.withDurability(max, state));
            Afloat a = afloat.get(row.shipId());
            if (a != null) {
                a.row = a.row.withDurability(max, ShipState.AFLOAT);
            }
            long id = row.shipId();
            var _ =
                    kit.api()
                            .database()
                            .asyncVoid(c -> ShipRepository.updateState(c, id, max, state));
        }
    }

    public int afloatCount() {
        return afloat.size();
    }

    Component name(ShipDef def) {
        return Component.text(def.name());
    }
}
