package wiki.creeper.harbor.quest.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.RegionDef;
import wiki.creeper.harbor.common.db.SqlFunction;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.PlayerComponent;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.quest.api.ObjectiveSignal;
import wiki.creeper.harbor.quest.api.ObjectiveTypeRegistry;
import wiki.creeper.harbor.quest.api.QuestService;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.api.QuestView;
import wiki.creeper.harbor.quest.api.RegionProgressProvider;
import wiki.creeper.harbor.quest.api.event.QuestAbandonedEvent;
import wiki.creeper.harbor.quest.api.event.QuestAcceptedEvent;
import wiki.creeper.harbor.quest.api.event.QuestProgressEvent;
import wiki.creeper.harbor.quest.api.event.QuestReadyEvent;
import wiki.creeper.harbor.quest.api.event.QuestStateChangedEvent;
import wiki.creeper.harbor.quest.content.BoardRules;
import wiki.creeper.harbor.quest.content.ChronicleDef;
import wiki.creeper.harbor.quest.content.CutsceneAt;
import wiki.creeper.harbor.quest.content.Objective;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.content.QuestType;
import wiki.creeper.harbor.quest.content.Rewards;
import wiki.creeper.harbor.quest.data.QuestData;
import wiki.creeper.harbor.quest.data.QuestEntry;
import wiki.creeper.harbor.quest.data.QuestRepository;
import wiki.creeper.harbor.quest.logic.Cycles;
import wiki.creeper.harbor.quest.logic.Matchers;
import wiki.creeper.harbor.quest.logic.Progress;
import wiki.creeper.harbor.quest.logic.QuestMachine;
import wiki.creeper.harbor.quest.logic.Requirements;

/**
 * 퀘스트 엔진 (docs 20 6.4): 상태 계산, 수락·포기, 목표 진행, 역색인. 보고·보상은 {@link RewardPayer}, 게시판은 {@link
 * BoardManager}. 플레이어 데이터는 메인 스레드에서만 읽고 쓴다.
 */
public final class QuestEngine
        implements QuestService, ObjectiveSignal, ObjectiveTypeRegistry, RegionProgressProvider {

    /** 퀘스트 캐시. 쓰기는 상태 전이마다 즉시·진행은 지연 쓰기 큐로 따로 넣으므로 컴포넌트 저장 함수는 비어 있다. */
    public static final PlayerComponent<QuestData> COMPONENT =
            new PlayerComponent<>("quest", QuestRepository::load, (c, uuid, season, data) -> {});

    /** 계산한 상태 (플레이어별 캐시). lockedWeek는 주차만 남았을 때 그 주차. */
    public record Avail(QuestState state, int lockedWeek) {}

    private record CustomType(Plugin owner, Validator validator, Matcher matcher) {}

    final Plugin plugin;
    final HarborApi api;
    final Tasks tasks;
    final Logger log;
    final Deps deps;

    private final Map<String, CustomType> customTypes = new ConcurrentHashMap<>();
    private final Map<UUID, Map<QuestId, Avail>> availCache = new HashMap<>();
    private final Map<UUID, Map<String, List<QuestId>>> index = new HashMap<>();
    private final Map<UUID, CompletableFuture<?>> chains = new HashMap<>();
    private final Set<UUID> busy = new HashSet<>();
    private final Map<String, Instant> abandonCooldown = new HashMap<>();

    private @Nullable RewardPayer payer;
    private @Nullable BoardManager board;
    private @Nullable Tracker tracker;
    private @Nullable ChronicleManager chronicle;

    public QuestEngine(Plugin plugin, HarborApi api, Tasks tasks, Logger log, Deps deps) {
        this.plugin = plugin;
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.deps = deps;
    }

    void attach(
            RewardPayer payer, BoardManager board, Tracker tracker, ChronicleManager chronicle) {
        this.payer = payer;
        this.board = board;
        this.tracker = tracker;
        this.chronicle = chronicle;
    }

    RewardPayer payer() {
        return java.util.Objects.requireNonNull(payer, "RewardPayer");
    }

    BoardManager board() {
        return java.util.Objects.requireNonNull(board, "BoardManager");
    }

    Tracker tracker() {
        return java.util.Objects.requireNonNull(tracker, "Tracker");
    }

    ChronicleManager chronicle() {
        return java.util.Objects.requireNonNull(chronicle, "ChronicleManager");
    }

    /** 메인 스레드 실행기 (UI·명령의 비동기 결과 반영). */
    public java.util.concurrent.Executor mainExecutor() {
        return tasks.main();
    }

    // ------------------------------------------------------------------ 콘텐츠

    public ContentSnapshot snapshot() {
        return api.content().snapshot();
    }

    public Optional<QuestDef> def(QuestId id) {
        return snapshot().get(QuestDef.TYPE, id.id());
    }

    public Collection<QuestDef> defs() {
        return snapshot().all(QuestDef.TYPE).values();
    }

    public BoardRules boardRules() {
        ContentSnapshot s = snapshot();
        return s.typeNames().contains(BoardRules.TYPE.name())
                ? s.single(BoardRules.TYPE)
                : new BoardRules(Map.of(), List.of(), 1, 100, null, null);
    }

    ChronicleDef chronicleDef() {
        ContentSnapshot s = snapshot();
        return s.typeNames().contains(ChronicleDef.TYPE.name())
                ? s.single(ChronicleDef.TYPE)
                : new ChronicleDef(List.of(), List.of(), null, 3, 150, 27);
    }

    Balance balance() {
        return snapshot().single(Balance.TYPE);
    }

    // ------------------------------------------------------------------ 플레이어 데이터

    public int season() {
        return api.infra().season().value();
    }

    public QuestData data(UUID uuid) {
        return api.players().get(uuid).map(p -> p.get(COMPONENT)).orElse(QuestData.EMPTY);
    }

    void put(HarborPlayer hp, QuestData data) {
        hp.set(COMPONENT, data);
        invalidate(hp.uuid());
    }

    /** 상태 캐시·역색인을 버린다 (다음 조회 때 다시 계산). */
    public void invalidate(UUID uuid) {
        availCache.remove(uuid);
        index.remove(uuid);
    }

    public void invalidateAll() {
        availCache.clear();
        index.clear();
    }

    void forget(UUID uuid) {
        invalidate(uuid);
        chains.remove(uuid);
        busy.remove(uuid);
    }

    /** NPC 머리 위 표시를 다시 계산하게 한다. */
    void refreshMarkers(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            deps.markers().refresh(player);
        }
    }

    /** 반복 주기 키 (지금 시각 기준). */
    public String cycleOf(QuestDef def) {
        return Cycles.of(def.repeat(), api.time().gameDay(), season(), deps.clock().week());
    }

    /** 플레이어별 즉시 쓰기 순서 보장 (수락 → 완료 → 지급 기록이 뒤바뀌지 않게). 결과는 메인 스레드. */
    <T> CompletableFuture<T> serial(UUID uuid, SqlFunction<T> work) {
        CompletableFuture<?> previous =
                chains.getOrDefault(uuid, CompletableFuture.completedFuture(null));
        CompletableFuture<T> next =
                previous.handle((r, e) -> null)
                        .thenCompose(ignored -> api.database().async(work))
                        .thenApplyAsync(r -> r, tasks.main());
        chains.put(uuid, next);
        return next;
    }

    static String queueKey(UUID uuid, QuestId quest) {
        return "player:" + uuid + ":quest:" + quest;
    }

    /** 진행을 지연 쓰기 큐에 넣는다 (키별 마지막 값만 씀, 퇴장·서버 이동 때 flushNow로 즉시). */
    void saveLater(UUID uuid, QuestEntry entry) {
        int season = entry.season();
        QuestId quest = entry.quest();
        QuestState state = entry.state();
        Progress progress = entry.progress();
        api.writeBehind()
                .enqueue(
                        queueKey(uuid, quest),
                        c -> QuestRepository.saveProgress(c, uuid, season, quest, state, progress));
    }

    /** 즉시 쓰기 전에 같은 퀘스트의 예약된 진행 저장을 지운다 (오래된 진행이 상태 전이를 덮어쓰지 않게). */
    void cancelPending(UUID uuid, QuestId quest) {
        api.writeBehind().enqueue(queueKey(uuid, quest), c -> {});
    }

    public boolean isBusy(UUID uuid) {
        return busy.contains(uuid);
    }

    boolean lock(UUID uuid) {
        return busy.add(uuid);
    }

    void unlock(UUID uuid) {
        busy.remove(uuid);
    }

    // ------------------------------------------------------------------ 상태 계산

    /** 계산 상태 (캐시). */
    public Avail avail(HarborPlayer hp, QuestDef def) {
        Map<QuestId, Avail> cache = availCache.computeIfAbsent(hp.uuid(), u -> new HashMap<>());
        Avail cached = cache.get(def.id());
        if (cached != null) {
            return cached;
        }
        Avail computed = compute(hp, def);
        cache.put(def.id(), computed);
        return computed;
    }

    private Avail compute(HarborPlayer hp, QuestDef def) {
        QuestData data = hp.get(COMPONENT);
        QuestEntry entry = data.entries().get(def.id());
        if (entry != null) {
            QuestState s = entry.state();
            if (payer != null && payer().completing(hp.uuid(), def.id())) {
                return new Avail(QuestState.COMPLETING, 0);
            }
            if (s == QuestState.ACTIVE || s == QuestState.READY) {
                return new Avail(s, 0);
            }
            boolean sameCycle = entry.progress().cycle().equals(cycleOf(def));
            if ((s == QuestState.COMPLETED || s == QuestState.FAILED)
                    && (def.repeat() == wiki.creeper.harbor.quest.content.Repeat.NONE
                            || sameCycle)) {
                return new Avail(s, 0);
            }
        }
        if (!inWindow(def)) {
            return new Avail(QuestState.LOCKED, 0);
        }
        if (def.type().board() && !board().offered(hp, def)) {
            return new Avail(QuestState.LOCKED, 0);
        }
        if (abandonCooldown(hp.uuid(), def).isPresent()) {
            return new Avail(QuestState.LOCKED, 0);
        }
        Requirements.Outcome outcome = Requirements.evaluate(def.requires(), facts(hp));
        return outcome.met()
                ? new Avail(QuestState.AVAILABLE, 0)
                : new Avail(QuestState.LOCKED, outcome.lockedWeek());
    }

    boolean inWindow(QuestDef def) {
        QuestDef.TimeWindow window = def.timeWindow();
        if (window == null) {
            return true;
        }
        LocalDateTime now = api.time().now().toLocalDateTime();
        LocalDateTime start = window.start();
        LocalDateTime end = window.end();
        return (start == null || !now.isBefore(start)) && (end == null || now.isBefore(end));
    }

    Requirements.Facts facts(HarborPlayer hp) {
        UUID uuid = hp.uuid();
        QuestData data = hp.get(COMPONENT);
        return new Requirements.Facts() {
            @Override
            public boolean completed(QuestId quest) {
                QuestEntry e = data.entries().get(quest);
                return e != null && e.state() == QuestState.COMPLETED;
            }

            @Override
            public int level() {
                return hp.level();
            }

            @Override
            public int jobTier() {
                return hp.jobTier().level();
            }

            @Override
            public boolean playerFlag(String flag) {
                return api.flags().hasPlayer(uuid, flag);
            }

            @Override
            public boolean serverFlag(String flag) {
                return api.flags().hasServer(flag);
            }

            @Override
            public int week() {
                return deps.clock().week();
            }

            @Override
            public int affinityLevel(NpcId npc) {
                return deps.affinity().level(uuid, npc);
            }

            @Override
            public int reputationTier() {
                return deps.reputation().tier(uuid);
            }
        };
    }

    /** 목표 역색인: 목표 유형 → 지금 진행되는 목표가 있는 퀘스트 (docs 20 6.4, sequence는 현재 목표만). */
    Map<String, List<QuestId>> index(HarborPlayer hp) {
        Map<String, List<QuestId>> cached = index.get(hp.uuid());
        if (cached != null) {
            return cached;
        }
        Map<String, List<QuestId>> built = new HashMap<>();
        for (QuestEntry e : hp.get(COMPONENT).entries().values()) {
            if (e.state() != QuestState.ACTIVE) {
                continue;
            }
            def(e.quest())
                    .ifPresent(
                            d -> {
                                for (Objective o : QuestMachine.current(d, e.progress())) {
                                    List<QuestId> list =
                                            built.computeIfAbsent(o.type(), t -> new ArrayList<>());
                                    if (!list.contains(d.id())) {
                                        list.add(d.id());
                                    }
                                }
                            });
        }
        index.put(hp.uuid(), built);
        return built;
    }

    /** 진행 중인 퀘스트 정의·행 (정의가 사라진 ORPHANED는 빠진다). */
    public List<Map.Entry<QuestDef, QuestEntry>> open(HarborPlayer hp) {
        List<Map.Entry<QuestDef, QuestEntry>> result = new ArrayList<>();
        for (QuestEntry e : hp.get(COMPONENT).entries().values()) {
            if (e.state() == QuestState.ACTIVE || e.state() == QuestState.READY) {
                def(e.quest()).ifPresent(d -> result.add(Map.entry(d, e)));
            }
        }
        result.sort(Comparator.comparing(x -> x.getKey().id().id()));
        return result;
    }

    // ------------------------------------------------------------------ QuestService

    @Override
    public QuestState state(UUID player, QuestId quest) {
        Optional<HarborPlayer> hp = api.players().get(player);
        Optional<QuestDef> def = def(quest);
        if (hp.isEmpty()) {
            return QuestState.LOCKED;
        }
        if (def.isEmpty()) {
            return hp.get().get(COMPONENT).entries().containsKey(quest)
                    ? QuestState.ORPHANED
                    : QuestState.LOCKED;
        }
        return avail(hp.get(), def.get()).state();
    }

    @Override
    public List<QuestView> active(UUID player) {
        Optional<HarborPlayer> hp = api.players().get(player);
        if (hp.isEmpty()) {
            return List.of();
        }
        List<QuestView> result = new ArrayList<>();
        for (Map.Entry<QuestDef, QuestEntry> e : open(hp.get())) {
            QuestDef d = e.getKey();
            result.add(
                    new QuestView(
                            d.id(),
                            d.name(),
                            d.type().key(),
                            avail(hp.get(), d).state(),
                            e.getValue().progress().counts(),
                            e.getValue().progress().cycle()));
        }
        return result;
    }

    @Override
    public boolean isCompleted(UUID player, QuestId quest) {
        return state(player, quest) == QuestState.COMPLETED;
    }

    @Override
    public CompletableFuture<Boolean> accept(Player player, QuestId quest) {
        HarborPlayer hp = api.players().get(player);
        Optional<QuestDef> found = def(quest);
        if (found.isEmpty()) {
            api.messages().send(player, "quest.error.unknown");
            return CompletableFuture.completedFuture(false);
        }
        QuestDef def = found.get();
        UUID uuid = hp.uuid();
        Avail avail = avail(hp, def);
        if (avail.state() != QuestState.AVAILABLE) {
            explainLocked(player, def, avail);
            return CompletableFuture.completedFuture(false);
        }
        if (!def.type().board()) {
            long open = open(hp).stream().filter(e -> !e.getKey().type().board()).count();
            int limit = balance().integer("quest.active_limit", 20);
            if (open >= limit) {
                api.messages()
                        .send(
                                player,
                                "quest.error.active_limit",
                                Placeholder.unparsed("limit", String.valueOf(limit)));
                return CompletableFuture.completedFuture(false);
            }
        } else if (!board().canChoose(hp, def)) {
            api.messages().send(player, "quest.board.no_pick");
            return CompletableFuture.completedFuture(false);
        }
        if (!lock(uuid)) {
            api.messages().send(player, "quest.error.busy");
            return CompletableFuture.completedFuture(false);
        }
        Progress.Snapshot snap = payer().snapshot(hp, def);
        String cycle = cycleOf(def);
        Progress progress = Progress.start(def.hash(), cycle, snap, System.currentTimeMillis());
        int rowSeason = season();
        cancelPending(uuid, quest);
        var boardWrite = def.type().board() ? board().chooseWrite(hp, def) : null;
        return serial(
                        uuid,
                        c -> {
                            QuestRepository.AcceptResult r =
                                    QuestRepository.accept(c, uuid, rowSeason, quest, progress);
                            if (r == QuestRepository.AcceptResult.OK && boardWrite != null) {
                                boardWrite.accept(c);
                            }
                            return r;
                        })
                .handle(
                        (result, error) -> {
                            unlock(uuid);
                            if (error != null) {
                                log.error("퀘스트 수락 저장 실패: {} {}", uuid, quest, error);
                                api.messages().send(player, "quest.error.save");
                                return false;
                            }
                            if (result != QuestRepository.AcceptResult.OK) {
                                api.messages().send(player, "quest.error.already");
                                return false;
                            }
                            afterAccept(
                                    player,
                                    hp,
                                    def,
                                    new QuestEntry(quest, rowSeason, QuestState.ACTIVE, progress));
                            return true;
                        });
    }

    private void afterAccept(Player player, HarborPlayer hp, QuestDef def, QuestEntry entry) {
        QuestData data = hp.get(COMPONENT).with(entry);
        if (def.type().board()) {
            data = board().markChosen(data, def);
        }
        if (data.tracked() == null || def.main()) {
            data = data.withTracked(def.id());
        }
        put(hp, data);
        Bukkit.getPluginManager()
                .callEvent(
                        new QuestStateChangedEvent(
                                hp.uuid(), def.id(), QuestState.AVAILABLE, QuestState.ACTIVE));
        Bukkit.getPluginManager()
                .callEvent(new QuestAcceptedEvent(player, def.id(), def.type().key()));
        api.messages().send(player, "quest.accepted", Placeholder.unparsed("name", def.name()));
        player.playSound(player, Sound.UI_TOAST_IN, 1f, 1.2f);
        refreshGauges(player, def.id());
        checkReady(player, def);
        HarborId cutscene = def.cutscene();
        if (cutscene != null && def.cutsceneAt().kind() == CutsceneAt.Kind.ACCEPT) {
            playCutscene(player, cutscene);
        }
        startCutsceneObjective(player, def);
        refreshMarkers(hp.uuid());
    }

    /** 잠긴 이유 안내 (주차 잠금이면 "N주차에 열립니다 (D-n)"). */
    public void explainLocked(Player player, QuestDef def, Avail avail) {
        switch (avail.state()) {
            case ACTIVE, READY, COMPLETING -> api.messages().send(player, "quest.error.already");
            case COMPLETED -> api.messages().send(player, "quest.error.completed");
            case FAILED -> api.messages().send(player, "quest.error.failed");
            default -> {
                if (avail.lockedWeek() > 0) {
                    api.messages()
                            .send(
                                    player,
                                    "quest.locked.week",
                                    Placeholder.unparsed(
                                            "week", String.valueOf(avail.lockedWeek())),
                                    Placeholder.unparsed(
                                            "days",
                                            String.valueOf(daysUntilWeek(avail.lockedWeek()))));
                } else {
                    Optional<Instant> cooldown = abandonCooldown(player.getUniqueId(), def);
                    if (cooldown.isPresent()) {
                        long minutes =
                                Math.max(
                                        1,
                                        Duration.between(Instant.now(), cooldown.get()).toMinutes()
                                                + 1);
                        api.messages()
                                .send(
                                        player,
                                        "quest.locked.cooldown",
                                        Placeholder.unparsed("minutes", String.valueOf(minutes)));
                    } else {
                        api.messages().send(player, "quest.locked.requires");
                    }
                }
            }
        }
    }

    /** 그 주차가 시작할 때까지 남은 날 (D-n). */
    public long daysUntilWeek(int week) {
        long days = Duration.between(api.time().now(), deps.clock().weekStart(week)).toDays();
        return Math.max(0, days + 1);
    }

    @Override
    public CompletableFuture<Boolean> abandon(Player player, QuestId quest) {
        HarborPlayer hp = api.players().get(player);
        Optional<QuestDef> def = def(quest);
        QuestEntry entry = hp.get(COMPONENT).entries().get(quest);
        if (def.isEmpty() || entry == null) {
            api.messages().send(player, "quest.error.not_active");
            return CompletableFuture.completedFuture(false);
        }
        if (!QuestMachine.canAbandon(def.get(), avail(hp, def.get()).state())) {
            api.messages().send(player, "quest.error.cannot_abandon");
            return CompletableFuture.completedFuture(false);
        }
        UUID uuid = hp.uuid();
        if (!lock(uuid)) {
            api.messages().send(player, "quest.error.busy");
            return CompletableFuture.completedFuture(false);
        }
        cancelPending(uuid, quest);
        int rowSeason = entry.season();
        return serial(
                        uuid,
                        c -> {
                            QuestRepository.delete(c, uuid, rowSeason, quest);
                            return Boolean.TRUE;
                        })
                .handle(
                        (ok, error) -> {
                            unlock(uuid);
                            if (error != null) {
                                log.error("퀘스트 포기 저장 실패: {} {}", uuid, quest, error);
                                api.messages().send(player, "quest.error.save");
                                return false;
                            }
                            int minutes = balance().integer("quest.abandon_cooldown_minutes", 10);
                            abandonCooldown.put(
                                    uuid + "|" + quest + "|" + entry.progress().cycle(),
                                    Instant.now().plus(Duration.ofMinutes(minutes)));
                            put(hp, hp.get(COMPONENT).without(quest));
                            Bukkit.getPluginManager()
                                    .callEvent(
                                            new QuestStateChangedEvent(
                                                    uuid,
                                                    quest,
                                                    entry.state(),
                                                    QuestState.ABANDONED));
                            Bukkit.getPluginManager()
                                    .callEvent(new QuestAbandonedEvent(player, quest));
                            api.messages()
                                    .send(
                                            player,
                                            "quest.abandoned",
                                            Placeholder.unparsed("name", def.get().name()));
                            refreshMarkers(uuid);
                            return true;
                        });
    }

    /** 포기 후 같은 cycle 재수락 대기 (제안값 10분, 메모리). */
    Optional<Instant> abandonCooldown(UUID uuid, QuestDef def) {
        String key = uuid + "|" + def.id() + "|" + cycleOf(def);
        Instant until = abandonCooldown.get(key);
        if (until == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(until)) {
            abandonCooldown.remove(key);
            return Optional.empty();
        }
        return Optional.of(until);
    }

    @Override
    public CompletableFuture<Boolean> turnIn(Player player, QuestId quest) {
        return payer().turnIn(player, quest);
    }

    @Override
    public boolean track(Player player, QuestId quest) {
        HarborPlayer hp = api.players().get(player);
        QuestEntry e = hp.get(COMPONENT).entries().get(quest);
        if (e == null || !e.state().open()) {
            return false;
        }
        hp.set(COMPONENT, hp.get(COMPONENT).withTracked(quest));
        def(quest)
                .ifPresent(
                        d ->
                                api.messages()
                                        .send(
                                                player,
                                                "quest.tracking",
                                                Placeholder.unparsed("name", d.name())));
        return true;
    }

    // ------------------------------------------------------------------ 목표 진행

    /**
     * 목표 진행의 공통 처리 (docs 20 6.4 진행 반영): 역색인에서 그 유형의 퀘스트만 보고, 메모리 갱신 → QuestProgressEvent → 액션바 → 지연
     * 쓰기 큐.
     *
     * @param gauge true면 도달 값(value)으로 둔다
     */
    public void progress(
            Player player, String type, Predicate<Objective> match, int amount, boolean gauge) {
        progressWith(player, type, (d, o) -> match.test(o), amount, gauge);
    }

    /** 퀘스트 정의도 보는 판정 (talk 대상 NPC = 목표 → npc 필드 → 보고 NPC). */
    public void progressWith(
            Player player,
            String type,
            java.util.function.BiPredicate<QuestDef, Objective> match,
            int amount,
            boolean gauge) {
        Optional<HarborPlayer> found = api.players().get(player.getUniqueId());
        if (found.isEmpty()) {
            return;
        }
        HarborPlayer hp = found.get();
        List<QuestId> candidates = index(hp).get(type);
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        for (QuestId id : List.copyOf(candidates)) {
            progressQuest(player, hp, id, type, match, amount, gauge);
        }
    }

    /** 퀘스트 하나 진행. 진행됐으면 true. */
    boolean progressQuest(
            Player player,
            HarborPlayer hp,
            QuestId id,
            String type,
            java.util.function.BiPredicate<QuestDef, Objective> match,
            int amount,
            boolean gauge) {
        QuestEntry entry = hp.get(COMPONENT).entries().get(id);
        Optional<QuestDef> found = def(id);
        if (entry == null || found.isEmpty() || entry.state() != QuestState.ACTIVE) {
            return false;
        }
        QuestDef def = found.get();
        Progress progress = entry.progress();
        boolean changed = false;
        List<String> completedKeys = new ArrayList<>();
        for (Objective o : QuestMachine.current(def, progress)) {
            if (!o.type().equals(type) || !match.test(def, o)) {
                continue;
            }
            QuestMachine.Step step =
                    gauge
                            ? QuestMachine.set(def, progress, o.key(), amount)
                            : QuestMachine.add(def, progress, o.key(), amount);
            if (!step.changed()) {
                continue;
            }
            progress = step.progress();
            changed = true;
            completedKeys.addAll(step.completedKeys());
            int value = progress.count(o.key());
            Bukkit.getPluginManager()
                    .callEvent(new QuestProgressEvent(player, id, o.key(), value, o.count()));
            tracker().progressed(player, def, o, value);
        }
        if (!changed) {
            return false;
        }
        boolean ready = QuestMachine.ready(def, progress);
        QuestState next = QuestMachine.afterProgress(entry.state(), ready);
        QuestEntry updated = new QuestEntry(id, entry.season(), next, progress);
        put(hp, hp.get(COMPONENT).with(updated));
        saveLater(hp.uuid(), updated);
        for (String key : completedKeys) {
            HarborId cutscene = def.cutscene();
            if (cutscene != null
                    && def.cutsceneAt().kind() == CutsceneAt.Kind.OBJECTIVE
                    && key.equals(def.cutsceneAt().objectiveKey())) {
                playCutscene(player, cutscene);
            }
        }
        if (next == QuestState.READY) {
            onReady(player, def);
        } else {
            // 다음 목표가 이미 채워져 있을 수 있다 (게이지·플래그), 컷씬 목표면 바로 재생
            refreshGauges(player, id);
            startCutsceneObjective(player, def);
        }
        return true;
    }

    /** 추적 목표가 없거나 이미 채워진 퀘스트(납품만 있는 의뢰 등)는 바로 READY. */
    void checkReady(Player player, QuestDef def) {
        HarborPlayer hp = api.players().get(player);
        QuestEntry e = hp.get(COMPONENT).entries().get(def.id());
        if (e == null || e.state() != QuestState.ACTIVE || !QuestMachine.ready(def, e.progress())) {
            return;
        }
        QuestEntry updated = e.withState(QuestState.READY);
        put(hp, hp.get(COMPONENT).with(updated));
        saveLater(hp.uuid(), updated);
        onReady(player, def);
    }

    private void onReady(Player player, QuestDef def) {
        Bukkit.getPluginManager()
                .callEvent(
                        new QuestStateChangedEvent(
                                player.getUniqueId(),
                                def.id(),
                                QuestState.ACTIVE,
                                QuestState.READY));
        Bukkit.getPluginManager().callEvent(new QuestReadyEvent(player, def.id()));
        String npc = def.turnInNpc().map(n -> plain(deps.npcs().displayName(player, n))).orElse("");
        api.messages()
                .send(
                        player,
                        npc.isEmpty() ? "quest.ready_self" : "quest.ready",
                        Placeholder.unparsed("name", def.name()),
                        Placeholder.unparsed("npc", npc));
        player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        refreshMarkers(player.getUniqueId());
        if (def.turnInNpc().isEmpty() && def.rewards().jobAdvance() == 0 && !hasDeliver(def)) {
            // 보고 NPC가 없는 퀘스트(게시판 의뢰 등)는 바로 완료
            var _ = payer().turnIn(player, def.id());
        }
    }

    static boolean hasDeliver(QuestDef def) {
        return def.objectives().stream().anyMatch(o -> !o.tracked());
    }

    /** 게이지·플래그 목표를 지금 값으로 맞춘다 (수락·접속·레벨업·도감 등록·플래그 변경 때). */
    public void refreshGauges(Player player, @Nullable QuestId only) {
        Optional<HarborPlayer> found = api.players().get(player.getUniqueId());
        if (found.isEmpty()) {
            return;
        }
        HarborPlayer hp = found.get();
        UUID uuid = hp.uuid();
        for (int guard = 0; guard < 8; guard++) {
            boolean any = false;
            for (Map.Entry<QuestDef, QuestEntry> e : open(hp)) {
                QuestDef def = e.getKey();
                if ((only != null && !only.equals(def.id()))
                        || e.getValue().state() != QuestState.ACTIVE) {
                    continue;
                }
                for (Objective o : QuestMachine.current(def, e.getValue().progress())) {
                    int value = gaugeValue(hp, uuid, def, o);
                    if (value < 0) {
                        continue;
                    }
                    String key = o.key();
                    any |=
                            progressQuest(
                                    player,
                                    hp,
                                    def.id(),
                                    o.type(),
                                    (d, x) -> x.key().equals(key),
                                    value,
                                    true);
                    break;
                }
            }
            if (!any) {
                return;
            }
        }
    }

    /** 지금 값 (-1이면 게이지 목표가 아님). */
    private int gaugeValue(HarborPlayer hp, UUID uuid, QuestDef def, Objective o) {
        return switch (o.type()) {
            case "job_level" -> hp.level();
            case "job_tier" -> hp.jobTier().level();
            case "reputation" -> deps.reputation().tier(uuid);
            case "affinity" -> npcOf(def, o).map(n -> deps.affinity().level(uuid, n)).orElse(0);
            case "codex" -> {
                HarborId region =
                        o.target().kind() == wiki.creeper.harbor.quest.content.Target.Kind.REGION
                                ? o.target().id()
                                : null;
                double percent =
                        deps.codex()
                                .percent(
                                        uuid,
                                        o.scope().equals("total")
                                                ? wiki.creeper.harbor.collection.api.CodexScope
                                                        .TOTAL
                                                : wiki.creeper.harbor.collection.api.CodexScope
                                                        .SEASON,
                                        region == null ? null : new RegionId(region));
                yield (int) Math.floor(percent);
            }
            case "flag" -> {
                String flag = o.target().raw();
                boolean on =
                        o.server()
                                ? api.flags().hasServer(flag)
                                : api.flags().hasPlayer(uuid, flag);
                yield on ? o.count() : 0;
            }
            default -> -1;
        };
    }

    /** talk·deliver·affinity 대상 NPC: target → npc 필드 → 보고 NPC. */
    public static Optional<NpcId> npcOf(QuestDef def, Objective o) {
        HarborId target = o.target().id();
        if (target != null && o.target().isId("npc")) {
            return Optional.of(new NpcId(target));
        }
        NpcId npc = o.npc();
        return npc != null ? Optional.of(npc) : def.turnInNpc();
    }

    /** 지금 목표가 cutscene이면 재생한다 (진행을 막는 컷씬, 끝까지 보면 CutsceneEndEvent로 진행). */
    void startCutsceneObjective(Player player, QuestDef def) {
        QuestEntry e = data(player.getUniqueId()).entries().get(def.id());
        if (e == null || e.state() != QuestState.ACTIVE) {
            return;
        }
        for (Objective o : QuestMachine.current(def, e.progress())) {
            HarborId id = o.target().id();
            if (o.type().equals("cutscene") && id != null && !deps.cutscenes().isPlaying(player)) {
                tasks.later(
                        20,
                        () -> {
                            if (player.isOnline()) {
                                playCutscene(player, id);
                            }
                        });
                return;
            }
        }
    }

    void playCutscene(Player player, HarborId cutscene) {
        if (!deps.cutscenes().exists(cutscene)) {
            log.warn("컷씬이 없습니다: {}", cutscene);
            return;
        }
        var _ = deps.cutscenes().play(player, cutscene);
    }

    // ------------------------------------------------------------------ ObjectiveSignal

    @Override
    public void signal(Player player, String type, String target, int amount) {
        progress(player, type, o -> matchCustom(o, type, target), amount, false);
    }

    @Override
    public void set(Player player, String type, String target, int value) {
        progress(player, type, o -> matchCustom(o, type, target), value, true);
    }

    private boolean matchCustom(Objective o, String type, String target) {
        CustomType custom = customTypes.get(type);
        if (custom != null) {
            try {
                return custom.matcher().matches(spec("", o), target);
            } catch (RuntimeException e) {
                log.warn("목표 유형 {} 판정 오류: {}", type, String.valueOf(e));
                return false;
            }
        }
        return Matchers.matchesRaw(o, target);
    }

    static ObjectiveSpec spec(String quest, Objective o) {
        return new ObjectiveSpec(quest, o.key(), o.type(), o.target().raw(), o.count());
    }

    // ------------------------------------------------------------------ ObjectiveTypeRegistry

    @Override
    public void register(Plugin owner, String key, Validator validator, Matcher matcher) {
        if (Objective.BUILTIN.contains(key)
                || customTypes.putIfAbsent(key, new CustomType(owner, validator, matcher))
                        != null) {
            throw new IllegalStateException("이미 등록된 목표 유형입니다: " + key);
        }
    }

    @Override
    public Set<String> types() {
        Set<String> all = new HashSet<>(Objective.BUILTIN);
        all.addAll(customTypes.keySet());
        return Set.copyOf(all);
    }

    /** 꺼진 플러그인의 유형을 지운다. */
    void unregisterOwner(Plugin owner) {
        customTypes.values().removeIf(t -> t.owner().equals(owner));
    }

    /** 콘텐츠 검사: 다른 모듈 유형의 인자 확인. */
    public void validateCustom(QuestDef quest, Consumer<String> error) {
        for (Objective o : quest.objectives()) {
            CustomType custom = customTypes.get(o.type());
            if (custom != null) {
                custom.validator().validate(spec(quest.id().toString(), o), error);
            }
        }
    }

    // ------------------------------------------------------------------ RegionProgressProvider

    /** World 구현이 등록되어 있으면 그것을, 없으면 Quest 기본 구현 (regions.yml unlock 레벨 + 해금 퀘스트). */
    public Optional<RegionId> highestRegion(UUID player) {
        RegisteredServiceProvider<RegionProgressProvider> reg =
                Bukkit.getServicesManager().getRegistration(RegionProgressProvider.class);
        if (reg != null && !reg.getPlugin().equals(plugin)) {
            try {
                return reg.getProvider().highestUnlockedRegion(player);
            } catch (RuntimeException e) {
                log.warn("RegionProgressProvider 오류: {}", String.valueOf(e));
            }
        }
        return highestUnlockedRegion(player);
    }

    @Override
    public Optional<RegionId> highestUnlockedRegion(UUID player) {
        Optional<HarborPlayer> hp = api.players().get(player);
        if (hp.isEmpty()) {
            return Optional.empty();
        }
        Map<HarborId, RegionDef> regions = snapshot().all(RegionDef.TYPE);
        QuestData data = hp.get().get(COMPONENT);
        RegionId best = null;
        for (BoardRules.RegionGold g : boardRules().ladder()) {
            RegionDef region = regions.get(g.region().id());
            if (region == null) {
                continue;
            }
            RegionDef.Unlock unlock = region.unlock();
            QuestId quest = unlock.quest();
            boolean questDone =
                    quest == null
                            || Optional.ofNullable(data.entries().get(quest))
                                    .map(e -> e.state() == QuestState.COMPLETED)
                                    .orElse(false);
            if (hp.get().level() >= unlock.level() && questDone) {
                best = region.id();
            }
        }
        return Optional.ofNullable(best);
    }

    // ------------------------------------------------------------------ 도우미

    static String plain(net.kyori.adventure.text.Component c) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(c);
    }

    /** 사람이 읽는 목표 문구 (액션바·목록). */
    public String objectiveText(Player viewer, QuestDef def, Objective o) {
        String text = o.text();
        if (text != null) {
            return text;
        }
        String target = targetName(viewer, def, o);
        return plain(
                api.messages()
                        .get(
                                "quest.objective." + o.type(),
                                Placeholder.unparsed("target", target),
                                Placeholder.unparsed("count", String.valueOf(o.count()))));
    }

    String targetName(Player viewer, QuestDef def, Objective o) {
        var t = o.target();
        return switch (t.kind()) {
            case ANY -> {
                if (o.type().equals("talk") || o.type().equals("deliver")) {
                    yield npcOf(def, o)
                            .map(n -> plain(deps.npcs().displayName(viewer, n)))
                            .orElse("");
                }
                yield plain(api.messages().get("quest.target.any"));
            }
            case GRADE -> {
                var grade = t.grade();
                String g =
                        grade == null
                                ? ""
                                : plain(api.messages().get("quest.grade." + grade.key()));
                yield t.orAbove()
                        ? plain(
                                api.messages()
                                        .get(
                                                "quest.target.grade_above",
                                                Placeholder.unparsed("grade", g)))
                        : g;
            }
            case REGION -> {
                HarborId id = t.id();
                yield id == null
                        ? t.raw()
                        : snapshot()
                                .get(RegionDef.TYPE, id)
                                .map(RegionDef::name)
                                .orElse(id.toString());
            }
            case POINT -> plain(api.messages().get("quest.target.point"));
            case NAME -> t.raw();
            case ID -> idName(viewer, t.id());
        };
    }

    private String idName(Player viewer, @Nullable HarborId id) {
        if (id == null) {
            return "";
        }
        ContentSnapshot s = snapshot();
        return switch (id.category()) {
            case "fish" ->
                    s.get(wiki.creeper.harbor.common.content.type.FishDef.TYPE, id)
                            .map(wiki.creeper.harbor.common.content.type.FishDef::name)
                            .orElse(id.toString());
            case "item" ->
                    s.get(wiki.creeper.harbor.common.content.type.ItemDef.TYPE, id)
                            .map(wiki.creeper.harbor.common.content.type.ItemDef::name)
                            .orElse(id.toString());
            case "npc" -> plain(deps.npcs().displayName(viewer, new NpcId(id)));
            default -> id.name();
        };
    }

    /** 정의가 사라진 퀘스트 수, 해시가 바뀐 퀘스트 수 (리로드 보고). */
    record MigrationReport(int changed, int orphaned) {}

    /** 리로드·접속 때 진행 이전 (목표 key 기준). */
    MigrationReport migrate(HarborPlayer hp) {
        QuestData data = hp.get(COMPONENT);
        int changed = 0;
        int orphaned = 0;
        Map<QuestId, QuestEntry> next = new LinkedHashMap<>(data.entries());
        for (QuestEntry e : data.entries().values()) {
            if (!e.state().open()) {
                continue;
            }
            Optional<QuestDef> def = def(e.quest());
            if (def.isEmpty()) {
                orphaned++;
                continue;
            }
            if (e.progress().version() == def.get().hash()) {
                continue;
            }
            Progress moved = QuestMachine.migrate(def.get(), e.progress());
            QuestState state =
                    QuestMachine.afterProgress(e.state(), QuestMachine.ready(def.get(), moved));
            QuestEntry updated = new QuestEntry(e.quest(), e.season(), state, moved);
            next.put(e.quest(), updated);
            saveLater(hp.uuid(), updated);
            changed++;
        }
        if (changed > 0) {
            put(hp, new QuestData(next, data.boards(), data.tracked()));
        }
        return new MigrationReport(changed, orphaned);
    }

    // ------------------------------------------------------------------ 이벤트 기간 · 관리

    /** 1분마다: 기간이 끝난 이벤트 퀘스트를 FAILED로 (docs 20 6.4 상태 기계). */
    void failExpiredEvents() {
        for (HarborPlayer hp : api.players().online()) {
            for (Map.Entry<QuestDef, QuestEntry> e : open(hp)) {
                QuestDef def = e.getKey();
                QuestDef.TimeWindow window = def.timeWindow();
                if (window == null
                        || window.end() == null
                        || inWindow(def)
                        || payer().completing(hp.uuid(), def.id())) {
                    continue;
                }
                var _ = setStateNow(hp, e.getValue(), QuestState.FAILED);
                Player player = Bukkit.getPlayer(hp.uuid());
                if (player != null) {
                    api.messages()
                            .send(player, "quest.failed", Placeholder.unparsed("name", def.name()));
                }
            }
        }
    }

    /** 상태를 즉시 바꾼다 (실패·관리 명령). */
    CompletableFuture<Boolean> setStateNow(HarborPlayer hp, QuestEntry entry, QuestState state) {
        UUID uuid = hp.uuid();
        QuestId quest = entry.quest();
        cancelPending(uuid, quest);
        QuestEntry next = entry.withState(state);
        put(hp, hp.get(COMPONENT).with(next));
        Bukkit.getPluginManager()
                .callEvent(new QuestStateChangedEvent(uuid, quest, entry.state(), state));
        refreshMarkers(uuid);
        return serial(
                uuid,
                c -> {
                    QuestRepository.upsert(c, uuid, entry.season(), quest, state, entry.progress());
                    return true;
                });
    }

    /** 관리 명령: 상태 강제 (active · ready · completed · failed). 행이 없으면 지금 시즌 행을 만든다. */
    public CompletableFuture<Boolean> adminSet(Player player, QuestDef def, QuestState state) {
        HarborPlayer hp = api.players().get(player);
        QuestEntry entry =
                hp.get(COMPONENT)
                        .entry(def.id())
                        .orElseGet(
                                () ->
                                        new QuestEntry(
                                                def.id(),
                                                season(),
                                                QuestState.ACTIVE,
                                                Progress.start(
                                                        def.hash(),
                                                        cycleOf(def),
                                                        payer().snapshot(hp, def),
                                                        System.currentTimeMillis())));
        Progress progress = entry.progress();
        if (state == QuestState.COMPLETED) {
            progress = progress.withPaid(Progress.PAID_GRANT).withPaid(Progress.PAID_DONE);
            var _ = setCompletionFlag(hp.uuid(), def);
        }
        return setStateNow(hp, entry.withProgress(progress), state);
    }

    /**
     * 완료 플래그 {@code quest/<유형>/<이름>} (퀘스트 ID 경로 그대로). Economy 상점·레시피 해금과 World 진입 조건이 퀘스트 완료를 이
     * 플래그로 읽는다 (Quest에 의존하지 않게). 메인은 영구, 나머지는 시즌 범위.
     */
    public CompletableFuture<Void> setCompletionFlag(UUID uuid, QuestDef def) {
        return api.flags()
                .setPlayer(
                        uuid,
                        def.main()
                                ? wiki.creeper.harbor.core.api.FlagService.Scope.PERM
                                : wiki.creeper.harbor.core.api.FlagService.Scope.SEASON,
                        def.id().id().path());
    }

    /** 관리 명령: 목표 진행 값 수정. */
    public boolean adminProgress(Player player, QuestDef def, String key, int value) {
        HarborPlayer hp = api.players().get(player);
        QuestEntry entry = hp.get(COMPONENT).entries().get(def.id());
        if (entry == null || !entry.state().open() || def.objective(key).isEmpty()) {
            return false;
        }
        Progress progress = QuestMachine.force(def, entry.progress(), key, value);
        QuestState state =
                QuestMachine.afterProgress(entry.state(), QuestMachine.ready(def, progress));
        QuestEntry next = new QuestEntry(def.id(), entry.season(), state, progress);
        put(hp, hp.get(COMPONENT).with(next));
        saveLater(hp.uuid(), next);
        if (state == QuestState.READY && entry.state() != QuestState.READY) {
            onReady(player, def);
        }
        refreshMarkers(hp.uuid());
        return true;
    }

    /** 관리 명령: 행 삭제 (메인도 가능). */
    public CompletableFuture<Boolean> adminReset(Player player, QuestId quest) {
        HarborPlayer hp = api.players().get(player);
        QuestEntry entry = hp.get(COMPONENT).entries().get(quest);
        if (entry == null) {
            return CompletableFuture.completedFuture(false);
        }
        UUID uuid = hp.uuid();
        cancelPending(uuid, quest);
        put(hp, hp.get(COMPONENT).without(quest));
        refreshMarkers(uuid);
        return serial(
                uuid,
                c -> {
                    QuestRepository.delete(c, uuid, entry.season(), quest);
                    return true;
                });
    }

    static Rewards rewardsOf(QuestDef def) {
        return def.rewards();
    }

    static boolean isBoard(QuestType type) {
        return type.board();
    }
}
