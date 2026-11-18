package wiki.creeper.harbor.fishing.trap;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Predicate;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.content.type.RegionDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.ledger.LedgerEntry;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.JobLine;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.core.api.HandoffBlocker;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.MailService;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.api.TrapInfo;
import wiki.creeper.harbor.fishing.api.TrapService;
import wiki.creeper.harbor.fishing.api.event.TrapHarvestEvent;
import wiki.creeper.harbor.fishing.content.FishingBalance;
import wiki.creeper.harbor.fishing.session.FishingManager;
import wiki.creeper.harbor.jobs.api.JobsService;

/**
 * 통발 (docs 20 3.4 TrapService, '12. 월드 · 하우징 · 통발' 5장, 결정 D1). 메인 스레드에서 쓰고 DB 작업은 모두 즉시 트랜잭션(가상
 * 스레드)이다.
 *
 * <ul>
 *   <li>설치: 지역 trap_zone의 부표 자리(반경 buoy_radius)에서 통발 아이템을 블록에 대고 우클릭. 개수 = 2 + trap_count(최대 4). 행을
 *       즉시 넣고 배럴 블록 + ItemDisplay 외형을 놓는다 (배럴 PDC에 통발 ID)
 *   <li>열기: 배럴 열기를 취소하고 전용 GUI. 주인이 아니면 주인 이름만 액션바에. 열 때 한 번 오프라인 계산(최대 2주기, 계정 수익 상한)을 조건부 갱신으로
 *       저장한다
 *   <li>수확: contents에서 지우는 트랜잭션이 커밋된 뒤에만 아이템을 만든다. 수확물 custom_data에 trap 출처를 남긴다 (경매 감시)
 *   <li>자동 회수: 하루 한 번 본 서버 한 곳이 분산 잠금을 잡고, 7일 수확하지 않은 통발의 내용물을 Core MailService(구현 Economy)로 보낸 뒤
 *       행과 블록을 지운다. MailService가 없으면 보류한다
 * </ul>
 */
public final class TrapManager implements TrapService, Listener {

    private static final String SOURCE_TRAP = "trap";
    private static final Duration RETRIEVE_LOCK_TTL = Duration.ofHours(20);
    private static final long RETRIEVE_CHECK_TICKS = 20L * 60 * 30;
    private static final int RETRIEVE_BATCH = 200;

    private final HarborApi api;
    private final Tasks tasks;
    private final JobsService jobs;
    private final FishingManager fishing;
    private final Logger log;
    private final NamespacedKey trapKey;
    private final NamespacedKey sourceKey;
    private final TrapMenu menu;
    private final Map<UUID, CompletableFuture<?>> inFlight = new HashMap<>();

    public TrapManager(
            Plugin plugin,
            HarborApi api,
            Tasks tasks,
            JobsService jobs,
            FishingManager fishing,
            Logger log) {
        this.api = api;
        this.tasks = tasks;
        this.jobs = jobs;
        this.fishing = fishing;
        this.log = log;
        this.trapKey = new NamespacedKey(plugin, "trap_id");
        this.sourceKey = new NamespacedKey(plugin, "source");
        this.menu = new TrapMenu(api, this);
    }

    public void start() {
        var _ = tasks.timer(20L * 60, RETRIEVE_CHECK_TICKS, this::autoRetrieve);
    }

    /** Harbor-Season이 켜져 있으면 시즌 전환 훅을 등록한다 (선택 의존). */
    public void registerSeasonHook(Plugin plugin) {
        if (Bukkit.getPluginManager().getPlugin("Harbor-Season") != null) {
            TrapSeasonHook.register(plugin, this);
        }
    }

    Tasks tasks() {
        return tasks;
    }

    public void shutdown() {
        inFlight.clear();
    }

    FishingBalance balance() {
        return new FishingBalance(api.content().snapshot().single(Balance.TYPE));
    }

    int season() {
        return api.infra().season().value();
    }

    /** 통발 작업이 진행 중이면 서버 이동을 잠깐 미룬다 (최대 5초, Core가 판단). */
    public HandoffBlocker.Verdict handoffVerdict(Player player) {
        CompletableFuture<?> pending = inFlight.get(player.getUniqueId());
        return pending == null || pending.isDone()
                ? HandoffBlocker.ALLOW
                : new HandoffBlocker.Delay(pending);
    }

    private <T> CompletableFuture<T> track(UUID player, CompletableFuture<T> future) {
        inFlight.put(player, future);
        var _ =
                future.whenComplete(
                        (v, e) -> {
                            var _ = tasks.sync(() -> inFlight.remove(player, future));
                        });
        return future;
    }

    // ------------------------------------------------------------------ TrapService

    @Override
    public CompletableFuture<List<TrapInfo>> traps(UUID owner) {
        int season = season();
        return api.database()
                .async(c -> TrapRepository.byOwner(c, owner, season, false))
                .thenApplyAsync(rows -> rows.stream().map(this::info).toList(), tasks.main());
    }

    private TrapInfo info(TrapRow r) {
        Duration cycle =
                TrapMath.cycle(
                        balance().trapCycleHours(),
                        spec(r).cycleMult(),
                        statOrZero(r.owner(), "trap_cycle_hours"),
                        balance().trapMinCycleHours());
        Instant next = r.lastCalc().plus(cycle);
        return new TrapInfo(
                r.id(),
                r.owner(),
                r.region(),
                r.world(),
                r.x(),
                r.y(),
                r.z(),
                r.item(),
                r.tier(),
                r.slots() + r.extraSlots(),
                r.extraSlots(),
                r.bait(),
                r.contents().size(),
                r.lastCalc(),
                r.lastHarvest(),
                next);
    }

    @Override
    public CompletableFuture<SlotPurchase> purchaseSlot(Player owner, long trapId) {
        UUID uuid = owner.getUniqueId();
        FishingBalance b = balance();
        int maxExtra = b.trapMaxExtraSlots();
        long unitPrice = b.trapSlotPrice();
        CompletableFuture<SlotPurchase> result =
                api.database()
                        .async(c -> TrapRepository.byId(c, trapId, false))
                        .thenComposeAsync(
                                row -> {
                                    if (row.isEmpty()) {
                                        return CompletableFuture.completedFuture(
                                                SlotPurchase.NOT_FOUND);
                                    }
                                    TrapRow r = row.get();
                                    if (!r.owner().equals(uuid)) {
                                        return CompletableFuture.completedFuture(
                                                SlotPurchase.NOT_OWNER);
                                    }
                                    if (r.extraSlots() >= maxExtra) {
                                        return CompletableFuture.completedFuture(
                                                SlotPurchase.MAX_REACHED);
                                    }
                                    long price = unitPrice * Math.max(1, r.tier());
                                    String ref = "trapslot:" + r.id() + ":" + (r.extraSlots() + 1);
                                    return api.currency()
                                            .atomic(
                                                    List.of(
                                                            new LedgerEntry(
                                                                    uuid,
                                                                    Currency.GOLD,
                                                                    -price,
                                                                    LedgerReason.TRAP_SLOT_EXPAND,
                                                                    ref)),
                                                    c ->
                                                            TrapRepository.expandSlot(
                                                                    c, r.id(), uuid, maxExtra))
                                            .thenApply(TrapManager::slotResult);
                                },
                                tasks.main())
                        .exceptionally(
                                e -> {
                                    log.warn("통발 슬롯 확장 실패 ({}): {}", trapId, String.valueOf(e));
                                    return SlotPurchase.FAILED;
                                });
        return track(uuid, result);
    }

    private static SlotPurchase slotResult(Result<List<Long>, LedgerError> r) {
        return switch (r) {
            case Result.Ok<List<Long>, LedgerError> ok -> SlotPurchase.OK;
            case Result.Err<List<Long>, LedgerError>(LedgerError error) ->
                    switch (error) {
                        case INSUFFICIENT_FUNDS -> SlotPurchase.NO_GOLD;
                        case REJECTED, DUPLICATE -> SlotPurchase.MAX_REACHED;
                        default -> SlotPurchase.FAILED;
                    };
        };
    }

    @Override
    public boolean isTrapCatch(ItemStack stack) {
        return SOURCE_TRAP.equals(
                stack.getPersistentDataContainer().get(sourceKey, PersistentDataType.STRING));
    }

    // ------------------------------------------------------------------ 설치 · 열기

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        Player player = event.getPlayer();
        Long id = trapIdAt(clicked);
        if (id != null) {
            event.setCancelled(true);
            open(player, id, clicked);
            return;
        }
        // 이벤트의 아이템 대신 실제 손의 스택을 줄인다
        ItemStack hand = player.getInventory().getItemInMainHand();
        Optional<ItemDef> def = fishingItem(hand).filter(d -> d.type() == ItemDef.Type.TRAP);
        if (def.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        place(player, hand, def.get(), clicked.getRelative(event.getBlockFace()));
    }

    private Optional<ItemDef> fishingItem(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        return api.items()
                .read(stack)
                .filter(i -> i instanceof HarborItem.Plain)
                .flatMap(
                        i ->
                                api.content()
                                        .snapshot()
                                        .get(ItemDef.TYPE, ((HarborItem.Plain) i).id().id()));
    }

    private @Nullable Long trapIdAt(Block block) {
        if (block.getType() != Material.BARREL) {
            return null;
        }
        BlockState state = block.getState(false);
        if (state instanceof TileState tile) {
            return tile.getPersistentDataContainer().get(trapKey, PersistentDataType.LONG);
        }
        return null;
    }

    private void place(Player player, ItemStack hand, ItemDef def, Block target) {
        FishingBalance b = balance();
        UUID uuid = player.getUniqueId();
        Location targetLoc = target.getLocation().add(0.5, 0, 0.5);
        Optional<RegionDef> region = fishing.regionAt(targetLoc);
        if (region.isEmpty() || region.get().trapZone().isEmpty()) {
            api.messages().send(player, "fishing.trap.not_zone");
            return;
        }
        RegionDef.Point buoy = nearestBuoy(region.get(), targetLoc, b.trapBuoyRadius());
        if (buoy == null) {
            api.messages().send(player, "fishing.trap.not_zone");
            return;
        }
        World world = target.getWorld();
        Block spot =
                world.getBlockAt(
                        (int) Math.floor(buoy.x()), target.getY(), (int) Math.floor(buoy.z()));
        if (!spot.getType().isAir() && spot.getType() != Material.WATER) {
            api.messages().send(player, "fishing.trap.blocked");
            return;
        }
        TrapMath.Spec spec = TrapMath.Spec.of(def.stats());
        if (spec.deep() && stat(uuid, "feature_deep_trap") <= 0) {
            api.messages().send(player, "fishing.trap.deep_locked");
            return;
        }
        int max = TrapMath.maxTraps(b.trapBaseCount(), stat(uuid, "trap_count"), b.trapMaxCount());
        // 아이템을 먼저 가져가고, 실패하면 돌려준다 (복제 방지)
        ItemStack taken = hand.asOne();
        hand.setAmount(hand.getAmount() - 1);
        int season = season();
        RegionId regionId = region.get().id();
        String worldName = world.getName();
        int x = spot.getX();
        int y = spot.getY();
        int z = spot.getZ();
        Instant now = Instant.now();
        var _ =
                track(
                                uuid,
                                api.database()
                                        .async(
                                                c -> {
                                                    if (TrapRepository.count(c, uuid, season)
                                                            >= max) {
                                                        return -1L;
                                                    }
                                                    return TrapRepository.insert(
                                                            c, uuid, season, regionId, worldName, x,
                                                            y, z, spec, def.id(), now);
                                                }))
                        .whenCompleteAsync(
                                (id, error) -> {
                                    if (error != null || id == null || id < 0) {
                                        fishing.give(player, taken);
                                        api.messages()
                                                .send(
                                                        player,
                                                        error == null
                                                                ? "fishing.trap.limit"
                                                                : duplicate(error)
                                                                        ? "fishing.trap.occupied"
                                                                        : "fishing.trap.failed",
                                                        Placeholder.unparsed(
                                                                "max", String.valueOf(max)));
                                        if (error != null && !duplicate(error)) {
                                            log.warn("통발 설치 실패: {}", String.valueOf(error));
                                        }
                                        return;
                                    }
                                    build(spot, id, def);
                                    api.messages()
                                            .send(
                                                    player,
                                                    "fishing.trap.placed",
                                                    Placeholder.unparsed("name", def.name()));
                                },
                                tasks.main());
    }

    private static boolean duplicate(Throwable error) {
        Throwable t = error;
        while (t != null) {
            if (t instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private static RegionDef.@Nullable Point nearestBuoy(
            RegionDef region, Location at, double radius) {
        RegionDef.Point best = null;
        double bestDist = radius * radius;
        for (RegionDef.Point p : region.trapZone()) {
            double dx = p.x() - at.getX();
            double dz = p.z() - at.getZ();
            double d = dx * dx + dz * dz;
            if (d <= bestDist) {
                best = p;
                bestDist = d;
            }
        }
        return best;
    }

    /** 배럴 블록 + 위에 통발 외형 ItemDisplay. */
    private void build(Block spot, long id, ItemDef def) {
        spot.setType(Material.BARREL);
        BlockState state = spot.getState();
        if (state instanceof TileState tile) {
            tile.getPersistentDataContainer().set(trapKey, PersistentDataType.LONG, id);
            tile.update();
        }
        ItemStack look = api.items().create(def, 1);
        var _ =
                spot.getWorld()
                        .spawn(
                                spot.getLocation().add(0.5, 1.35, 0.5),
                                ItemDisplay.class,
                                d -> {
                                    d.setItemStack(look);
                                    d.setBillboard(Display.Billboard.VERTICAL);
                                    d.setPersistent(true);
                                    d.getPersistentDataContainer()
                                            .set(trapKey, PersistentDataType.LONG, id);
                                });
    }

    /** 블록과 외형을 지운다 (청크가 로드되어 있을 때만, 아니면 다음에 누가 열 때 정리). */
    void demolish(String worldName, int x, int y, int z, long id) {
        World world = Bukkit.getWorld(worldName);
        if (world == null || !world.isChunkLoaded(x >> 4, z >> 4)) {
            return;
        }
        Block block = world.getBlockAt(x, y, z);
        Long at = trapIdAt(block);
        if (at != null && at == id) {
            block.setType(Material.AIR);
        }
        Location center = block.getLocation().add(0.5, 1, 0.5);
        for (Entity e : world.getNearbyEntities(center, 1.5, 2, 1.5)) {
            Long tag = e.getPersistentDataContainer().get(trapKey, PersistentDataType.LONG);
            if (e instanceof ItemDisplay && tag != null && tag == id) {
                e.remove();
            }
        }
    }

    private void open(Player player, long id, Block block) {
        UUID uuid = player.getUniqueId();
        var _ =
                api.database()
                        .async(c -> TrapRepository.byId(c, id, false))
                        .whenCompleteAsync(
                                (row, error) -> {
                                    if (error != null) {
                                        api.messages().send(player, "fishing.trap.db_down");
                                        return;
                                    }
                                    if (row.isEmpty()) {
                                        // 회수된 통발의 남은 블록 정리
                                        demolish(
                                                block.getWorld().getName(),
                                                block.getX(),
                                                block.getY(),
                                                block.getZ(),
                                                id);
                                        return;
                                    }
                                    TrapRow r = row.get();
                                    if (!r.owner().equals(uuid)) {
                                        OfflinePlayer owner = Bukkit.getOfflinePlayer(r.owner());
                                        String name = owner.getName();
                                        api.ui()
                                                .actionBar(
                                                        player,
                                                        api.messages()
                                                                .get(
                                                                        "fishing.trap.owner",
                                                                        Placeholder.unparsed(
                                                                                "player",
                                                                                name == null
                                                                                        ? "?"
                                                                                        : name)));
                                        return;
                                    }
                                    var _ =
                                            calculate(player, r.id())
                                                    .thenAccept(
                                                            calc -> {
                                                                calc.message(api, player);
                                                                menu.open(player, calc.row());
                                                            });
                                },
                                tasks.main());
    }

    // ------------------------------------------------------------------ 오프라인 계산

    /** 계산 결과 (메인 스레드에서 받음). */
    public record Calc(TrapRow row, int produced, boolean capReached) {
        void message(HarborApi api, Player player) {
            if (produced > 0) {
                api.messages()
                        .send(
                                player,
                                "fishing.trap.produced",
                                Placeholder.unparsed("count", String.valueOf(produced)));
            }
            if (capReached) {
                api.messages().send(player, "fishing.trap.cap_reached");
            }
        }
    }

    /** 열 때 한 번 오프라인 계산 (docs 20 3.4). 주인이 접속해 있어야 한다 (직업 상한·스탯). */
    CompletableFuture<Calc> calculate(Player owner, long id) {
        UUID uuid = owner.getUniqueId();
        HarborPlayer hp = api.players().get(owner);
        ContentSnapshot snapshot = api.content().snapshot();
        FishingBalance b = balance();
        Quality cap = jobs.qualityCap(uuid);
        boolean produce = hp.jobLine().map(l -> l == JobLine.PRODUCE).orElse(false);
        double ratio = b.trapRevenueRatio(produce, hp.jobTier().level());
        double bonusSlots = stat(uuid, "trap_slots");
        double cycleDelta = stat(uuid, "trap_cycle_hours");
        double extraChance = stat(uuid, "trap_extra_chance");
        Predicate<String> flags = flagSnapshot(snapshot);
        int season = season();
        SplittableRandom random = new SplittableRandom();
        // 지역 어종 풀과 통발 스펙은 메인 스레드에서 미리 만든다 (판정은 가상 스레드)
        Map<String, TrapMath.Spec> specs = new HashMap<>();
        for (ItemDef def : snapshot.all(ItemDef.TYPE).values()) {
            if (def.type() == ItemDef.Type.TRAP) {
                specs.put(def.id().toString(), TrapMath.Spec.of(def.stats()));
            }
        }
        var engine = fishing.engine();
        Duration window = Duration.ofMillis(Math.round(b.trapRevenueWindowHours() * 3_600_000));
        CompletableFuture<Calc> future =
                api.database()
                        .async(
                                c -> {
                                    List<TrapRow> rows =
                                            TrapRepository.byOwner(c, uuid, season, true);
                                    TrapRow row =
                                            rows.stream()
                                                    .filter(r -> r.id() == id)
                                                    .findFirst()
                                                    .orElse(null);
                                    if (row == null) {
                                        throw new IllegalStateException("통발이 없습니다: " + id);
                                    }
                                    Instant now = Instant.now();
                                    TrapMath.Spec spec =
                                            specs.getOrDefault(
                                                    row.item().toString(),
                                                    new TrapMath.Spec(
                                                            row.slots(),
                                                            Grade.COMMON,
                                                            1,
                                                            row.tier(),
                                                            false));
                                    Duration cycle =
                                            TrapMath.cycle(
                                                    b.trapCycleHours(),
                                                    spec.cycleMult(),
                                                    cycleDelta,
                                                    b.trapMinCycleHours());
                                    TrapMath.Accrual acc =
                                            TrapMath.accrue(
                                                    row.lastCalc(), now, cycle, b.trapMaxCycles());
                                    if (acc.cycles() == 0) {
                                        return new Calc(row, 0, false);
                                    }
                                    double hourly = 0;
                                    double others = 0;
                                    for (TrapRow r : rows) {
                                        hourly =
                                                Math.max(
                                                        hourly,
                                                        b.hourlyGold(r.region().id().name()));
                                        if (r.id() != id) {
                                            others +=
                                                    TrapMath.windowValue(
                                                            r.value24h(),
                                                            r.windowStart(),
                                                            now,
                                                            window);
                                        }
                                    }
                                    double own =
                                            TrapMath.windowValue(
                                                    row.value24h(), row.windowStart(), now, window);
                                    Instant windowStart =
                                            now.isBefore(row.windowStart().plus(window))
                                                    ? row.windowStart()
                                                    : now;
                                    TrapRoller roller =
                                            new TrapRoller(
                                                    engine.regionPool(row.region()),
                                                    spec.maxGrade(),
                                                    flags,
                                                    b,
                                                    cap,
                                                    random);
                                    int slots =
                                            roller.isEmpty()
                                                    ? 0
                                                    : TrapMath.effectiveSlots(
                                                            row.slots(),
                                                            row.extraSlots(),
                                                            bonusSlots);
                                    TrapMath.Production p =
                                            TrapMath.produce(
                                                    acc.cycles(),
                                                    slots,
                                                    row.bait(),
                                                    b.trapBaitPerSlot(),
                                                    roller.isEmpty() ? 0 : extraChance,
                                                    others + own,
                                                    TrapMath.accountCap(hourly, ratio),
                                                    roller,
                                                    now,
                                                    random);
                                    List<TrapCatch> contents = new ArrayList<>(row.contents());
                                    contents.addAll(p.catches());
                                    int bait = row.bait() - p.baitUsed();
                                    double value = own + p.value();
                                    boolean saved =
                                            TrapRepository.saveCalc(
                                                    c,
                                                    id,
                                                    row.lastCalc(),
                                                    acc.lastCalc(),
                                                    contents,
                                                    bait,
                                                    value,
                                                    windowStart);
                                    if (!saved) {
                                        // 다른 곳에서 먼저 계산했다: 그 결과를 그대로 쓴다
                                        return new Calc(
                                                TrapRepository.byId(c, id, false).orElse(row),
                                                0,
                                                false);
                                    }
                                    return new Calc(
                                            new TrapRow(
                                                    row.id(),
                                                    row.owner(),
                                                    row.season(),
                                                    row.region(),
                                                    row.world(),
                                                    row.x(),
                                                    row.y(),
                                                    row.z(),
                                                    row.tier(),
                                                    row.item(),
                                                    row.slots(),
                                                    row.extraSlots(),
                                                    bait,
                                                    acc.lastCalc(),
                                                    contents,
                                                    value,
                                                    windowStart,
                                                    row.lastHarvest(),
                                                    row.placedAt()),
                                            p.catches().size(),
                                            p.capReached());
                                })
                        .thenApplyAsync(calc -> calc, tasks.main());
        var _ =
                future.exceptionally(
                        e -> {
                            log.warn("통발 계산 실패 ({}): {}", id, String.valueOf(e));
                            var _ =
                                    tasks.sync(
                                            () ->
                                                    api.messages()
                                                            .send(owner, "fishing.trap.failed"));
                            return null;
                        });
        return track(uuid, future);
    }

    /** 서버 플래그를 지금 값으로 고정한다 (가상 스레드에서 FlagService를 부르지 않게). */
    private Predicate<String> flagSnapshot(ContentSnapshot snapshot) {
        Set<String> on = new HashSet<>();
        for (FishDef f : snapshot.all(FishDef.TYPE).values()) {
            String flag = f.unlockFlag();
            if (flag != null && api.flags().hasServer(flag)) {
                on.add(flag);
            }
        }
        return on::contains;
    }

    // ------------------------------------------------------------------ 수확 · 미끼 · 철거

    /** 항목을 수확한다 (트랜잭션 커밋 후 아이템 생성). 가방 빈칸 수만큼만 꺼낸다. */
    void harvest(Player player, TrapRow row, List<TrapCatch> entries) {
        int free = 0;
        for (ItemStack s : player.getInventory().getStorageContents()) {
            if (s == null || s.isEmpty()) {
                free++;
            }
        }
        if (free == 0) {
            api.messages().send(player, "fishing.trap.full");
            return;
        }
        Set<String> uids = new HashSet<>();
        for (TrapCatch e : entries) {
            if (uids.size() >= free) {
                break;
            }
            uids.add(e.uid());
        }
        UUID uuid = player.getUniqueId();
        Instant now = Instant.now();
        var _ =
                track(
                                uuid,
                                api.database()
                                        .async(
                                                c ->
                                                        TrapRepository.take(
                                                                c, row.id(), uuid, uids, now)))
                        .whenCompleteAsync(
                                (taken, error) -> {
                                    if (error != null) {
                                        log.warn(
                                                "통발 수확 실패 ({}): {}",
                                                row.id(),
                                                String.valueOf(error));
                                        api.messages().send(player, "fishing.trap.failed");
                                        return;
                                    }
                                    List<HarborItem.Fish> given = new ArrayList<>();
                                    for (TrapCatch entry : taken) {
                                        ItemStack stack = createCatch(player, entry);
                                        if (stack == null) {
                                            continue;
                                        }
                                        api.items()
                                                .read(stack)
                                                .filter(i -> i instanceof HarborItem.Fish)
                                                .ifPresent(i -> given.add((HarborItem.Fish) i));
                                        fishing.give(player, stack);
                                    }
                                    if (!given.isEmpty()) {
                                        api.messages()
                                                .send(
                                                        player,
                                                        "fishing.trap.harvested",
                                                        Placeholder.unparsed(
                                                                "count",
                                                                String.valueOf(given.size())));
                                        Bukkit.getPluginManager()
                                                .callEvent(
                                                        new TrapHarvestEvent(
                                                                player,
                                                                row.id(),
                                                                row.region(),
                                                                given));
                                    }
                                    reopen(player, row.id());
                                },
                                tasks.main());
    }

    /** 통발 항목 → 어종 아이템 (trap 출처 표시). 콘텐츠에서 사라진 어종이면 null. */
    @Nullable ItemStack createCatch(Player owner, TrapCatch entry) {
        return createCatch(api.players().get(owner).ref(), entry);
    }

    private @Nullable ItemStack createCatch(
            wiki.creeper.harbor.common.model.PlayerRef owner, TrapCatch entry) {
        Optional<FishDef> def;
        try {
            def = api.content().snapshot().get(FishDef.TYPE, FishId.parse(entry.fish()).id());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (def.isEmpty()) {
            return null;
        }
        ItemStack stack =
                api.items()
                        .createFish(
                                def.get(),
                                entry.size(),
                                Quality.of(Math.clamp(entry.quality(), Quality.MIN, Quality.MAX)),
                                owner,
                                Instant.ofEpochMilli(entry.caughtAt()));
        stack.editPersistentDataContainer(
                pdc -> pdc.set(sourceKey, PersistentDataType.STRING, SOURCE_TRAP));
        return stack;
    }

    void reopen(Player player, long id) {
        if (!player.isOnline()) {
            return;
        }
        var _ =
                api.database()
                        .async(c -> TrapRepository.byId(c, id, false))
                        .whenCompleteAsync(
                                (row, error) -> {
                                    if (error == null && row.isPresent()) {
                                        menu.open(player, row.get());
                                    } else {
                                        player.closeInventory();
                                    }
                                },
                                tasks.main());
    }

    /** 가방의 미끼를 통발에 넣는다 (사용 횟수 미끼·전설의 미끼 제외). */
    void addBait(Player player, TrapRow row) {
        FishingBalance b = balance();
        int room = b.trapBaitCapacity() - row.bait();
        if (room <= 0) {
            api.messages().send(player, "fishing.trap.bait_full");
            return;
        }
        int taken = 0;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (ItemStack stack : contents) {
            if (taken >= room || stack == null || stack.isEmpty()) {
                continue;
            }
            Optional<ItemDef> def = fishingItem(stack);
            if (def.isEmpty()
                    || def.get().type() != ItemDef.Type.BAIT
                    || def.get().stats().getOrDefault("legend_only", 0.0) > 0
                    || def.get().stats().getOrDefault("uses", 0.0) > 1) {
                continue;
            }
            int n = Math.min(room - taken, stack.getAmount());
            stack.setAmount(stack.getAmount() - n);
            taken += n;
        }
        if (taken == 0) {
            api.messages().send(player, "fishing.trap.no_bait");
            return;
        }
        int amount = taken;
        UUID uuid = player.getUniqueId();
        var _ =
                track(
                                uuid,
                                api.database()
                                        .async(
                                                c ->
                                                        TrapRepository.addBait(
                                                                c,
                                                                row.id(),
                                                                uuid,
                                                                amount,
                                                                b.trapBaitCapacity())))
                        .whenCompleteAsync(
                                (ok, error) -> {
                                    if (error != null || !Boolean.TRUE.equals(ok)) {
                                        refundBait(player, amount);
                                        api.messages().send(player, "fishing.trap.failed");
                                        return;
                                    }
                                    api.messages()
                                            .send(
                                                    player,
                                                    "fishing.trap.bait_added",
                                                    Placeholder.unparsed(
                                                            "count", String.valueOf(amount)));
                                    reopen(player, row.id());
                                },
                                tasks.main());
    }

    /** 미끼 반환 (어떤 미끼였는지 기록하지 않으므로 기본 미끼 fishing.trap.refund_bait_item, 제안값). */
    private void refundBait(Player player, int amount) {
        if (amount <= 0) {
            return;
        }
        refundBaitItems(amount).forEach(s -> fishing.give(player, s));
    }

    private List<ItemStack> refundBaitItems(int amount) {
        String id =
                api.content()
                        .snapshot()
                        .single(Balance.TYPE)
                        .string("fishing.trap.refund_bait_item")
                        .orElse("harbor:item/bait/earthworm");
        List<ItemStack> result = new ArrayList<>();
        try {
            api.content()
                    .snapshot()
                    .get(ItemDef.TYPE, ItemId.parse(id).id())
                    .ifPresent(
                            def -> {
                                int left = amount;
                                while (left > 0) {
                                    int n = Math.min(64, left);
                                    result.add(api.items().create(def, n));
                                    left -= n;
                                }
                            });
        } catch (IllegalArgumentException e) {
            log.warn("fishing.trap.refund_bait_item 형식이 틀렸습니다: {}", id);
        }
        return result;
    }

    /** 철거: 내용물이 비었을 때만. 통발 아이템과 남은 미끼를 돌려준다. */
    void remove(Player player, TrapRow row) {
        if (!row.contents().isEmpty()) {
            api.messages().send(player, "fishing.trap.not_empty");
            return;
        }
        UUID uuid = player.getUniqueId();
        var _ =
                track(
                                uuid,
                                api.database()
                                        .async(c -> TrapRepository.deleteEmpty(c, row.id(), uuid)))
                        .whenCompleteAsync(
                                (ok, error) -> {
                                    if (error != null || !Boolean.TRUE.equals(ok)) {
                                        api.messages().send(player, "fishing.trap.failed");
                                        return;
                                    }
                                    player.closeInventory();
                                    demolish(row.world(), row.x(), row.y(), row.z(), row.id());
                                    api.content()
                                            .snapshot()
                                            .get(ItemDef.TYPE, row.item().id())
                                            .ifPresent(
                                                    def ->
                                                            fishing.give(
                                                                    player,
                                                                    api.items().create(def, 1)));
                                    refundBait(player, row.bait());
                                    api.messages().send(player, "fishing.trap.removed");
                                },
                                tasks.main());
    }

    /** 관리자 철거 (내용물은 버린다). */
    public CompletableFuture<Boolean> adminRemove(long id) {
        return api.database()
                .async(
                        c -> {
                            Optional<TrapRow> row = TrapRepository.byId(c, id, true);
                            if (row.isEmpty()) {
                                return Optional.<TrapRow>empty();
                            }
                            TrapRepository.delete(c, id);
                            return row;
                        })
                .thenApplyAsync(
                        row -> {
                            row.ifPresent(r -> demolish(r.world(), r.x(), r.y(), r.z(), r.id()));
                            return row.isPresent();
                        },
                        tasks.main());
    }

    /** 관리자 계산 (주인이 접속해 있어야 한다). */
    public CompletableFuture<Optional<Calc>> adminCalc(long id) {
        return api.database()
                .async(c -> TrapRepository.byId(c, id, false))
                .thenComposeAsync(
                        row -> {
                            Player owner = row.map(r -> Bukkit.getPlayer(r.owner())).orElse(null);
                            if (owner == null) {
                                return CompletableFuture.completedFuture(Optional.<Calc>empty());
                            }
                            return calculate(owner, id).thenApply(Optional::of);
                        },
                        tasks.main());
    }

    // ------------------------------------------------------------------ 보호

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (trapIdAt(event.getBlock()) != null) {
            event.setCancelled(true);
            api.messages().send(event.getPlayer(), "fishing.trap.break_denied");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> trapIdAt(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> trapIdAt(b) != null);
    }

    // ------------------------------------------------------------------ 자동 회수

    /** 하루 한 번 (본 서버 한 곳): 7일 수확하지 않은 통발을 우편으로 회수한다. */
    private void autoRetrieve() {
        if (api.infra().role() != wiki.creeper.harbor.common.config.ServerRole.MAIN) {
            return;
        }
        Optional<MailService> mail = api.mail();
        if (mail.isEmpty()) {
            // 우편함(Economy)이 없으면 보류 (docs 20 3.8)
            return;
        }
        if (api.locks()
                .tryAcquire("harbor:lock:fishing-trap-retrieve", RETRIEVE_LOCK_TTL)
                .isEmpty()) {
            return;
        }
        int season = season();
        Instant before = Instant.now().minus(Duration.ofDays(balance().trapRetrieveDays()));
        var _ =
                api.database()
                        .async(c -> TrapRepository.stale(c, season, before, RETRIEVE_BATCH))
                        .whenCompleteAsync(
                                (rows, error) -> {
                                    if (error != null) {
                                        log.warn("통발 자동 회수 조회 실패: {}", String.valueOf(error));
                                        return;
                                    }
                                    if (!rows.isEmpty()) {
                                        log.info("통발 자동 회수 {}개", rows.size());
                                    }
                                    retrieveNext(mail.get(), new ArrayList<>(rows));
                                },
                                tasks.main());
    }

    /** 한 개씩 차례로: 항목마다 우편이 커밋된 뒤 contents에서 지운다 (중간에 꺼져도 중복 지급은 최대 1개). */
    private void retrieveNext(MailService mail, List<TrapRow> queue) {
        if (queue.isEmpty()) {
            return;
        }
        TrapRow row = queue.removeFirst();
        wiki.creeper.harbor.common.model.PlayerRef owner =
                new wiki.creeper.harbor.common.model.PlayerRef(
                        row.owner(),
                        Optional.ofNullable(Bukkit.getOfflinePlayer(row.owner()).getName())
                                .orElse("?"));
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (TrapCatch entry : row.contents()) {
            ItemStack stack = createCatch(owner, entry);
            if (stack == null) {
                continue;
            }
            String ref = "trap:" + row.id() + ":" + entry.uid();
            chain =
                    chain.thenCompose(v -> mail.sendItem(row.owner(), stack, "trap_retrieve", ref))
                            .thenCompose(
                                    v ->
                                            api.database()
                                                    .asyncVoid(
                                                            c ->
                                                                    TrapRepository.dropEntry(
                                                                            c,
                                                                            row.id(),
                                                                            entry.uid())));
        }
        List<ItemStack> extras = new ArrayList<>();
        api.content()
                .snapshot()
                .get(ItemDef.TYPE, row.item().id())
                .ifPresent(def -> extras.add(api.items().create(def, 1)));
        extras.addAll(refundBaitItems(row.bait()));
        CompletableFuture<Void> done =
                chain.thenCompose(
                                v -> api.database().async(c -> TrapRepository.delete(c, row.id())))
                        .thenCompose(
                                deleted -> {
                                    if (!Boolean.TRUE.equals(deleted)) {
                                        return CompletableFuture.completedFuture(null);
                                    }
                                    CompletableFuture<Void> rest =
                                            CompletableFuture.completedFuture(null);
                                    for (ItemStack extra : extras) {
                                        rest =
                                                rest.thenCompose(
                                                        v ->
                                                                mail.sendItem(
                                                                        row.owner(),
                                                                        extra,
                                                                        "trap_retrieve",
                                                                        "trapbox:" + row.id()));
                                    }
                                    return rest;
                                });
        var _ =
                done.whenCompleteAsync(
                        (v, error) -> {
                            if (error != null) {
                                log.warn(
                                        "통발 자동 회수 실패 ({}): {}",
                                        row.id(),
                                        String.valueOf(
                                                error instanceof CompletionException ce
                                                        ? ce.getCause()
                                                        : error));
                            } else {
                                demolish(row.world(), row.x(), row.y(), row.z(), row.id());
                            }
                            retrieveNext(mail, queue);
                        },
                        tasks.main());
    }

    // ------------------------------------------------------------------ 도우미

    TrapMath.Spec spec(TrapRow row) {
        return api.content()
                .snapshot()
                .get(ItemDef.TYPE, row.item().id())
                .map(d -> TrapMath.Spec.of(d.stats()))
                .orElse(new TrapMath.Spec(row.slots(), Grade.COMMON, 1, row.tier(), false));
    }

    private double statOrZero(UUID owner, String key) {
        return Bukkit.getPlayer(owner) == null ? 0 : stat(owner, key);
    }

    double stat(UUID uuid, String key) {
        return api.modifiers().value(uuid, StatKey.of(key));
    }

    int bonusSlots(UUID uuid) {
        return (int) Math.floor(stat(uuid, "trap_slots"));
    }

    long slotPrice(TrapRow row) {
        return balance().trapSlotPrice() * Math.max(1, row.tier());
    }
}
