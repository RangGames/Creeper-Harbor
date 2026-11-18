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
 * 서버 진행도 · 클라이맥스 개방 (docs 20 6.4). 본 서버가 check_interval마다 분산 잠금 harbor:lock:climax를 잡고 7장 클리어 인원(이번
 * 시즌 클리어 + 연대기 회상)과 수족관 기부율을 확인한다. 충족하면 서버 플래그 climax_ready(FlagService가 harbor:flag로 전파)와
 * harbor:announce 공지. 운영자 확인 뒤 월드 보스 시작은 Boss가 맡는다 (서버 플래그 climax_start 요청, Boss가 CLEARED 뒤
 * climax_done을 세운다).
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

    /** 주기 판정 (메인 서버 한 곳만, 분산 잠금). */
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

    /** climax_ready를 세우고 공지한다 (관리 명령으로 강제도 가능). */
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

    /** 운영자 확인: 월드 보스 시작 요청 (Boss가 서버 플래그 climax_start를 듣고 WorldBossService.start). */
    public void requestClimaxStart() {
        var _ = engine.api.flags().setServer(START_FLAG, "1", true);
    }
}
