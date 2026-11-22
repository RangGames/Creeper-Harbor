package wiki.creeper.harbor.npc.cutscene;

import com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.FlagService;
import wiki.creeper.harbor.core.api.HandoffBlocker;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborSounds;
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
import wiki.creeper.harbor.npc.content.Spot;
import wiki.creeper.harbor.npc.data.NpcRepository;
import wiki.creeper.harbor.npc.data.RestoreInfo;
import wiki.creeper.harbor.npc.entity.MannequinBody;
import wiki.creeper.harbor.npc.entity.NpcManager;
import wiki.creeper.harbor.npc.logic.CameraPlan;

/**
 * 컷씬 재생기 (docs 20 5.4 컷씬 1~6단계). 카메라는 이 플레이어에게만 보이는 빈 ItemDisplay이고 teleport_duration 보간으로 움직인다.
 * 플레이어는 관전 모드로 카메라에 시점을 고정한다. 시작 전에 cutscene_restore에 복구 정보를 즉시 써서, 퇴장·비정상 종료에도 다음 접속 때 게임 모드·위치를
 * 되돌린다.
 *
 * <p>카메라·배우 표시는 Core 5.10 리그 표시(RigInstance)가 생기면 같은 방식(기본 숨김 + 대상에게만 표시)으로 옮긴다.
 */
public final class CutsceneManager implements CutsceneService, HandoffBlocker, Listener {

    /** 재생 중인 컷씬 하나 (플레이어별 독립 인스턴스). */
    private static final class Playback {
        final Player player;
        final CutsceneDef def;
        final RestoreInfo restore;
        final CompletableFuture<CutsceneResult> future = new CompletableFuture<>();
        final List<Entity> entities = new ArrayList<>();
        final Map<String, MannequinBody> actors = new LinkedHashMap<>();
        final Map<TextDisplay, Integer> lineDisplays = new HashMap<>();
        @Nullable ItemDisplay camera;
        List<CameraPlan.Move> moves = List.of();
        Sequence.@Nullable Handle handle;
        CutsceneResult cancelResult = CutsceneResult.CANCELLED;
        boolean begun;
        boolean finished;
        boolean skipAllowed;
        boolean sneaking;
        int lastSneakPress = -1_000;
        int tick;
        int moveIndex;
        int lineIndex;
        int titleIndex;
        int soundIndex;
        @Nullable Component subtitle;
        int subtitleUntil;

        Playback(Player player, CutsceneDef def, RestoreInfo restore) {
            this.player = player;
            this.def = def;
            this.restore = restore;
        }
    }

    private final Plugin plugin;
    private final HarborApi api;
    private final Tasks tasks;
    private final Worlds worlds;
    private final NpcManager npcs;
    private final Logger log;
    private final Map<UUID, Playback> playing = new HashMap<>();

    /** 접속 전에 읽어 둔 복구 정보 (AsyncPlayerPreLoginEvent → PlayerJoinEvent). */
    private final Map<UUID, RestoreInfo> pendingRestore = new ConcurrentHashMap<>();

    /** 플레이어별 복구 행 쓰기 순서 (저장과 지우기가 엇갈리면 남은 행이 다음 접속 때 컷씬 시작 위치로 끌고 간다). */
    private final Map<UUID, CompletableFuture<Void>> restoreWrites = new ConcurrentHashMap<>();

    /** 이 플레이어의 앞선 복구 행 쓰기가 끝난 뒤 실행한다 (성공·실패와 상관없이 순서만 지킨다). */
    private CompletableFuture<Void> restoreWrite(UUID uuid, SqlConsumer work) {
        CompletableFuture<Void> next =
                restoreWrites
                        .getOrDefault(uuid, CompletableFuture.completedFuture(null))
                        .handle((v, e) -> null)
                        .thenCompose(ignored -> api.database().asyncVoid(work));
        restoreWrites.put(uuid, next);
        var _ = next.whenComplete((v, e) -> restoreWrites.remove(uuid, next));
        return next;
    }

    public CutsceneManager(
            Plugin plugin, HarborApi api, Tasks tasks, Worlds worlds, NpcManager npcs, Logger log) {
        this.plugin = plugin;
        this.api = api;
        this.tasks = tasks;
        this.worlds = worlds;
        this.npcs = npcs;
        this.log = log;
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
            // 1단계: 미니게임·보스전·다른 컷씬 중이면 BUSY
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
        // 2단계: 복구 정보를 즉시 쓴다. 1초를 넘기면 메모리 정보만으로 진행한다 (같은 틱에 모두 시작)
        List<CompletableFuture<Void>> writes = new ArrayList<>();
        for (Playback pb : started) {
            UUID uuid = pb.player.getUniqueId();
            writes.add(restoreWrite(uuid, c -> NpcRepository.saveRestore(c, uuid, pb.restore)));
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

    /** 서버 이동 전 훅: 컷씬 중에는 거부하고 끝난 뒤 다시 시도하게 한다 (docs 20 0.2). */
    @Override
    public Verdict check(Player player, String destination) {
        return isPlaying(player) ? new Deny("cutscene") : ALLOW;
    }

    /** 모듈이 꺼질 때: 재생 중인 컷씬을 모두 되돌린다. */
    public void shutdown() {
        List.copyOf(playing.values()).forEach(pb -> cancel(pb, CutsceneResult.CANCELLED));
    }

    // ------------------------------------------------------------------ 재생

    private void cancel(Playback pb, CutsceneResult result) {
        pb.cancelResult = result;
        Sequence.Handle handle = pb.handle;
        if (handle != null && handle.isRunning()) {
            handle.cancel(); // onCancel → finish
        } else {
            finish(pb, result);
        }
    }

    /** 3단계 (준비) + 4단계 (재생 시작). */
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
            Location cameraAt = location(world.get(), def.camera().getFirst().pos());
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
                                location(world.get(), CameraPlan.at(actor.path(), 0)),
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
            pb.moveIndex = 1; // 0번은 생성 위치
            if (pb.moves.size() > 1) {
                camera.setTeleportDuration(Math.clamp(pb.moves.get(1).duration(), 0, 59));
            }

            // 플레이어: 무적·관전·시점 고정·개인 시간·날씨
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
                        .run(p -> attach(pb))
                        .repeat(def.duration() + 1, 1, p -> step(pb))
                        .onComplete(p -> finish(pb, CutsceneResult.FINISHED))
                        .onCancel(p -> finish(pb, pb.cancelResult))
                        .start();
    }

    private static Location location(World world, Spot spot) {
        return new Location(world, spot.x(), spot.y(), spot.z(), spot.yaw(), spot.pitch());
    }

    private void attach(Playback pb) {
        ItemDisplay camera = pb.camera;
        if (camera != null && !camera.equals(pb.player.getSpectatorTarget())) {
            pb.player.setSpectatorTarget(camera);
        }
    }

    /** 한 틱 진행 (카메라·배우·대사·타이틀·사운드). */
    private void step(Playback pb) {
        int t = pb.tick++;
        Player player = pb.player;
        CutsceneDef def = pb.def;
        ItemDisplay camera = pb.camera;
        if (camera == null) {
            return;
        }
        World world = camera.getWorld();
        attach(pb);
        // 카메라: 때가 된 이동을 보내고, 다음 틱에 보낼 이동의 보간 길이를 한 틱 먼저 맞춘다
        while (pb.moveIndex < pb.moves.size() && pb.moves.get(pb.moveIndex).tick() <= t) {
            camera.teleport(location(world, pb.moves.get(pb.moveIndex).target()));
            pb.moveIndex++;
        }
        if (pb.moveIndex < pb.moves.size() && pb.moves.get(pb.moveIndex).tick() == t + 1) {
            camera.setTeleportDuration(Math.clamp(pb.moves.get(pb.moveIndex).duration(), 0, 59));
        }
        // 배우 경로 (틱마다 보간)
        for (CutsceneDef.Actor actor : def.actors()) {
            MannequinBody body = pb.actors.get(actor.id());
            if (body != null && actor.path().size() > 1 && t <= actor.path().getLast().t()) {
                body.moveTo(location(world, CameraPlan.at(actor.path(), t)));
            }
        }
        // 대사
        while (pb.lineIndex < def.lines().size() && def.lines().get(pb.lineIndex).t() <= t) {
            line(pb, def.lines().get(pb.lineIndex++), t);
        }
        if (pb.subtitle != null) {
            if (t >= pb.subtitleUntil) {
                pb.subtitle = null;
            } else if ((pb.subtitleUntil - t) % 40 == 0) {
                api.ui().actionBar(player, pb.subtitle); // 액션바는 몇 초면 사라지므로 다시 보낸다
            }
        }
        pb.lineDisplays
                .entrySet()
                .removeIf(
                        e -> {
                            if (t >= e.getValue()) {
                                e.getKey().remove();
                                pb.entities.remove(e.getKey());
                                return true;
                            }
                            return false;
                        });
        // 타이틀
        while (pb.titleIndex < def.titles().size() && def.titles().get(pb.titleIndex).t() <= t) {
            CutsceneDef.TitleCue cue = def.titles().get(pb.titleIndex++);
            String sub = cue.subtitle();
            api.ui()
                    .title(
                            player,
                            api.messages().get(cue.title()),
                            sub == null ? Component.empty() : api.messages().get(sub),
                            Duration.ofMillis(cue.fadeIn() * 50L),
                            Duration.ofMillis(cue.stay() * 50L),
                            Duration.ofMillis(cue.fadeOut() * 50L));
        }
        // 사운드
        while (pb.soundIndex < def.sounds().size() && def.sounds().get(pb.soundIndex).t() <= t) {
            CutsceneDef.SoundCue cue = def.sounds().get(pb.soundIndex++);
            // harbor: 소리는 팩 사용 여부에 따라 대체 소리로 (Core HarborSounds, '15. 사운드')
            HarborSounds.play(
                    player,
                    cue.sound(),
                    HarborSounds.Options.DEFAULT.withVolume(cue.volume()).withPitch(cue.pitch()));
        }
    }

    private void line(Playback pb, CutsceneDef.Line line, int t) {
        Player player = pb.player;
        Component text =
                api.messages().get(line.text(), Placeholder.unparsed("player", player.getName()));
        String actorId = line.actor();
        Component subtitle;
        if (actorId == null) {
            subtitle =
                    api.messages()
                            .get("npc.cutscene.narration", Placeholder.component("text", text));
        } else {
            Component name = actorName(pb.def, actorId, player);
            subtitle =
                    api.messages()
                            .get(
                                    "npc.cutscene.subtitle",
                                    Placeholder.component("name", name),
                                    Placeholder.component("text", text));
            MannequinBody body = pb.actors.get(actorId);
            if (body != null && pb.entities.size() < balance().cutsceneMaxEntities()) {
                Location above = Worlds.loc(body.entity()).add(0, 2.2, 0);
                TextDisplay display =
                        above.getWorld()
                                .spawn(
                                        above,
                                        TextDisplay.class,
                                        d -> {
                                            d.setPersistent(false);
                                            d.setVisibleByDefault(false);
                                            d.setBillboard(Display.Billboard.CENTER);
                                            d.text(text);
                                            d.setLineWidth(200);
                                            NpcKeys.tag(
                                                    d, NpcKeys.CUTSCENE, pb.def.id().toString());
                                        });
                player.showEntity(plugin, display);
                pb.entities.add(display);
                pb.lineDisplays.put(display, t + line.ticks());
            }
        }
        pb.subtitle = subtitle;
        pb.subtitleUntil = t + line.ticks();
        api.ui().actionBar(player, subtitle);
    }

    private Component actorName(CutsceneDef def, String actorId, Player viewer) {
        Optional<CutsceneDef.Actor> actor = def.actor(actorId);
        if (actor.isPresent()) {
            String name = actor.get().name();
            if (name != null) {
                return MiniMessage.miniMessage().deserialize(name);
            }
            wiki.creeper.harbor.common.id.NpcId npc = actor.get().npc();
            if (npc != null) {
                return npcs.displayName(viewer, npc);
            }
        }
        return Component.text(actorId);
    }

    /** 6단계 (종료): 위치·게임 모드·시간·날씨 복구, 엔티티·복구 행 삭제, 본 기록 플래그, CutsceneEndEvent. 여러 번 불려도 한 번만 처리한다. */
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
                restorePlayer(player, pb.restore, true, pb.def.end());
            } catch (RuntimeException e) {
                log.error("컷씬 종료 복구 실패 ({}) — 다음 접속 때 다시 복구합니다", player.getName(), e);
                complete(pb, result);
                return;
            }
        }
        var _ = restoreWrite(uuid, c -> NpcRepository.deleteRestore(c, uuid));
        // 실제로 시작한 컷씬만 본 것으로 (기록 대기 중 퇴장·월드 없음 등으로 못 본 컷씬을 건너뛸 수 있게 되지 않도록)
        if (pb.begun && !api.flags().hasPlayer(uuid, seenFlag(pb.def.id()))) {
            var _ = api.flags().setPlayer(uuid, FlagService.Scope.PERM, seenFlag(pb.def.id()));
        }
        complete(pb, result);
    }

    private void complete(Playback pb, CutsceneResult result) {
        Bukkit.getPluginManager().callEvent(new CutsceneEndEvent(pb.player, pb.def.id(), result));
        pb.future.complete(result);
    }

    /**
     * @param samePlace 위치도 되돌릴지 (다른 서버에서 시작한 컷씬이면 게임 모드만)
     * @param end 컷씬의 end 위치 (없으면 원래 위치)
     */
    private void restorePlayer(
            Player player, RestoreInfo info, boolean samePlace, @Nullable Spot end) {
        player.setSpectatorTarget(null);
        GameMode mode;
        try {
            mode = GameMode.valueOf(info.gameMode());
        } catch (IllegalArgumentException e) {
            mode = GameMode.SURVIVAL;
        }
        player.setGameMode(mode);
        player.setAllowFlight(info.allowFlight() || mode == GameMode.CREATIVE);
        player.setFlying(info.flying() && player.getAllowFlight());
        player.resetPlayerTime();
        player.resetPlayerWeather();
        if (samePlace) {
            Location target = null;
            if (end != null) {
                target = worlds.location(end).orElse(null);
            }
            if (target == null) {
                World w = Bukkit.getWorld(info.world());
                if (w != null) {
                    target =
                            new Location(w, info.x(), info.y(), info.z(), info.yaw(), info.pitch());
                }
            }
            if (target != null) {
                player.teleport(target);
            }
        }
    }

    // ------------------------------------------------------------------ 퇴장·비정상 종료 복구

    /** 5단계 (건너뛰기): 이미 본 컷씬이면 10틱 안에 웅크리기 두 번. */
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

    /** 관전 모드에서 웅크리기는 시점 고정을 푸므로 막는다. */
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

    /** 재생 중 퇴장: 바닐라가 플레이어 파일을 저장하기 전에 동기로 되돌린다 (결과 CANCELLED). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Playback pb = playing.get(event.getPlayer().getUniqueId());
        if (pb != null) {
            cancel(pb, CutsceneResult.CANCELLED);
        }
    }

    /** 접속 전에 복구 정보를 읽어 둔다 (메인 스레드에서 DB를 읽지 않도록). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID uuid = event.getUniqueId();
        // 직전 접속의 복구 행 쓰기·지우기가 끝난 뒤 읽는다 (안 그러면 지우기 전 행을 읽어 컷씬 시작 위치로 끌려간다)
        CompletableFuture<Void> previous = restoreWrites.get(uuid);
        if (previous != null) {
            try {
                previous.get(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException
                    | java.util.concurrent.TimeoutException e) {
                log.warn("컷씬 복구 행 정리를 기다리지 못했습니다 ({})", uuid);
            }
        }
        try {
            api.database()
                    .tx(c -> NpcRepository.loadRestore(c, uuid))
                    .ifPresentOrElse(
                            info -> pendingRestore.put(uuid, info),
                            () -> pendingRestore.remove(uuid));
        } catch (java.sql.SQLException e) {
            log.error("컷씬 복구 정보를 읽지 못했습니다 ({})", uuid, e);
        }
    }

    /** 서버 비정상 종료 뒤 첫 접속: 게임 모드는 항상, 위치는 같은 서버일 때만 되돌린다. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        RestoreInfo info = pendingRestore.remove(player.getUniqueId());
        if (info != null) {
            applyRestore(player, info);
        }
    }

    private void applyRestore(Player player, RestoreInfo info) {
        boolean samePlace = info.server().equals(api.infra().serverId());
        if (!samePlace) {
            // 게임 모드는 서버마다 자기 플레이어 파일에 있다. 다른 서버에서 적용하고 지우면, 관전 모드가 저장된 원래 서버에서는 복구할
            // 행이 없어 관전 모드로 남는다 (게다가 이 서버에 원래 서버의 게임 모드를 씌운다). 원래 서버에 들어갈 때까지 둔다
            log.info(
                    "{}의 중단된 컷씬 {} 복구는 원래 서버({})에서 합니다",
                    player.getName(),
                    info.cutscene(),
                    info.server());
            return;
        }
        restorePlayer(player, info, true, null);
        UUID uuid = player.getUniqueId();
        var _ = restoreWrite(uuid, c -> NpcRepository.deleteRestore(c, uuid));
        log.info(
                "{}의 중단된 컷씬 {} 상태를 복구했습니다 (위치 복구: {})",
                player.getName(),
                info.cutscene(),
                samePlace);
    }

    /** /harbor npc cutscene restore: 복구 정보를 강제로 적용한다. 행이 없으면 false. */
    public CompletableFuture<Boolean> forceRestore(Player player) {
        UUID uuid = player.getUniqueId();
        return api.database()
                .async(c -> NpcRepository.loadRestore(c, uuid))
                .thenApplyAsync(
                        info -> {
                            if (info.isEmpty() || !player.isOnline() || isPlaying(player)) {
                                return false;
                            }
                            applyRestore(player, info.get());
                            return true;
                        },
                        tasks.main());
    }
}
