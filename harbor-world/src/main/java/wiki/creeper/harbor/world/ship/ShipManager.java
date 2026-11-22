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
 *
 * <p>상태는 {@link ShipFleet}, 소환은 {@link ShipSummoner}, 항해·폭풍·쇄빙은 {@link ShipNavigator}, 화물은 {@link
 * ShipCargo}, 수리 키트는 {@link ShipRepairKit}, 조선소 거래는 {@link ShipyardService}가 맡고, 이 클래스는
 * ShipService·접속·이벤트 연결을 맡는다.
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

    /** 서버 종료: 모두 회수하고 저장을 기다린다 (최대 3초). */
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

    // ------------------------------------------------------------------ 조회

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

    /** 지금 타고 있는 선박 종류 (지역 탈것 조건). */
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

    // ------------------------------------------------------------------ 적재·접속

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

    /** 서버 이동 전: 띄운 배를 회수한다 (선박·탈것은 이동 전에 회수, docs 20 8.4 '서버 이동'). */
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

    // ------------------------------------------------------------------ 소환·회수

    /**
     * 띄운다. shipId가 0 이하면 파손되지 않은 마지막 선박 (가장 늦게 산 배).
     *
     * @return 띄웠으면 true
     */
    public boolean summon(Player player, long shipId) {
        return summoner.summon(player, shipId);
    }

    /** 주인의 띄운 배를 회수한다 (/선박 회수). */
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

    // ------------------------------------------------------------------ 이벤트

    /** 폭풍 해역 진입 차단 (원양 탐사선이 아니면). */
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

    /** 상자 보트를 열면 화물 GUI. */
    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        cargo.onOpen(event);
    }

    /** /선박 화물: 타고 있거나 8블록 안에 있는 자기 배. */
    public void openCargo(Player player) {
        cargo.open(player);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        cargo.onClose(event);
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

    // ------------------------------------------------------------------ 조선소 (구매·업그레이드·수리)

    public void openYard(Player player) {
        yard.open(player);
    }

    void giveDeed(Player player, ShipDef def, long shipId) {
        shipyard.giveDeed(player, def, shipId);
    }

    /** 구매 조건 (레벨·직업 단계·플래그, D3 원양 탐사선 = 2차 전직 후 Lv50). 못 채우면 메시지 키. */
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

    /** 관리 명령: 선박 지급 (조선소 구매와 같은 행, 결제 없음). */
    public CompletableFuture<Boolean> adminGive(Player player, ShipDef def) {
        return shipyard.adminGive(player, def);
    }

    /** 관리 명령: 모든 선박 완전 수리. */
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
