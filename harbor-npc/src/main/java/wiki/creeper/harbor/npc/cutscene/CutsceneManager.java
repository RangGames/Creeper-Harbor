package wiki.creeper.harbor.npc.cutscene;

import com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.FlagService;
import wiki.creeper.harbor.core.api.HandoffBlocker;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.task.Sequence;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.npc.NpcKeys;
import wiki.creeper.harbor.npc.Worlds;
import wiki.creeper.harbor.npc.api.CutsceneResult;
import wiki.creeper.harbor.npc.api.CutsceneService;
import wiki.creeper.harbor.npc.api.event.CutsceneEndEvent;
import wiki.creeper.harbor.npc.api.event.CutsceneStartEvent;
import wiki.creeper.harbor.npc.content.CutsceneDef;
import wiki.creeper.harbor.npc.content.NpcBalance;
import wiki.creeper.harbor.npc.content.NpcDef;
import wiki.creeper.harbor.npc.content.Skin;
import wiki.creeper.harbor.npc.data.NpcRepository;
import wiki.creeper.harbor.npc.data.RestoreInfo;
import wiki.creeper.harbor.npc.entity.MannequinBody;
import wiki.creeper.harbor.npc.entity.NpcManager;
import wiki.creeper.harbor.npc.logic.CameraPlan;

/**
 * Cutscene player (docs 20 5.4 cutscene steps 1–6). The camera is an empty ItemDisplay visible only
 * to this player and moves via teleport_duration interpolation. The player is put in spectator mode
 * with the view locked to the camera. Restore info is written to cutscene_restore right before
 * starting, so game mode · location are put back on the next join even after a quit or crash.
 *
 * <p>Once Core 5.10 rig display (RigInstance) exists, camera · actor display moves to it in the
 * same way (hidden by default + shown only to the target).
 */
public final class CutsceneManager implements CutsceneService, HandoffBlocker, Listener {

    private final Plugin plugin;
    private final HarborApi api;
    private final Tasks tasks;
    private final Worlds worlds;
    private final NpcManager npcs;
    private final Logger log;
    private final Map<UUID, Playback> playing = new HashMap<>();
    private final CutsceneRestore restore;
    private final CutsceneTicker ticker;

    public CutsceneManager(
            Plugin plugin, HarborApi api, Tasks tasks, Worlds worlds, NpcManager npcs, Logger log) {
        this.plugin = plugin;
        this.api = api;
        this.tasks = tasks;
        this.worlds = worlds;
        this.npcs = npcs;
        this.log = log;
        this.restore = new CutsceneRestore(api, worlds, log);
        this.ticker = new CutsceneTicker(plugin, api, npcs);
    }

    private NpcBalance balance() {
        return new NpcBalance(api.content().snapshot().single(Balance.TYPE));
    }

    static String seenFlag(HarborId cutscene) {
        return cutscene.path();
    }

    // ------------------------------------------------------------------ CutsceneService

    @Override
    public CompletableFuture<CutsceneResult> play(Player player, HarborId cutscene) {
        Map<UUID, CompletableFuture<CutsceneResult>> result = playGroup(List.of(player), cutscene);
        return result.getOrDefault(
                player.getUniqueId(), CompletableFuture.completedFuture(CutsceneResult.CANCELLED));
    }

    @Override
    public Map<UUID, CompletableFuture<CutsceneResult>> playGroup(
            Collection<? extends Player> players, HarborId cutscene) {
        Map<UUID, CompletableFuture<CutsceneResult>> futures = new LinkedHashMap<>();
        Optional<CutsceneDef> def = api.content().snapshot().get(CutsceneDef.TYPE, cutscene);
        if (def.isEmpty()) {
            log.warn("없는 컷씬을 재생하려 했습니다: {}", cutscene);
            players.forEach(
                    p ->
                            futures.put(
                                    p.getUniqueId(),
                                    CompletableFuture.completedFuture(CutsceneResult.CANCELLED)));
            return futures;
        }
        List<Playback> started = new ArrayList<>();
        for (Player p : players) {
            // step 1: BUSY if in a minigame · boss fight · another cutscene
            if (!p.isOnline() || npcs.busyReason(p).isPresent()) {
                futures.put(
                        p.getUniqueId(), CompletableFuture.completedFuture(CutsceneResult.BUSY));
                continue;
            }
            Location at = Worlds.loc(p);
            RestoreInfo restore =
                    new RestoreInfo(
                            api.infra().serverId(),
                            cutscene.toString(),
                            at.getWorld().getName(),
                            at.getX(),
                            at.getY(),
                            at.getZ(),
                            at.getYaw(),
                            at.getPitch(),
                            p.getGameMode().name(),
                            p.getAllowFlight(),
                            p.isFlying());
            Playback pb = new Playback(p, def.get(), restore);
            playing.put(p.getUniqueId(), pb);
            futures.put(p.getUniqueId(), pb.future);
            started.add(pb);
        }
        if (started.isEmpty()) {
            return futures;
        }
        // step 2: write restore info right away. Past 1 second, proceed with in-memory info only
        // (everyone starts on the same tick)
        List<CompletableFuture<Void>> writes = new ArrayList<>();
        for (Playback pb : started) {
            UUID uuid = pb.player.getUniqueId();
            writes.add(restore.write(uuid, c -> NpcRepository.saveRestore(c, uuid, pb.restore)));
        }
        AtomicBoolean launched = new AtomicBoolean();
        Runnable launch =
                () -> {
                    if (launched.compareAndSet(false, true)) {
                        started.forEach(this::begin);
                    }
                };
        var _ =
                CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new))
                        .whenCompleteAsync(
                                (v, ex) -> {
                                    if (ex != null) {
                                        log.warn(
                                                "컷씬 복구 정보 저장 실패, 메모리 정보로 진행합니다 ({})", cutscene, ex);
                                    }
                                    launch.run();
                                },
                                tasks.main());
        long timeoutTicks = Math.max(1, balance().cutsceneRestoreTimeoutMillis() / 50);
        var _ =
                tasks.later(
                        timeoutTicks,
                        () -> {
                            if (!launched.get()) {
                                log.warn(
                                        "컷씬 복구 정보 저장이 {}ms를 넘어 메모리 정보로 진행합니다 ({})",
                                        timeoutTicks * 50,
                                        cutscene);
                                launch.run();
                            }
                        });
        return futures;
    }

    @Override
    public boolean isPlaying(Player player) {
        return playing.containsKey(player.getUniqueId());
    }

    @Override
    public Optional<HarborId> playing(Player player) {
        Playback pb = playing.get(player.getUniqueId());
        return pb == null ? Optional.empty() : Optional.of(pb.def.id());
    }

    @Override
    public boolean hasSeen(Player player, HarborId cutscene) {
        return api.flags().hasPlayer(player.getUniqueId(), seenFlag(cutscene));
    }

    @Override
    public boolean stop(Player player) {
        Playback pb = playing.get(player.getUniqueId());
        if (pb == null) {
            return false;
        }
        cancel(pb, CutsceneResult.CANCELLED);
        return true;
    }

    @Override
    public boolean exists(HarborId cutscene) {
        return api.content().snapshot().has(CutsceneDef.TYPE.name(), cutscene);
    }

    /**
     * Pre-server-move hook: refuse during a cutscene so it is retried after it ends (docs 20 0.2).
     */
    @Override
    public Verdict check(Player player, String destination) {
        return isPlaying(player) ? new Deny("cutscene") : ALLOW;
    }

    /** On module disable: reverts every cutscene in progress. */
    public void shutdown() {
        List.copyOf(playing.values()).forEach(pb -> cancel(pb, CutsceneResult.CANCELLED));
    }

    // ------------------------------------------------------------------ playback

    private void cancel(Playback pb, CutsceneResult result) {
        pb.cancelResult = result;
        Sequence.Handle handle = pb.handle;
        if (handle != null && handle.isRunning()) {
            handle.cancel(); // onCancel → finish
        } else {
            finish(pb, result);
        }
    }

    /** Step 3 (setup) + step 4 (start playback). */
    private void begin(Playback pb) {
        Player player = pb.player;
        if (pb.finished) {
            return;
        }
        if (!player.isOnline() || playing.get(player.getUniqueId()) != pb) {
            finish(pb, CutsceneResult.CANCELLED);
            return;
        }
        CutsceneDef def = pb.def;
        Optional<World> world = worlds.world(def.world());
        if (world.isEmpty()) {
            log.warn("컷씬 {}의 월드 {}가 이 서버에 없습니다", def.id(), def.world());
            finish(pb, CutsceneResult.CANCELLED);
            return;
        }
        NpcBalance balance = balance();
        try {
            String tag = def.id().toString();
            Location cameraAt = CutsceneTicker.location(world.get(), def.camera().getFirst().pos());
            ItemDisplay camera =
                    world.get()
                            .spawn(
                                    cameraAt,
                                    ItemDisplay.class,
                                    d -> {
                                        d.setPersistent(false);
                                        d.setVisibleByDefault(false);
                                        d.setTeleportDuration(0);
                                        NpcKeys.tag(d, NpcKeys.CUTSCENE, tag);
                                    });
            pb.camera = camera;
            pb.entities.add(camera);
            player.showEntity(plugin, camera);
            int limit = Math.max(0, balance.cutsceneMaxEntities() - 1);
            for (CutsceneDef.Actor actor : def.actors()) {
                if (pb.entities.size() >= limit) {
                    log.warn("컷씬 {} 배우가 엔티티 상한({})을 넘어 {} 이후를 생략합니다", def.id(), limit, actor.id());
                    break;
                }
                wiki.creeper.harbor.common.id.NpcId npcId = actor.npc();
                Optional<NpcDef> npc = npcId == null ? Optional.empty() : npcs.def(npcId);
                Skin actorSkin = actor.skin();
                String actorPose = actor.pose();
                Skin skin = actorSkin != null ? actorSkin : npc.map(NpcDef::skin).orElse(null);
                String pose = actorPose != null ? actorPose : npc.map(NpcDef::pose).orElse(null);
                MannequinBody body =
                        MannequinBody.spawn(
                                CutsceneTicker.location(
                                        world.get(), CameraPlan.at(actor.path(), 0)),
                                skin,
                                pose,
                                m -> {
                                    m.setVisibleByDefault(false);
                                    NpcKeys.tag(m, NpcKeys.CUTSCENE, tag);
                                });
                pb.actors.put(actor.id(), body);
                pb.entities.add(body.entity());
                player.showEntity(plugin, body.entity());
            }
            pb.moves = CameraPlan.plan(def.camera(), balance.cameraStepTicks());
            pb.moveIndex = 1; // index 0 is the spawn position
            if (pb.moves.size() > 1) {
                camera.setTeleportDuration(Math.clamp(pb.moves.get(1).duration(), 0, 59));
            }

            // player: invulnerable · spectator · locked view · personal time · weather
            pb.begun = true;
            player.closeDialog();
            player.setGameMode(GameMode.SPECTATOR);
            player.teleport(cameraAt);
            Long time = def.time();
            if (time != null) {
                player.setPlayerTime(time, false);
            }
            String weather = def.weather();
            if (weather != null) {
                player.setPlayerWeather(
                        weather.equals("clear") ? WeatherType.CLEAR : WeatherType.DOWNFALL);
            }
            pb.skipAllowed = def.skippable().allows(hasSeen(player, def.id()));
            if (pb.skipAllowed) {
                api.ui().actionBar(player, api.messages().get("npc.cutscene.skip_hint"));
            }
        } catch (RuntimeException e) {
            log.error("컷씬 {} 준비 실패", def.id(), e);
            finish(pb, CutsceneResult.CANCELLED);
            return;
        }
        Bukkit.getPluginManager().callEvent(new CutsceneStartEvent(player, def.id()));
        pb.handle =
                Sequence.of(plugin, player)
                        .waitTicks(1)
                        .run(p -> ticker.attach(pb))
                        .repeat(def.duration() + 1, 1, p -> ticker.step(pb))
                        .onComplete(p -> finish(pb, CutsceneResult.FINISHED))
                        .onCancel(p -> finish(pb, pb.cancelResult))
                        .start();
    }

    /**
     * Step 6 (end): restores location · game mode · time · weather, removes entities and the
     * restore row, sets the seen flag, fires CutsceneEndEvent. Runs only once even if called
     * repeatedly.
     */
    private void finish(Playback pb, CutsceneResult result) {
        if (pb.finished) {
            return;
        }
        pb.finished = true;
        Player player = pb.player;
        UUID uuid = player.getUniqueId();
        playing.remove(uuid, pb);
        for (Entity e : pb.entities) {
            e.remove();
        }
        pb.entities.clear();
        pb.lineDisplays.clear();
        if (pb.begun) {
            try {
                restore.restorePlayer(player, pb.restore, true, pb.def.end());
            } catch (RuntimeException e) {
                log.error("컷씬 종료 복구 실패 ({}) — 다음 접속 때 다시 복구합니다", player.getName(), e);
                complete(pb, result);
                return;
            }
        }
        var _ = restore.write(uuid, c -> NpcRepository.deleteRestore(c, uuid));
        // only a cutscene that actually began counts as seen (so one missed due to quitting while
        // the
        // write was pending, a missing world, etc. does not become skippable)
        if (pb.begun && !api.flags().hasPlayer(uuid, seenFlag(pb.def.id()))) {
            var _ = api.flags().setPlayer(uuid, FlagService.Scope.PERM, seenFlag(pb.def.id()));
        }
        complete(pb, result);
    }

    private void complete(Playback pb, CutsceneResult result) {
        Bukkit.getPluginManager().callEvent(new CutsceneEndEvent(pb.player, pb.def.id(), result));
        pb.future.complete(result);
    }

    // ------------------------------------------------------------------ quit · crash recovery

    /** Step 5 (skip): for an already-seen cutscene, sneak twice within 10 ticks. */
    @EventHandler
    public void onInput(PlayerInputEvent event) {
        Playback pb = playing.get(event.getPlayer().getUniqueId());
        if (pb == null || !pb.begun) {
            return;
        }
        boolean sneak = event.getInput().isSneak();
        if (sneak && !pb.sneaking && pb.skipAllowed) {
            int now = Bukkit.getCurrentTick();
            if (now - pb.lastSneakPress <= balance().cutsceneSkipWindowTicks()) {
                cancel(pb, CutsceneResult.SKIPPED);
            } else {
                pb.lastSneakPress = now;
            }
        }
        pb.sneaking = sneak;
    }

    /** Sneaking in spectator mode releases the locked view, so block it. */
    @EventHandler(ignoreCancelled = true)
    public void onStopSpectating(PlayerStopSpectatingEntityEvent event) {
        if (playing.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpectateTeleport(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE
                && playing.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p && playing.containsKey(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (playing.containsKey(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player p && playing.containsKey(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Quit during playback: revert synchronously before vanilla saves the player file (result
     * CANCELLED).
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Playback pb = playing.get(event.getPlayer().getUniqueId());
        if (pb != null) {
            cancel(pb, CutsceneResult.CANCELLED);
        }
    }

    /** Reads restore info before login (so the main thread never reads the DB). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        restore.preload(event);
    }

    /** First join after a server crash: game mode always, location only on the same server. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        restore.join(event.getPlayer());
    }

    /** /harbor npc cutscene restore: force-applies the restore info. False if there is no row. */
    public CompletableFuture<Boolean> forceRestore(Player player) {
        UUID uuid = player.getUniqueId();
        return api.database()
                .async(c -> NpcRepository.loadRestore(c, uuid))
                .thenApplyAsync(
                        info -> {
                            if (info.isEmpty() || !player.isOnline() || isPlaying(player)) {
                                return false;
                            }
                            restore.apply(player, info.get());
                            return true;
                        },
                        tasks.main());
    }
}
