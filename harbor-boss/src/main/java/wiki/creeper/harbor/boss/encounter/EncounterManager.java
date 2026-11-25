package wiki.creeper.harbor.boss.encounter;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.boss.api.BossRole;
import wiki.creeper.harbor.boss.api.BossService;
import wiki.creeper.harbor.boss.api.Contribution;
import wiki.creeper.harbor.boss.api.EncounterInfo;
import wiki.creeper.harbor.boss.api.EncounterState;
import wiki.creeper.harbor.boss.api.event.BossEncounterEndEvent;
import wiki.creeper.harbor.boss.api.event.BossPhaseChangeEvent;
import wiki.creeper.harbor.boss.content.BossBalance;
import wiki.creeper.harbor.boss.content.BossDef;
import wiki.creeper.harbor.boss.data.BossProfile;
import wiki.creeper.harbor.boss.data.BossRepository;
import wiki.creeper.harbor.boss.logic.Fight;
import wiki.creeper.harbor.boss.party.PartyManager;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.BossId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.PlayerComponent;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.api.FishingService;
import wiki.creeper.harbor.season.api.SeasonServices;
import wiki.creeper.harbor.season.api.WeeklyLimitService;

/**
 * Encounter lifecycle management (docs 20 10.4 EncounterManager). Summon checks → preparation →
 * start record → per-tick progress → end detection → results and rewards → cleanup. Start checks
 * live in {@link EncounterStarter}, DB records in {@link EncounterRecorder}, the result screen in
 * {@link EncounterResults}, part hit detection (harpoon trajectory) in {@link HarpoonTracker}, and
 * input (QTE, captain), leaving, rejoin and falling-into-water listeners in {@link #listener()}.
 * Implements {@link BossService}. Main thread only.
 */
public final class EncounterManager implements BossService {

    /** PlayerComponent&lt;BossProfile&gt;. */
    public static final PlayerComponent<BossProfile> PROFILE =
            new PlayerComponent<>(
                    "boss_profile", BossRepository::loadProfile, BossRepository::saveProfile);

    /** Click target binding. */
    record Click(Encounter encounter, Encounter.ClickKind kind, int index) {}

    private final HarborApi api;
    private final Plugin plugin;
    private final Tasks tasks;
    private final Logger log;
    private final BossItems items;
    private final PartyManager parties;
    private final FishingService fishing;
    private RewardService rewards;

    private final Map<UUID, Encounter> byId = new LinkedHashMap<>();
    private final Map<UUID, Encounter> byPlayer = new HashMap<>();
    private final Map<UUID, Click> clicks = new HashMap<>();
    private final HarpoonTracker harpoons = new HarpoonTracker(this);
    private final TempDecks decks = new TempDecks(this);
    private final ChunkTickets chunkTickets;
    private final EncounterStarter starter;
    private final EncounterRecorder recorder = new EncounterRecorder(this);
    private final EncounterResults results;
    private final EncounterListener listener;
    private @Nullable WorldHooks worldHooks;
    private @Nullable InstanceHooks instanceHooks;
    private long tick;

    /** World boss link (WorldBossManager). */
    public interface WorldHooks {
        void stage(EncounterInfo info, int stage, int participants, double percent);

        void ui(Component name, float progress);

        void ended(EncounterInfo info, EncounterState result);
    }

    /** Instance link (InstanceManager). */
    public interface InstanceHooks {
        void ended(UUID encounter, Map<UUID, String> origins, Collection<UUID> members);

        void quit(UUID player, UUID encounter);
    }

    public EncounterManager(
            HarborApi api,
            Plugin plugin,
            Tasks tasks,
            Logger log,
            PartyManager parties,
            FishingService fishing) {
        this.api = api;
        this.plugin = plugin;
        this.tasks = tasks;
        this.log = log;
        this.items = new BossItems(api);
        this.parties = parties;
        this.fishing = fishing;
        this.rewards = new RewardService(this);
        this.chunkTickets = new ChunkTickets(plugin);
        this.starter = new EncounterStarter(this, parties);
        this.results = new EncounterResults(api);
        this.listener = new EncounterListener(this);
    }

    public void start() {
        var _ = tasks.timer(1, 1, this::tick);
        recorder.recover();
    }

    /**
     * Encounter event listener (harpoon throw, summon, click, input, falling into water, join,
     * quit, reload). Registered by the plugin.
     */
    public Listener listener() {
        return listener;
    }

    public void setWorldHooks(@Nullable WorldHooks hooks) {
        this.worldHooks = hooks;
    }

    public void setInstanceHooks(@Nullable InstanceHooks hooks) {
        this.instanceHooks = hooks;
    }

    // ------------------------------------------------------------------ Shared
    // (Encounter, RewardService, collaborators)

    HarborApi api() {
        return api;
    }

    Plugin plugin() {
        return plugin;
    }

    Tasks tasks() {
        return tasks;
    }

    BossItems items() {
        return items;
    }

    FishingService fishing() {
        return fishing;
    }

    Logger log() {
        return log;
    }

    PartyManager parties() {
        return parties;
    }

    HarpoonTracker harpoons() {
        return harpoons;
    }

    TempDecks decks() {
        return decks;
    }

    @Nullable InstanceHooks instanceHooks() {
        return instanceHooks;
    }

    long now() {
        return tick;
    }

    String serverId() {
        return api.infra().serverId();
    }

    public RewardService rewards() {
        return rewards;
    }

    public BossBalance balance() {
        return new BossBalance(api.content().snapshot().single(Balance.TYPE));
    }

    /** Running encounters (an uncopied view, use read-only). */
    Collection<Encounter> active() {
        return byId.values();
    }

    /**
     * Registers a new encounter (EncounterStarter). Participants are linked separately via {@link
     * #bind}.
     */
    void register(Encounter e) {
        byId.put(e.id, e);
    }

    @Nullable Click click(UUID entity) {
        return clicks.get(entity);
    }

    // ------------------------------------------------------------------ BossService

    @Override
    public Collection<BossDef> definitions() {
        return api.content().snapshot().all(BossDef.TYPE).values();
    }

    @Override
    public Optional<BossDef> definition(BossId boss) {
        return api.content().snapshot().get(BossDef.TYPE, boss.id());
    }

    @Override
    public Optional<EncounterInfo> encounterOf(UUID player) {
        return encounterOfPlayer(player).map(Encounter::info);
    }

    Optional<Encounter> encounterOfPlayer(UUID player) {
        return Optional.ofNullable(byPlayer.get(player));
    }

    public Optional<Encounter> encounter(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public boolean inEncounter(UUID player) {
        Encounter e = byPlayer.get(player);
        return e != null && !e.finished();
    }

    @Override
    public List<EncounterInfo> encounters() {
        return byId.values().stream().map(Encounter::info).toList();
    }

    public Collection<Encounter> running() {
        return List.copyOf(byId.values());
    }

    @Override
    public StartResult forceStart(BossId bossId, Player leader) {
        Optional<BossDef> def = definition(bossId);
        if (def.isEmpty()) {
            return StartResult.UNKNOWN_BOSS;
        }
        if (def.get().isWorld()) {
            return StartResult.WRONG_SERVER;
        }
        List<Player> party = starter.partyPlayers(leader);
        Location from = BossItems.loc(leader);
        return starter.startField(def.get(), leader, party, null, true, from);
    }

    @Override
    public CompletableFuture<Optional<WeeklyRewards>> weeklyRewards(UUID player, BossId boss) {
        Optional<BossDef> def = definition(boss);
        if (def.isEmpty() || def.get().story()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        int limit = balance().weeklyLimit(def.get().type());
        return SeasonServices.limits()
                .remaining(player, WeeklyLimitService.bossKey(boss.id().name()), limit)
                .thenApplyAsync(
                        r -> Optional.of(new WeeklyRewards(r.used(), r.remaining(), r.limit())),
                        tasks.main());
    }

    @Override
    public boolean forceEnd(UUID encounterId, EncounterState result) {
        Encounter e = byId.get(encounterId);
        if (e == null || !result.ended()) {
            return false;
        }
        if (!e.launched()) {
            finishEarly(e, "boss.admin.ended");
            return true;
        }
        for (Fight.Transition t : e.fight.end(result, "admin")) {
            firePhase(e, t);
        }
        finish(e, result, "admin");
        return true;
    }

    // ------------------------------------------------------------------ Start (EncounterStarter)

    /**
     * Summon bait use (docs 20 10.4 step 1). Conditions: party size min-max, everyone within the
     * arena radius, a free slot, not under maintenance.
     */
    StartResult trySummon(Player leader, BossDef def) {
        return starter.trySummon(leader, def);
    }

    /** Instance server: starts a raid with the arrived party (InstanceManager). */
    public StartResult startInstance(
            BossDef def,
            int slot,
            Location center,
            UUID leader,
            List<Player> party,
            Map<UUID, String> origins) {
        return starter.startInstance(def, slot, center, leader, party, origins);
    }

    /** World boss start (WorldBossManager). Spawns immediately without a preparation stage. */
    public @Nullable Encounter startWorld(BossDef def, UUID eventId, Location center, long seed) {
        return starter.startWorld(def, eventId, center, seed);
    }

    /** Whether an instance cell is in use. */
    public boolean instanceSlotBusy(int slot) {
        return starter.instanceSlotBusy(slot);
    }

    // ------------------------------------------------------------------ Encounter callbacks

    void rememberRole(UUID player, BossRole role) {
        api.players()
                .get(player)
                .ifPresent(h -> h.set(PROFILE, h.get(PROFILE).withPreferred(role)));
    }

    /**
     * Writes the boss_encounter RUNNING row asynchronously. The result future completes on the main
     * thread.
     */
    CompletableFuture<Void> recordStart(Encounter e) {
        return recorder.recordStart(e);
    }

    void firePhase(Encounter e, Fight.Transition t) {
        Bukkit.getPluginManager()
                .callEvent(new BossPhaseChangeEvent(e.info(), t.from(), t.to(), t.cause()));
    }

    void bind(UUID player, Encounter e) {
        byPlayer.put(player, e);
    }

    void unbind(UUID player, Encounter e) {
        byPlayer.remove(player, e);
    }

    void bindClick(Interaction entity, Encounter e, Encounter.ClickKind kind, int index) {
        clicks.put(entity.getUniqueId(), new Click(e, kind, index));
    }

    void unbindClick(Entity entity) {
        clicks.remove(entity.getUniqueId());
    }

    /**
     * Adds plugin chunk tickets to arena chunks during an encounter (prevents unloading, 10.4).
     * Counted when several encounters use the same chunk.
     */
    void chunkHold(Encounter e, boolean hold) {
        chunkTickets.hold(e, hold);
    }

    void worldStage(Encounter e, int stage) {
        WorldHooks hooks = worldHooks;
        if (hooks != null) {
            hooks.stage(e.info(), stage, e.effectiveParticipants(), e.fight.hpRatio() * 100);
        }
    }

    void worldUi(Encounter e, Component name, float progress) {
        WorldHooks hooks = worldHooks;
        if (hooks != null) {
            hooks.ui(name, progress);
        }
    }

    /**
     * Ended before departure (no bait, admin end). No rewards or records; if the bait was already
     * taken during departure, it is refunded.
     */
    void finishEarly(Encounter e, String messageKey) {
        rewards.refundIfTaken(e);
        e.broadcast(messageKey);
        e.cleanup();
        byId.remove(e.id);
        byPlayer.values().removeIf(x -> x == e);
    }

    /**
     * End (docs 20 10.4 step 6): record the result → BossEncounterEndEvent → result screen → reward
     * transaction → remove rig and Interactions → restore the world border.
     */
    void finish(Encounter e, EncounterState result, String cause) {
        if (e.finished()) {
            return;
        }
        List<Contribution> contributions = e.contributions();
        EncounterInfo info = e.info();
        e.cleanup();
        byId.remove(e.id);
        byPlayer.values().removeIf(x -> x == e);
        long durationMs = e.fight.elapsed() * 50L;
        Instant endedAt = Instant.now();
        boolean releaseOnly = false;
        var fishId = e.def.fish();
        if (fishId != null) {
            releaseOnly =
                    api.content()
                            .snapshot()
                            .get(FishDef.TYPE, fishId.id())
                            .map(FishDef::releaseOnly)
                            .orElse(false);
        }
        Bukkit.getPluginManager()
                .callEvent(
                        new BossEncounterEndEvent(
                                info,
                                result,
                                e.def.type(),
                                contributions,
                                fishId,
                                releaseOnly,
                                durationMs,
                                e.def.story()));
        int size = contributions.size();
        recorder.recordEnd(e, result, endedAt, durationMs, contributions);
        // The result screen and effects are cosmetic, so an exception there must not block rewards
        // or follow-up handling
        try {
            results.showResult(e, result, contributions);
            results.playOutro(e, result);
        } catch (RuntimeException ex) {
            log.error("보스 결과 연출 오류 {}", e.id, ex);
        }
        try {
            rewards.settle(e, info, result, contributions, durationMs, endedAt, releaseOnly);
        } catch (RuntimeException ex) {
            // The instance return and world boss cleanup hooks must still run so players are not
            // left in the arena
            log.error("보스 보상 정산 오류 {}", e.id, ex);
        }
        if (e.def.isWorld()) {
            WorldHooks hooks = worldHooks;
            if (hooks != null) {
                hooks.ended(info, result);
            }
        }
        if (e.def.arena().mode() == BossDef.ArenaMode.INSTANCE) {
            InstanceHooks hooks = instanceHooks;
            if (hooks != null) {
                hooks.ended(e.id, e.origins, info.members());
            }
        }
        log.info(
                "보스 조우 종료 {} {} {} ({}ms, {}명, {})",
                e.def.id(),
                e.id,
                result,
                durationMs,
                size,
                cause);
    }

    static String formatMs(long ms) {
        long s = ms / 1000;
        return String.format("%d:%02d.%d", s / 60, s % 60, (ms % 1000) / 100);
    }

    // ------------------------------------------------------------------ Tick

    private void tick() {
        tick++;
        for (Encounter e : List.copyOf(byId.values())) {
            try {
                e.tick(tick);
            } catch (RuntimeException ex) {
                log.error("보스 조우 틱 오류 {} — 조우를 중단합니다", e.id, ex);
                if (!e.finished()) {
                    for (Fight.Transition t : e.fight.end(EncounterState.ABORTED, "error")) {
                        firePhase(e, t);
                    }
                    finish(e, EncounterState.ABORTED, "error");
                }
            }
        }
        harpoons.track();
        if (tick % 20 == 0) {
            decks.tick(tick);
        }
        if (tick % 1200 == 0) {
            parties.purgeInvites(System.currentTimeMillis());
            starter.purgeCooldowns(tick);
        }
    }

    // ------------------------------------------------------------------ End

    /**
     * Server shutdown → ABORTED (bait refunded via pending). Writes to the DB directly since the
     * server is shutting down.
     */
    public void shutdown() {
        for (Encounter e : List.copyOf(byId.values())) {
            if (e.def.isWorld()) {
                // World bosses are left RUNNING for resume
                e.cleanup();
                continue;
            }
            e.fight.end(EncounterState.ABORTED, "shutdown");
            e.cleanup();
            recorder.abortOnShutdown(e);
        }
        byId.clear();
        byPlayer.clear();
        harpoons.clear();
        decks.shutdown();
    }

    /** Role selection during preparation (/boss role). False if no encounter is in preparation. */
    public boolean chooseRole(UUID player, BossRole role) {
        Encounter e = byPlayer.get(player);
        return e != null && e.chooseRole(player, role);
    }

    /** Move blocking (HandoffBlocker "encounter"). */
    public boolean blocksHandoff(Player p) {
        Encounter e = byPlayer.get(p.getUniqueId());
        if (e == null || e.finished()) {
            return false;
        }
        return e.member(p.getUniqueId()).map(m -> !m.dropped).orElse(false);
    }

    /** /harbor boss debug. */
    public List<String> debug(UUID encounterId) {
        Encounter e = byId.get(encounterId);
        if (e == null) {
            return List.of();
        }
        return EncounterDebug.lines(e);
    }
}
