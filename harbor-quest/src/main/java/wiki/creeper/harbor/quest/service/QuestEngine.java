package wiki.creeper.harbor.quest.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.db.SqlFunction;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.PlayerComponent;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.quest.api.ObjectiveSignal;
import wiki.creeper.harbor.quest.api.ObjectiveTypeRegistry;
import wiki.creeper.harbor.quest.api.QuestService;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.api.QuestView;
import wiki.creeper.harbor.quest.api.RegionProgressProvider;
import wiki.creeper.harbor.quest.content.BoardRules;
import wiki.creeper.harbor.quest.content.ChronicleDef;
import wiki.creeper.harbor.quest.content.Objective;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.content.QuestType;
import wiki.creeper.harbor.quest.content.Rewards;
import wiki.creeper.harbor.quest.data.QuestData;
import wiki.creeper.harbor.quest.data.QuestEntry;
import wiki.creeper.harbor.quest.data.QuestRepository;
import wiki.creeper.harbor.quest.logic.Cycles;
import wiki.creeper.harbor.quest.logic.Progress;
import wiki.creeper.harbor.quest.logic.Requirements;

/**
 * Quest engine (docs 20 6.4). The entry point other modules and the UI see; the work is done by
 * collaborators: state computation {@link QuestAvailability}, accept/abandon {@link
 * QuestLifecycle}, objective progress {@link ObjectiveProgressor}, notifications {@link
 * QuestNotifier}, texts {@link QuestTexts}, migration and admin {@link QuestMaintenance}, external
 * objective types {@link CustomObjectives}. Turn-in and rewards are {@link RewardPayer}, the board
 * is {@link BoardManager}. Player data is read and written on the main thread only.
 */
public final class QuestEngine
        implements QuestService, ObjectiveSignal, ObjectiveTypeRegistry, RegionProgressProvider {

    /**
     * Quest cache. Writes happen immediately on each state transition and progress goes through the
     * write-behind queue separately, so the component's save function is empty.
     */
    public static final PlayerComponent<QuestData> COMPONENT =
            new PlayerComponent<>("quest", QuestRepository::load, (c, uuid, season, data) -> {});

    /** Computed state (per-player cache). lockedWeek is the week when only the week remains. */
    public record Avail(QuestState state, int lockedWeek) {}

    final Plugin plugin;
    final HarborApi api;
    final Tasks tasks;
    final Logger log;
    final Deps deps;

    private final Map<UUID, CompletableFuture<?>> chains = new HashMap<>();
    private final Set<UUID> busy = new HashSet<>();

    private final QuestAvailability availability = new QuestAvailability(this);
    private final QuestLifecycle lifecycle = new QuestLifecycle(this);
    private final ObjectiveProgressor progressor = new ObjectiveProgressor(this);
    private final QuestNotifier notifier = new QuestNotifier(this);
    private final QuestTexts texts = new QuestTexts(this);
    private final QuestMaintenance maintenance = new QuestMaintenance(this);
    private final RegionLadder regions = new RegionLadder(this);
    private final CustomObjectives customs;

    private @Nullable RewardPayer payer;
    private @Nullable BoardManager board;
    private @Nullable Tracker tracker;
    private @Nullable ChronicleManager chronicle;

    public QuestEngine(Plugin plugin, HarborApi api, Tasks tasks, Logger log, Deps deps) {
        this.plugin = plugin;
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.deps = deps;
        this.customs = new CustomObjectives(log);
    }

    void attach(
            RewardPayer payer, BoardManager board, Tracker tracker, ChronicleManager chronicle) {
        this.payer = payer;
        this.board = board;
        this.tracker = tracker;
        this.chronicle = chronicle;
    }

    RewardPayer payer() {
        return java.util.Objects.requireNonNull(payer, "RewardPayer");
    }

    BoardManager board() {
        return java.util.Objects.requireNonNull(board, "BoardManager");
    }

    Tracker tracker() {
        return java.util.Objects.requireNonNull(tracker, "Tracker");
    }

    ChronicleManager chronicle() {
        return java.util.Objects.requireNonNull(chronicle, "ChronicleManager");
    }

    /** Main thread executor (applies async results for the UI and commands). */
    public java.util.concurrent.Executor mainExecutor() {
        return tasks.main();
    }

    // ------------------------------------------------------------------ Content

    public ContentSnapshot snapshot() {
        return api.content().snapshot();
    }

    public Optional<QuestDef> def(QuestId id) {
        return snapshot().get(QuestDef.TYPE, id.id());
    }

    public Collection<QuestDef> defs() {
        return snapshot().all(QuestDef.TYPE).values();
    }

    public BoardRules boardRules() {
        ContentSnapshot s = snapshot();
        return s.typeNames().contains(BoardRules.TYPE.name())
                ? s.single(BoardRules.TYPE)
                : new BoardRules(Map.of(), List.of(), 1, 100, null, null);
    }

    ChronicleDef chronicleDef() {
        ContentSnapshot s = snapshot();
        return s.typeNames().contains(ChronicleDef.TYPE.name())
                ? s.single(ChronicleDef.TYPE)
                : new ChronicleDef(List.of(), List.of(), null, 3, 150, 27);
    }

    public Balance balance() {
        return snapshot().single(Balance.TYPE);
    }

    // ------------------------------------------------------------------ Player data

    public int season() {
        return api.infra().season().value();
    }

    public QuestData data(UUID uuid) {
        return api.players().get(uuid).map(p -> p.get(COMPONENT)).orElse(QuestData.EMPTY);
    }

    void put(HarborPlayer hp, QuestData data) {
        hp.set(COMPONENT, data);
        invalidate(hp.uuid());
    }

    /** Discards the state cache and inverted index (recomputed on the next lookup). */
    public void invalidate(UUID uuid) {
        availability.invalidate(uuid);
    }

    public void invalidateAll() {
        availability.invalidateAll();
    }

    void forget(UUID uuid) {
        invalidate(uuid);
        chains.remove(uuid);
        busy.remove(uuid);
    }

    /** Makes the NPC overhead markers recompute. */
    void refreshMarkers(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            deps.markers().refresh(player);
        }
    }

    /** Repeat cycle key (based on the current time). */
    public String cycleOf(QuestDef def) {
        return Cycles.of(def.repeat(), api.time().gameDay(), season(), deps.clock().week());
    }

    /**
     * Guarantees per-player ordering of immediate writes (accept → complete → payout record never
     * swap). Result on the main thread.
     */
    <T> CompletableFuture<T> serial(UUID uuid, SqlFunction<T> work) {
        CompletableFuture<?> previous =
                chains.getOrDefault(uuid, CompletableFuture.completedFuture(null));
        CompletableFuture<T> next =
                previous.handle((r, e) -> null)
                        .thenCompose(ignored -> api.database().async(work))
                        .thenApplyAsync(r -> r, tasks.main());
        chains.put(uuid, next);
        return next;
    }

    static String queueKey(UUID uuid, QuestId quest) {
        return "player:" + uuid + ":quest:" + quest;
    }

    /**
     * Puts progress in the write-behind queue (only the last value per key is written; flushNow
     * writes it immediately on quit or server move).
     */
    void saveLater(UUID uuid, QuestEntry entry) {
        int season = entry.season();
        QuestId quest = entry.quest();
        QuestState state = entry.state();
        Progress progress = entry.progress();
        api.writeBehind()
                .enqueue(
                        queueKey(uuid, quest),
                        c -> QuestRepository.saveProgress(c, uuid, season, quest, state, progress));
    }

    /**
     * Before an immediate write, removes the scheduled progress save of the same quest (so stale
     * progress never overwrites a state transition).
     */
    void cancelPending(UUID uuid, QuestId quest) {
        api.writeBehind().enqueue(queueKey(uuid, quest), c -> {});
    }

    public boolean isBusy(UUID uuid) {
        return busy.contains(uuid);
    }

    boolean lock(UUID uuid) {
        return busy.add(uuid);
    }

    void unlock(UUID uuid) {
        busy.remove(uuid);
    }

    // ------------------------------------------------------------------ Collaborators

    QuestAvailability availability() {
        return availability;
    }

    QuestNotifier notifier() {
        return notifier;
    }

    ObjectiveProgressor progressor() {
        return progressor;
    }

    QuestTexts texts() {
        return texts;
    }

    boolean payerAttached() {
        return payer != null;
    }

    // ------------------------------------------------------------------ State computation
    // (QuestAvailability)

    /** Computed state (cached). */
    public Avail avail(HarborPlayer hp, QuestDef def) {
        return availability.avail(hp, def);
    }

    boolean inWindow(QuestDef def) {
        return availability.inWindow(def);
    }

    Requirements.Facts facts(HarborPlayer hp) {
        return availability.facts(hp);
    }

    /**
     * Objective inverted index: objective type → quests with a currently progressing objective of
     * that type (docs 20 6.4, only the current objective for sequence).
     */
    Map<String, List<QuestId>> index(HarborPlayer hp) {
        return availability.index(hp);
    }

    /**
     * Definitions and rows of quests in progress (ORPHANED ones whose definition disappeared are
     * excluded).
     */
    public List<Map.Entry<QuestDef, QuestEntry>> open(HarborPlayer hp) {
        return availability.open(hp);
    }

    /**
     * Cooldown before re-accepting the same cycle after abandoning (proposed value 10 minutes, in
     * memory).
     */
    Optional<Instant> abandonCooldown(UUID uuid, QuestDef def) {
        return availability.abandonCooldown(uuid, def);
    }

    // ------------------------------------------------------------------ QuestService

    @Override
    public QuestState state(UUID player, QuestId quest) {
        Optional<HarborPlayer> hp = api.players().get(player);
        Optional<QuestDef> def = def(quest);
        if (hp.isEmpty()) {
            return QuestState.LOCKED;
        }
        if (def.isEmpty()) {
            return hp.get().get(COMPONENT).entries().containsKey(quest)
                    ? QuestState.ORPHANED
                    : QuestState.LOCKED;
        }
        return avail(hp.get(), def.get()).state();
    }

    @Override
    public List<QuestView> active(UUID player) {
        Optional<HarborPlayer> hp = api.players().get(player);
        if (hp.isEmpty()) {
            return List.of();
        }
        List<QuestView> result = new ArrayList<>();
        for (Map.Entry<QuestDef, QuestEntry> e : open(hp.get())) {
            QuestDef d = e.getKey();
            result.add(
                    new QuestView(
                            d.id(),
                            d.name(),
                            d.type().key(),
                            avail(hp.get(), d).state(),
                            e.getValue().progress().counts(),
                            e.getValue().progress().cycle()));
        }
        return result;
    }

    @Override
    public boolean isCompleted(UUID player, QuestId quest) {
        return state(player, quest) == QuestState.COMPLETED;
    }

    @Override
    public CompletableFuture<Boolean> accept(Player player, QuestId quest) {
        return lifecycle.accept(player, quest);
    }

    /** Explains why it is locked (for a week lock, "opens in week N (D-n)"). */
    public void explainLocked(Player player, QuestDef def, Avail avail) {
        lifecycle.explainLocked(player, def, avail);
    }

    /** Days left until that week starts (D-n). */
    public long daysUntilWeek(int week) {
        return lifecycle.daysUntilWeek(week);
    }

    @Override
    public CompletableFuture<Boolean> abandon(Player player, QuestId quest) {
        return lifecycle.abandon(player, quest);
    }

    @Override
    public CompletableFuture<Boolean> turnIn(Player player, QuestId quest) {
        return payer().turnIn(player, quest);
    }

    @Override
    public boolean track(Player player, QuestId quest) {
        return lifecycle.track(player, quest);
    }

    // ------------------------------------------------------------------ Objective progress
    // (ObjectiveProgressor)

    /**
     * Common handling of objective progress (docs 20 6.4 applying progress).
     *
     * @param gauge if true, set to the reached value (value)
     */
    public void progress(
            Player player, String type, Predicate<Objective> match, int amount, boolean gauge) {
        progressor.progressWith(player, type, (d, o) -> match.test(o), amount, gauge);
    }

    /**
     * Check that also looks at the quest definition (talk target NPC = objective → npc field →
     * turn-in NPC).
     */
    public void progressWith(
            Player player,
            String type,
            BiPredicate<QuestDef, Objective> match,
            int amount,
            boolean gauge) {
        progressor.progressWith(player, type, match, amount, gauge);
    }

    /**
     * Quests with no tracked objectives or already filled ones (delivery-only requests etc.) go
     * straight to READY.
     */
    void checkReady(Player player, QuestDef def) {
        progressor.checkReady(player, def);
    }

    static boolean hasDeliver(QuestDef def) {
        return def.objectives().stream().anyMatch(o -> !o.tracked());
    }

    /**
     * Sets gauge and flag objectives to the current value (on accept, join, level up, codex entry,
     * flag change).
     */
    public void refreshGauges(Player player, @Nullable QuestId only) {
        progressor.refreshGauges(player, only);
    }

    /** Target NPC for talk, deliver and affinity: target → npc field → turn-in NPC. */
    public static Optional<NpcId> npcOf(QuestDef def, Objective o) {
        HarborId target = o.target().id();
        if (target != null && o.target().isId("npc")) {
            return Optional.of(new NpcId(target));
        }
        NpcId npc = o.npc();
        return npc != null ? Optional.of(npc) : def.turnInNpc();
    }

    /**
     * If the current objective is a cutscene, plays it (a cutscene that blocks progress; watching
     * to the end advances via CutsceneEndEvent).
     */
    void startCutsceneObjective(Player player, QuestDef def) {
        progressor.startCutsceneObjective(player, def);
    }

    void playCutscene(Player player, HarborId cutscene) {
        progressor.playCutscene(player, cutscene);
    }

    // ------------------------------------------------------------------ ObjectiveSignal ·
    // ObjectiveTypeRegistry

    @Override
    public void signal(Player player, String type, String target, int amount) {
        progress(player, type, o -> customs.matches(o, type, target), amount, false);
    }

    @Override
    public void set(Player player, String type, String target, int value) {
        progress(player, type, o -> customs.matches(o, type, target), value, true);
    }

    @Override
    public void register(Plugin owner, String key, Validator validator, Matcher matcher) {
        customs.register(owner, key, validator, matcher);
    }

    @Override
    public Set<String> types() {
        return customs.types();
    }

    /** Removes the types of a disabled plugin. */
    void unregisterOwner(Plugin owner) {
        customs.unregisterOwner(owner);
    }

    /** Content check: validates arguments of other modules' types. */
    public void validateCustom(QuestDef quest, Consumer<String> error) {
        customs.validate(quest, error);
    }

    // ------------------------------------------------------------------ RegionProgressProvider
    /**
     * The World implementation if registered, otherwise the Quest default implementation
     * (regions.yml unlock level + unlock quest).
     */
    public Optional<RegionId> highestRegion(UUID player) {
        return regions.highestRegion(player);
    }

    @Override
    public Optional<RegionId> highestUnlockedRegion(UUID player) {
        return regions.highestUnlockedRegion(player);
    }

    // ------------------------------------------------------------------ Texts (QuestTexts)

    static String plain(net.kyori.adventure.text.Component c) {
        return QuestTexts.plain(c);
    }

    /** Human-readable objective text (action bar, lists). */
    public String objectiveText(Player viewer, QuestDef def, Objective o) {
        return texts.objectiveText(viewer, def, o);
    }

    String targetName(Player viewer, QuestDef def, Objective o) {
        return texts.targetName(viewer, def, o);
    }

    // ------------------------------------------------------------------ Migration · period · admin
    // (QuestMaintenance)

    /**
     * Number of quests whose definition disappeared and number whose hash changed (reload report).
     */
    record MigrationReport(int changed, int orphaned) {}

    /** Progress migration on reload and join (by objective key). */
    MigrationReport migrate(HarborPlayer hp) {
        return maintenance.migrate(hp);
    }

    /** Every minute: event quests whose period ended become FAILED (docs 20 6.4 state machine). */
    void failExpiredEvents() {
        maintenance.failExpiredEvents();
    }

    /**
     * Admin command: forces a state (active · ready · completed · failed). Creates a current-season
     * row if there is none.
     */
    public CompletableFuture<Boolean> adminSet(Player player, QuestDef def, QuestState state) {
        return maintenance.adminSet(player, def, state);
    }

    /**
     * Completion flag {@code quest/<type>/<name>} (permanent for main quests, season-scoped
     * otherwise).
     */
    public CompletableFuture<Void> setCompletionFlag(UUID uuid, QuestDef def) {
        return maintenance.setCompletionFlag(uuid, def);
    }

    /** Admin command: edits an objective progress value. */
    public boolean adminProgress(Player player, QuestDef def, String key, int value) {
        return maintenance.adminProgress(player, def, key, value);
    }

    /** Admin command: deletes the row (main quests too). */
    public CompletableFuture<Boolean> adminReset(Player player, QuestId quest) {
        return maintenance.adminReset(player, quest);
    }

    static Rewards rewardsOf(QuestDef def) {
        return def.rewards();
    }

    static boolean isBoard(QuestType type) {
        return type.board();
    }
}
