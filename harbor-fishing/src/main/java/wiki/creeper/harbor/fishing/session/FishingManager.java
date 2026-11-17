package wiki.creeper.harbor.fishing.session;

import io.papermc.paper.world.MoonPhase;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
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
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
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
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
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
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.PlayerComponent;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.core.api.event.ContentReloadedEvent;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.api.CodexProvider;
import wiki.creeper.harbor.fishing.api.FishingService;
import wiki.creeper.harbor.fishing.api.MinigameResult;
import wiki.creeper.harbor.fishing.api.SessionState;
import wiki.creeper.harbor.fishing.api.event.FishBiteEvent;
import wiki.creeper.harbor.fishing.api.event.FishCaughtEvent;
import wiki.creeper.harbor.fishing.api.event.FishRollEvent;
import wiki.creeper.harbor.fishing.api.event.FishingCastEvent;
import wiki.creeper.harbor.fishing.api.event.MinigameEndEvent;
import wiki.creeper.harbor.fishing.api.event.QualityRolledEvent;
import wiki.creeper.harbor.fishing.content.FishingBalance;
import wiki.creeper.harbor.fishing.data.FishingData;
import wiki.creeper.harbor.fishing.data.FishingRepository;
import wiki.creeper.harbor.fishing.roll.QualityRoller;
import wiki.creeper.harbor.fishing.roll.RollContext;
import wiki.creeper.harbor.fishing.roll.RollEngine;
import wiki.creeper.harbor.fishing.roll.RollModifiers;
import wiki.creeper.harbor.jobs.api.ExpGrant;
import wiki.creeper.harbor.jobs.api.ExpSource;
import wiki.creeper.harbor.jobs.api.JobsService;

/**
 * 낚시 한 번의 판정과 진행 (docs 20 3.4). 바닐라 낚시 이벤트를 세션 상태 기계에 연결한다. 바닐라 루트는 모두 취소하고 모든 포획은 미니게임을 거친다. 메인
 * 스레드 전용.
 *
 * <p>미니게임 조작: Shift(웅크리기)를 누르고 있으면 감기, A·D로 물고기 방향 따라가기 (Player Input 동기화). 문서의 "낚싯대 consumable을
 * 누르고 있기" 방식은 바닐라 낚싯대 동작과의 충돌 여부를 Phase 1에서 확인한 뒤 바꾼다 (docs 20 3.8 열린 질문).
 */
public final class FishingManager implements FishingService, Listener {

    public static final PlayerComponent<FishingData> COMPONENT =
            new PlayerComponent<>("fishing", FishingRepository::load, FishingRepository::save);

    private static final Duration PENDING_TTL = Duration.ofSeconds(60);
    private static final double BASE_WARN_SECONDS = 0.6;
    private static final int GAUGE_CELLS = 20;

    private final Plugin plugin;
    private final HarborApi api;
    private final Tasks tasks;
    private final JobsService jobs;
    private final Logger log;
    private final MacroGuard macro;
    private final NamespacedKey moveLock;
    private final Map<UUID, FishingSession> sessions = new HashMap<>();
    private final RandomGenerator random = RandomGenerator.getDefault();
    private @Nullable CodexProvider codex;
    private RollEngine engine;

    public FishingManager(Plugin plugin, HarborApi api, Tasks tasks, JobsService jobs, Logger log) {
        this.plugin = plugin;
        this.api = api;
        this.tasks = tasks;
        this.jobs = jobs;
        this.log = log;
        this.macro = new MacroGuard(api, tasks, log);
        this.moveLock = new NamespacedKey(plugin, "minigame_lock");
        this.engine = new RollEngine(api.content().snapshot(), balance());
    }

    /** Paper의 위치 조회는 null 가능으로 표시되어 있지만 접속 중인 엔티티는 항상 위치가 있다. */
    static Location loc(Entity entity) {
        return Objects.requireNonNull(entity.getLocation());
    }

    public void start() {
        var _ = tasks.timer(1, 1, this::tickMinigames);
    }

    FishingBalance balance() {
        return new FishingBalance(api.content().snapshot().single(Balance.TYPE));
    }

    FishingSession sessionOf(Player player) {
        return sessions.computeIfAbsent(player.getUniqueId(), k -> new FishingSession());
    }

    MacroGuard macro() {
        return macro;
    }

    RollEngine engine() {
        return engine;
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
    public CompletableFuture<SimulationResult> simulate(RegionId region, int count) {
        RollEngine snapshotEngine = engine;
        return tasks.supplyAsync(() -> Simulation.run(snapshotEngine, region, count));
    }

    // ------------------------------------------------------------------ 컨텍스트

    /** 위치의 지역. 월드 이름은 balance.yml world_alias로 harbor_main 등에 대응시킬 수 있다 (개발 서버용). */
    Optional<RegionDef> regionAt(Location loc) {
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

    RollContext context(Player player, RegionDef region, @Nullable ItemId bait) {
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
        return new RollContext(
                region.id(),
                timeKey,
                weather,
                moon,
                bait,
                hp.jobTier().level(),
                flag -> api.flags().hasServer(flag),
                fish -> provider != null && provider.hasCaught(uuid, fish),
                Set.of());
    }

    RollModifiers modifiers(UUID uuid, Map<String, Double> gearStats) {
        double rare = stat(uuid, "rare_weight") + gearStats.getOrDefault("rare_weight", 0.0);
        double epic = rare + stat(uuid, "epic_weight") + gearStats.getOrDefault("epic_weight", 0.0);
        Map<Grade, Double> bonus = new EnumMap<>(Grade.class);
        bonus.put(Grade.RARE, rare);
        bonus.put(Grade.EPIC, epic);
        return new RollModifiers(
                bonus,
                stat(uuid, "uncaught_weight"),
                (int) Math.round(stat(uuid, "legend_pity_start")),
                api.modifiers().value(uuid, StatKey.of("legend_chance")),
                Map.of());
    }

    private double stat(UUID uuid, String key) {
        return api.modifiers().value(uuid, StatKey.of(key));
    }

    /** 왼손 미끼 (ItemDef type: bait). */
    private @Nullable ItemId equippedBait(Player player) {
        return itemDef(player.getInventory().getItemInOffHand())
                .filter(d -> d.type() == ItemDef.Type.BAIT)
                .map(ItemDef::id)
                .orElse(null);
    }

    private Optional<ItemDef> itemDef(@Nullable ItemStack stack) {
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

    // ------------------------------------------------------------------ 낚시 이벤트

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        FishingSession s = sessionOf(player);
        switch (event.getState()) {
            case FISHING -> cast(event, player, s);
            case BITE -> bite(player, s);
            case CAUGHT_FISH -> hooked(event, player, s);
            case REEL_IN, FAILED_ATTEMPT, IN_GROUND, CAUGHT_ENTITY -> keepPending(s);
            default -> {
                // LURED: 무시
            }
        }
    }

    private void cast(PlayerFishEvent event, Player player, FishingSession s) {
        if (s.state == SessionState.MINIGAME || s.state == SessionState.RESULT) {
            event.setCancelled(true);
            return;
        }
        if (macro.blocked(player)) {
            event.setCancelled(true);
            api.messages().send(player, "fishing.macro.blocked");
            return;
        }
        Optional<RegionDef> region = regionAt(FishingManager.loc(player));
        if (region.isEmpty()) {
            event.setCancelled(true);
            api.ui().actionBar(player, api.messages().get("fishing.no_region"));
            return;
        }
        ItemId bait = equippedBait(player);
        UUID uuid = player.getUniqueId();
        Map<String, Double> gear = gearStats(player);
        FishDef fish;
        List<FishDef> legends;
        FishingSession.PendingRoll pending = s.pending;
        FishDef forced = s.forcedFish;
        if (forced != null) {
            fish = forced;
            legends = List.of();
            s.forcedFish = null;
        } else if (pending != null
                && Instant.now().isBefore(pending.expiresAt())
                && pending.region().equals(region.get().id())
                && Objects.equals(pending.bait(), bait)) {
            // 재판정 낚시 방지: 같은 지역·미끼면 보류 판정을 그대로 쓴다
            fish = pending.fish();
            legends = pending.legends();
        } else {
            Optional<RollEngine.Outcome> outcome =
                    engine.roll(
                            context(player, region.get(), bait),
                            modifiers(uuid, gear),
                            api.players().get(player).get(COMPONENT).pity(),
                            random);
            if (outcome.isEmpty()) {
                event.setCancelled(true);
                api.ui().actionBar(player, api.messages().get("fishing.no_fish"));
                return;
            }
            fish = outcome.get().fish();
            legends = outcome.get().legendCandidates();
        }
        s.pending = null;
        FishRollEvent roll = new FishRollEvent(player, fish, bait);
        Bukkit.getPluginManager().callEvent(roll);
        if (roll.isCancelled()) {
            s.clearCast();
            return;
        }
        s.fish = roll.fish();
        s.region = region.get().id();
        s.bait = bait;
        s.legends = legends;
        s.state = SessionState.WAITING;

        FishingBalance b = balance();
        double seconds =
                b.biteWaitMin() + random.nextDouble() * (b.biteWaitMax() - b.biteWaitMin());
        seconds *= stat(uuid, "bite_wait") * (1 + gear.getOrDefault("bite_wait", 0.0));
        if (bait == null) {
            seconds *= b.noBaitWaitMult();
        }
        int ticks = (int) Math.max(20, Math.round(seconds * 20));
        FishHook hook = event.getHook();
        hook.setApplyLure(false);
        hook.setWaitTime(ticks, ticks);
        macro.recordCast(player);
        Bukkit.getPluginManager()
                .callEvent(
                        new FishingCastEvent(
                                player, hook.getLocation(), region.get().id(), bait, roll.fish()));
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
        api.ui().actionBar(player, api.messages().get("fishing.bite"));
        Bukkit.getPluginManager().callEvent(new FishBiteEvent(player, fish));
    }

    /** 챔질: 바닐라 루트를 막고, 미끼 1개를 쓰고, 미니게임을 시작한다. */
    private void hooked(PlayerFishEvent event, Player player, FishingSession s) {
        event.setCancelled(true);
        event.getHook().remove();
        FishDef fish = s.fish;
        if (fish == null || (s.state != SessionState.BITE && s.state != SessionState.WAITING)) {
            s.clearCast();
            return;
        }
        if (s.biteAtNanos > 0) {
            macro.recordReaction(player, System.nanoTime() - s.biteAtNanos);
        }
        s.state = SessionState.HOOKED;
        consumeBait(player, s, fish);

        UUID uuid = player.getUniqueId();
        Map<String, Double> gear = gearStats(player);
        FishingBalance b = balance();
        double warn =
                BASE_WARN_SECONDS
                        + stat(uuid, "pattern_warn")
                        + (player.getPing() >= 150 ? b.pingCompensationSeconds() : 0);
        Minigame.Gear minigameGear =
                new Minigame.Gear(
                        stat(uuid, "line_strength") + gear.getOrDefault("line_strength", 0.0),
                        stat(uuid, "reel_speed") + gear.getOrDefault("reel_speed", 0.0),
                        stat(uuid, "tension_safe_zone")
                                + gear.getOrDefault("tension_safe_zone", 0.0),
                        gear.getOrDefault("stability", 0.0),
                        (int) Math.round(warn * 20));
        s.minigame =
                new Minigame(b.minigame(fish.grade()), minigameGear, b.lineStrengthStep(), random);
        s.state = SessionState.MINIGAME;
        s.inputSneak = false;
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

    private void keepPending(FishingSession s) {
        FishDef fish = s.fish;
        RegionId region = s.region;
        if (s.state == SessionState.MINIGAME || s.state == SessionState.RESULT) {
            return;
        }
        if (fish != null && region != null) {
            s.pending =
                    new FishingSession.PendingRoll(
                            region, s.bait, fish, s.legends, Instant.now().plus(PENDING_TTL));
        }
        s.clearCast();
    }

    /** 걸린 순간 1개 차감 (성공·실패 모두). 전설 전용 미끼(stats.legend_only)는 그 미끼를 요구하는 어종이 걸렸을 때만. */
    private void consumeBait(Player player, FishingSession s, FishDef fish) {
        ItemId bait = s.bait;
        if (bait == null) {
            return;
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        Optional<ItemDef> def = itemDef(offhand).filter(d -> d.id().equals(bait));
        if (def.isEmpty()) {
            return;
        }
        boolean legendOnly = def.get().stats().getOrDefault("legend_only", 0.0) > 0;
        if (legendOnly && !fish.bait().contains(bait)) {
            return;
        }
        offhand.setAmount(offhand.getAmount() - 1);
    }

    private void refundBait(Player player, ItemId bait) {
        api.content()
                .snapshot()
                .get(ItemDef.TYPE, bait.id())
                .ifPresent(def -> give(player, api.items().create(def, 1)));
    }

    // ------------------------------------------------------------------ 미니게임

    private void tickMinigames() {
        for (Map.Entry<UUID, FishingSession> entry : sessions.entrySet()) {
            FishingSession s = entry.getValue();
            Minigame game = s.minigame;
            if (s.state != SessionState.MINIGAME || game == null) {
                continue;
            }
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            Minigame.Pattern warningBefore = game.warning();
            MinigameResult result =
                    game.tick(new Minigame.Input(s.inputSneak, s.inputLeft, s.inputRight));
            if (game.warning() != null && warningBefore == null) {
                Minigame.Pattern warning = game.warning();
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

    private static BossBar.Color barColor(Grade grade) {
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
        lockMovement(player, false);
        RegionId region = s.region;
        ItemId bait = s.bait;
        s.clearCast();
        s.pending = null;
        if (fish == null || game == null) {
            return;
        }
        Bukkit.getPluginManager()
                .callEvent(new MinigameEndEvent(player, fish, result, game.ticks()));
        switch (result) {
            case CAUGHT -> {
                if (region != null) {
                    catchFish(player, s, fish, region, bait);
                }
            }
            case LINE_BROKE -> {
                damageRod(player);
                api.messages().send(player, "fishing.result.line_broke");
            }
            case ESCAPED -> {
                damageRod(player);
                api.messages().send(player, "fishing.result.escaped");
            }
            case ABORTED -> {
                // 강제 이동(점검) 등: 미끼를 돌려준다
                if (bait != null && player.isOnline()) {
                    refundBait(player, bait);
                }
            }
        }
    }

    private void damageRod(Player player) {
        ItemStack rod = player.getInventory().getItemInMainHand();
        if (!rod.isEmpty() && rod.getType().getMaxDurability() > 0) {
            player.damageItemStack(EquipmentSlot.HAND, 1);
        }
    }

    // ------------------------------------------------------------------ 결과 (성공)

    private void catchFish(
            Player player, FishingSession s, FishDef fish, RegionId region, @Nullable ItemId bait) {
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
            caught =
                    api.items()
                            .read(stack)
                            .filter(i -> i instanceof HarborItem.Fish)
                            .map(i -> (HarborItem.Fish) i)
                            .orElse(
                                    new HarborItem.Fish(
                                            fish.id(),
                                            qualityEvent.sizeCm(),
                                            qualityEvent.quality(),
                                            hp.ref(),
                                            hp.season(),
                                            now,
                                            null,
                                            1));
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
        if (bait != null && random.nextDouble() < stat(uuid, "bait_save_chance")) {
            refundBait(player, bait);
        }
        damageRod(player);

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
        celebrate(player, fish, caught);
    }

    /** 인벤토리가 가득 차면 발밑에 떨어뜨리고 60초 동안 본인만 주울 수 있게 한다. */
    private void give(Player player, ItemStack stack) {
        for (ItemStack left : player.getInventory().addItem(stack).values()) {
            Item dropped = player.getWorld().dropItem(FishingManager.loc(player), left);
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

    private void celebrate(Player player, FishDef fish, HarborItem.Fish caught) {
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
            player.playSound(FishingManager.loc(player), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        if (fish.grade().isAtLeast(Grade.LEGENDARY)) {
            Component announce =
                    api.messages()
                            .get(
                                    "fishing.announce.legendary",
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
        FishingSession s = sessions.get(event.getPlayer().getUniqueId());
        if (s != null) {
            s.inputSneak = event.getInput().isSneak();
            s.inputLeft = event.getInput().isLeft();
            s.inputRight = event.getInput().isRight();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        if (session(event.getPlayer().getUniqueId()) == SessionState.MINIGAME) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (session(event.getPlayer().getUniqueId()) == SessionState.MINIGAME) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        abort(event.getPlayer(), MinigameResult.ESCAPED);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        abort(event.getPlayer(), MinigameResult.ESCAPED);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        abort(event.getPlayer(), MinigameResult.ESCAPED);
        sessions.remove(event.getPlayer().getUniqueId());
        macro.reset(event.getPlayer().getUniqueId());
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

    @EventHandler
    public void onReload(ContentReloadedEvent event) {
        // 진행 중 세션은 시작 때의 FishDef로 끝까지, 새 캐스팅부터 새 데이터. 보류 판정은 모두 버린다
        engine = new RollEngine(event.snapshot(), balance());
        sessions.values().forEach(s -> s.pending = null);
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
