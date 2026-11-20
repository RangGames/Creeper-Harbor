package wiki.creeper.harbor.quest.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
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
import wiki.creeper.harbor.quest.content.BoardRules;
import wiki.creeper.harbor.quest.content.ChronicleDef;
import wiki.creeper.harbor.quest.content.Objective;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.content.QuestType;
import wiki.creeper.harbor.quest.content.Rewards;
import wiki.creeper.harbor.quest.data.QuestData;
import wiki.creeper.harbor.quest.data.QuestEntry;
import wiki.creeper.harbor.quest.data.QuestRepository;
import wiki.creeper.harbor.quest.logic.Cycles;
import wiki.creeper.harbor.quest.logic.Progress;
import wiki.creeper.harbor.quest.logic.Requirements;

/**
 * 퀘스트 엔진 (docs 20 6.4). 다른 모듈·UI가 보는 창구이고, 일은 협력 객체가 한다: 상태 계산 {@link QuestAvailability}, 수락·포기
 * {@link QuestLifecycle}, 목표 진행 {@link ObjectiveProgressor}, 알림 {@link QuestNotifier}, 문구 {@link
 * QuestTexts}, 이전·관리 {@link QuestMaintenance}, 외부 목표 유형 {@link CustomObjectives}. 보고·보상은 {@link
 * RewardPayer}, 게시판은 {@link BoardManager}. 플레이어 데이터는 메인 스레드에서만 읽고 쓴다.
 */
public final class QuestEngine
        implements QuestService, ObjectiveSignal, ObjectiveTypeRegistry, RegionProgressProvider {

    /** 퀘스트 캐시. 쓰기는 상태 전이마다 즉시·진행은 지연 쓰기 큐로 따로 넣으므로 컴포넌트 저장 함수는 비어 있다. */
    public static final PlayerComponent<QuestData> COMPONENT =
            new PlayerComponent<>("quest", QuestRepository::load, (c, uuid, season, data) -> {});

    /** 계산한 상태 (플레이어별 캐시). lockedWeek는 주차만 남았을 때 그 주차. */
    public record Avail(QuestState state, int lockedWeek) {}

    final Plugin plugin;
    final HarborApi api;
    final Tasks tasks;
    final Logger log;
    final Deps deps;

    private final Map<UUID, CompletableFuture<?>> chains = new HashMap<>();
    private final Set<UUID> busy = new HashSet<>();

    private final QuestAvailability availability = new QuestAvailability(this);
    private final QuestLifecycle lifecycle = new QuestLifecycle(this);
    private final ObjectiveProgressor progressor = new ObjectiveProgressor(this);
    private final QuestNotifier notifier = new QuestNotifier(this);
    private final QuestTexts texts = new QuestTexts(this);
    private final QuestMaintenance maintenance = new QuestMaintenance(this);
    private final CustomObjectives customs;

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
        this.customs = new CustomObjectives(log);
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
        availability.invalidate(uuid);
    }

    public void invalidateAll() {
        availability.invalidateAll();
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

    // ------------------------------------------------------------------ 협력 객체

    QuestAvailability availability() {
        return availability;
    }

    QuestNotifier notifier() {
        return notifier;
    }

    ObjectiveProgressor progressor() {
        return progressor;
    }

    QuestTexts texts() {
        return texts;
    }

    boolean payerAttached() {
        return payer != null;
    }

    // ------------------------------------------------------------------ 상태 계산 (QuestAvailability)

    /** 계산 상태 (캐시). */
    public Avail avail(HarborPlayer hp, QuestDef def) {
        return availability.avail(hp, def);
    }

    boolean inWindow(QuestDef def) {
        return availability.inWindow(def);
    }

    Requirements.Facts facts(HarborPlayer hp) {
        return availability.facts(hp);
    }

    /** 목표 역색인: 목표 유형 → 지금 진행되는 목표가 있는 퀘스트 (docs 20 6.4, sequence는 현재 목표만). */
    Map<String, List<QuestId>> index(HarborPlayer hp) {
        return availability.index(hp);
    }

    /** 진행 중인 퀘스트 정의·행 (정의가 사라진 ORPHANED는 빠진다). */
    public List<Map.Entry<QuestDef, QuestEntry>> open(HarborPlayer hp) {
        return availability.open(hp);
    }

    /** 포기 후 같은 cycle 재수락 대기 (제안값 10분, 메모리). */
    Optional<Instant> abandonCooldown(UUID uuid, QuestDef def) {
        return availability.abandonCooldown(uuid, def);
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
        return lifecycle.accept(player, quest);
    }

    /** 잠긴 이유 안내 (주차 잠금이면 "N주차에 열립니다 (D-n)"). */
    public void explainLocked(Player player, QuestDef def, Avail avail) {
        lifecycle.explainLocked(player, def, avail);
    }

    /** 그 주차가 시작할 때까지 남은 날 (D-n). */
    public long daysUntilWeek(int week) {
        return lifecycle.daysUntilWeek(week);
    }

    @Override
    public CompletableFuture<Boolean> abandon(Player player, QuestId quest) {
        return lifecycle.abandon(player, quest);
    }

    @Override
    public CompletableFuture<Boolean> turnIn(Player player, QuestId quest) {
        return payer().turnIn(player, quest);
    }

    @Override
    public boolean track(Player player, QuestId quest) {
        return lifecycle.track(player, quest);
    }

    // ------------------------------------------------------------------ 목표 진행
    // (ObjectiveProgressor)

    /**
     * 목표 진행의 공통 처리 (docs 20 6.4 진행 반영).
     *
     * @param gauge true면 도달 값(value)으로 둔다
     */
    public void progress(
            Player player, String type, Predicate<Objective> match, int amount, boolean gauge) {
        progressor.progressWith(player, type, (d, o) -> match.test(o), amount, gauge);
    }

    /** 퀘스트 정의도 보는 판정 (talk 대상 NPC = 목표 → npc 필드 → 보고 NPC). */
    public void progressWith(
            Player player,
            String type,
            BiPredicate<QuestDef, Objective> match,
            int amount,
            boolean gauge) {
        progressor.progressWith(player, type, match, amount, gauge);
    }

    /** 추적 목표가 없거나 이미 채워진 퀘스트(납품만 있는 의뢰 등)는 바로 READY. */
    void checkReady(Player player, QuestDef def) {
        progressor.checkReady(player, def);
    }

    static boolean hasDeliver(QuestDef def) {
        return def.objectives().stream().anyMatch(o -> !o.tracked());
    }

    /** 게이지·플래그 목표를 지금 값으로 맞춘다 (수락·접속·레벨업·도감 등록·플래그 변경 때). */
    public void refreshGauges(Player player, @Nullable QuestId only) {
        progressor.refreshGauges(player, only);
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
        progressor.startCutsceneObjective(player, def);
    }

    void playCutscene(Player player, HarborId cutscene) {
        progressor.playCutscene(player, cutscene);
    }

    // ------------------------------------------------------------------ ObjectiveSignal ·
    // ObjectiveTypeRegistry

    @Override
    public void signal(Player player, String type, String target, int amount) {
        progress(player, type, o -> customs.matches(o, type, target), amount, false);
    }

    @Override
    public void set(Player player, String type, String target, int value) {
        progress(player, type, o -> customs.matches(o, type, target), value, true);
    }

    @Override
    public void register(Plugin owner, String key, Validator validator, Matcher matcher) {
        customs.register(owner, key, validator, matcher);
    }

    @Override
    public Set<String> types() {
        return customs.types();
    }

    /** 꺼진 플러그인의 유형을 지운다. */
    void unregisterOwner(Plugin owner) {
        customs.unregisterOwner(owner);
    }

    /** 콘텐츠 검사: 다른 모듈 유형의 인자 확인. */
    public void validateCustom(QuestDef quest, Consumer<String> error) {
        customs.validate(quest, error);
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

    // ------------------------------------------------------------------ 문구 (QuestTexts)

    static String plain(net.kyori.adventure.text.Component c) {
        return QuestTexts.plain(c);
    }

    /** 사람이 읽는 목표 문구 (액션바·목록). */
    public String objectiveText(Player viewer, QuestDef def, Objective o) {
        return texts.objectiveText(viewer, def, o);
    }

    String targetName(Player viewer, QuestDef def, Objective o) {
        return texts.targetName(viewer, def, o);
    }

    // ------------------------------------------------------------------ 이전 · 기간 · 관리
    // (QuestMaintenance)

    /** 정의가 사라진 퀘스트 수, 해시가 바뀐 퀘스트 수 (리로드 보고). */
    record MigrationReport(int changed, int orphaned) {}

    /** 리로드·접속 때 진행 이전 (목표 key 기준). */
    MigrationReport migrate(HarborPlayer hp) {
        return maintenance.migrate(hp);
    }

    /** 1분마다: 기간이 끝난 이벤트 퀘스트를 FAILED로 (docs 20 6.4 상태 기계). */
    void failExpiredEvents() {
        maintenance.failExpiredEvents();
    }

    /** 관리 명령: 상태 강제 (active · ready · completed · failed). 행이 없으면 지금 시즌 행을 만든다. */
    public CompletableFuture<Boolean> adminSet(Player player, QuestDef def, QuestState state) {
        return maintenance.adminSet(player, def, state);
    }

    /** 완료 플래그 {@code quest/<유형>/<이름>} (메인은 영구, 나머지는 시즌 범위). */
    public CompletableFuture<Void> setCompletionFlag(UUID uuid, QuestDef def) {
        return maintenance.setCompletionFlag(uuid, def);
    }

    /** 관리 명령: 목표 진행 값 수정. */
    public boolean adminProgress(Player player, QuestDef def, String key, int value) {
        return maintenance.adminProgress(player, def, key, value);
    }

    /** 관리 명령: 행 삭제 (메인도 가능). */
    public CompletableFuture<Boolean> adminReset(Player player, QuestId quest) {
        return maintenance.adminReset(player, quest);
    }

    static Rewards rewardsOf(QuestDef def) {
        return def.rewards();
    }

    static boolean isBoard(QuestType type) {
        return type.board();
    }
}
