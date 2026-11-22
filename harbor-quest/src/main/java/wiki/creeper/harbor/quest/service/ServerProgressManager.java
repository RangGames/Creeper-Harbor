package wiki.creeper.harbor.quest.service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import wiki.creeper.harbor.collection.api.AquariumService;
import wiki.creeper.harbor.common.config.ServerRole;
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.common.redis.DistributedLock;
import wiki.creeper.harbor.quest.api.ServerProgressService;
import wiki.creeper.harbor.quest.content.ChronicleDef;
import wiki.creeper.harbor.quest.content.ProgressRules;
import wiki.creeper.harbor.quest.data.QuestRepository;
import wiki.creeper.harbor.quest.logic.ClimaxMath;

/**
 * Server progress and climax unlock (docs 20 6.4). Every check_interval the main server takes the
 * distributed lock harbor:lock:climax and checks the ch. 7 clear count (clears this season +
 * chronicle recaps) and the aquarium donation rate. When met, it sets the server flag climax_ready
 * (FlagService propagates it via harbor:flag) and posts a harbor:announce announcement. Starting
 * the world boss after operator confirmation is up to Boss (requested via the server flag
 * climax_start; Boss sets climax_done after CLEARED).
 */
public final class ServerProgressManager implements ServerProgressService {

    static final String READY_FLAG = "climax_ready";
    static final String START_FLAG = "climax_start";
    private static final String LOCK = "harbor:lock:climax";

    private final QuestEngine engine;

    ServerProgressManager(QuestEngine engine) {
        this.engine = engine;
    }

    ProgressRules rules() {
        var s = engine.snapshot();
        return s.typeNames().contains(ProgressRules.TYPE.name())
                ? s.single(ProgressRules.TYPE)
                : new ProgressRules(0.8, 7, 50, 0.15, 5, true);
    }

    void start() {
        if (engine.api.infra().role() != ServerRole.MAIN) {
            return;
        }
        long ticks = rules().checkMinutes() * 60L * 20L;
        var _ = engine.tasks.timer(ticks, ticks, this::check);
    }

    @Override
    public CompletableFuture<Long> chapterClearCount(int chapter) {
        Optional<ChronicleDef.Chapter> c = engine.chronicleDef().chapter(chapter);
        if (c.isEmpty()) {
            return CompletableFuture.completedFuture(0L);
        }
        int season = engine.season();
        var fin = c.get().finalQuest();
        String flag = ChronicleManager.RECAP_FLAG + chapter;
        return engine.api
                .database()
                .async(con -> QuestRepository.chapterClears(con, fin, flag, season))
                .thenApplyAsync(n -> n, engine.tasks.main());
    }

    @Override
    public CompletableFuture<ClimaxStatus> climaxStatus() {
        ProgressRules rules = rules();
        AquariumService.Status aquarium = engine.deps.aquarium().status();
        Optional<ChronicleDef.Chapter> c = engine.chronicleDef().chapter(rules.chapter());
        int season = engine.season();
        boolean flagged = engine.api.flags().hasServer(READY_FLAG);
        String flag = ChronicleManager.RECAP_FLAG + rules.chapter();
        return engine.api
                .database()
                .async(
                        con -> {
                            long clears =
                                    c.isEmpty()
                                            ? 0
                                            : QuestRepository.chapterClears(
                                                    con, c.get().finalQuest(), flag, season);
                            long active = QuestRepository.activePlayers(con, season);
                            return new ClimaxStatus(
                                    aquarium.rate(),
                                    rules.donationRate(),
                                    clears,
                                    ClimaxMath.requiredClears(rules, active),
                                    active,
                                    ClimaxMath.ready(rules, aquarium.rate(), clears, active),
                                    flagged);
                        })
                .thenApplyAsync(s -> s, engine.tasks.main());
    }

    /** Periodic check (on one main server only, distributed lock). */
    void check() {
        if (engine.api.flags().hasServer(READY_FLAG)) {
            return;
        }
        var _ =
                engine.tasks
                        .supplyAsync(
                                () -> engine.api.locks().tryAcquire(LOCK, Duration.ofSeconds(60)))
                        .thenAcceptAsync(
                                lock -> {
                                    if (lock.isEmpty()) {
                                        return;
                                    }
                                    DistributedLock.Handle handle = lock.get();
                                    var _ =
                                            climaxStatus()
                                                    .whenComplete(
                                                            (status, error) -> {
                                                                var _ =
                                                                        engine.tasks.async(
                                                                                handle::release);
                                                                if (error != null) {
                                                                    engine.log.warn(
                                                                            "클라이맥스 판정 실패: {}",
                                                                            String.valueOf(
                                                                                    error
                                                                                            .getMessage()));
                                                                    return;
                                                                }
                                                                if (status.ready()
                                                                        && !status.flagged()) {
                                                                    openClimax(false);
                                                                }
                                                            });
                                },
                                engine.tasks.main());
    }

    /** Sets climax_ready and announces it (can also be forced with an admin command). */
    public void openClimax(boolean forced) {
        engine.log.info("클라이맥스 개방 조건 충족{} → climax_ready", forced ? " (관리자 강제)" : "");
        var _ = engine.api.flags().setServer(READY_FLAG, "1", true);
        if (rules().announce()) {
            Component msg = engine.api.messages().get("quest.announce.climax_ready");
            engine.api
                    .bus()
                    .publish(
                            Channels.ANNOUNCEMENTS,
                            new Channels.Announcement(MiniMessage.miniMessage().serialize(msg)));
        }
        Bukkit.getOnlinePlayers().forEach(p -> engine.refreshMarkers(p.getUniqueId()));
    }

    /**
     * Operator confirmation: requests the world boss start (Boss listens for the server flag
     * climax_start and calls WorldBossService.start).
     */
    public void requestClimaxStart() {
        var _ = engine.api.flags().setServer(START_FLAG, "1", true);
    }
}
