package wiki.creeper.harbor.fishing.session;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.random.RandomGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.content.type.RegionDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.core.api.HandoffBlocker;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.MailService;
import wiki.creeper.harbor.core.api.PlayerComponent;
import wiki.creeper.harbor.core.api.event.ContentReloadedEvent;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.Locs;
import wiki.creeper.harbor.fishing.api.CatchOptions;
import wiki.creeper.harbor.fishing.api.CatchPresentationOverride;
import wiki.creeper.harbor.fishing.api.CatchResult;
import wiki.creeper.harbor.fishing.api.CollectionProvider;
import wiki.creeper.harbor.fishing.api.FishingService;
import wiki.creeper.harbor.fishing.api.FoodBuff;
import wiki.creeper.harbor.fishing.api.MinigameResult;
import wiki.creeper.harbor.fishing.api.RegionKeyResolver;
import wiki.creeper.harbor.fishing.api.SeaEventMessage;
import wiki.creeper.harbor.fishing.api.SessionInfo;
import wiki.creeper.harbor.fishing.api.SessionState;
import wiki.creeper.harbor.fishing.api.ShadowDriver;
import wiki.creeper.harbor.fishing.api.TensionSession;
import wiki.creeper.harbor.fishing.api.TensionSpec;
import wiki.creeper.harbor.fishing.api.TrapService;
import wiki.creeper.harbor.fishing.api.TreasureDropHook;
import wiki.creeper.harbor.fishing.api.event.FishingSessionEndEvent;
import wiki.creeper.harbor.fishing.content.FishingBalance;
import wiki.creeper.harbor.fishing.data.FishingData;
import wiki.creeper.harbor.fishing.data.FishingRepository;
import wiki.creeper.harbor.fishing.food.FoodManager;
import wiki.creeper.harbor.fishing.present.CatchPresenter;
import wiki.creeper.harbor.fishing.present.DisplayShadowPresenter;
import wiki.creeper.harbor.fishing.present.RigShadowPresenter;
import wiki.creeper.harbor.fishing.present.ShadowPresenter;
import wiki.creeper.harbor.fishing.roll.RollContext;
import wiki.creeper.harbor.fishing.roll.RollEngine;
import wiki.creeper.harbor.fishing.roll.RollModifiers;
import wiki.creeper.harbor.fishing.sea.MythicCounter;
import wiki.creeper.harbor.fishing.sea.SeaEventBook;
import wiki.creeper.harbor.fishing.trap.TrapManager;
import wiki.creeper.harbor.jobs.api.JobsService;

/**
 * Rolls and progress of a single fishing attempt (docs 20 3.4). Connects vanilla fishing events to
 * the session state machine. All vanilla loot is cancelled and every catch goes through the
 * minigame. Main thread only.
 *
 * <p>Flow: throw (CAST, sets the wait time) → bobber hits water (spawn roll, FishingCastEvent,
 * shadow approach, WAITING) → bite (BITE) → hook (HOOKED, bait deduction) → minigame (MINIGAME) →
 * result (RESULT, size/quality/item/junk/EXP/presentation) → IDLE.
 *
 * <p>Minigame controls: holding Shift (sneak) reels, A/D follows the fish direction (Player Input
 * sync). A camera-rotation mode can be chosen in settings, and it is automatically camera rotation
 * while riding a vehicle. The docs' "hold the rod consumable" approach will be switched to after
 * Phase 1 confirms whether it conflicts with vanilla rod behaviour (docs 20 3.8 open question).
 */
public final class FishingManager implements FishingService, Listener {

    public static final PlayerComponent<FishingData> COMPONENT =
            new PlayerComponent<>("fishing", FishingRepository::load, FishingRepository::save);

    private final Plugin plugin;
    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final MacroGuard macro;
    private final Map<UUID, FishingSession> sessions = new HashMap<>();
    private final Map<UUID, TensionSessionImpl> tensions = new HashMap<>();
    private final RandomGenerator random = RandomGenerator.getDefault();
    private final SeaEventBook seaEvents = new SeaEventBook();
    private final MythicCounter mythicCounter;
    private final ShadowPresenter shadows;
    private final CatchPresenter presenter;
    private final MinigameStats stats = new MinigameStats();
    private final TrapManager traps;
    private final CastContext ctx;
    private final FishingGear gear;
    private final MythicHolds mythic;
    private final MinigameDisplay display;
    private final CastHandler casts;
    private final CatchRewards rewards;
    private final MinigameRunner minigames;
    private final FoodManager food;
    private RollEngine engine;
    private long ticks;

    public FishingManager(Plugin plugin, HarborApi api, Tasks tasks, JobsService jobs, Logger log) {
        this.plugin = plugin;
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.macro = new MacroGuard(api, tasks, log);
        this.ctx = new CastContext(api, seaEvents, log);
        this.engine = new RollEngine(api.content().snapshot(), balance());
        this.mythicCounter = new MythicCounter(api.database());
        // Shadow: Core rig (species rig → grade default rig); a single ItemDisplay if there is no
        // rig
        this.shadows =
                new RigShadowPresenter(
                        api.rigs(), new DisplayShadowPresenter(plugin, tasks), tasks);
        this.presenter = new CatchPresenter(plugin, api, tasks);
        this.traps = new TrapManager(plugin, api, tasks, jobs, this, log);
        this.gear = new FishingGear(plugin, api, tasks, ctx);
        this.mythic = new MythicHolds(api, tasks, log, mythicCounter, ctx, this::engine, random);
        this.display = new MinigameDisplay(plugin, api, tasks, presenter, ctx);
        this.casts =
                new CastHandler(
                        api,
                        macro,
                        ctx,
                        gear,
                        mythic,
                        display,
                        presenter,
                        shadows,
                        this::engine,
                        random,
                        tensions);
        this.rewards = new CatchRewards(api, jobs, log, ctx, gear, presenter, random);
        this.minigames =
                new MinigameRunner(
                        api, ctx, gear, mythic, display, casts, rewards, presenter, stats, random,
                        sessions, tensions);
        this.food = new FoodManager(api, COMPONENT, log);
    }

    /** Food buffs (listener registered by the plugin). */
    public FoodManager food() {
        return food;
    }

    /** Paper marks location lookups as nullable, but an online entity always has a location. */
    static Location loc(Entity entity) {
        return Locs.of(entity);
    }

    public void start() {
        var _ = tasks.timer(1, 1, this::tick);
        // harbor:event sea events (published by World). The handler runs on a Lettuce thread, so
        // hand off to the main thread
        api.bus()
                .subscribe(
                        SeaEventMessage.CHANNEL,
                        envelope -> {
                            var _ =
                                    tasks.sync(
                                            () ->
                                                    seaEvents.apply(
                                                            envelope.payload(), Instant.now()));
                        });
        traps.start();
    }

    public TrapManager trapManager() {
        return traps;
    }

    /**
     * Before a server move (Core HandoffBlocker): if in a minigame, ends it as a failure and allows
     * the move.
     */
    public HandoffBlocker handoffBlocker() {
        return (player, destination) -> {
            abort(player, MinigameResult.ESCAPED);
            TensionSessionImpl t = tensions.get(player.getUniqueId());
            if (t != null) {
                t.close(MinigameResult.ESCAPED);
            }
            return traps.handoffVerdict(player);
        };
    }

    public FishingBalance balance() {
        return ctx.balance();
    }

    FishingSession sessionOf(Player player) {
        return sessions.computeIfAbsent(player.getUniqueId(), k -> new FishingSession());
    }

    MacroGuard macro() {
        return macro;
    }

    public RollEngine engine() {
        return engine;
    }

    MinigameStats stats() {
        return stats;
    }

    SeaEventBook seaEvents() {
        return seaEvents;
    }

    MythicCounter mythicCounter() {
        return mythicCounter;
    }

    // ------------------------------------------------------------------ FishingService

    @Override
    public SessionState session(UUID player) {
        FishingSession s = sessions.get(player);
        return s == null ? SessionState.IDLE : s.state;
    }

    @Override
    public boolean isFishing(UUID player) {
        return session(player) != SessionState.IDLE;
    }

    @Override
    public int pity(UUID player, FishId fish) {
        return api.players().get(player).map(p -> p.get(COMPONENT).pity(fish)).orElse(0);
    }

    @Override
    public int missedBigCount(UUID player) {
        FishingSession s = sessions.get(player);
        return s == null ? 0 : s.missedBig;
    }

    @Override
    public SessionInfo sessionInfo(UUID player) {
        FishingSession s = sessions.get(player);
        TensionSessionImpl t = tensions.get(player);
        boolean tensionOn = t != null && t.isActive();
        Minigame game = s == null || s.state != SessionState.MINIGAME ? null : s.minigame;
        double tension = 0;
        double hp = 1;
        if (game != null) {
            tension = game.tension();
            hp = game.hpRatio();
        } else if (t != null && tensionOn) {
            tension = t.tension();
            hp = t.hpRatio();
        }
        if (s == null) {
            return new SessionInfo(
                    SessionState.IDLE,
                    null,
                    false,
                    false,
                    tensionOn,
                    tension,
                    hp,
                    null,
                    null,
                    null);
        }
        FishDef fish = s.fish;
        FishDef forced = s.forcedFish;
        return new SessionInfo(
                s.state,
                fish == null ? null : fish.id(),
                s.state == SessionState.BITE,
                game != null,
                tensionOn,
                tension,
                hp,
                s.region,
                s.bait,
                forced == null ? null : forced.id());
    }

    @Override
    public boolean forceNextFish(Player player, FishId fish, @Nullable Integer quality) {
        Optional<FishDef> def = snapshot().get(FishDef.TYPE, fish.id());
        if (def.isEmpty()) {
            return false;
        }
        // Same fields as /harbor fishing force (consumed by the next cast and result)
        FishingSession s = sessionOf(player);
        s.forcedFish = def.get();
        s.forcedQuality =
                quality == null ? null : Quality.of(Math.clamp(quality, Quality.MIN, Quality.MAX));
        return true;
    }

    @Override
    public boolean clearForcedFish(UUID player) {
        FishingSession s = sessions.get(player);
        if (s == null || s.forcedFish == null) {
            return false;
        }
        s.forcedFish = null;
        s.forcedQuality = null;
        return true;
    }

    @Override
    public boolean setPity(UUID player, FishId fish, int casts) {
        Optional<HarborPlayer> hp = api.players().get(player);
        if (hp.isEmpty()) {
            return false;
        }
        // Same as /harbor fishing pity ... set: the component is marked dirty and saved by the
        // write-behind auto save (pity_counter)
        hp.get().set(COMPONENT, hp.get().get(COMPONENT).withPity(fish, Math.max(0, casts)));
        return true;
    }

    @Override
    public void resetMissedBig(UUID player) {
        FishingSession s = sessions.get(player);
        if (s != null) {
            s.missedBig = 0;
        }
    }

    @Override
    public Optional<FoodBuff> foodBuff(UUID player) {
        return api.players()
                .get(player)
                .flatMap(hp -> Optional.ofNullable(hp.get(COMPONENT).food()))
                .filter(f -> f.isActive(Instant.now()))
                .map(f -> new FoodBuff(f.item(), f.expiresAt()));
    }

    @Override
    public boolean applyFood(Player player, ItemId item) {
        return food.apply(player, item);
    }

    @Override
    public boolean clearFood(Player player) {
        return food.clear(player);
    }

    @Override
    public void registerCollection(CollectionProvider provider) {
        ctx.registerCollection(provider);
    }

    @Override
    public void registerRegionResolver(RegionKeyResolver resolver) {
        ctx.registerRegionResolver(resolver);
    }

    @Override
    public void registerTreasureHook(TreasureDropHook hook) {
        rewards.registerTreasureHook(hook);
    }

    @Override
    public void registerPresentation(CatchPresentationOverride override) {
        rewards.registerPresentation(override);
    }

    @Override
    public boolean claimShadow(UUID player, ShadowDriver driver) {
        FishingSession s = sessions.get(player);
        Player p = Bukkit.getPlayer(player);
        FishDef fish = s == null ? null : s.fish;
        Location hook = s == null ? null : s.hookLoc;
        if (s == null || p == null || fish == null || hook == null) {
            return false;
        }
        if (s.state != SessionState.WAITING) {
            return false;
        }
        int remaining = Math.max(20, s.waitTicks - s.castTicks);
        ShadowControl control = s.shadow;
        if (control != null) {
            return control.claim(driver, remaining);
        }
        s.shadow =
                new ShadowControl(
                        shadows,
                        p,
                        fish,
                        ctx.presets().of(fish.grade()),
                        false,
                        hook,
                        remaining,
                        driver,
                        random);
        return true;
    }

    @Override
    public TrapService traps() {
        return traps;
    }

    @Override
    public CompletableFuture<SimulationResult> simulate(RegionId region, int count) {
        RollEngine snapshotEngine = engine;
        return tasks.supplyAsync(() -> Simulation.run(snapshotEngine, region, count));
    }

    @Override
    public double expectedShare(RegionId region, FishId fish) {
        return engine.expectedShare(region, fish);
    }

    @Override
    public Set<String> activeSeaEvents() {
        return seaEvents.ids(Instant.now());
    }

    @Override
    public Optional<TensionSession> openTension(Player player, TensionSpec spec) {
        return minigames.openTension(player, spec);
    }

    @Override
    public Optional<CatchResult> createCatch(Player player, FishId fishId, CatchOptions options) {
        return rewards.createCatch(player, fishId, options);
    }

    // ------------------------------------------------------------------ Context

    /**
     * Region of a location (regions.yml bounds). World names can be mapped to harbor_main etc. via
     * balance.yml world_alias (for development servers).
     */
    public Optional<RegionDef> regionAt(Location loc) {
        return ctx.regionAt(loc);
    }

    /**
     * Region key at the bobber location. The RegionKeyResolver registered by World first, otherwise
     * regions.yml.
     */
    Optional<RegionKeyResolver.RegionKey> regionKey(Location bobber) {
        return ctx.regionKey(bobber);
    }

    boolean areasKnown() {
        return ctx.areasKnown();
    }

    RollContext context(Player player, RegionKeyResolver.RegionKey key, @Nullable ItemId bait) {
        return ctx.context(player, key, bait);
    }

    /** Roll modifiers (category product, "7. Balance design" 4.2). */
    RollModifiers modifiers(UUID uuid, Map<String, Double> gearStats, RegionId region) {
        return ctx.modifiers(uuid, gearStats, region);
    }

    double stat(UUID uuid, String key) {
        return ctx.stat(uuid, key);
    }

    Optional<ItemDef> itemDef(@Nullable ItemStack stack) {
        return ctx.itemDef(stack);
    }

    /**
     * If the inventory is full, drops it at the feet and lets only the owner pick it up for 60
     * seconds. For items given after a DB commit, such as trap harvests, retrievals and refunds. If
     * the player left in the meantime or the server-move snapshot was already taken, putting it in
     * the inventory would not be saved (it is already gone from the DB), so it is sent by mail. If
     * there is no mailbox, it is only logged.
     */
    public void give(Player player, ItemStack stack) {
        UUID uuid = player.getUniqueId();
        if (player.isOnline() && !api.players().frozen(uuid)) {
            gear.give(player, stack);
            return;
        }
        Optional<MailService> mail = api.mail();
        if (mail.isEmpty()) {
            log.error("우편함이 없어 통발 아이템을 줄 수 없습니다 (관리자 복구 필요): {} {}", uuid, stack);
            return;
        }
        String ref = "trap_give:" + UUID.randomUUID();
        var _ =
                mail.get()
                        .sendItem(uuid, stack, "trap_give", ref)
                        .exceptionally(
                                e -> {
                                    log.error("통발 아이템 우편 실패 (관리자 복구 필요): {} {}", uuid, stack, e);
                                    return null;
                                });
    }

    // ------------------------------------------------------------------ Fishing events

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        FishingSession s = sessionOf(player);
        switch (event.getState()) {
            case FISHING -> casts.cast(event, player, s);
            case BITE -> casts.bite(player, s);
            case CAUGHT_FISH -> casts.hooked(event, player, s);
            case REEL_IN -> casts.endEarly(player, s, FishingSessionEndEvent.Reason.REELED_EARLY);
            case FAILED_ATTEMPT ->
                    casts.endEarly(player, s, FishingSessionEndEvent.Reason.MISSED_BITE);
            case IN_GROUND, CAUGHT_ENTITY ->
                    casts.endEarly(player, s, FishingSessionEndEvent.Reason.INTERRUPTED);
            default -> {
                // LURED: ignored
            }
        }
    }

    // ------------------------------------------------------------------ Tick

    private void tick() {
        ticks++;
        for (Map.Entry<UUID, FishingSession> entry : sessions.entrySet()) {
            FishingSession s = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            switch (s.state) {
                case CAST -> casts.tickCast(player, s);
                case WAITING, BITE -> {
                    FishHook hook = s.hook;
                    if (hook == null || !hook.isValid()) {
                        // Vanilla removed the bobber without an event: end the cast so the mythic
                        // reservation and shadow are returned and the fishing indicator is cleared
                        casts.endEarly(player, s, FishingSessionEndEvent.Reason.INTERRUPTED);
                        continue;
                    }
                    casts.tickWaiting(player, s);
                }
                case MINIGAME -> minigames.tickMinigame(player, s);
                default -> {
                    // IDLE, HOOKED, RESULT: nothing to do
                }
            }
            if (ticks % 20 == 0) {
                mythic.discardExpired(s, Instant.now());
            }
        }
        for (TensionSessionImpl t : List.copyOf(tensions.values())) {
            minigames.tickTension(t);
        }
    }

    // ------------------------------------------------------------------ Input · abort

    /**
     * Pressing Shift (reel) on a boat dismounts, so dismounting is prevented during the minigame.
     */
    @EventHandler(ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player player && event.isCancellable() && locked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInput(PlayerInputEvent event) {
        FishingSession s = sessionOf(event.getPlayer());
        s.inputSneak = event.getInput().isSneak();
        s.inputLeft = event.getInput().isLeft();
        s.inputRight = event.getInput().isRight();
    }

    private boolean locked(Player player) {
        return session(player.getUniqueId()) == SessionState.MINIGAME
                || tensions.containsKey(player.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        if (locked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (locked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (locked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        abort(event.getPlayer(), MinigameResult.ESCAPED);
        TensionSessionImpl t = tensions.get(event.getPlayer().getUniqueId());
        if (t != null) {
            t.close(MinigameResult.ESCAPED);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        abort(event.getPlayer(), MinigameResult.ESCAPED);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        abort(player, MinigameResult.ESCAPED);
        TensionSessionImpl t = tensions.get(player.getUniqueId());
        if (t != null) {
            t.close(MinigameResult.ESCAPED);
        }
        FishingSession s = sessions.remove(player.getUniqueId());
        if (s != null) {
            CastHandler.closeShadow(s, false);
            FishingSession.MythicHold hold = s.mythic;
            if (hold != null) {
                mythic.releaseMythic(hold);
            }
            mythic.discardPending(s);
        }
        presenter.forget(player);
        macro.reset(player.getUniqueId());
    }

    /** Ends it as a failure if in a minigame (bait already deducted, pity kept). */
    void abort(Player player, MinigameResult reason) {
        minigames.abort(player, reason);
    }

    /** Server shutdown: aborts running minigames (bait returned) and removes shadows. */
    public void shutdown() {
        for (Map.Entry<UUID, FishingSession> e : sessions.entrySet()) {
            Player player = Bukkit.getPlayer(e.getKey());
            if (player != null) {
                abort(player, MinigameResult.ABORTED);
            }
            FishingSession s = e.getValue();
            CastHandler.closeShadow(s, false);
            // No quit event arrives on shutdown, so return mythic reservations here (the DB
            // connection closes soon, so wait for the write). Otherwise the event's server limit
            // shrinks on every restart
            FishingSession.MythicHold hold = s.mythic;
            if (hold != null) {
                mythic.releaseMythicNow(hold);
            }
            for (FishingSession.PendingRoll pending : s.pendings.values()) {
                FishingSession.MythicHold pendingHold = pending.mythic();
                if (pendingHold != null) {
                    mythic.releaseMythicNow(pendingHold);
                }
            }
            s.pendings.clear();
        }
        for (TensionSessionImpl t : List.copyOf(tensions.values())) {
            t.close(MinigameResult.ABORTED);
        }
        presenter.shutdown();
        traps.shutdown();
    }

    @EventHandler
    public void onReload(ContentReloadedEvent event) {
        // Running sessions finish with the FishDef from their start; new data applies from the next
        // cast. All pending rolls are discarded
        engine = new RollEngine(event.snapshot(), balance());
        sessions.values().forEach(mythic::discardPending);
    }

    ContentSnapshot snapshot() {
        return api.content().snapshot();
    }

    Logger log() {
        return log;
    }

    Plugin plugin() {
        return plugin;
    }
}
