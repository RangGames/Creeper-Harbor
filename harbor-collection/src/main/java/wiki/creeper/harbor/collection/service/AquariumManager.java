package wiki.creeper.harbor.collection.service;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.collection.api.AquariumService;
import wiki.creeper.harbor.collection.api.event.AquariumDonatedEvent;
import wiki.creeper.harbor.collection.api.event.AquariumStageChangedEvent;
import wiki.creeper.harbor.collection.aquarium.AquariumTanks;
import wiki.creeper.harbor.collection.content.AquariumRules;
import wiki.creeper.harbor.collection.content.StatBonus;
import wiki.creeper.harbor.collection.data.CollectionRepository;
import wiki.creeper.harbor.collection.data.CollectionRepository.AquariumState;
import wiki.creeper.harbor.collection.data.CollectionRepository.SpeciesRow;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.common.redis.Channel;
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.common.redis.DistributedLock;
import wiki.creeper.harbor.core.api.HandoffBlocker;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.Modifier;
import wiki.creeper.harbor.core.api.event.ContentReloadedEvent;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * Harbor aquarium (docs 20 4.4). Stages and the climax condition are judged by donation rate, not
 * points, so mass-donating one species cannot push them forward.
 *
 * <ul>
 *   <li>Donation paths: one held fish (/수족관 기부), the donation box GUI (Iseul dialog
 *       aquarium:donate), Quest collection requests ({@link #donateItems}).
 *   <li>Multiple fish in one transaction: insert aquarium_donation rows, INSERT IGNORE
 *       aquarium_species, increment aquarium_state, unique ID ledger ACTIVE -> DONATED. On failure
 *       everything is given back, via the mailbox when offline or the inventory is full (0.1).
 *   <li>Server moves are delayed up to 4 seconds while donating (Core HandoffBlocker).
 *   <li>Tank visuals and structures are in {@link AquariumTanks}.
 * </ul>
 */
public final class AquariumManager implements AquariumService, Listener {

    /** Cross-server sync (harbor:collection, proposed). */
    public record AquariumUpdated(int season, int species, int stage) {}

    static final Channel<AquariumUpdated> UPDATES =
            new Channel<>("harbor:collection", AquariumUpdated.class);

    private static final String BUFF_SOURCE = "aquarium_stage";
    static final String RETURN_SOURCE = "aquarium_return";

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final ItemReturns returns;
    private final AquariumTanks tanks;
    private final AquariumDonationFlow flow;
    private final Map<UUID, CompletableFuture<?>> busy = new ConcurrentHashMap<>();
    private volatile AquariumState state = AquariumState.EMPTY;
    private volatile Set<FishId> donatedSpecies = Set.of();

    /** One fish to donate. */
    record Candidate(ItemStack stack, HarborItem.Fish item, FishDef fish, boolean counted) {}

    public AquariumManager(HarborApi api, Tasks tasks, Logger log, ItemReturns returns) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.returns = returns;
        this.tanks = new AquariumTanks(api, tasks, log);
        this.flow = new AquariumDonationFlow(this, api, tasks, returns);
    }

    public void start() {
        refresh();
        tanks.start();
        api.bus()
                .subscribe(
                        UPDATES,
                        envelope -> {
                            if (!envelope.server().equals(api.infra().serverId())) {
                                tasks.main().execute(this::refresh);
                            }
                        });
    }

    /**
     * Shutdown: gives back items in open donation boxes or awaiting confirmation and removes the
     * tank rigs.
     */
    public void stop() {
        flow.stop();
        tanks.stop();
    }

    /** Reloads from the DB and replaces buffs and tanks (call on the main thread). */
    public void refresh() {
        SeasonId season = api.infra().season();
        record Loaded(AquariumState state, List<SpeciesRow> species) {}
        var _ =
                api.database()
                        .async(
                                c ->
                                        new Loaded(
                                                CollectionRepository.aquarium(c, season),
                                                CollectionRepository.species(c, season)))
                        .thenAcceptAsync(
                                loaded -> {
                                    AquariumState s = loaded.state();
                                    List<SpeciesRow> rows = loaded.species();
                                    state = s;
                                    Set<FishId> ids = new HashSet<>();
                                    rows.forEach(r -> ids.add(r.fish()));
                                    donatedSpecies = Set.copyOf(ids);
                                    applyBuffs(s.stage());
                                    tanks.update(rows, s.stage());
                                },
                                tasks.main())
                        .exceptionally(
                                e -> {
                                    log.warn(
                                            "수족관 상태를 읽지 못했습니다: {}", String.valueOf(e.getMessage()));
                                    return null;
                                });
    }

    AquariumRules rules() {
        return api.content().snapshot().single(AquariumRules.TYPE);
    }

    /** Number of donatable species: season species excluding mythic and release_only. */
    int donatable() {
        int season = api.infra().season().value();
        return (int)
                api.content().snapshot().all(FishDef.TYPE).values().stream()
                        .filter(f -> donatableFish(f, season))
                        .count();
    }

    static boolean donatableFish(FishDef f, int season) {
        return f.season() <= season && f.grade() != Grade.MYTHIC && !f.releaseOnly();
    }

    @Override
    public Status status() {
        AquariumState s = state;
        int donatable = donatable();
        return new Status(
                s.points(),
                s.speciesCount(),
                donatable,
                donatable == 0 ? 0 : (double) s.speciesCount() / donatable,
                s.stage());
    }

    /** The next stage (shown by /수족관). */
    public Optional<AquariumRules.Stage> nextStage(Status status) {
        return rules().next(status.stage());
    }

    /**
     * Whether this species is donated for the first time this season (by the memory cache, for the
     * confirmation screen; points are judged again inside the transaction).
     */
    boolean firstSpecies(FishId fish) {
        return !donatedSpecies.contains(fish);
    }

    /** Server move delay (Core HandoffBlocker): until the donation transaction finishes. */
    public HandoffBlocker handoffBlocker() {
        return (player, destination) -> {
            // While the donation box is open or awaiting confirmation the items are outside the
            // inventory (box or pending list). A snapshot would miss them, and items given back
            // after the snapshot are not saved, so block the move
            if (flow.holding(player)) {
                return new HandoffBlocker.Deny("busy");
            }
            CompletableFuture<?> f = busy.get(player.getUniqueId());
            return f == null || f.isDone() ? HandoffBlocker.ALLOW : new HandoffBlocker.Delay(f);
        };
    }

    // ------------------------------------------------------------------ one held fish

    @Override
    public CompletableFuture<Boolean> donateHeld(Player player) {
        return donateSlot(
                player,
                player.getInventory().getHeldItemSlot(),
                "hand",
                "collection.aquarium.changed");
    }

    /**
     * Donates one fish from an inventory slot after a confirmation screen; see {@link
     * AquariumDonationFlow#donateSlot}.
     *
     * @param changedKey message key for when the slot changed
     */
    CompletableFuture<Boolean> donateSlot(
            Player player, int slot, String source, String changedKey) {
        return flow.donateSlot(player, slot, source, changedKey);
    }

    /** Species donated this season (memory cache, progress screen). */
    Set<FishId> donatedSpecies() {
        return donatedSpecies;
    }

    // ------------------------------------------------------------------ donation box

    /**
     * For auto-save (Core ItemCustody): items put in an open donation box and items awaiting the
     * donation confirmation.
     */
    public List<ItemStack> held(Player player) {
        return flow.held(player);
    }

    @Override
    public void openDonationBox(Player player) {
        flow.open(player);
    }

    @EventHandler
    public void onBoxClose(InventoryCloseEvent event) {
        flow.onClose(event);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        flow.onQuit(event);
    }

    // ------------------------------------------------------------------ donating multiple fish

    @Override
    public CompletableFuture<DonationResult> donateItems(
            Player player, List<ItemStack> items, String source) {
        UUID uuid = player.getUniqueId();
        CompletableFuture<?> running = busy.get(uuid);
        if (running != null && !running.isDone()) {
            // Before the previous donation commits the species cap (memory value) could be
            // exceeded, so reject and give the items back
            returns.giveBack(uuid, items, RETURN_SOURCE);
            api.messages().send(player, "collection.aquarium.busy");
            return CompletableFuture.completedFuture(
                    new DonationResult(0, 0, countOf(items), false));
        }
        List<ItemStack> giveBack = new ArrayList<>();
        List<Candidate> list = candidates(player, items, giveBack);
        if (list.isEmpty()) {
            returns.giveBack(uuid, giveBack, RETURN_SOURCE);
            return CompletableFuture.completedFuture(
                    new DonationResult(0, 0, countOf(giveBack), false));
        }
        SeasonId season = api.players().get(player).season();
        AquariumRules rules = rules();
        boolean questOwned = source.startsWith("quest:");
        CompletableFuture<int[]> work =
                api.database()
                        .async(
                                c -> {
                                    // Lock the season row to serialize donations: when two
                                    // players donated the same new species at once, both got
                                    // the "first donation" bonus
                                    AquariumTx.lockSeason(c, season);
                                    int[] points = new int[list.size()];
                                    for (int i = 0; i < list.size(); i++) {
                                        Candidate cand = list.get(i);
                                        AquariumTx.ledger(c, cand.item().uid(), questOwned);
                                        boolean first =
                                                cand.counted()
                                                        && !AquariumTx.speciesDonated(
                                                                c, season, cand.fish().id());
                                        int p =
                                                cand.counted()
                                                        ? rules.pointsFor(
                                                                cand.fish().grade(),
                                                                cand.item().quality().stars(),
                                                                first)
                                                        : 0;
                                        CollectionRepository.donate(
                                                c,
                                                season,
                                                uuid,
                                                cand.fish().id(),
                                                cand.item().sizeCm(),
                                                cand.item().quality().stars(),
                                                cand.item().uid(),
                                                p);
                                        points[i] = p;
                                    }
                                    return points;
                                });
        busy.put(uuid, work);
        return work.handleAsync(
                (points, error) -> {
                    busy.remove(uuid, work);
                    // Donation records commit immediately but the inventory is write-behind: save
                    // soon
                    // so donated fish don't come back if the server dies (the inventory also
                    // changed
                    // when items were given back on failure)
                    api.players().saveSoon(uuid);
                    if (error != null || points == null) {
                        log.warn(
                                "수족관 기부 실패, 아이템을 돌려줍니다: {} {} {}",
                                player.getName(),
                                source,
                                String.valueOf(error == null ? null : error.getMessage()));
                        List<ItemStack> all = new ArrayList<>(giveBack);
                        list.forEach(c -> all.add(c.stack()));
                        returns.giveBack(uuid, all, RETURN_SOURCE);
                        if (player.isOnline()) {
                            api.messages().send(player, "collection.aquarium.failed");
                        }
                        return new DonationResult(0, 0, countOf(all), true);
                    }
                    returns.giveBack(uuid, giveBack, RETURN_SOURCE);
                    int total = 0;
                    Optional<HarborPlayer> hp = api.players().get(uuid);
                    Set<FishId> newSpecies = new HashSet<>(donatedSpecies);
                    for (int i = 0; i < list.size(); i++) {
                        Candidate cand = list.get(i);
                        total += points[i];
                        newSpecies.add(cand.fish().id());
                        hp.ifPresent(
                                h ->
                                        h.set(
                                                CollectionManager.COMPONENT,
                                                h.get(CollectionManager.COMPONENT)
                                                        .withDonation(cand.fish().id())));
                        Bukkit.getPluginManager()
                                .callEvent(
                                        new AquariumDonatedEvent(
                                                uuid,
                                                cand.fish().id(),
                                                cand.fish().grade(),
                                                cand.item().quality(),
                                                points[i]));
                    }
                    donatedSpecies = Set.copyOf(newSpecies);
                    if (player.isOnline()) {
                        if (list.size() == 1) {
                            api.messages()
                                    .send(
                                            player,
                                            "collection.aquarium.donated",
                                            Placeholder.unparsed(
                                                    "name", list.getFirst().fish().name()),
                                            Placeholder.unparsed("points", String.valueOf(total)));
                        } else {
                            api.messages()
                                    .send(
                                            player,
                                            "collection.aquarium.donated_many",
                                            Placeholder.unparsed(
                                                    "count", String.valueOf(list.size())),
                                            Placeholder.unparsed("points", String.valueOf(total)));
                        }
                    }
                    var _ = tasks.async(this::checkStage);
                    return new DonationResult(list.size(), total, countOf(giveBack), false);
                },
                tasks.main());
    }

    /**
     * Splits items into single fish. Items that are not fish or cannot be donated go into giveBack.
     * The personal cap (N of the same species per season) also counts the order within the batch.
     */
    List<Candidate> candidates(Player player, List<ItemStack> items, List<ItemStack> giveBack) {
        HarborPlayer hp = api.players().get(player);
        Map<FishId, Integer> counts =
                new HashMap<>(hp.get(CollectionManager.COMPONENT).donations());
        int cap = rules().perPlayerSpeciesCap();
        List<Candidate> result = new ArrayList<>();
        for (ItemStack stack : items) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            Optional<HarborItem.Fish> item = fishOf(stack);
            Optional<FishDef> fish =
                    item.flatMap(i -> api.content().snapshot().get(FishDef.TYPE, i.species().id()));
            if (item.isEmpty() || fish.isEmpty() || fish.get().releaseOnly()) {
                giveBack.add(stack);
                continue;
            }
            for (int n = 0; n < stack.getAmount(); n++) {
                int already = counts.merge(fish.get().id(), 1, Integer::sum) - 1;
                result.add(new Candidate(stack.asOne(), item.get(), fish.get(), already < cap));
            }
        }
        return result;
    }

    Optional<HarborItem.Fish> fishOf(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        return api.items()
                .read(stack)
                .filter(i -> i instanceof HarborItem.Fish)
                .map(i -> (HarborItem.Fish) i);
    }

    private static int countOf(List<ItemStack> items) {
        return items.stream().mapToInt(ItemStack::getAmount).sum();
    }

    // ------------------------------------------------------------------ stages

    /**
     * Virtual thread: rechecks the donation rate and, if the stage can rise, raises it exactly once
     * via a distributed lock plus a conditional UPDATE.
     */
    void checkStage() {
        SeasonId season = api.infra().season();
        Optional<DistributedLock.Handle> lock;
        try {
            lock = api.locks().tryAcquire("harbor:lock:aquarium", Duration.ofSeconds(30));
        } catch (RuntimeException e) {
            log.warn("수족관 잠금 실패: {}", String.valueOf(e.getMessage()));
            return;
        }
        if (lock.isEmpty()) {
            return;
        }
        try {
            AquariumState current =
                    api.database().tx(c -> CollectionRepository.aquarium(c, season));
            int donatable = donatable();
            double rate = donatable == 0 ? 0 : (double) current.speciesCount() / donatable;
            int target = current.stage();
            for (AquariumRules.Stage stage : rules().stages()) {
                if (rate >= stage.rate()) {
                    target = Math.max(target, stage.stage());
                }
            }
            int finalTarget = target;
            boolean raised =
                    target > current.stage()
                            && api.database()
                                    .tx(
                                            c ->
                                                    CollectionRepository.raiseStage(
                                                            c, season, finalTarget));
            AquariumState next =
                    new AquariumState(
                            current.points(),
                            current.speciesCount(),
                            raised ? target : current.stage());
            tasks.main()
                    .execute(
                            () -> {
                                state = next;
                                if (raised) {
                                    onStageRaised(season, finalTarget);
                                }
                                api.bus()
                                        .publish(
                                                UPDATES,
                                                new AquariumUpdated(
                                                        season.value(),
                                                        next.speciesCount(),
                                                        next.stage()));
                                refresh();
                            });
        } catch (SQLException e) {
            log.error("수족관 단계 확인 실패", e);
        } finally {
            lock.get().release();
        }
    }

    /** Only on the server that raised the stage: server flags, event, announcement. */
    private void onStageRaised(SeasonId season, int stage) {
        applyBuffs(stage);
        for (AquariumRules.Stage s : rules().stages()) {
            if (s.stage() <= stage && !s.flag().isEmpty()) {
                var _ = api.flags().setServer(s.flag(), "1", true);
            }
        }
        Bukkit.getPluginManager().callEvent(new AquariumStageChangedEvent(season.value(), stage));
        Component msg =
                api.messages()
                        .get(
                                "collection.announce.aquarium_stage",
                                Placeholder.unparsed("stage", String.valueOf(stage)));
        api.bus()
                .publish(
                        Channels.ANNOUNCEMENTS,
                        new Channels.Announcement(MiniMessage.miniMessage().serialize(msg)));
    }

    /** Stage buffs (source aquarium_stage, server-wide modifiers): registered by replacement. */
    private void applyBuffs(int stage) {
        api.modifiers().removeGlobalSource(BUFF_SOURCE);
        for (AquariumRules.Stage s : rules().stages()) {
            if (s.stage() <= stage) {
                for (StatBonus bonus : s.modifiers()) {
                    api.modifiers()
                            .addGlobal(Modifier.add(bonus.stat(), bonus.value(), BUFF_SOURCE));
                }
            }
        }
    }

    @EventHandler
    public void onReload(ContentReloadedEvent event) {
        applyBuffs(state.stage());
        tanks.reload();
    }

    /** Admin: recount from the donation records. */
    public CompletableFuture<Void> recount() {
        SeasonId season = api.infra().season();
        return api.database()
                .asyncVoid(c -> CollectionRepository.recount(c, season))
                .thenRunAsync(this::refresh, tasks.main());
    }

    /** Admin: force a stage (raise only). */
    public CompletableFuture<Boolean> forceStage(int stage) {
        SeasonId season = api.infra().season();
        return api.database()
                .async(c -> CollectionRepository.raiseStage(c, season, stage))
                .thenApplyAsync(
                        raised -> {
                            if (raised) {
                                state =
                                        new AquariumState(
                                                state.points(), state.speciesCount(), stage);
                                onStageRaised(season, stage);
                                refresh();
                            }
                            return raised;
                        },
                        tasks.main());
    }
}
