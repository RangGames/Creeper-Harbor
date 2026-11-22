package wiki.creeper.harbor.fishing.trap;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
import wiki.creeper.harbor.common.id.ItemId;
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
import wiki.creeper.harbor.fishing.api.TrapAction;
import wiki.creeper.harbor.fishing.api.TrapInfo;
import wiki.creeper.harbor.fishing.api.TrapService;
import wiki.creeper.harbor.fishing.content.FishingBalance;
import wiki.creeper.harbor.fishing.session.FishingManager;
import wiki.creeper.harbor.jobs.api.JobsService;

/**
 * Traps (docs 20 3.4 TrapService, "12. World, housing, traps" chapter 5, decision D1). Used on the
 * main thread; all DB work is immediate transactions (virtual threads).
 *
 * <ul>
 *   <li>Placement: right-click a block with a trap item at a buoy spot (radius buoy_radius) in a
 *       region trap_zone. Count = 2 + trap_count (max 4). The row is inserted immediately and a
 *       barrel block + ItemDisplay look is placed (trap ID in the barrel PDC)
 *   <li>Open: cancels the barrel opening and shows a dedicated GUI. Non-owners only see the owner's
 *       name in the action bar. On open, one offline calculation (up to 2 cycles, account income
 *       cap) is saved with a conditional update
 *   <li>Harvest: items are created only after the transaction removing them from contents commits.
 *       The trap source is left in the catch custom_data (auction monitoring)
 *   <li>Auto-retrieval: once a day one main server takes a distributed lock, sends the contents of
 *       traps not harvested for 7 days via Core MailService (implemented by Economy), then removes
 *       the rows and blocks. Deferred if MailService is missing
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

    /** Registers the season transition hook if Harbor-Season is enabled (optional dependency). */
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

    /**
     * Briefly delays server moves while trap work is in progress (up to 5 seconds, Core decides).
     */
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
                            // Trap placement, bait, harvest and retrieval commit to the DB
                            // immediately while items are write-behind, so save the inventory soon
                            // too (during the delay main thread callbacks give and take items). If
                            // the server dies in between, items are duplicated or lost
                            api.players().saveSoon(player);
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

    // ------------------------------------------------------------------ Placement · open

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
        // Shrink the actual hand stack instead of the event's item
        ItemStack hand = player.getInventory().getItemInMainHand();
        Optional<ItemDef> def = fishingItem(hand).filter(d -> d.type() == ItemDef.Type.TRAP);
        if (def.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        var _ = placement.place(player, hand, def.get(), clicked.getRelative(event.getBlockFace()));
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

    /**
     * Removes the block and look (only if the chunk is loaded; otherwise cleaned up the next time
     * someone opens it).
     */
    void demolish(String worldName, int x, int y, int z, long id) {
        blocks.demolish(worldName, x, y, z, id);
    }

    // ------------------------------------------------------------------ Offline calculation

    /** Calculation result (received on the main thread). */
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

    /**
     * Offline calculation once on open (docs 20 3.4). The owner must be online (job cap, stats).
     */
    CompletableFuture<Calc> calculate(Player owner, long id) {
        return calculator.calculate(owner, id);
    }

    // ------------------------------------------------------------------ Harvest · bait · removal

    /**
     * Harvests entries (items created after the transaction commits). Takes out only as many as
     * there are free inventory slots.
     */
    void harvest(Player player, TrapRow row, List<TrapCatch> entries) {
        var _ = harvests.harvest(player, row, entries, true);
    }

    /**
     * Trap entry → species item (marked with the trap source). Null if the species left content.
     */
    @Nullable ItemStack createCatch(Player owner, TrapCatch entry) {
        return harvests.createCatch(owner, entry);
    }

    void reopen(Player player, long id) {
        harvests.reopen(player, id);
    }

    /** Puts bait from the inventory into the trap (excluding bait with uses and legendary bait). */
    void addBait(Player player, TrapRow row) {
        var _ = harvests.addBait(player, row, true);
    }

    /** Removal: only when empty. Returns the trap item and the remaining bait. */
    void remove(Player player, TrapRow row) {
        var _ = harvests.remove(player, row, true);
    }

    // ------------------------------------------------------------------ TrapService actions

    @Override
    public CompletableFuture<Optional<TrapInfo>> trap(long trapId) {
        return api.database()
                .async(c -> TrapRepository.byId(c, trapId, false))
                .thenApplyAsync(row -> row.map(this::info), tasks.main());
    }

    @Override
    public OptionalLong trapIdAt(Location location) {
        Long id = blocks.trapIdAt(location.getBlock());
        return id == null ? OptionalLong.empty() : OptionalLong.of(id);
    }

    @Override
    public CompletableFuture<TrapAction> place(
            Player owner, Location location, ItemId trapItem, boolean takeItem) {
        Optional<ItemDef> def =
                api.content()
                        .snapshot()
                        .get(ItemDef.TYPE, trapItem.id())
                        .filter(d -> d.type() == ItemDef.Type.TRAP);
        if (def.isEmpty()) {
            return CompletableFuture.completedFuture(TrapAction.of(TrapAction.Outcome.NO_ITEM));
        }
        @Nullable ItemStack hand = null;
        if (takeItem) {
            for (ItemStack stack : owner.getInventory().getStorageContents()) {
                if (fishingItem(stack).filter(d -> d.id().equals(def.get().id())).isPresent()) {
                    hand = stack;
                    break;
                }
            }
            if (hand == null) {
                return CompletableFuture.completedFuture(TrapAction.of(TrapAction.Outcome.NO_ITEM));
            }
        }
        return placement.place(owner, hand, def.get(), location.getBlock());
    }

    @Override
    public CompletableFuture<TrapAction> harvest(Player owner, long trapId) {
        return withCalc(
                owner,
                trapId,
                row -> {
                    if (row.contents().isEmpty()) {
                        api.messages().send(owner, "fishing.trap.empty");
                        return CompletableFuture.completedFuture(
                                TrapAction.of(TrapAction.Outcome.EMPTY, trapId));
                    }
                    return harvests.harvest(owner, row, row.contents(), false);
                });
    }

    @Override
    public CompletableFuture<TrapAction> addBait(Player owner, long trapId) {
        return withCalc(owner, trapId, row -> harvests.addBait(owner, row, false));
    }

    @Override
    public CompletableFuture<TrapAction> remove(Player owner, long trapId) {
        return withCalc(owner, trapId, row -> harvests.remove(owner, row, false));
    }

    /**
     * Owner check, then the offline calculation the trap screen runs on open (so catches produced
     * since the last calculation are counted), then the action on the fresh row.
     */
    private CompletableFuture<TrapAction> withCalc(
            Player owner,
            long trapId,
            java.util.function.Function<TrapRow, CompletableFuture<TrapAction>> action) {
        UUID uuid = owner.getUniqueId();
        return api.database()
                .async(c -> TrapRepository.byId(c, trapId, false))
                .thenComposeAsync(
                        row -> {
                            if (row.isEmpty()) {
                                return CompletableFuture.completedFuture(
                                        TrapAction.of(TrapAction.Outcome.NOT_FOUND, trapId));
                            }
                            if (!row.get().owner().equals(uuid) || row.get().season() != season()) {
                                return CompletableFuture.completedFuture(
                                        TrapAction.of(
                                                row.get().owner().equals(uuid)
                                                        ? TrapAction.Outcome.NOT_FOUND
                                                        : TrapAction.Outcome.NOT_OWNER,
                                                trapId));
                            }
                            if (!owner.isOnline()) {
                                return CompletableFuture.completedFuture(
                                        TrapAction.of(TrapAction.Outcome.FAILED, trapId));
                            }
                            return calculate(owner, trapId)
                                    .thenComposeAsync(
                                            calc -> action.apply(calc.row()), tasks.main());
                        },
                        tasks.main())
                .exceptionally(
                        e -> {
                            log.warn("통발 작업 실패 ({}): {}", trapId, String.valueOf(e));
                            return TrapAction.of(TrapAction.Outcome.FAILED, trapId);
                        });
    }

    /** Admin removal (contents are discarded). */
    @Override
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

    /** Admin calculation (the owner must be online). */
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

    // ------------------------------------------------------------------ Protection

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

    // ------------------------------------------------------------------ Helpers

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
