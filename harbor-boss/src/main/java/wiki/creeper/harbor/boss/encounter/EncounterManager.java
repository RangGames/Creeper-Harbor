package wiki.creeper.harbor.boss.encounter;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.boss.api.BossRole;
import wiki.creeper.harbor.boss.api.BossService;
import wiki.creeper.harbor.boss.api.Contribution;
import wiki.creeper.harbor.boss.api.EncounterInfo;
import wiki.creeper.harbor.boss.api.EncounterState;
import wiki.creeper.harbor.boss.api.event.BossEncounterEndEvent;
import wiki.creeper.harbor.boss.api.event.BossPhaseChangeEvent;
import wiki.creeper.harbor.boss.content.BossBalance;
import wiki.creeper.harbor.boss.content.BossDef;
import wiki.creeper.harbor.boss.data.BossProfile;
import wiki.creeper.harbor.boss.data.BossRepository;
import wiki.creeper.harbor.boss.logic.Fight;
import wiki.creeper.harbor.boss.party.PartyManager;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.BossId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.PlayerComponent;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.api.FishingService;

/**
 * 조우 수명 관리 (docs 20 10.4 EncounterManager). 소환 조건 검사 → 준비 → 시작 기록 → 매 틱 진행 → 종료 판정 → 결과·보상 → 정리. 시작
 * 검사는 {@link EncounterStarter}, DB 기록은 {@link EncounterRecorder}, 결과 화면은 {@link EncounterResults},
 * 부위 판정(작살 궤적)은 {@link HarpoonTracker}, 입력(QTE·선장)·이탈·재접속·낙수 리스너는 {@link #listener()}에 있다. {@link
 * BossService} 구현. 메인 스레드 전용.
 */
public final class EncounterManager implements BossService {

    /** PlayerComponent&lt;BossProfile&gt;. */
    public static final PlayerComponent<BossProfile> PROFILE =
            new PlayerComponent<>(
                    "boss_profile", BossRepository::loadProfile, BossRepository::saveProfile);

    /** 클릭 대상 바인딩. */
    record Click(Encounter encounter, Encounter.ClickKind kind, int index) {}

    private final HarborApi api;
    private final Plugin plugin;
    private final Tasks tasks;
    private final Logger log;
    private final BossItems items;
    private final PartyManager parties;
    private final FishingService fishing;
    private RewardService rewards;

    private final Map<UUID, Encounter> byId = new LinkedHashMap<>();
    private final Map<UUID, Encounter> byPlayer = new HashMap<>();
    private final Map<UUID, Click> clicks = new HashMap<>();
    private final HarpoonTracker harpoons = new HarpoonTracker(this);
    private final ChunkTickets chunkTickets;
    private final EncounterStarter starter;
    private final EncounterRecorder recorder = new EncounterRecorder(this);
    private final EncounterResults results;
    private final EncounterListener listener;
    private @Nullable WorldHooks worldHooks;
    private @Nullable InstanceHooks instanceHooks;
    private long tick;

    /** 월드 보스 연결 (WorldBossManager). */
    public interface WorldHooks {
        void stage(EncounterInfo info, int stage, int participants, double percent);

        void ui(Component name, float progress);

        void ended(EncounterInfo info, EncounterState result);
    }

    /** 인스턴스 연결 (InstanceManager). */
    public interface InstanceHooks {
        void ended(UUID encounter, Map<UUID, String> origins, Collection<UUID> members);

        void quit(UUID player, UUID encounter);
    }

    public EncounterManager(
            HarborApi api,
            Plugin plugin,
            Tasks tasks,
            Logger log,
            PartyManager parties,
            FishingService fishing) {
        this.api = api;
        this.plugin = plugin;
        this.tasks = tasks;
        this.log = log;
        this.items = new BossItems(api);
        this.parties = parties;
        this.fishing = fishing;
        this.rewards = new RewardService(this);
        this.chunkTickets = new ChunkTickets(plugin);
        this.starter = new EncounterStarter(this, parties);
        this.results = new EncounterResults(api);
        this.listener = new EncounterListener(this);
    }

    public void start() {
        var _ = tasks.timer(1, 1, this::tick);
        recorder.recover();
    }

    /** 조우 이벤트 리스너 (작살 투척·소환·클릭·입력·낙수·접속·퇴장·리로드). 플러그인이 등록한다. */
    public Listener listener() {
        return listener;
    }

    public void setWorldHooks(@Nullable WorldHooks hooks) {
        this.worldHooks = hooks;
    }

    public void setInstanceHooks(@Nullable InstanceHooks hooks) {
        this.instanceHooks = hooks;
    }

    // ------------------------------------------------------------------ 공용
    // (Encounter·RewardService·협력 객체)

    HarborApi api() {
        return api;
    }

    Plugin plugin() {
        return plugin;
    }

    Tasks tasks() {
        return tasks;
    }

    BossItems items() {
        return items;
    }

    FishingService fishing() {
        return fishing;
    }

    Logger log() {
        return log;
    }

    PartyManager parties() {
        return parties;
    }

    HarpoonTracker harpoons() {
        return harpoons;
    }

    @Nullable InstanceHooks instanceHooks() {
        return instanceHooks;
    }

    long now() {
        return tick;
    }

    String serverId() {
        return api.infra().serverId();
    }

    public RewardService rewards() {
        return rewards;
    }

    public BossBalance balance() {
        return new BossBalance(api.content().snapshot().single(Balance.TYPE));
    }

    /** 진행 중 조우 (복사하지 않은 보기, 읽기 전용으로만 쓴다). */
    Collection<Encounter> active() {
        return byId.values();
    }

    /** 새 조우를 등록한다 (EncounterStarter). 참가자 연결은 {@link #bind}로 따로 한다. */
    void register(Encounter e) {
        byId.put(e.id, e);
    }

    @Nullable Click click(UUID entity) {
        return clicks.get(entity);
    }

    // ------------------------------------------------------------------ BossService

    @Override
    public Collection<BossDef> definitions() {
        return api.content().snapshot().all(BossDef.TYPE).values();
    }

    @Override
    public Optional<BossDef> definition(BossId boss) {
        return api.content().snapshot().get(BossDef.TYPE, boss.id());
    }

    @Override
    public Optional<EncounterInfo> encounterOf(UUID player) {
        return encounterOfPlayer(player).map(Encounter::info);
    }

    Optional<Encounter> encounterOfPlayer(UUID player) {
        return Optional.ofNullable(byPlayer.get(player));
    }

    public Optional<Encounter> encounter(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public boolean inEncounter(UUID player) {
        Encounter e = byPlayer.get(player);
        return e != null && !e.finished();
    }

    @Override
    public List<EncounterInfo> encounters() {
        return byId.values().stream().map(Encounter::info).toList();
    }

    public Collection<Encounter> running() {
        return List.copyOf(byId.values());
    }

    @Override
    public StartResult forceStart(BossId bossId, Player leader) {
        Optional<BossDef> def = definition(bossId);
        if (def.isEmpty()) {
            return StartResult.UNKNOWN_BOSS;
        }
        if (def.get().isWorld()) {
            return StartResult.WRONG_SERVER;
        }
        List<Player> party = starter.partyPlayers(leader);
        Location from = BossItems.loc(leader);
        return starter.startField(def.get(), leader, party, null, true, from);
    }

    @Override
    public boolean forceEnd(UUID encounterId, EncounterState result) {
        Encounter e = byId.get(encounterId);
        if (e == null || !result.ended()) {
            return false;
        }
        if (!e.launched()) {
            finishEarly(e, "boss.admin.ended");
            return true;
        }
        for (Fight.Transition t : e.fight.end(result, "admin")) {
            firePhase(e, t);
        }
        finish(e, result, "admin");
        return true;
    }

    // ------------------------------------------------------------------ 시작 (EncounterStarter)

    /** 소환 미끼 사용 (docs 20 10.4 1단계). 조건: 파티 인원 min~max, 전원 경기장 반경 안, 슬롯 여유, 점검 중 아님. */
    StartResult trySummon(Player leader, BossDef def) {
        return starter.trySummon(leader, def);
    }

    /** 인스턴스 서버: 도착한 파티로 레이드 시작 (InstanceManager). */
    public StartResult startInstance(
            BossDef def,
            int slot,
            Location center,
            UUID leader,
            List<Player> party,
            Map<UUID, String> origins) {
        return starter.startInstance(def, slot, center, leader, party, origins);
    }

    /** 월드 보스 시작 (WorldBossManager). 준비 단계 없이 바로 등장. */
    public @Nullable Encounter startWorld(BossDef def, UUID eventId, Location center, long seed) {
        return starter.startWorld(def, eventId, center, seed);
    }

    /** 인스턴스 칸 사용 중 여부. */
    public boolean instanceSlotBusy(int slot) {
        return starter.instanceSlotBusy(slot);
    }

    // ------------------------------------------------------------------ Encounter 콜백

    void rememberRole(UUID player, BossRole role) {
        api.players()
                .get(player)
                .ifPresent(h -> h.set(PROFILE, h.get(PROFILE).withPreferred(role)));
    }

    /** boss_encounter RUNNING 행을 비동기로 쓴다. 결과 future는 메인 스레드에서 완료된다. */
    CompletableFuture<Void> recordStart(Encounter e) {
        return recorder.recordStart(e);
    }

    void firePhase(Encounter e, Fight.Transition t) {
        Bukkit.getPluginManager()
                .callEvent(new BossPhaseChangeEvent(e.info(), t.from(), t.to(), t.cause()));
    }

    void bind(UUID player, Encounter e) {
        byPlayer.put(player, e);
    }

    void unbind(UUID player, Encounter e) {
        byPlayer.remove(player, e);
    }

    void bindClick(Interaction entity, Encounter e, Encounter.ClickKind kind, int index) {
        clicks.put(entity.getUniqueId(), new Click(e, kind, index));
    }

    void unbindClick(Entity entity) {
        clicks.remove(entity.getUniqueId());
    }

    /** 조우 중 경기장 청크에 플러그인 청크 티켓을 건다 (언로드 방지, 10.4). 여러 조우가 같은 청크를 쓰면 센다. */
    void chunkHold(Encounter e, boolean hold) {
        chunkTickets.hold(e, hold);
    }

    void worldStage(Encounter e, int stage) {
        WorldHooks hooks = worldHooks;
        if (hooks != null) {
            hooks.stage(e.info(), stage, e.effectiveParticipants(), e.fight.hpRatio() * 100);
        }
    }

    void worldUi(Encounter e, Component name, float progress) {
        WorldHooks hooks = worldHooks;
        if (hooks != null) {
            hooks.ui(name, progress);
        }
    }

    /** 출발 전에 끝남 (미끼 없음·관리자 종료). 보상·기록은 없고, 출발 중이라 미끼를 이미 뺐으면 돌려준다. */
    void finishEarly(Encounter e, String messageKey) {
        rewards.refundIfTaken(e);
        e.broadcast(messageKey);
        e.cleanup();
        byId.remove(e.id);
        byPlayer.values().removeIf(x -> x == e);
    }

    /**
     * 종료 (docs 20 10.4 6단계): 결과 기록 → BossEncounterEndEvent → 결과 화면 → 보상 트랜잭션 → 리그·Interaction 제거 →
     * 세계 경계 원복.
     */
    void finish(Encounter e, EncounterState result, String cause) {
        if (e.finished()) {
            return;
        }
        List<Contribution> contributions = e.contributions();
        EncounterInfo info = e.info();
        e.cleanup();
        byId.remove(e.id);
        byPlayer.values().removeIf(x -> x == e);
        long durationMs = e.fight.elapsed() * 50L;
        Instant endedAt = Instant.now();
        boolean releaseOnly = false;
        var fishId = e.def.fish();
        if (fishId != null) {
            releaseOnly =
                    api.content()
                            .snapshot()
                            .get(FishDef.TYPE, fishId.id())
                            .map(FishDef::releaseOnly)
                            .orElse(false);
        }
        Bukkit.getPluginManager()
                .callEvent(
                        new BossEncounterEndEvent(
                                info,
                                result,
                                e.def.type(),
                                contributions,
                                fishId,
                                releaseOnly,
                                durationMs,
                                e.def.story()));
        int size = contributions.size();
        recorder.recordEnd(e, result, endedAt, durationMs, contributions);
        // 결과 화면·연출은 꾸밈이라 예외가 나도 보상·후속 처리를 막지 않게 한다
        try {
            results.showResult(e, result, contributions);
            results.playOutro(e, result);
        } catch (RuntimeException ex) {
            log.error("보스 결과 연출 오류 {}", e.id, ex);
        }
        try {
            rewards.settle(e, info, result, contributions, durationMs, endedAt, releaseOnly);
        } catch (RuntimeException ex) {
            // 인스턴스 귀환·월드 보스 정리 훅은 그래도 돌려야 플레이어가 경기장에 남지 않는다
            log.error("보스 보상 정산 오류 {}", e.id, ex);
        }
        if (e.def.isWorld()) {
            WorldHooks hooks = worldHooks;
            if (hooks != null) {
                hooks.ended(info, result);
            }
        }
        if (e.def.arena().mode() == BossDef.ArenaMode.INSTANCE) {
            InstanceHooks hooks = instanceHooks;
            if (hooks != null) {
                hooks.ended(e.id, e.origins, info.members());
            }
        }
        log.info(
                "보스 조우 종료 {} {} {} ({}ms, {}명, {})",
                e.def.id(),
                e.id,
                result,
                durationMs,
                size,
                cause);
    }

    static String formatMs(long ms) {
        long s = ms / 1000;
        return String.format("%d:%02d.%d", s / 60, s % 60, (ms % 1000) / 100);
    }

    // ------------------------------------------------------------------ 틱

    private void tick() {
        tick++;
        for (Encounter e : List.copyOf(byId.values())) {
            try {
                e.tick(tick);
            } catch (RuntimeException ex) {
                log.error("보스 조우 틱 오류 {} — 조우를 중단합니다", e.id, ex);
                if (!e.finished()) {
                    for (Fight.Transition t : e.fight.end(EncounterState.ABORTED, "error")) {
                        firePhase(e, t);
                    }
                    finish(e, EncounterState.ABORTED, "error");
                }
            }
        }
        harpoons.track();
        if (tick % 1200 == 0) {
            parties.purgeInvites(System.currentTimeMillis());
            starter.purgeCooldowns(tick);
        }
    }

    // ------------------------------------------------------------------ 종료

    /** 서버 종료 → ABORTED (미끼는 pending으로 돌려준다). 종료 중이라 DB를 바로 쓴다. */
    public void shutdown() {
        for (Encounter e : List.copyOf(byId.values())) {
            if (e.def.isWorld()) {
                // 월드 보스는 resume을 위해 RUNNING으로 남긴다
                e.cleanup();
                continue;
            }
            e.fight.end(EncounterState.ABORTED, "shutdown");
            e.cleanup();
            recorder.abortOnShutdown(e);
        }
        byId.clear();
        byPlayer.clear();
        harpoons.clear();
    }

    /** 준비 단계 역할 선택 (/보스 역할). 준비 중인 조우가 없으면 false. */
    public boolean chooseRole(UUID player, BossRole role) {
        Encounter e = byPlayer.get(player);
        return e != null && e.chooseRole(player, role);
    }

    /** 이동 차단 (HandoffBlocker "encounter"). */
    public boolean blocksHandoff(Player p) {
        Encounter e = byPlayer.get(p.getUniqueId());
        if (e == null || e.finished()) {
            return false;
        }
        return e.member(p.getUniqueId()).map(m -> !m.dropped).orElse(false);
    }

    /** /harbor boss debug. */
    public List<String> debug(UUID encounterId) {
        Encounter e = byId.get(encounterId);
        if (e == null) {
            return List.of();
        }
        return EncounterDebug.lines(e);
    }
}
