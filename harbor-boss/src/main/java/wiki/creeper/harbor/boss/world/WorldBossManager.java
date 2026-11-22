package wiki.creeper.harbor.boss.world;

import com.google.gson.reflect.TypeToken;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.boss.api.Contribution;
import wiki.creeper.harbor.boss.api.EncounterInfo;
import wiki.creeper.harbor.boss.api.EncounterState;
import wiki.creeper.harbor.boss.api.WorldBossService;
import wiki.creeper.harbor.boss.api.WorldBossStatus;
import wiki.creeper.harbor.boss.api.event.WorldBossProgressEvent;
import wiki.creeper.harbor.boss.content.BossBalance;
import wiki.creeper.harbor.boss.content.BossDef;
import wiki.creeper.harbor.boss.data.BossRepository;
import wiki.creeper.harbor.boss.encounter.BossItems;
import wiki.creeper.harbor.boss.encounter.Encounter;
import wiki.creeper.harbor.boss.encounter.EncounterManager;
import wiki.creeper.harbor.boss.logic.UuidV7;
import wiki.creeper.harbor.common.id.BossId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.redis.Channel;
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.common.redis.DistributedLock;
import wiki.creeper.harbor.common.util.Json;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * World boss (Verdigris Thunderfish, docs 20 10.4). Only the one server that acquires
 * harbor:lock:worldboss runs it, broadcasting {stage, percent, participants} on harbor:worldboss
 * every 2 seconds so players on lobby and instance servers also see it as a boss bar. Every 10
 * seconds progress and contributions are stored in the Redis hash harbor:worldboss:state and the
 * DB, so even if the main server dies the fight can continue within 10 minutes with /harbor boss
 * world resume.
 */
public final class WorldBossManager
        implements WorldBossService, EncounterManager.WorldHooks, org.bukkit.event.Listener {

    /**
     * Server flag Quest raises after operator confirmation of the climax (world boss start
     * request).
     */
    public static final String START_FLAG = "climax_start";

    /** harbor:worldboss broadcast payload. */
    public record WorldBossProgress(
            String boss,
            String eventId,
            int stage,
            double percent,
            int participants,
            String state,
            String server,
            String name) {}

    public static final Channel<WorldBossProgress> CHANNEL =
            new Channel<>(Channels.WORLD_BOSS, WorldBossProgress.class);

    public static final String LOCK_KEY = "harbor:lock:worldboss";
    public static final String STATE_KEY = "harbor:worldboss:state";
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final EncounterManager encounters;
    private final BossItems items;

    private DistributedLock.@Nullable Handle lock;
    private @Nullable Encounter running;
    private @Nullable WorldBossStatus status;
    private final BossBar globalBar =
            BossBar.bossBar(Component.empty(), 1f, BossBar.Color.GREEN, BossBar.Overlay.NOTCHED_20);
    private boolean barShown;
    private long lastRemote;

    public WorldBossManager(HarborApi api, Tasks tasks, Logger log, EncounterManager encounters) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.encounters = encounters;
        this.items = new BossItems(api);
    }

    public void start() {
        api.bus()
                .subscribe(CHANNEL, env -> tasks.sync(() -> received(env.server(), env.payload())));
        BossBalance bb = encounters.balance();
        var _ = tasks.timer(bb.worldBroadcastTicks(), bb.worldBroadcastTicks(), this::broadcast);
        var _ = tasks.timer(bb.worldSaveTicks(), bb.worldSaveTicks(), this::save);
        var _ = tasks.timer(100, 100, this::expireRemote);
        // If a request came in before the restart, continue handling it
        var _ =
                tasks.later(
                        100,
                        () -> {
                            if (api.flags().hasServer(START_FLAG)) {
                                climaxRequested();
                            }
                        });
    }

    /**
     * Quest's climax start request (server flag climax_start). Only the one server that acquires
     * the lock starts it and lowers the flag.
     */
    @org.bukkit.event.EventHandler
    public void onFlag(wiki.creeper.harbor.core.api.event.ServerFlagChangedEvent event) {
        if (START_FLAG.equals(event.flag()) && event.value() != null) {
            climaxRequested();
        }
    }

    private void climaxRequested() {
        if (runningHere()) {
            return;
        }
        String raw =
                api.content()
                        .snapshot()
                        .single(wiki.creeper.harbor.common.content.type.Balance.TYPE)
                        .string("boss.world.climax_boss")
                        .orElse("boss/green_thunder_fish");
        BossId boss;
        try {
            boss = new BossId(HarborId.parse(raw));
        } catch (IllegalArgumentException e) {
            log.warn("boss.world.climax_boss 형식이 틀렸습니다: {}", raw);
            return;
        }
        var _ =
                start(boss)
                        .thenAccept(
                                r -> {
                                    if (r == StartResult.STARTED
                                            || r == StartResult.ALREADY_RUNNING) {
                                        var _ = api.flags().unsetServer(START_FLAG);
                                    } else if (r != StartResult.LOCKED
                                            && r != StartResult.NO_ARENA) {
                                        log.warn("클라이맥스 월드 보스 시작 실패: {}", r);
                                    }
                                });
    }

    // ------------------------------------------------------------------ WorldBossService

    @Override
    public Optional<WorldBossStatus> status() {
        Encounter e = running;
        if (e != null && !e.finished()) {
            return Optional.of(statusOf(e));
        }
        return Optional.ofNullable(status);
    }

    @Override
    public boolean runningHere() {
        Encounter e = running;
        return e != null && !e.finished();
    }

    @Override
    public CompletableFuture<StartResult> start(BossId boss) {
        return begin(boss, null);
    }

    /** /harbor boss world resume: continues progress left in Redis (within 10 minutes). */
    public CompletableFuture<StartResult> resume() {
        CompletableFuture<StartResult> out = new CompletableFuture<>();
        var _ =
                tasks.supplyAsync(() -> api.redis().sync().hgetall(STATE_KEY))
                        .whenCompleteAsync(
                                (map, err) -> {
                                    if (err != null || map == null || map.isEmpty()) {
                                        out.complete(StartResult.FAILED);
                                        return;
                                    }
                                    Instant updated =
                                            Instant.ofEpochMilli(
                                                    Long.parseLong(
                                                            map.getOrDefault("updated", "0")));
                                    BossBalance bb = encounters.balance();
                                    if (updated.plus(Duration.ofMinutes(bb.worldResumeMinutes()))
                                            .isBefore(Instant.now())) {
                                        out.complete(StartResult.FAILED);
                                        return;
                                    }
                                    try {
                                        BossId boss =
                                                new BossId(
                                                        HarborId.parse(
                                                                map.getOrDefault("boss", "")));
                                        Saved saved =
                                                new Saved(
                                                        UUID.fromString(
                                                                map.getOrDefault("event", "")),
                                                        Double.parseDouble(
                                                                map.getOrDefault("progress", "1")),
                                                        Long.parseLong(
                                                                map.getOrDefault("seed", "0")),
                                                        contributions(map.get("contrib")),
                                                        activeTicks(map.get("active")));
                                        var _ = begin(boss, saved).thenAccept(out::complete);
                                    } catch (IllegalArgumentException e) {
                                        out.complete(StartResult.FAILED);
                                    }
                                },
                                tasks.main());
        return out;
    }

    /** /harbor boss world stop. */
    public boolean stop() {
        Encounter e = running;
        if (e == null || e.finished()) {
            return false;
        }
        return encounters.forceEnd(e.id, EncounterState.ABORTED);
    }

    private record Saved(
            UUID event,
            double progress,
            long seed,
            Map<UUID, Contribution> contributions,
            Map<UUID, Long> activeTicks) {}

    /**
     * Active time per participant (UUID string → ticks). Empty map for saves without this field.
     */
    private static Map<UUID, Long> activeTicks(@Nullable String json) {
        Map<UUID, Long> out = new HashMap<>();
        if (json == null || json.isEmpty()) {
            return out;
        }
        try {
            Map<String, Long> raw =
                    Json.GSON.fromJson(json, new TypeToken<Map<String, Long>>() {}.getType());
            if (raw != null) {
                raw.forEach(
                        (k, v) -> {
                            if (v != null) {
                                out.put(UUID.fromString(k), v);
                            }
                        });
            }
        } catch (RuntimeException e) {
            // If malformed, continue without active time (contributions are kept)
            out.clear();
        }
        return out;
    }

    private static Map<UUID, Contribution> contributions(@Nullable String json) {
        Map<UUID, Contribution> out = new HashMap<>();
        if (json == null || json.isEmpty()) {
            return out;
        }
        List<Contribution> list =
                Json.GSON.fromJson(json, new TypeToken<List<Contribution>>() {}.getType());
        if (list != null) {
            for (Contribution c : list) {
                out.put(c.player(), c);
            }
        }
        return out;
    }

    private CompletableFuture<StartResult> begin(BossId boss, @Nullable Saved saved) {
        CompletableFuture<StartResult> out = new CompletableFuture<>();
        if (runningHere()) {
            out.complete(StartResult.ALREADY_RUNNING);
            return out;
        }
        Optional<BossDef> def = encounters.definition(boss).filter(BossDef::isWorld);
        if (def.isEmpty()) {
            out.complete(StartResult.UNKNOWN_BOSS);
            return out;
        }
        Optional<World> world = items.world(def.get().arena().world());
        if (world.isEmpty()) {
            out.complete(StartResult.NO_ARENA);
            return out;
        }
        var _ =
                tasks.supplyAsync(() -> api.locks().tryAcquire(LOCK_KEY, LOCK_TTL))
                        .whenCompleteAsync(
                                (handle, err) -> {
                                    if (err != null) {
                                        out.complete(StartResult.FAILED);
                                        return;
                                    }
                                    if (handle == null || handle.isEmpty()) {
                                        out.complete(StartResult.LOCKED);
                                        return;
                                    }
                                    lock = handle.get();
                                    var slot = def.get().arena().slots().getFirst();
                                    Location center =
                                            new Location(
                                                    world.get(),
                                                    slot.center().x(),
                                                    slot.center().y(),
                                                    slot.center().z());
                                    UUID event = saved == null ? UuidV7.next() : saved.event();
                                    long seed =
                                            saved == null
                                                    ? new java.security.SecureRandom().nextLong()
                                                    : saved.seed();
                                    Encounter e =
                                            encounters.startWorld(def.get(), event, center, seed);
                                    if (e == null) {
                                        releaseLock();
                                        out.complete(StartResult.FAILED);
                                        return;
                                    }
                                    if (saved != null) {
                                        e.restore(
                                                saved.progress(),
                                                saved.contributions(),
                                                saved.activeTicks());
                                    }
                                    running = e;
                                    Component msg =
                                            api.messages()
                                                    .get(
                                                            saved == null
                                                                    ? "boss.world.started"
                                                                    : "boss.world.resumed",
                                                            Placeholder.component(
                                                                    "boss", e.bossName()));
                                    api.bus()
                                            .publish(
                                                    Channels.ANNOUNCEMENTS,
                                                    new Channels.Announcement(
                                                            MiniMessage.miniMessage()
                                                                    .serialize(msg)));
                                    out.complete(StartResult.STARTED);
                                },
                                tasks.main());
        return out;
    }

    // ------------------------------------------------------------------ Periodic tasks

    private WorldBossStatus statusOf(Encounter e) {
        return new WorldBossStatus(
                e.def.id(),
                e.id.toString(),
                e.finished() ? 4 : e.stage(),
                Math.round(e.fight.hpRatio() * 1000) / 10.0,
                e.effectiveParticipants(),
                api.infra().serverId(),
                e.info().state());
    }

    /** harbor:worldboss broadcast every 2 seconds. */
    private void broadcast() {
        Encounter e = running;
        if (e == null || e.finished()) {
            return;
        }
        WorldBossStatus s = statusOf(e);
        api.bus()
                .publish(
                        CHANNEL,
                        new WorldBossProgress(
                                s.boss().toString(),
                                s.eventId(),
                                s.stage(),
                                s.percent(),
                                s.participants(),
                                s.state().name(),
                                s.server(),
                                e.def.name()));
    }

    /** Every 10 seconds, stores progress in the Redis hash and DB and extends the lock. */
    private void save() {
        Encounter e = running;
        if (e == null || e.finished()) {
            return;
        }
        List<Contribution> contrib = e.contributions();
        Map<String, String> fields = new HashMap<>();
        fields.put("event", e.id.toString());
        fields.put("boss", e.def.id().toString());
        fields.put("progress", String.valueOf(e.fight.hpRatio()));
        fields.put("stage", String.valueOf(e.stage()));
        fields.put("state", e.fight.state().name());
        fields.put("seed", String.valueOf(e.seed));
        fields.put("server", api.infra().serverId());
        fields.put("updated", String.valueOf(System.currentTimeMillis()));
        fields.put("contrib", Json.GSON.toJson(contrib));
        Map<String, Long> active = new HashMap<>();
        e.activeTicks().forEach((k, v) -> active.put(k.toString(), v));
        fields.put("active", Json.GSON.toJson(active));
        double progress = e.fight.hpRatio();
        int season = wiki.creeper.harbor.season.api.SeasonServices.clock().current().value();
        DistributedLock.Handle handle = lock;
        UUID id = e.id;
        var _ =
                tasks.async(
                        () -> {
                            api.redis().sync().hset(STATE_KEY, fields);
                            if (handle != null && !handle.extend(LOCK_TTL)) {
                                log.warn("월드 보스 잠금 연장 실패 (다른 서버가 가져감?)");
                            }
                        });
        var _ =
                api.database()
                        .asyncVoid(
                                c -> {
                                    BossRepository.updateProgress(
                                            c, id, progress, Math.max(1, contrib.size()));
                                    BossRepository.upsertParticipation(c, id, season, contrib);
                                });
    }

    private void releaseLock() {
        DistributedLock.Handle handle = lock;
        lock = null;
        if (handle != null) {
            var _ = tasks.async(handle::release);
        }
    }

    // ------------------------------------------------------------------
    // EncounterManager.WorldHooks

    @Override
    public void stage(EncounterInfo info, int stage, int participants, double percent) {
        Encounter e = running;
        if (e == null) {
            return;
        }
        WorldBossStatus s = statusOf(e);
        Bukkit.getPluginManager().callEvent(new WorldBossProgressEvent(s, stage));
        Component msg =
                api.messages()
                        .get(
                                "boss.world.stage." + stage,
                                Placeholder.component("boss", e.bossName()),
                                Placeholder.unparsed("participants", String.valueOf(participants)));
        api.bus()
                .publish(
                        Channels.ANNOUNCEMENTS,
                        new Channels.Announcement(MiniMessage.miniMessage().serialize(msg)));
        broadcast();
    }

    @Override
    public void ui(Component name, float progress) {
        globalBar.name(name);
        globalBar.progress(Math.clamp(progress, 0f, 1f));
        Encounter e = running;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (e != null && e.isMember(p.getUniqueId())) {
                p.hideBossBar(globalBar);
            } else {
                p.showBossBar(globalBar);
            }
        }
        barShown = true;
    }

    @Override
    public void ended(EncounterInfo info, EncounterState result) {
        Encounter e = running;
        running = null;
        if (e != null) {
            api.bus()
                    .publish(
                            CHANNEL,
                            new WorldBossProgress(
                                    info.boss().toString(),
                                    info.id().toString(),
                                    4,
                                    Math.round(info.hpRatio() * 1000) / 10.0,
                                    e.effectiveParticipants(),
                                    result.name(),
                                    api.infra().serverId(),
                                    e.def.name()));
        }
        status =
                new WorldBossStatus(
                        info.boss(),
                        info.id().toString(),
                        4,
                        info.hpRatio() * 100,
                        0,
                        api.infra().serverId(),
                        result);
        if (result != EncounterState.ABORTED) {
            var _ = tasks.async(() -> api.redis().sync().del(STATE_KEY));
        }
        releaseLock();
        var _ = tasks.later(200, this::hideBar);
    }

    // ------------------------------------------------------------------ Broadcasts from other
    // servers

    private void received(String server, WorldBossProgress p) {
        if (server.equals(api.infra().serverId())) {
            return;
        }
        EncounterState state;
        try {
            state = EncounterState.valueOf(p.state());
            status =
                    new WorldBossStatus(
                            new BossId(HarborId.parse(p.boss())),
                            p.eventId(),
                            p.stage(),
                            p.percent(),
                            p.participants(),
                            p.server(),
                            state);
        } catch (IllegalArgumentException e) {
            return;
        }
        lastRemote = System.currentTimeMillis();
        if (state.ended()) {
            var _ = tasks.later(200, this::hideBar);
            return;
        }
        Component name =
                api.messages()
                        .get(
                                "boss.world.remote_bar",
                                Placeholder.component(
                                        "boss", MiniMessage.miniMessage().deserialize(p.name())),
                                Placeholder.unparsed(
                                        "percent", String.valueOf(Math.round(p.percent()))),
                                Placeholder.unparsed(
                                        "participants", String.valueOf(p.participants())));
        globalBar.name(name);
        globalBar.progress((float) Math.clamp(p.percent() / 100.0, 0.0, 1.0));
        for (Player pl : Bukkit.getOnlinePlayers()) {
            pl.showBossBar(globalBar);
        }
        barShown = true;
    }

    /** Removes the boss bar when the running server dies and broadcasts stop. */
    private void expireRemote() {
        if (barShown && running == null && System.currentTimeMillis() - lastRemote > 10_000) {
            hideBar();
        }
    }

    private void hideBar() {
        if (running != null) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.hideBossBar(globalBar);
        }
        barShown = false;
    }

    public void shutdown() {
        save();
        running = null;
        hideBar();
        DistributedLock.Handle handle = lock;
        lock = null;
        if (handle != null) {
            try {
                handle.release();
            } catch (RuntimeException ex) {
                log.warn("월드 보스 잠금 해제 실패: {}", String.valueOf(ex.getMessage()));
            }
        }
    }
}
