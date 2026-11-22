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
import wiki.creeper.harbor.core.api.HandoffBlocker;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.MailService;
import wiki.creeper.harbor.core.api.PlayerComponent;
import wiki.creeper.harbor.core.api.event.ContentReloadedEvent;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.Locs;
import wiki.creeper.harbor.fishing.api.CatchOptions;
import wiki.creeper.harbor.fishing.api.CatchPresentationOverride;
import wiki.creeper.harbor.fishing.api.CatchResult;
import wiki.creeper.harbor.fishing.api.CodexProvider;
import wiki.creeper.harbor.fishing.api.FishingService;
import wiki.creeper.harbor.fishing.api.MinigameResult;
import wiki.creeper.harbor.fishing.api.RegionKeyResolver;
import wiki.creeper.harbor.fishing.api.SeaEventMessage;
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
 * 낚시 한 번의 판정과 진행 (docs 20 3.4). 바닐라 낚시 이벤트를 세션 상태 기계에 연결한다. 바닐라 루트는 모두 취소하고 모든 포획은 미니게임을 거친다. 메인
 * 스레드 전용.
 *
 * <p>흐름: 던짐(CAST, 대기 시간 설정) → 찌가 물에 닿음(출현 판정, FishingCastEvent, 그림자 접근, WAITING) → 입질(BITE) →
 * 챔질(HOOKED, 미끼 차감) → 미니게임(MINIGAME) → 결과(RESULT, 크기·품질·아이템·잡템·EXP·연출) → IDLE.
 *
 * <p>미니게임 조작: Shift(웅크리기)를 누르고 있으면 감기, A·D로 물고기 방향 따라가기 (Player Input 동기화). 설정에서 시점 회전 방식을 고를 수 있고
 * 탈것 위에서는 자동으로 시점 회전이다. 문서의 "낚싯대 consumable을 누르고 있기" 방식은 바닐라 낚싯대 동작과의 충돌 여부를 Phase 1에서 확인한 뒤 바꾼다
 * (docs 20 3.8 열린 질문).
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
        // 그림자: Core 리그(어종 리그 → 등급 기본 리그), 리그가 없으면 ItemDisplay 하나
        this.shadows =
                new RigShadowPresenter(
                        api.rigs(), new DisplayShadowPresenter(plugin, tasks), tasks);
        this.presenter = new CatchPresenter(api, tasks);
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
    }

    /** Paper의 위치 조회는 null 가능으로 표시되어 있지만 접속 중인 엔티티는 항상 위치가 있다. */
    static Location loc(Entity entity) {
        return Locs.of(entity);
    }

    public void start() {
        var _ = tasks.timer(1, 1, this::tick);
        // harbor:event 해상 이벤트 (World 발행). 핸들러는 Lettuce 스레드라 메인으로 넘긴다
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

    /** 서버 이동 전 (Core HandoffBlocker): 미니게임 중이면 실패로 끝내고 이동을 허용한다. */
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
    public void registerCodex(CodexProvider provider) {
        ctx.registerCodex(provider);
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

    // ------------------------------------------------------------------ 컨텍스트

    /**
     * 위치의 지역 (regions.yml 경계). 월드 이름은 balance.yml world_alias로 harbor_main 등에 대응시킬 수 있다 (개발 서버용).
     */
    public Optional<RegionDef> regionAt(Location loc) {
        return ctx.regionAt(loc);
    }

    /** 찌 위치의 지역 키. World가 등록한 RegionKeyResolver가 먼저, 없으면 regions.yml. */
    Optional<RegionKeyResolver.RegionKey> regionKey(Location bobber) {
        return ctx.regionKey(bobber);
    }

    boolean areasKnown() {
        return ctx.areasKnown();
    }

    RollContext context(Player player, RegionKeyResolver.RegionKey key, @Nullable ItemId bait) {
        return ctx.context(player, key, bait);
    }

    /** 판정 보정 (범주 곱, '7. 밸런스 기획' 4.2). */
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
     * 인벤토리가 가득 차면 발밑에 떨어뜨리고 60초 동안 본인만 주울 수 있게 한다. 통발 수확·회수·환불처럼 DB 커밋 뒤 주는 아이템. 그 사이 나갔거나 서버 이동
     * 저장본을 뜬 뒤면 인벤토리에 넣어도 저장되지 않으므로 (DB에서는 이미 빠졌다) 우편으로 보낸다. 우편함이 없으면 기록만 남긴다.
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

    // ------------------------------------------------------------------ 낚시 이벤트

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
                // LURED: 무시
            }
        }
    }

    // ------------------------------------------------------------------ 틱

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
                        // 바닐라가 이벤트 없이 찌를 지웠다: 끝내야 신화 예약·그림자가 반납되고 낚는 중 표시가 풀린다
                        casts.endEarly(player, s, FishingSessionEndEvent.Reason.INTERRUPTED);
                        continue;
                    }
                    s.castTicks++;
                    ShadowControl shadow = s.shadow;
                    if (shadow != null && s.state == SessionState.WAITING) {
                        shadow.tickWaiting();
                    }
                }
                case MINIGAME -> minigames.tickMinigame(player, s);
                default -> {
                    // IDLE·HOOKED·RESULT: 할 일 없음
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

    // ------------------------------------------------------------------ 입력 · 중단

    /** 배 위에서 Shift(감기)를 누르면 내리게 되므로 미니게임 중에는 내리지 않게 한다. */
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
        macro.reset(player.getUniqueId());
    }

    /** 미니게임 중이면 실패로 끝낸다 (미끼는 이미 차감, 천장 유지). */
    void abort(Player player, MinigameResult reason) {
        minigames.abort(player, reason);
    }

    /** 서버 종료: 진행 중인 미니게임을 중단(미끼 반환)하고 그림자를 지운다. */
    public void shutdown() {
        for (Map.Entry<UUID, FishingSession> e : sessions.entrySet()) {
            Player player = Bukkit.getPlayer(e.getKey());
            if (player != null) {
                abort(player, MinigameResult.ABORTED);
            }
            FishingSession s = e.getValue();
            CastHandler.closeShadow(s, false);
            // 종료 때는 퇴장 이벤트가 오지 않으므로 신화 예약을 여기서 반납한다 (DB 연결이 곧 닫히므로 기다려서 쓴다).
            // 안 하면 재시작마다 그 이벤트의 서버 한도가 줄어든다
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
        traps.shutdown();
    }

    @EventHandler
    public void onReload(ContentReloadedEvent event) {
        // 진행 중 세션은 시작 때의 FishDef로 끝까지, 새 캐스팅부터 새 데이터. 보류 판정은 모두 버린다
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
