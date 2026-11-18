package wiki.creeper.harbor.fishing.session;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.world.MoonPhase;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.random.RandomGenerator;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
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
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.core.api.HandoffBlocker;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.core.api.Modifier;
import wiki.creeper.harbor.core.api.PlayerComponent;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.core.api.WorldBorderService;
import wiki.creeper.harbor.core.api.event.ContentReloadedEvent;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.Locs;
import wiki.creeper.harbor.fishing.api.CatchOptions;
import wiki.creeper.harbor.fishing.api.CatchPresentationOverride;
import wiki.creeper.harbor.fishing.api.CatchResult;
import wiki.creeper.harbor.fishing.api.CatchSource;
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
import wiki.creeper.harbor.fishing.api.event.FishBiteEvent;
import wiki.creeper.harbor.fishing.api.event.FishCaughtEvent;
import wiki.creeper.harbor.fishing.api.event.FishRollEvent;
import wiki.creeper.harbor.fishing.api.event.FishingCastEvent;
import wiki.creeper.harbor.fishing.api.event.FishingSessionEndEvent;
import wiki.creeper.harbor.fishing.api.event.MinigameEndEvent;
import wiki.creeper.harbor.fishing.api.event.QualityRolledEvent;
import wiki.creeper.harbor.fishing.content.FishingBalance;
import wiki.creeper.harbor.fishing.content.FishingPresets;
import wiki.creeper.harbor.fishing.content.JunkTable;
import wiki.creeper.harbor.fishing.data.FishingData;
import wiki.creeper.harbor.fishing.data.FishingRepository;
import wiki.creeper.harbor.fishing.present.CatchPresenter;
import wiki.creeper.harbor.fishing.present.DisplayShadowPresenter;
import wiki.creeper.harbor.fishing.present.RigShadowPresenter;
import wiki.creeper.harbor.fishing.present.ShadowPresenter;
import wiki.creeper.harbor.fishing.roll.BaitRule;
import wiki.creeper.harbor.fishing.roll.GradeBonus;
import wiki.creeper.harbor.fishing.roll.JunkRoller;
import wiki.creeper.harbor.fishing.roll.QualityRoller;
import wiki.creeper.harbor.fishing.roll.RollContext;
import wiki.creeper.harbor.fishing.roll.RollEngine;
import wiki.creeper.harbor.fishing.roll.RollModifiers;
import wiki.creeper.harbor.fishing.sea.MythicCounter;
import wiki.creeper.harbor.fishing.sea.SeaEventBook;
import wiki.creeper.harbor.fishing.trap.TrapManager;
import wiki.creeper.harbor.jobs.api.ExpGrant;
import wiki.creeper.harbor.jobs.api.ExpSource;
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

    private static final Duration PENDING_TTL = Duration.ofSeconds(60);
    private static final double BASE_WARN_SECONDS = 0.6;
    private static final int GAUGE_CELLS = 20;
    private static final int MAX_CAST_TICKS = 20 * 20;
    private static final float LOOK_THRESHOLD = 12f;
    private static final double JUMP_VIEW_RADIUS = 24;

    private final Plugin plugin;
    private final HarborApi api;
    private final Tasks tasks;
    private final JobsService jobs;
    private final Logger log;
    private final MacroGuard macro;
    private final NamespacedKey moveLock;
    private final NamespacedKey baitUses;
    private final Map<UUID, FishingSession> sessions = new HashMap<>();
    private final Map<UUID, TensionSessionImpl> tensions = new HashMap<>();
    private final RandomGenerator random = RandomGenerator.getDefault();
    private final SeaEventBook seaEvents = new SeaEventBook();
    private final MythicCounter mythicCounter;
    private final ShadowPresenter shadows;
    private final CatchPresenter presenter;
    private final MinigameStats stats = new MinigameStats();
    private final TrapManager traps;
    private @Nullable CodexProvider codex;
    private @Nullable RegionKeyResolver regionResolver;
    private @Nullable TreasureDropHook treasureHook;
    private @Nullable CatchPresentationOverride presentation;
    private RollEngine engine;
    private long ticks;

    public FishingManager(Plugin plugin, HarborApi api, Tasks tasks, JobsService jobs, Logger log) {
        this.plugin = plugin;
        this.api = api;
        this.tasks = tasks;
        this.jobs = jobs;
        this.log = log;
        this.macro = new MacroGuard(api, tasks, log);
        this.moveLock = new NamespacedKey(plugin, "minigame_lock");
        this.baitUses = new NamespacedKey(plugin, "bait_uses");
        this.engine = new RollEngine(api.content().snapshot(), balance());
        this.mythicCounter = new MythicCounter(api.database());
        // 그림자: Core 리그(어종 리그 → 등급 기본 리그), 리그가 없으면 ItemDisplay 하나
        this.shadows =
                new RigShadowPresenter(
                        api.rigs(), new DisplayShadowPresenter(plugin, tasks), tasks);
        this.presenter = new CatchPresenter(api, tasks);
        this.traps = new TrapManager(plugin, api, tasks, jobs, this, log);
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
        return new FishingBalance(api.content().snapshot().single(Balance.TYPE));
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
        this.codex = provider;
    }

    @Override
    public void registerRegionResolver(RegionKeyResolver resolver) {
        this.regionResolver = resolver;
    }

    @Override
    public void registerTreasureHook(TreasureDropHook hook) {
        this.treasureHook = hook;
    }

    @Override
    public void registerPresentation(CatchPresentationOverride override) {
        this.presentation = override;
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
                        presets().of(fish.grade()),
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
        UUID uuid = player.getUniqueId();
        if (tensions.containsKey(uuid)) {
            return Optional.empty();
        }
        FishingSession s = sessions.get(uuid);
        if (s != null && s.state == SessionState.MINIGAME) {
            abort(player, MinigameResult.ABORTED);
        } else if (s != null && s.state != SessionState.IDLE) {
            endEarly(player, s, FishingSessionEndEvent.Reason.INTERRUPTED);
        }
        FishingBalance b = balance();
        FishingBalance.MinigameSpec base = b.minigame(spec.grade());
        FishingBalance.MinigameSpec adjusted =
                new FishingBalance.MinigameSpec(
                        spec.hp() > 0 ? spec.hp() : base.hp(),
                        base.tensionRise() * spec.tensionRiseMult(),
                        base.releaseRate(),
                        base.reelPerTick(),
                        spec.patterns() ? base.patterns() : 0,
                        base.patternIntervalSeconds(),
                        base.safeLow(),
                        base.safeWidth(),
                        base.escapeTicks(),
                        base.phased());
        Minigame game =
                new Minigame(
                        adjusted,
                        gear(player, spec.extraLineStrength(), false),
                        b.lineStrengthStep(),
                        random);
        TensionSessionImpl session =
                new TensionSessionImpl(uuid, game, spec, () -> closeTension(player));
        session.baseYaw = loc(player).getYaw();
        session.bar = api.ui().showBossBar(player, spec.title(), 1f, barColor(spec.grade()));
        tensions.put(uuid, session);
        lockMovement(player, true);
        api.messages().send(player, "fishing.minigame.help");
        return Optional.of(session);
    }

    private void closeTension(Player player) {
        TensionSessionImpl t = tensions.remove(player.getUniqueId());
        if (t == null) {
            return;
        }
        BossBar bar = t.bar;
        if (bar != null) {
            api.ui().hideBossBar(player, bar);
        }
        if (t.borderWarn) {
            api.worldBorders().clear(player, WorldBorderService.Priority.FISHING_TENSION);
        }
        lockMovement(player, false);
    }

    @Override
    public Optional<CatchResult> createCatch(Player player, FishId fishId, CatchOptions options) {
        Optional<FishDef> def = api.content().snapshot().get(FishDef.TYPE, fishId.id());
        if (def.isEmpty()) {
            return Optional.empty();
        }
        FishDef fish = def.get();
        UUID uuid = player.getUniqueId();
        HarborPlayer hp = api.players().get(player);
        FishingBalance b = balance();
        Quality cap = jobs.qualityCap(uuid);
        QualityRoller.Rolled rolled =
                QualityRoller.roll(
                        fish,
                        stat(uuid, "size_luck") + options.extraLuck(),
                        cap,
                        b.qualityBounds(),
                        random);
        int stars = Math.min(cap.stars(), rolled.quality().stars() + options.qualityBonusStars());
        QualityRolledEvent qualityEvent =
                new QualityRolledEvent(
                        player, fish, rolled.sizeCm(), Quality.of(stars), rolled.capped());
        Bukkit.getPluginManager().callEvent(qualityEvent);
        Instant now = Instant.now();
        ItemStack stack = null;
        HarborItem.Fish info;
        if (fish.releaseOnly()) {
            info =
                    new HarborItem.Fish(
                            fish.id(),
                            qualityEvent.sizeCm(),
                            qualityEvent.quality(),
                            hp.ref(),
                            hp.season(),
                            now,
                            null,
                            1);
        } else {
            stack =
                    api.items()
                            .createFish(
                                    fish,
                                    qualityEvent.sizeCm(),
                                    qualityEvent.quality(),
                                    hp.ref(),
                                    now);
            info = readFish(stack, fish, qualityEvent, hp, now);
        }
        if (options.fireCaughtEvent()) {
            CodexProvider provider = codex;
            boolean firstCatch = provider != null && !provider.hasCaught(uuid, fish.id());
            Bukkit.getPluginManager()
                    .callEvent(
                            new FishCaughtEvent(
                                    player,
                                    fish,
                                    info,
                                    fish.region(),
                                    fish.releaseOnly(),
                                    firstCatch,
                                    rolled.capped(),
                                    CatchSource.BOSS));
        }
        return Optional.of(new CatchResult(stack, info, rolled.capped()));
    }

    private HarborItem.Fish readFish(
            ItemStack stack, FishDef fish, QualityRolledEvent q, HarborPlayer hp, Instant now) {
        return api.items()
                .read(stack)
                .filter(i -> i instanceof HarborItem.Fish)
                .map(i -> (HarborItem.Fish) i)
                .orElse(
                        new HarborItem.Fish(
                                fish.id(),
                                q.sizeCm(),
                                q.quality(),
                                hp.ref(),
                                hp.season(),
                                now,
                                null,
                                1));
    }

    // ------------------------------------------------------------------ 컨텍스트

    /**
     * 위치의 지역 (regions.yml 경계). 월드 이름은 balance.yml world_alias로 harbor_main 등에 대응시킬 수 있다 (개발 서버용).
     */
    public Optional<RegionDef> regionAt(Location loc) {
        World world = loc.getWorld();
        if (world == null) {
            return Optional.empty();
        }
        String worldId =
                api.content()
                        .snapshot()
                        .single(Balance.TYPE)
                        .string("world_alias." + world.getName())
                        .orElse(world.getName());
        return api.content().snapshot().all(RegionDef.TYPE).values().stream()
                .filter(r -> r.world().equals(worldId) && r.contains(loc.getX(), loc.getZ()))
                .findFirst();
    }

    /** 찌 위치의 지역 키. World가 등록한 RegionKeyResolver가 먼저, 없으면 regions.yml. */
    Optional<RegionKeyResolver.RegionKey> regionKey(Location bobber) {
        RegionKeyResolver resolver = regionResolver;
        if (resolver != null) {
            try {
                return resolver.resolve(bobber);
            } catch (RuntimeException e) {
                log.warn("RegionKeyResolver 오류: {}", String.valueOf(e));
            }
        }
        return regionAt(bobber)
                .map(
                        r ->
                                new RegionKeyResolver.RegionKey(
                                        r.id(), r.biome(), List.of(), false, null));
    }

    boolean areasKnown() {
        return regionResolver != null;
    }

    RollContext context(Player player, RegionKeyResolver.RegionKey key, @Nullable ItemId bait) {
        World world = player.getWorld();
        long time = world.getTime();
        String timeKey =
                time >= 23000 || time < 1000
                        ? "dawn"
                        : time < 12000 ? "day" : time < 13800 ? "dusk" : "night";
        String weather = world.isThundering() ? "thunder" : world.hasStorm() ? "rain" : "clear";
        MoonPhase phase = world.getMoonPhase();
        String moon =
                phase == MoonPhase.FULL_MOON ? "full" : phase == MoonPhase.NEW_MOON ? "new" : "";
        HarborPlayer hp = api.players().get(player);
        CodexProvider provider = codex;
        UUID uuid = player.getUniqueId();
        ContentSnapshot snapshot = api.content().snapshot();
        return new RollContext(
                key.region(),
                timeKey,
                weather,
                moon,
                bait,
                hp.jobTier().level(),
                flag -> api.flags().hasServer(flag),
                fish -> provider != null && provider.hasCaught(uuid, fish),
                key.areas(),
                areasKnown(),
                seaEvents.mythicWindows(
                        key.region(),
                        Instant.now(),
                        id ->
                                HarborId.tryParse(id)
                                        .flatMap(h -> snapshot.get(FishDef.TYPE, h))
                                        .filter(f -> f.grade() == Grade.MYTHIC)));
    }

    /** 판정 보정 (범주 곱, '7. 밸런스 기획' 4.2). */
    RollModifiers modifiers(UUID uuid, Map<String, Double> gearStats, RegionId region) {
        GradeBonus.Sum rare = categorySum(uuid, "rare_weight");
        rare.add(GradeBonus.Category.BAIT, gearStats.getOrDefault("rare_weight", 0.0));
        GradeBonus.Sum epic = categorySum(uuid, "rare_weight");
        epic.add(GradeBonus.Category.BAIT, gearStats.getOrDefault("rare_weight", 0.0));
        for (Modifier m : applied(uuid, "epic_weight")) {
            epic.add(m.source(), addValue(m));
        }
        epic.add(GradeBonus.Category.BAIT, gearStats.getOrDefault("epic_weight", 0.0));
        double cap = balance().rareBonusCap();
        Map<Grade, Double> bonus = new java.util.EnumMap<>(Grade.class);
        bonus.put(Grade.RARE, rare.capped(cap));
        bonus.put(Grade.EPIC, epic.capped(cap));
        return new RollModifiers(
                bonus,
                stat(uuid, "uncaught_weight"),
                (int) Math.round(stat(uuid, "legend_pity_start")),
                stat(uuid, "legend_chance") * (1 + gearStats.getOrDefault("legend_chance", 0.0)),
                seaEvents.speciesMult(region, Instant.now()),
                stat(uuid, "mythic_chance"));
    }

    private GradeBonus.Sum categorySum(UUID uuid, String key) {
        GradeBonus.Sum sum = new GradeBonus.Sum();
        for (Modifier m : applied(uuid, key)) {
            sum.add(m.source(), addValue(m));
        }
        return sum;
    }

    private List<Modifier> applied(UUID uuid, String key) {
        return api.modifiers().explain(uuid, StatKey.of(key), 0).applied();
    }

    private static double addValue(Modifier m) {
        return m.op() == Modifier.Op.ADD ? m.value() : m.value() - 1;
    }

    double stat(UUID uuid, String key) {
        return api.modifiers().value(uuid, StatKey.of(key));
    }

    /** 왼손 미끼 (ItemDef type: bait). */
    private @Nullable ItemId equippedBait(Player player) {
        return itemDef(player.getInventory().getItemInOffHand())
                .filter(d -> d.type() == ItemDef.Type.BAIT)
                .map(ItemDef::id)
                .orElse(null);
    }

    Optional<ItemDef> itemDef(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        return api.items()
                .read(stack)
                .filter(i -> i instanceof HarborItem.Plain)
                .map(i -> (HarborItem.Plain) i)
                .flatMap(p -> api.content().snapshot().get(ItemDef.TYPE, p.id().id()));
    }

    /** 낚싯대(오른손) + 미끼(왼손)의 stats 합. */
    private Map<String, Double> gearStats(Player player) {
        Map<String, Double> sum = new HashMap<>();
        itemDef(player.getInventory().getItemInMainHand())
                .ifPresent(d -> d.stats().forEach((k, v) -> sum.merge(k, v, Double::sum)));
        itemDef(player.getInventory().getItemInOffHand())
                .filter(d -> d.type() == ItemDef.Type.BAIT)
                .ifPresent(d -> d.stats().forEach((k, v) -> sum.merge(k, v, Double::sum)));
        return sum;
    }

    private Minigame.Gear gear(Player player, double extraLine, boolean longCast) {
        UUID uuid = player.getUniqueId();
        Map<String, Double> gear = gearStats(player);
        FishingBalance b = balance();
        double warn =
                BASE_WARN_SECONDS
                        + stat(uuid, "pattern_warn")
                        + (player.getPing() >= 150 ? b.pingCompensationSeconds() : 0);
        double safe =
                stat(uuid, "tension_safe_zone")
                        + gear.getOrDefault("tension_safe_zone", 0.0)
                        + (longCast ? stat(uuid, "tension_safe_zone_long_cast") : 0);
        return new Minigame.Gear(
                stat(uuid, "line_strength") + gear.getOrDefault("line_strength", 0.0) + extraLine,
                stat(uuid, "reel_speed") + gear.getOrDefault("reel_speed", 0.0),
                safe,
                gear.getOrDefault("stability", 0.0),
                (int) Math.round(warn * 20));
    }

    private FishingPresets presets() {
        return api.content().snapshot().single(FishingPresets.TYPE);
    }

    // ------------------------------------------------------------------ 낚시 이벤트

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        FishingSession s = sessionOf(player);
        switch (event.getState()) {
            case FISHING -> cast(event, player, s);
            case BITE -> bite(player, s);
            case CAUGHT_FISH -> hooked(event, player, s);
            case REEL_IN -> endEarly(player, s, FishingSessionEndEvent.Reason.REELED_EARLY);
            case FAILED_ATTEMPT -> endEarly(player, s, FishingSessionEndEvent.Reason.MISSED_BITE);
            case IN_GROUND, CAUGHT_ENTITY ->
                    endEarly(player, s, FishingSessionEndEvent.Reason.INTERRUPTED);
            default -> {
                // LURED: 무시
            }
        }
    }

    /** 던짐: 대기 시간을 정하고 찌가 물에 닿기를 기다린다 (판정은 착수 때). */
    private void cast(PlayerFishEvent event, Player player, FishingSession s) {
        UUID uuid = player.getUniqueId();
        if (s.state == SessionState.MINIGAME
                || s.state == SessionState.RESULT
                || tensions.containsKey(uuid)) {
            event.setCancelled(true);
            return;
        }
        if (macro.blocked(player)) {
            event.setCancelled(true);
            api.messages().send(player, "fishing.macro.blocked");
            return;
        }
        if (rodBroken(player.getInventory().getItemInMainHand())) {
            event.setCancelled(true);
            api.messages().send(player, "fishing.rod_broken");
            return;
        }
        if (regionAt(loc(player)).isEmpty() && regionResolver == null) {
            event.setCancelled(true);
            api.ui().actionBar(player, api.messages().get("fishing.no_region"));
            return;
        }
        ItemId bait = equippedBait(player);
        Map<String, Double> gear = gearStats(player);
        FishingBalance b = balance();
        double seconds =
                b.biteWaitMin() + random.nextDouble() * (b.biteWaitMax() - b.biteWaitMin());
        seconds *= stat(uuid, "bite_wait") * (1 + gear.getOrDefault("bite_wait", 0.0));
        if (bait == null) {
            seconds *= b.noBaitWaitMult();
        }
        int waitTicks = (int) Math.max(20, Math.round(seconds * 20));
        FishHook hook = event.getHook();
        hook.setApplyLure(false);
        hook.setWaitTime(waitTicks, waitTicks);
        double distance = stat(uuid, "cast_distance") + gear.getOrDefault("cast_distance", 0.0);
        if (distance > 0 && Math.abs(distance - 1) > 1e-6) {
            hook.setVelocity(hook.getVelocity().multiply(distance));
        }
        s.clearCast();
        s.state = SessionState.CAST;
        s.hook = hook;
        s.bait = bait;
        s.waitTicks = waitTicks;
        macro.recordCast(player);
        presenter.step(loc(player), "cast");
    }

    /** 찌 착수: 출현 판정과 대기 연출 (docs 20 3.4 CAST → WAITING). */
    private void land(Player player, FishingSession s, FishHook hook) {
        Location at = loc(hook);
        presenter.step(at, landStep(at));
        Optional<RegionKeyResolver.RegionKey> key = regionKey(at);
        if (key.isEmpty()) {
            hook.remove();
            s.clearCast();
            api.ui().actionBar(player, api.messages().get("fishing.no_region"));
            return;
        }
        ItemId bait = equippedBait(player);
        s.bait = bait;
        RegionId region = key.get().region();
        UUID uuid = player.getUniqueId();
        FishDef fish;
        List<FishDef> legends;
        RollContext.MythicWindow mythicWindow = null;
        FishingSession.MythicHold hold = null;
        FishingSession.PendingRoll pending = s.pending;
        FishDef forced = s.forcedFish;
        if (forced != null) {
            fish = forced;
            legends = List.of();
            s.forcedFish = null;
        } else if (pending != null
                && Instant.now().isBefore(pending.expiresAt())
                && pending.region().equals(region)
                && Objects.equals(pending.bait(), bait)) {
            // 재판정 낚시 방지: 같은 지역·미끼면 보류 판정을 그대로 쓴다
            fish = pending.fish();
            legends = pending.legends();
            hold = pending.mythic();
        } else {
            discardPending(s);
            Optional<RollEngine.Outcome> outcome =
                    engine.roll(
                            context(player, key.get(), bait),
                            modifiers(uuid, gearStats(player), region),
                            api.players().get(player).get(COMPONENT).pity(),
                            random);
            if (outcome.isEmpty()) {
                hook.remove();
                s.clearCast();
                api.ui().actionBar(player, api.messages().get("fishing.no_fish"));
                return;
            }
            fish = outcome.get().fish();
            legends = outcome.get().legendCandidates();
            mythicWindow = outcome.get().mythic();
        }
        s.pending = null;
        FishRollEvent roll = new FishRollEvent(player, fish, bait);
        Bukkit.getPluginManager().callEvent(roll);
        if (roll.isCancelled()) {
            hook.remove();
            if (hold != null) {
                releaseMythic(hold);
            }
            s.clearCast();
            return;
        }
        if (mythicWindow != null && roll.fish().id().equals(mythicWindow.fish().id())) {
            hold = reserveMythic(player, s, mythicWindow, key.get(), bait);
        }
        int waitTicks = s.waitTicks;
        s.fish = roll.fish();
        s.region = region;
        s.legends = legends;
        s.school = key.get().school();
        s.hookLoc = at;
        s.mythic = hold;
        s.state = SessionState.WAITING;
        s.castTicks = 0;
        s.settings = FishingSettings.of(api.flags(), uuid);

        FishingCastEvent castEvent =
                new FishingCastEvent(
                        player, at, region, bait, roll.fish(), waitTicks, key.get().school());
        Bukkit.getPluginManager().callEvent(castEvent);
        ShadowDriver driver = castEvent.shadow();
        if (driver != null || presenter.shadows(s.settings.intensity())) {
            s.shadow =
                    new ShadowControl(
                            shadows,
                            player,
                            roll.fish(),
                            presets().of(roll.fish().grade()),
                            stat(uuid, "feature_shadow_grade") > 0,
                            at,
                            waitTicks,
                            driver,
                            random);
        }
    }

    /** 신화 서버 마릿수를 예약한다. 한도를 넘었으면 일반 판정으로 바꾼다. */
    private FishingSession.MythicHold reserveMythic(
            Player player,
            FishingSession s,
            RollContext.MythicWindow window,
            RegionKeyResolver.RegionKey key,
            @Nullable ItemId bait) {
        FishingSession.MythicHold hold =
                new FishingSession.MythicHold(window.eventId(), window.fish().id().toString());
        var _ =
                mythicCounter
                        .reserve(hold.eventId, hold.fishId, window.cap())
                        .whenCompleteAsync(
                                (ok, error) -> {
                                    boolean reserved = error == null && Boolean.TRUE.equals(ok);
                                    if (error != null) {
                                        log.warn("신화 예약 실패: {}", String.valueOf(error));
                                    }
                                    if (reserved && hold.released) {
                                        // 기다리는 사이 캐스팅이 끝났으면 바로 돌려준다
                                        var _ = mythicCounter.release(hold.eventId, hold.fishId);
                                        return;
                                    }
                                    hold.confirmed = reserved;
                                    if (!reserved && s.mythic == hold && player.isOnline()) {
                                        hold.released = true;
                                        s.mythic = null;
                                        rerollWithoutMythic(player, s, key, bait);
                                    }
                                },
                                tasks.main());
        return hold;
    }

    private void rerollWithoutMythic(
            Player player,
            FishingSession s,
            RegionKeyResolver.RegionKey key,
            @Nullable ItemId bait) {
        RollContext base = context(player, key, bait);
        RollContext ctx =
                new RollContext(
                        base.region(),
                        base.time(),
                        base.weather(),
                        base.moon(),
                        base.bait(),
                        base.jobTier(),
                        base.serverFlags(),
                        base.caught(),
                        base.areas(),
                        base.areasKnown(),
                        List.of());
        engine.roll(
                        ctx,
                        modifiers(player.getUniqueId(), gearStats(player), key.region()),
                        api.players().get(player).get(COMPONENT).pity(),
                        random)
                .ifPresent(
                        o -> {
                            s.fish = o.fish();
                            s.legends = o.legendCandidates();
                        });
    }

    private void releaseMythic(FishingSession.MythicHold hold) {
        if (hold.released) {
            return;
        }
        hold.released = true;
        if (hold.confirmed) {
            var _ = mythicCounter.release(hold.eventId, hold.fishId);
        }
    }

    private void bite(Player player, FishingSession s) {
        FishDef fish = s.fish;
        if (s.state != SessionState.WAITING || fish == null) {
            return;
        }
        s.state = SessionState.BITE;
        s.biteAtNanos = System.nanoTime();
        // 전설 천장: 입질까지 간 캐스팅만 +1 (던지자마자 감아서 쌓는 것을 막음)
        if (!s.pityCounted && !s.legends.isEmpty()) {
            HarborPlayer hp = api.players().get(player);
            FishingData data = hp.get(COMPONENT);
            for (FishDef legend : s.legends) {
                data = data.withPity(legend.id(), data.pity(legend.id()) + 1);
            }
            hp.set(COMPONENT, data);
            s.pityCounted = true;
        }
        Location hook = s.hookLoc;
        if (hook != null) {
            presenter.bite(player, fish, hook, s.settings.intensity());
        }
        ShadowControl shadow = s.shadow;
        if (shadow != null) {
            shadow.bite();
        }
        api.ui().actionBar(player, api.messages().get("fishing.bite"));
        Bukkit.getPluginManager().callEvent(new FishBiteEvent(player, fish));
    }

    /** 챔질: 바닐라 루트를 막고, 미끼를 쓰고, 미니게임을 시작한다. */
    private void hooked(PlayerFishEvent event, Player player, FishingSession s) {
        event.setCancelled(true);
        event.getHook().remove();
        FishDef fish = s.fish;
        if (fish == null || (s.state != SessionState.BITE && s.state != SessionState.WAITING)) {
            closeShadow(s, false);
            s.clearCast();
            return;
        }
        // 대기 중 미끼를 바꾸거나 버렸으면 판정을 버린다
        if (!Objects.equals(equippedBait(player), s.bait)) {
            endEarly(player, s, FishingSessionEndEvent.Reason.BAIT_CHANGED);
            discardPending(s);
            api.messages().send(player, "fishing.bait_changed");
            return;
        }
        FishingSession.MythicHold hold = s.mythic;
        if (hold != null && !hold.confirmed) {
            // 예약 확인이 늦으면 신화 대신 일반 판정 (복제 방지 우선)
            releaseMythic(hold);
            s.mythic = null;
            Location hookLoc = s.hookLoc;
            if (hookLoc != null) {
                regionKey(hookLoc).ifPresent(k -> rerollWithoutMythic(player, s, k, s.bait));
            }
            fish = Objects.requireNonNull(s.fish);
        }
        if (s.biteAtNanos > 0) {
            macro.recordReaction(player, System.nanoTime() - s.biteAtNanos);
        }
        s.state = SessionState.HOOKED;
        s.legendBait = isLegendBait(s.bait);
        if (BaitRule.consumeOnHook(s.legendBait)) {
            consumeBait(player, s.bait);
        }

        FishingBalance b = balance();
        Location hookLoc = s.hookLoc;
        boolean longCast =
                hookLoc != null
                        && player.getWorld().equals(hookLoc.getWorld())
                        && hookLoc.distance(loc(player)) >= b.longCastDistance();
        s.minigame =
                new Minigame(
                        b.minigame(fish.grade()),
                        gear(player, 0, longCast),
                        b.lineStrengthStep(),
                        random);
        s.state = SessionState.MINIGAME;
        presenter.step(hookLoc != null ? hookLoc : loc(player), "hook");
        s.inputSneak = false;
        s.baseYaw = loc(player).getYaw();
        s.lastPhase = 1;
        lockMovement(player, true);
        s.bar =
                api.ui()
                        .showBossBar(
                                player,
                                api.messages().get("fishing.minigame.unknown"),
                                1f,
                                barColor(fish.grade()));
        api.messages().send(player, "fishing.minigame.help");
    }

    /** 미니게임 전에 끝난 캐스팅 (미리 감기·입질 놓침·땅에 걸림). 판정은 60초 보류한다. */
    private void endEarly(Player player, FishingSession s, FishingSessionEndEvent.Reason reason) {
        FishDef fish = s.fish;
        RegionId region = s.region;
        if (s.state == SessionState.MINIGAME || s.state == SessionState.RESULT) {
            return;
        }
        closeShadow(s, false);
        if (fish != null
                && region != null
                && reason != FishingSessionEndEvent.Reason.BAIT_CHANGED) {
            s.pending =
                    new FishingSession.PendingRoll(
                            region,
                            s.bait,
                            fish,
                            s.legends,
                            s.mythic,
                            Instant.now().plus(PENDING_TTL));
            s.mythic = null;
        } else {
            FishingSession.MythicHold hold = s.mythic;
            if (hold != null) {
                releaseMythic(hold);
            }
        }
        s.clearCast();
        if (fish != null) {
            Bukkit.getPluginManager().callEvent(new FishingSessionEndEvent(player, fish, reason));
        }
    }

    private void discardPending(FishingSession s) {
        FishingSession.PendingRoll pending = s.pending;
        if (pending != null) {
            FishingSession.MythicHold hold = pending.mythic();
            if (hold != null) {
                releaseMythic(hold);
            }
        }
        s.pending = null;
    }

    private void closeShadow(FishingSession s, boolean caught) {
        ShadowControl shadow = s.shadow;
        if (shadow != null) {
            shadow.close(caught);
            s.shadow = null;
        }
    }

    // ------------------------------------------------------------------ 미끼 · 낚싯대

    private boolean isLegendBait(@Nullable ItemId bait) {
        if (bait == null) {
            return false;
        }
        return api.content()
                .snapshot()
                .get(ItemDef.TYPE, bait.id())
                .map(d -> d.stats().getOrDefault("legend_only", 0.0) > 0)
                .orElse(false);
    }

    /** 왼손 미끼 1개 차감. 사용 횟수가 있는 미끼(반짝이 루어 uses)는 남은 횟수를 1 줄이고 0이 되면 1개를 없앤다. */
    private void consumeBait(Player player, @Nullable ItemId bait) {
        if (bait == null) {
            return;
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        Optional<ItemDef> def = itemDef(offhand).filter(d -> d.id().equals(bait));
        if (def.isEmpty()) {
            return;
        }
        int uses = (int) Math.round(def.get().stats().getOrDefault("uses", 0.0));
        if (uses > 1) {
            Integer stored =
                    offhand.getPersistentDataContainer().get(baitUses, PersistentDataType.INTEGER);
            int left = (stored == null ? uses : stored) - 1;
            if (left > 0) {
                offhand.editPersistentDataContainer(
                        pdc -> pdc.set(baitUses, PersistentDataType.INTEGER, left));
                api.ui()
                        .actionBar(
                                player,
                                api.messages()
                                        .get(
                                                "fishing.bait_uses",
                                                Placeholder.unparsed(
                                                        "left", String.valueOf(left))));
                return;
            }
            offhand.editPersistentDataContainer(pdc -> pdc.remove(baitUses));
        }
        offhand.setAmount(offhand.getAmount() - 1);
    }

    private void refundBait(Player player, ItemId bait) {
        api.content()
                .snapshot()
                .get(ItemDef.TYPE, bait.id())
                .ifPresent(def -> give(player, api.items().create(def, 1)));
    }

    /** 내구도가 1 이하면 낚시 불가 (수리 안내). */
    private static boolean rodBroken(ItemStack rod) {
        if (rod.isEmpty()) {
            return false;
        }
        Integer max = rod.getData(DataComponentTypes.MAX_DAMAGE);
        Integer damage = rod.getData(DataComponentTypes.DAMAGE);
        return max != null && max > 0 && damage != null && damage >= max - 1;
    }

    /** 내구도 −1 (0이 되기 전에 멈춘다: 부서지지 않고 수리 안내). */
    private void damageRod(Player player) {
        ItemStack rod = player.getInventory().getItemInMainHand();
        if (rod.isEmpty() || rod.getType().getMaxDurability() <= 0 || rodBroken(rod)) {
            return;
        }
        Integer max = rod.getData(DataComponentTypes.MAX_DAMAGE);
        Integer damage = rod.getData(DataComponentTypes.DAMAGE);
        if (max != null && damage != null && damage + 1 >= max - 1) {
            rod.setData(DataComponentTypes.DAMAGE, max - 1);
            api.messages().send(player, "fishing.rod_broken");
            return;
        }
        player.damageItemStack(EquipmentSlot.HAND, 1);
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
                case CAST -> tickCast(player, s);
                case WAITING, BITE -> {
                    s.castTicks++;
                    ShadowControl shadow = s.shadow;
                    if (shadow != null && s.state == SessionState.WAITING) {
                        shadow.tickWaiting();
                    }
                }
                case MINIGAME -> tickMinigame(player, s);
                default -> {
                    // IDLE·HOOKED·RESULT: 할 일 없음
                }
            }
            if (ticks % 20 == 0) {
                FishingSession.PendingRoll pending = s.pending;
                if (pending != null && !Instant.now().isBefore(pending.expiresAt())) {
                    discardPending(s);
                }
            }
        }
        for (TensionSessionImpl t : List.copyOf(tensions.values())) {
            tickTension(t);
        }
    }

    private void tickCast(Player player, FishingSession s) {
        FishHook hook = s.hook;
        s.castTicks++;
        if (hook == null || !hook.isValid() || s.castTicks > MAX_CAST_TICKS) {
            s.clearCast();
            return;
        }
        if (hook.getState() == FishHook.HookState.BOBBING || hook.isInWater()) {
            land(player, s, hook);
        }
    }

    /** 방향 입력: 이동 키 또는 시점 회전 (탈것 위에서는 시점 회전). */
    private Minigame.Input input(
            Player player,
            boolean sneak,
            boolean left,
            boolean right,
            boolean look,
            float baseYaw) {
        if (look || player.isInsideVehicle()) {
            float delta = wrap(loc(player).getYaw() - baseYaw);
            return new Minigame.Input(sneak, delta < -LOOK_THRESHOLD, delta > LOOK_THRESHOLD);
        }
        return new Minigame.Input(sneak, left, right);
    }

    private static float wrap(float degrees) {
        float d = degrees % 360;
        if (d > 180) {
            d -= 360;
        } else if (d < -180) {
            d += 360;
        }
        return d;
    }

    private void tickMinigame(Player player, FishingSession s) {
        Minigame game = s.minigame;
        FishDef fish = s.fish;
        if (game == null || fish == null) {
            return;
        }
        Minigame.Pattern warningBefore = game.warning();
        MinigameResult result =
                game.tick(
                        input(
                                player,
                                s.inputSneak,
                                s.inputLeft,
                                s.inputRight,
                                s.settings.lookMode(),
                                s.baseYaw));
        Minigame.Pattern warning = game.warning();
        if (warning != null && warningBefore == null) {
            api.ui()
                    .title(
                            player,
                            Component.empty(),
                            api.messages()
                                    .get(
                                            "fishing.pattern."
                                                    + warning.name().toLowerCase(Locale.ROOT)),
                            Duration.ZERO,
                            Duration.ofMillis(700),
                            Duration.ofMillis(200));
        }
        ShadowControl shadow = s.shadow;
        if (shadow != null) {
            shadow.tickFight(game.direction(), game.ticks());
            if (game.active() == Minigame.Pattern.JUMP && s.lastActive != Minigame.Pattern.JUMP) {
                shadow.jump(
                        presenter.jumpViewers(s.settings.intensity()) ? nearby(player) : List.of());
            }
        }
        s.lastActive = game.active();
        if (game.phase() > s.lastPhase) {
            s.lastPhase = game.phase();
            phaseSky(player, game.phase(), s.settings.intensity(), fish);
        }
        warnBorder(player, game.tension(), s.borderWarn, w -> s.borderWarn = w);
        s.reel = reelSound(player, s.inputSneak, s.reel);
        if (game.ticks() % 2 == 0) {
            api.ui().actionBar(player, gauge(game));
            BossBar bar = s.bar;
            if (bar != null) {
                bar.progress((float) game.hpRatio());
            }
        }
        if (result != null) {
            end(player, s, result);
        }
    }

    private void tickTension(TensionSessionImpl t) {
        Player player = Bukkit.getPlayer(t.player());
        if (player == null) {
            t.close(MinigameResult.ESCAPED);
            return;
        }
        if (!t.isActive()) {
            return;
        }
        Minigame game = t.game();
        MinigameResult result =
                game.tick(
                        input(
                                player,
                                inputSneak(player),
                                inputLeft(player),
                                inputRight(player),
                                FishingSettings.of(api.flags(), player.getUniqueId()).lookMode(),
                                t.baseYaw));
        warnBorder(player, game.tension(), t.borderWarn, w -> t.borderWarn = w);
        t.reel = reelSound(player, inputSneak(player), t.reel);
        if (game.ticks() % 2 == 0) {
            api.ui().actionBar(player, gauge(game));
            BossBar bar = t.bar;
            if (bar != null) {
                bar.progress((float) game.hpRatio());
            }
        }
        if (result != null) {
            t.finish(result);
        }
    }

    private boolean inputSneak(Player player) {
        FishingSession s = sessions.get(player.getUniqueId());
        return s != null && s.inputSneak;
    }

    private boolean inputLeft(Player player) {
        FishingSession s = sessions.get(player.getUniqueId());
        return s != null && s.inputLeft;
    }

    private boolean inputRight(Player player) {
        FishingSession s = sessions.get(player.getUniqueId());
        return s != null && s.inputRight;
    }

    /**
     * 릴 감기 반복 소리: Shift를 누르고 있는 동안 켜고, 떼면 끊는다 ('15. 사운드' 2장 fishing.reel_loop). 플레이어를 따라가며 근처에도
     * 들린다.
     */
    private HarborSounds.Loop reelSound(Player player, boolean reeling, HarborSounds.Loop current) {
        if (reeling && !current.active()) {
            return HarborSounds.loopFrom(
                    tasks, player, presenter.stepSound("reel_loop"), CatchPresenter.SOUND);
        }
        if (!reeling && current.active()) {
            current.stop();
            return HarborSounds.Loop.NONE;
        }
        return current;
    }

    /** 찌가 떨어진 곳: 용암이면 용암 착수, 얼음 구멍(옆이나 위가 얼음)이면 얼음 구멍 착수, 아니면 일반 착수 ('15. 사운드' 4.1). */
    private static String landStep(Location at) {
        Block block = at.getBlock();
        if (block.getType() == Material.LAVA) {
            return "land_lava";
        }
        for (BlockFace face :
                List.of(
                        BlockFace.UP,
                        BlockFace.NORTH,
                        BlockFace.SOUTH,
                        BlockFace.EAST,
                        BlockFace.WEST)) {
            if (Tag.ICE.isTagged(block.getRelative(face).getType())) {
                return "land_ice";
            }
        }
        return "land";
    }

    /** 텐션 0.85 이상이면 Core WorldBorderService에 경고를 요청해 화면 가장자리를 붉게 한다. */
    private void warnBorder(
            Player player,
            double tension,
            boolean active,
            java.util.function.Consumer<Boolean> set) {
        boolean warn = tension >= balance().tensionWarn();
        if (warn && !active) {
            presenter.step(loc(player), "tension_warn");
            api.worldBorders()
                    .request(
                            player,
                            WorldBorderService.Priority.FISHING_TENSION,
                            WorldBorderService.Border.warning(loc(player)));
            set.accept(true);
        } else if (!warn && active) {
            api.worldBorders().clear(player, WorldBorderService.Priority.FISHING_TENSION);
            set.accept(false);
        }
    }

    /** 전설 3페이즈 하늘 연출 (개인 날씨·시간). */
    private void phaseSky(Player player, int phase, int intensity, FishDef fish) {
        if (!fish.grade().isAtLeast(Grade.LEGENDARY) || !presets().shows(intensity, "darkness")) {
            return;
        }
        if (phase == 2) {
            player.setPlayerWeather(WeatherType.DOWNFALL);
        } else if (phase >= 3) {
            player.setPlayerTime(18000, false);
        }
        api.messages()
                .send(
                        player,
                        "fishing.minigame.phase",
                        Placeholder.unparsed("phase", String.valueOf(phase)));
    }

    private List<Player> nearby(Player player) {
        Location at = loc(player);
        List<Player> result = new ArrayList<>();
        for (Player other : player.getWorld().getPlayers()) {
            if (!other.equals(player)
                    && loc(other).distanceSquared(at) <= JUMP_VIEW_RADIUS * JUMP_VIEW_RADIUS) {
                result.add(other);
            }
        }
        return result;
    }

    /** 텐션 게이지 (20칸): 안전 구간 초록, 위험(0.85+) 빨강, 바늘 ▼. 커스텀 폰트 글리프가 준비되면 Ui 글리프로 바꾼다. */
    private static Component gauge(Minigame game) {
        int needle = (int) Math.min(GAUGE_CELLS - 1, Math.floor(game.tension() * GAUGE_CELLS));
        Component bar = Component.empty();
        for (int i = 0; i < GAUGE_CELLS; i++) {
            double pos = (i + 0.5) / GAUGE_CELLS;
            NamedTextColor color =
                    pos >= 0.85
                            ? NamedTextColor.RED
                            : pos >= game.safeLow() && pos <= game.safeHigh()
                                    ? NamedTextColor.GREEN
                                    : NamedTextColor.DARK_GRAY;
            bar =
                    bar.append(
                            Component.text(
                                    i == needle ? "▼" : "|",
                                    i == needle ? NamedTextColor.WHITE : color));
        }
        String arrow =
                switch (game.direction()) {
                    case LEFT -> "◀ ";
                    case RIGHT -> " ▶";
                    case CENTER -> " ● ";
                };
        return Component.text(arrow, NamedTextColor.AQUA).append(bar);
    }

    static BossBar.Color barColor(Grade grade) {
        return switch (grade) {
            case COMMON -> BossBar.Color.WHITE;
            case UNCOMMON -> BossBar.Color.GREEN;
            case RARE -> BossBar.Color.BLUE;
            case EPIC -> BossBar.Color.PURPLE;
            case LEGENDARY -> BossBar.Color.YELLOW;
            case MYTHIC -> BossBar.Color.RED;
        };
    }

    private void lockMovement(Player player, boolean lock) {
        AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        speed.removeModifier(moveLock);
        if (lock) {
            speed.addTransientModifier(
                    new AttributeModifier(
                            moveLock, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
    }

    /** 미니게임 종료 처리 (성공·실패·중단). */
    private void end(Player player, FishingSession s, MinigameResult result) {
        FishDef fish = s.fish;
        Minigame game = s.minigame;
        BossBar bar = s.bar;
        if (bar != null) {
            api.ui().hideBossBar(player, bar);
        }
        s.bar = null;
        s.reel.stop();
        s.reel = HarborSounds.Loop.NONE;
        lockMovement(player, false);
        if (s.borderWarn) {
            api.worldBorders().clear(player, WorldBorderService.Priority.FISHING_TENSION);
        }
        if (s.lastPhase > 1) {
            player.resetPlayerWeather();
            player.resetPlayerTime();
        }
        RegionId region = s.region;
        ItemId bait = s.bait;
        boolean legendBait = s.legendBait;
        Location hookLoc = s.hookLoc;
        FishingSession.MythicHold hold = s.mythic;
        closeShadow(s, result == MinigameResult.CAUGHT);
        s.state = SessionState.RESULT;
        s.clearCast();
        s.pending = null;
        if (fish == null || game == null) {
            return;
        }
        stats.record(fish.grade(), result, game.ticks(), Instant.now());
        Bukkit.getPluginManager()
                .callEvent(new MinigameEndEvent(player, fish, result, game.ticks()));
        if (hold != null && result != MinigameResult.CAUGHT) {
            // 신화 예약 반환
            releaseMythic(hold);
        }
        if (bait != null && player.isOnline()) {
            if (BaitRule.consumeOnResult(legendBait, result, fish.grade())) {
                consumeBait(player, bait);
            }
            if (BaitRule.refund(
                    legendBait,
                    result,
                    random.nextDouble(),
                    stat(player.getUniqueId(), "bait_save_chance"))) {
                refundBait(player, bait);
            }
        }
        switch (result) {
            case CAUGHT -> {
                if (region != null) {
                    catchFish(player, s, fish, region, hookLoc);
                }
            }
            case LINE_BROKE -> {
                damageRod(player);
                presenter.step(hookLoc != null ? hookLoc : loc(player), "line_snap");
                api.messages().send(player, "fishing.result.line_broke");
            }
            case ESCAPED -> {
                damageRod(player);
                presenter.step(
                        hookLoc != null ? hookLoc : loc(player),
                        fish.grade().isAtLeast(Grade.RARE) ? "escape_big" : "escape");
                api.messages().send(player, "fishing.result.escaped");
            }
            case ABORTED -> {
                // 강제 이동(점검) 등: 일반 미끼는 위에서 돌려줬다
            }
        }
    }

    // ------------------------------------------------------------------ 결과 (성공)

    private void catchFish(
            Player player,
            FishingSession s,
            FishDef fish,
            RegionId region,
            @Nullable Location hookLoc) {
        UUID uuid = player.getUniqueId();
        HarborPlayer hp = api.players().get(player);
        FishingBalance b = balance();
        Quality cap = jobs.qualityCap(uuid);
        QualityRoller.Rolled rolled =
                QualityRoller.roll(fish, stat(uuid, "size_luck"), cap, b.qualityBounds(), random);
        Quality forced = s.forcedQuality;
        if (forced != null) {
            rolled = new QualityRoller.Rolled(rolled.sizeCm(), forced, false);
            s.forcedQuality = null;
        }
        if (rolled.capped()) {
            s.missedBig++;
            api.ui().actionBar(player, api.messages().get("fishing.missed_big"));
        }
        QualityRolledEvent qualityEvent =
                new QualityRolledEvent(
                        player, fish, rolled.sizeCm(), rolled.quality(), rolled.capped());
        Bukkit.getPluginManager().callEvent(qualityEvent);

        CodexProvider provider = codex;
        boolean firstCatch = provider != null && !provider.hasCaught(uuid, fish.id());
        Instant now = Instant.now();
        HarborItem.Fish caught;
        if (fish.releaseOnly()) {
            caught =
                    new HarborItem.Fish(
                            fish.id(),
                            qualityEvent.sizeCm(),
                            qualityEvent.quality(),
                            hp.ref(),
                            hp.season(),
                            now,
                            null,
                            1);
            api.messages()
                    .send(
                            player,
                            "fishing.result.released",
                            Placeholder.unparsed("name", fish.name()));
        } else {
            ItemStack stack =
                    api.items()
                            .createFish(
                                    fish,
                                    qualityEvent.sizeCm(),
                                    qualityEvent.quality(),
                                    hp.ref(),
                                    now);
            caught = readFish(stack, fish, qualityEvent, hp, now);
            give(player, stack);
        }
        if (fish.grade() == Grade.LEGENDARY) {
            // 전설 포획: 그 어종의 천장 카운터를 즉시 0으로
            hp.set(COMPONENT, hp.get(COMPONENT).withPity(fish.id(), 0));
            var _ =
                    api.database()
                            .asyncVoid(
                                    c ->
                                            FishingRepository.resetPity(
                                                    c, uuid, hp.season(), fish.id()));
        }
        damageRod(player);
        junk(player, region, hookLoc);

        Bukkit.getPluginManager()
                .callEvent(
                        new FishCaughtEvent(
                                player,
                                fish,
                                caught,
                                region,
                                fish.releaseOnly(),
                                firstCatch,
                                rolled.capped()));

        // EXP: 등급 기본 × 지역 배율 × 첫 포획 배율 (첫 만남 +1·랭크)
        double exp = b.gradeExp(fish.grade()) * b.regionExpMult(region.id().name());
        if (firstCatch) {
            exp *= b.firstCatchMult(fish.grade()) + stat(uuid, "first_catch_exp_bonus");
        }
        jobs.grantExp(uuid, new ExpGrant(ExpSource.FISHING, exp, region, null));
        if (qualityEvent.quality().stars() >= 4) {
            jobs.claimTierFirstQuality(uuid, qualityEvent.quality());
        }
        celebrate(player, s, fish, caught, firstCatch);
    }

    /** 잡템: 지역 표로 따로 굴려 추가 지급, 보물 지도는 World 확장 지점으로. */
    private void junk(Player player, RegionId region, @Nullable Location hookLoc) {
        JunkTable table = api.content().snapshot().single(JunkTable.TYPE);
        TreasureDropHook hook = treasureHook;
        JunkRoller.Drops drops =
                JunkRoller.roll(
                        table,
                        region,
                        stat(player.getUniqueId(), "treasure_map_chance"),
                        hook != null,
                        random);
        for (JunkTable.Entry entry : drops.items()) {
            api.content()
                    .snapshot()
                    .get(ItemDef.TYPE, entry.item().id())
                    .ifPresent(
                            def -> {
                                give(player, api.items().create(def, entry.amount()));
                                api.messages()
                                        .send(
                                                player,
                                                "fishing.junk",
                                                Placeholder.unparsed("name", def.name()),
                                                Placeholder.unparsed(
                                                        "amount", String.valueOf(entry.amount())));
                            });
        }
        if (drops.treasure() && hook != null) {
            try {
                hook.onTreasureMap(player, hookLoc == null ? loc(player) : hookLoc, region);
            } catch (RuntimeException e) {
                log.warn("TreasureDropHook 오류: {}", String.valueOf(e));
            }
        }
    }

    /** 인벤토리가 가득 차면 발밑에 떨어뜨리고 60초 동안 본인만 주울 수 있게 한다. */
    public void give(Player player, ItemStack stack) {
        for (ItemStack left : player.getInventory().addItem(stack).values()) {
            Item dropped = player.getWorld().dropItem(loc(player), left);
            dropped.setOwner(player.getUniqueId());
            var _ =
                    tasks.later(
                            60 * 20,
                            () -> {
                                if (dropped.isValid()) {
                                    dropped.setOwner(null);
                                }
                            });
        }
    }

    private void celebrate(
            Player player,
            FishingSession s,
            FishDef fish,
            HarborItem.Fish caught,
            boolean firstCatch) {
        String size = String.format(Locale.ROOT, "%.1f", caught.sizeCm());
        Component line =
                api.messages()
                        .get(
                                "fishing.result.caught",
                                Placeholder.unparsed("name", fish.name()),
                                Placeholder.unparsed("size", size),
                                Placeholder.unparsed("quality", caught.quality().display()));
        player.sendMessage(line);
        if (fish.grade().isAtLeast(Grade.EPIC)) {
            api.ui()
                    .title(
                            player,
                            api.messages()
                                    .get(
                                            "item.name." + fish.grade().key(),
                                            Placeholder.unparsed("name", fish.name())),
                            Component.text(size + "cm " + caught.quality().display()),
                            Duration.ofMillis(300),
                            Duration.ofSeconds(3),
                            Duration.ofSeconds(1));
            // 소리는 presets.yml 등급 포획음(catch.*)이 낸다
        }
        presenter.caught(
                player,
                fish,
                caught.sizeCm(),
                caught.quality(),
                firstCatch,
                s.settings.intensity(),
                s.settings.dialog(),
                presentation);
        if (fish.grade().isAtLeast(Grade.LEGENDARY)) {
            String key =
                    fish.grade() == Grade.MYTHIC
                            ? "fishing.announce.mythic"
                            : "fishing.announce.legendary";
            Component announce =
                    api.messages()
                            .get(
                                    key,
                                    Placeholder.unparsed("player", player.getName()),
                                    Placeholder.unparsed("name", fish.name()),
                                    Placeholder.unparsed("size", size));
            api.bus()
                    .publish(
                            Channels.ANNOUNCEMENTS,
                            new Channels.Announcement(
                                    MiniMessage.miniMessage().serialize(announce)));
        }
    }

    // ------------------------------------------------------------------ 입력 · 중단

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
            closeShadow(s, false);
            FishingSession.MythicHold hold = s.mythic;
            if (hold != null) {
                releaseMythic(hold);
            }
            discardPending(s);
        }
        macro.reset(player.getUniqueId());
    }

    /** 미니게임 중이면 실패로 끝낸다 (미끼는 이미 차감, 천장 유지). */
    void abort(Player player, MinigameResult reason) {
        FishingSession s = sessions.get(player.getUniqueId());
        Minigame game = s == null ? null : s.minigame;
        if (s != null && game != null && s.state == SessionState.MINIGAME) {
            game.abort(reason);
            end(player, s, reason);
        }
    }

    /** 서버 종료: 진행 중인 미니게임을 중단(미끼 반환)하고 그림자를 지운다. */
    public void shutdown() {
        for (Map.Entry<UUID, FishingSession> e : sessions.entrySet()) {
            Player player = Bukkit.getPlayer(e.getKey());
            if (player != null) {
                abort(player, MinigameResult.ABORTED);
            }
            closeShadow(e.getValue(), false);
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
        sessions.values().forEach(this::discardPending);
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
