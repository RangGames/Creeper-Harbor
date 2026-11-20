package wiki.creeper.harbor.fishing.trap;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
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
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.ledger.LedgerEntry;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.core.api.HandoffBlocker;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.api.TrapInfo;
import wiki.creeper.harbor.fishing.api.TrapService;
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

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final TrapMenu menu;
    private final TrapBlocks blocks;
    private final TrapPlacement placement;
    private final TrapCalculator calculator;
    private final TrapHarvest harvests;
    private final TrapRetrieval retrieval;
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
        this.log = log;
        this.blocks = new TrapBlocks(plugin, api);
        this.menu = new TrapMenu(api, this);
        this.placement = new TrapPlacement(api, tasks, log, fishing, this, blocks, menu);
        this.calculator = new TrapCalculator(api, tasks, jobs, log, fishing, this);
        this.harvests = new TrapHarvest(plugin, api, tasks, log, fishing, this, blocks, menu);
        this.retrieval = new TrapRetrieval(api, tasks, log, this, blocks, harvests);
    }

    public void start() {
        retrieval.start();
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

    <T> CompletableFuture<T> track(UUID player, CompletableFuture<T> future) {
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
        return harvests.isTrapCatch(stack);
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
        Long id = blocks.trapIdAt(clicked);
        if (id != null) {
            event.setCancelled(true);
            placement.open(player, id, clicked);
            return;
        }
        // 이벤트의 아이템 대신 실제 손의 스택을 줄인다
        ItemStack hand = player.getInventory().getItemInMainHand();
        Optional<ItemDef> def = fishingItem(hand).filter(d -> d.type() == ItemDef.Type.TRAP);
        if (def.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        placement.place(player, hand, def.get(), clicked.getRelative(event.getBlockFace()));
    }

    Optional<ItemDef> fishingItem(@Nullable ItemStack stack) {
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

    /** 블록과 외형을 지운다 (청크가 로드되어 있을 때만, 아니면 다음에 누가 열 때 정리). */
    void demolish(String worldName, int x, int y, int z, long id) {
        blocks.demolish(worldName, x, y, z, id);
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
        return calculator.calculate(owner, id);
    }

    // ------------------------------------------------------------------ 수확 · 미끼 · 철거

    /** 항목을 수확한다 (트랜잭션 커밋 후 아이템 생성). 가방 빈칸 수만큼만 꺼낸다. */
    void harvest(Player player, TrapRow row, List<TrapCatch> entries) {
        harvests.harvest(player, row, entries);
    }

    /** 통발 항목 → 어종 아이템 (trap 출처 표시). 콘텐츠에서 사라진 어종이면 null. */
    @Nullable ItemStack createCatch(Player owner, TrapCatch entry) {
        return harvests.createCatch(owner, entry);
    }

    void reopen(Player player, long id) {
        harvests.reopen(player, id);
    }

    /** 가방의 미끼를 통발에 넣는다 (사용 횟수 미끼·전설의 미끼 제외). */
    void addBait(Player player, TrapRow row) {
        harvests.addBait(player, row);
    }

    /** 철거: 내용물이 비었을 때만. 통발 아이템과 남은 미끼를 돌려준다. */
    void remove(Player player, TrapRow row) {
        harvests.remove(player, row);
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
        if (blocks.trapIdAt(event.getBlock()) != null) {
            event.setCancelled(true);
            api.messages().send(event.getPlayer(), "fishing.trap.break_denied");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> blocks.trapIdAt(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> blocks.trapIdAt(b) != null);
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
