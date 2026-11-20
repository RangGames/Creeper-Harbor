package wiki.creeper.harbor.boss.encounter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.boss.api.BossRole;
import wiki.creeper.harbor.boss.api.Contribution;
import wiki.creeper.harbor.boss.api.EncounterInfo;
import wiki.creeper.harbor.boss.api.EncounterState;
import wiki.creeper.harbor.boss.content.BossBalance;
import wiki.creeper.harbor.boss.content.BossDef;
import wiki.creeper.harbor.boss.content.BossType;
import wiki.creeper.harbor.boss.logic.ContributionCalc;
import wiki.creeper.harbor.boss.logic.Fight;
import wiki.creeper.harbor.boss.logic.HpScaling;
import wiki.creeper.harbor.boss.logic.Qte;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.core.api.RigInstance;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.fishing.api.TensionSession;

/**
 * 실행 중인 조우 하나 (docs 20 10.4 조우 흐름). 정의는 시작 시점 스냅샷을 끝까지 쓴다 (리로드해도 바뀌지 않음). 페이즈 규칙은 순수 로직 {@link
 * Fight}에 두고, 여기서는 준비·출발·매 틱 진행·상태 전환·정리를 맡는다. 리그·판정·패턴 예고·앵커 텐션·QTE·UI는 같은 패키지의 협력 객체 ({@link
 * BossRig}, {@link HarpoonCombat}, {@link PatternTelegraphs}, {@link AnchorLines}, {@link
 * FinalTugQte}, {@link DeckStations}, {@link EncounterRoster}, {@link WorldBossTracker}, {@link
 * EncounterHud})가 서버 API로 돌린다. 메인 스레드 전용.
 */
public final class Encounter {

    /** 보정자 키: 보스 피해 (Core 보정자 파이프라인, 스킬 트리·버프). */
    static final StatKey BOSS_DAMAGE = StatKey.of("boss_damage");

    /** 클릭 대상 (조타륜·파손 지점). */
    enum ClickKind {
        WHEEL,
        REPAIR
    }

    public final UUID id;
    public final BossDef def;
    final BossBalance bb;
    final EncounterManager mgr;
    final World world;

    /** 슬롯 중심 (경계 중심). */
    final Location center;

    final int slot;
    final double radius;
    final UUID leader;
    final @Nullable ItemId summonItem;
    final boolean forced;
    public final long seed;
    final SplittableRandom random;
    public final Fight fight;
    final Instant createdAt = Instant.now();

    /** 인스턴스 조우면 돌아갈 서버 (원래 서버별 파티원). */
    final Map<UUID, String> origins = new HashMap<>();

    /** 참가자 (역할 선택 순서 = 들어온 순서). */
    final LinkedHashMap<UUID, Member> members = new LinkedHashMap<>();

    // 협력 객체 (생성자에서는 참조만 담는다)
    final EncounterHud hud = new EncounterHud(this);
    final BossRig bossRig = new BossRig(this);
    final DeckStations stations = new DeckStations(this);
    final AnchorLines anchors = new AnchorLines(this);
    final HarpoonCombat combat = new HarpoonCombat(this);
    final PatternTelegraphs patterns = new PatternTelegraphs(this);
    final FinalTugQte qte = new FinalTugQte(this);
    final WorldBossTracker worldBoss = new WorldBossTracker(this);
    final EncounterRoster roster = new EncounterRoster(this);

    private boolean launched;
    boolean launching;
    private long prepareDeadline;
    private Instant startedAt = Instant.now();
    long anchorGraceUntil = -1;
    boolean latencySlow;

    private EncounterState lastState = EncounterState.PREPARING;
    private boolean finished;

    Encounter(
            EncounterManager mgr,
            UUID id,
            BossDef def,
            BossBalance bb,
            World world,
            Location center,
            int slot,
            UUID leader,
            List<Player> players,
            Map<UUID, BossRole> presetRoles,
            long seed,
            @Nullable ItemId summonItem,
            boolean forced) {
        this.mgr = mgr;
        this.id = id;
        this.def = def;
        this.bb = bb;
        this.world = world;
        this.center = center.clone();
        this.slot = slot;
        this.radius = def.isWorld() ? bb.worldRadius() : def.arena().slots().getFirst().radius();
        this.leader = leader;
        this.summonItem = summonItem;
        this.forced = forced;
        this.seed = seed;
        this.random = new SplittableRandom(seed);
        for (Player p : players) {
            BossRole preset = presetRoles.get(p.getUniqueId());
            Member m =
                    new Member(
                            p.getUniqueId(),
                            p.getName(),
                            preset == null ? recommended(p) : preset,
                            true);
            m.chose = preset != null;
            members.put(p.getUniqueId(), m);
        }
        double maxHp =
                def.isWorld()
                        ? HpScaling.worldMaxHp(def.hp().base(), 0, bb.worldMinParticipants())
                        : HpScaling.partyMaxHp(def.hp().base(), perPlayerRate(), members.size());
        int timeLimit =
                def.timeLimitSeconds() > 0
                        ? def.timeLimitSeconds()
                        : bb.timeLimitSeconds(def.type());
        BossDef.PhaseDef hook = def.phase(BossDef.PhaseKind.HOOK).orElseThrow();
        Fight.Rules rules =
                new Fight.Rules(
                        Integer.MAX_VALUE,
                        bb.introTicks(),
                        hook.hookTicks() > 0 ? hook.hookTicks() : bb.hookTicks(),
                        bb.hookLineBreakCap(),
                        bb.hookDecayPerSecond() / 20.0,
                        def.finalTugBelow(),
                        bb.qteWin(),
                        bb.qteLose(),
                        bb.qteRecover(),
                        timeLimit * 20L,
                        bb.allAbsentFailTicks());
        this.fight = new Fight(rules, maxHp, def.hull().max());
    }

    private double perPlayerRate() {
        double rate = def.hp().perPlayerRate();
        return rate >= 0 ? rate : bb.perPlayerRate(def.type());
    }

    private BossRole recommended(Player p) {
        return BossRole.recommended(mgr.api().players().get(p).jobLine());
    }

    // ------------------------------------------------------------------ 위치

    Location deck(Vec3 v) {
        return center.clone().add(v.x(), v.y(), v.z());
    }

    Location deckSpawn() {
        return deck(def.arena().deck().spawn());
    }

    Location bossLocation() {
        Location boss = deck(def.arena().deck().boss());
        Location target = deckSpawn();
        Vector dir = target.toVector().subtract(boss.toVector());
        if (dir.lengthSquared() > 1e-6) {
            boss.setDirection(dir);
            boss.setPitch(0);
        }
        return boss;
    }

    boolean inArena(Location loc) {
        if (loc.getWorld() == null || !loc.getWorld().equals(world)) {
            return false;
        }
        double dx = loc.getX() - center.getX();
        double dz = loc.getZ() - center.getZ();
        return dx * dx + dz * dz <= (radius + 2) * (radius + 2);
    }

    // ------------------------------------------------------------------ 시작

    /** 준비 단계로 시작한다 (역할 선택 Dialog). prepare가 거짓이면 바로 출발한다 (월드 보스·resume). */
    void begin(boolean prepare) {
        long now = mgr.now();
        fight.begin(now);
        prepareDeadline = now + (prepare ? bb.prepareTicks() : 0);
        for (Member m : members.values()) {
            Player p = Bukkit.getPlayer(m.uuid);
            if (p == null) {
                continue;
            }
            roster.enter(p, m, true);
            if (prepare && !m.chose) {
                roster.askRole(p);
            }
        }
        if (prepare) {
            broadcast(
                    "boss.prepare.start",
                    Placeholder.component("boss", bossName()),
                    Placeholder.unparsed("seconds", String.valueOf(bb.prepareTicks() / 20)));
        }
    }

    /** 준비 단계 역할 선택 (Dialog 또는 /보스 역할). */
    boolean chooseRole(UUID player, BossRole role) {
        return roster.chooseRole(player, role);
    }

    /**
     * 출발: 앵커 최소 인원 채우기 → 미끼 차감 → 소환자 즉시 저장 → boss_encounter RUNNING 기록 → 등장 (10.4 3~4단계). 저장이 끝나기
     * 전에는 보스를 띄우지 않는다.
     */
    private void launch() {
        launching = true;
        roster.fillAnchors();
        if (summonItem != null) {
            Player summoner = Bukkit.getPlayer(leader);
            if (summoner == null || !mgr.items().takeOne(summoner, summonItem)) {
                mgr.finishEarly(this, "boss.start.no_bait");
                return;
            }
            summoner.saveData();
        }
        startedAt = Instant.now();
        var _ =
                mgr.recordStart(this)
                        .whenComplete(
                                (ok, err) -> {
                                    if (finished) {
                                        return;
                                    }
                                    if (err != null) {
                                        mgr.log()
                                                .warn(
                                                        "boss_encounter 기록 실패 {}: {}",
                                                        id,
                                                        String.valueOf(err.getMessage()));
                                    }
                                    launched = true;
                                    handle(fight.allReady());
                                });
    }

    // ------------------------------------------------------------------ 틱

    void tick(long now) {
        if (finished) {
            return;
        }
        if (fight.state() == EncounterState.PREPARING) {
            if (!launching && (now >= prepareDeadline || roster.allChose())) {
                launch();
            }
            return;
        }
        if (!launched) {
            return;
        }
        if (now % 10 == 0) {
            roster.updatePresence(now);
        }
        int active = 0;
        int safe = 0;
        for (Member m : members.values()) {
            TensionSession s = m.session;
            if (s != null && s.isActive()) {
                active++;
                if (anchors.inSafe(s)) {
                    safe++;
                    m.tensionTicks++;
                    m.activity(now);
                    worldBoss.activity(m.uuid, now);
                }
            }
            if (!m.dropped && now - m.lastActivity <= bb.activeWindowTicks()) {
                m.activeTicks++;
            }
            if (m.fallenUntil >= 0 && now >= m.fallenUntil) {
                m.fallenUntil = -1;
                Player p = Bukkit.getPlayer(m.uuid);
                if (p != null && m.present()) {
                    var _ = p.teleportAsync(def.isWorld() ? p.getLocation() : deckSpawn());
                    send(m.uuid, "boss.fall.back");
                }
            }
            if (m.reopenAt >= 0 && now >= m.reopenAt) {
                m.reopenAt = -1;
                anchors.open(m);
            }
        }
        boolean grace = anchorGraceUntil >= 0 && now < anchorGraceUntil;
        boolean allAbsent = !def.isWorld() && members.values().stream().noneMatch(Member::present);
        handle(fight.tick(now, new Fight.Inputs(safe, active, grace, allAbsent)));
        if (finished) {
            return;
        }
        switch (fight.phase()) {
            case Fight.Hook _ -> {
                patterns.run(now, BossDef.PhaseKind.HOOK);
                patterns.update(now);
            }
            case Fight.Counter _ -> {
                patterns.run(now, BossDef.PhaseKind.COUNTER);
                patterns.update(now);
                combat.tickWeak(now, safe > 0);
                if (safe > 0) {
                    double perTick = fight.maxHp() * bb.anchorReelDamagePerSecond() / 20.0;
                    List<Fight.Transition> out = new ArrayList<>();
                    fight.damage(perTick * safe, out);
                    anchors.mirror();
                    handle(out);
                }
            }
            case Fight.FinalTug _ -> qte.tick(now);
            default -> {
                // 준비·등장·종료
            }
        }
        if (def.isWorld()) {
            worldBoss.tick(now);
        }
        if (now % 5 == 0) {
            hud.update(now);
        }
    }

    // ------------------------------------------------------------------ 상태 전환

    void handle(List<Fight.Transition> transitions) {
        for (Fight.Transition t : transitions) {
            onTransition(t);
            if (finished) {
                return;
            }
        }
    }

    private void onTransition(Fight.Transition t) {
        lastState = t.to();
        mgr.firePhase(this, t);
        switch (t.to()) {
            case INTRO -> onIntro();
            case HOOK -> {
                hud.titleAll("boss.phase.hook.title", "boss.phase.hook.subtitle");
                for (Member m : members.values()) {
                    if (m.role == BossRole.ANCHOR && m.present()) {
                        anchors.open(m);
                    }
                }
            }
            case COUNTER -> {
                if ("tug_failed".equals(t.cause())) {
                    hud.titleAll("boss.phase.recover.title", "boss.phase.recover.subtitle");
                    qte.end();
                    for (Member m : members.values()) {
                        if (m.role == BossRole.ANCHOR && m.present()) {
                            anchors.open(m);
                        }
                    }
                } else {
                    hud.titleAll("boss.phase.counter.title", "boss.phase.counter.subtitle");
                }
                bossRig.roar();
                combat.resetWeakTimer();
                patterns.delay(mgr.now() + 40);
                RigInstance r = bossRig.instance();
                if (r != null) {
                    r.play("idle");
                }
            }
            case FINAL_TUG -> {
                if ("tug_retry".equals(t.cause())) {
                    broadcast("boss.qte.retry");
                } else {
                    hud.titleAll("boss.phase.tug.title", "boss.phase.tug.subtitle");
                    bossRig.roar();
                }
                patterns.clear();
                combat.setWeak(false);
                for (Member m : members.values()) {
                    anchors.close(m);
                }
                qte.arm(mgr.now());
            }
            case CLEARED, FAILED, ABORTED -> mgr.finish(this, t.to(), t.cause());
            default -> {
                // PREPARING
            }
        }
    }

    private void onIntro() {
        bossRig.spawn();
        stations.spawnInteractions();
        // 보스 테마: 팩에 없는 키면 Core HarborSounds가 건너뛴다 (대체 소리 없음)
        HarborSounds.play(
                onlinePlayers(),
                def.presentation().music(),
                HarborSounds.Options.DEFAULT.withSource(Sound.Source.RECORD));
        bossRig.roar();
        hud.titleAll("boss.phase.intro.title", "boss.phase.intro.subtitle");
        mgr.chunkHold(this, true);
    }

    // ------------------------------------------------------------------ 입력·상호작용 (협력 객체로 넘김)

    /** 리그 부위 작살 투척 적중 (EncounterManager의 궤적 판정). */
    void harpoonHit(Player p, ItemDef harpoon, String bone) {
        combat.harpoonHit(p, harpoon, bone);
    }

    /** 조타륜·파손 지점 우클릭. */
    void click(Player p, ClickKind kind, int index) {
        stations.click(p, kind, index);
    }

    /** 수리 지점이 없는 갑판: 손에 수리 키트를 들고 우클릭. */
    boolean freeRepair(Player p) {
        return stations.freeRepair(p);
    }

    /** 보급관이 버프 요리를 먹음. */
    void supply(Player p) {
        stations.supply(p);
    }

    /** 보급관이 있으면 드롭 보너스 (추가 제안). */
    boolean hasQuartermaster() {
        return stations.hasQuartermaster();
    }

    /** 이동 입력 (QTE·선장). 새로 눌린 키만 넘긴다. */
    void input(Player p, Qte.InputState state) {
        Member m = members.get(p.getUniqueId());
        if (m == null) {
            return;
        }
        long now = mgr.now();
        var pressed = state.newlyPressed(m.input);
        m.input = state;
        if (pressed.isEmpty()) {
            return;
        }
        if (m.uuid.equals(stations.helm())) {
            patterns.steer(p, m, pressed);
        }
        qte.press(p, m, pressed, now);
    }

    /** 재접속 (슬롯 유지 시간 안). field는 갑판으로 돌려보낸다. */
    void rejoin(Player p) {
        roster.rejoin(p);
    }

    void quit(Player p) {
        roster.quit(p);
    }

    /** 경기장 안 사망 대신 '낙수' (아이템 보존, 5초 뒤 갑판 복귀). */
    void fall(Member m, Player p) {
        roster.fall(m, p);
    }

    public boolean isMember(UUID player) {
        return members.containsKey(player);
    }

    Optional<Member> member(UUID player) {
        return Optional.ofNullable(members.get(player));
    }

    // ------------------------------------------------------------------ 월드 보스

    public int effectiveParticipants() {
        return worldBoss.effectiveParticipants();
    }

    /** 25% 단위 단계 (0 시작, 1 = 75% 이하, 2 = 50% 이하, 3 = 25% 이하). */
    public int stage() {
        return worldBoss.stage();
    }

    /** resume: 저장한 진행률·기여도로 되살린다. */
    public void restore(double hpRatio, Map<UUID, Contribution> saved) {
        worldBoss.restore(hpRatio, saved);
    }

    // ------------------------------------------------------------------ 종료

    /** 리그·Interaction·예고·보스바·텐션·경계·청크 티켓을 모두 걷는다. */
    void cleanup() {
        finished = true;
        patterns.clear();
        bossRig.remove();
        stations.remove();
        for (Member m : members.values()) {
            anchors.close(m);
            m.track = null;
            Player p = Bukkit.getPlayer(m.uuid);
            if (p != null) {
                roster.leaveVisuals(p, m);
            }
        }
        mgr.chunkHold(this, false);
    }

    /** 기여도 순위 (ContributionCalc). */
    public List<Contribution> contributions() {
        List<ContributionCalc.Stats> stats = new ArrayList<>();
        for (Member m : members.values()) {
            stats.add(m.stats());
        }
        List<ContributionCalc.Scored> scored =
                ContributionCalc.rank(
                        stats,
                        bb.weights(),
                        bb.topRatio(),
                        Math.max(1, fight.elapsed()),
                        bb.minScoreRatio(),
                        bb.minActiveRatio());
        List<Contribution> out = new ArrayList<>();
        for (ContributionCalc.Scored s : scored) {
            Member m = members.get(s.stats().player());
            BossRole role = m == null ? BossRole.HARPOONER : m.role;
            out.add(
                    new Contribution(
                            s.stats().player(),
                            role,
                            s.stats().damage(),
                            s.stats().tension(),
                            s.stats().repair(),
                            s.stats().evasion(),
                            s.score(),
                            s.rank(),
                            s.top(),
                            s.eligible()));
        }
        return out;
    }

    /** 모두 처음부터 참가했는지 (최단 기록 후보). */
    boolean allFromStart() {
        return members.values().stream().allMatch(m -> m.fromStart);
    }

    public EncounterInfo info() {
        Map<UUID, BossRole> roles = new LinkedHashMap<>();
        List<UUID> order = new ArrayList<>();
        order.add(leader);
        for (Member m : members.values()) {
            roles.put(m.uuid, m.role);
            if (!m.uuid.equals(leader)) {
                order.add(m.uuid);
            }
        }
        if (!members.containsKey(leader)) {
            order.remove(leader);
        }
        return new EncounterInfo(
                id,
                def.id(),
                finished ? lastState : fight.state(),
                leader,
                order,
                roles,
                mgr.serverId(),
                slot,
                fight.hpRatio(),
                fight.hull(),
                fight.hullMax(),
                fight.elapsed());
    }

    Instant startedAt() {
        return startedAt;
    }

    boolean launched() {
        return launched;
    }

    public boolean finished() {
        return finished;
    }

    @Nullable RigInstance rig() {
        return bossRig.instance();
    }

    /** 리그 판정 상자 Interaction과 뼈 이름 (작살 궤적 판정용). */
    List<Map.Entry<Interaction, String>> hitboxes() {
        return bossRig.hitboxes();
    }

    int telegraphEntities() {
        return patterns.entityCount();
    }

    /** 표식·리그·Interaction을 보는 사람 (파티원, 월드 보스는 반경 안 플레이어). */
    List<Player> viewers() {
        if (!def.isWorld()) {
            return onlinePlayers();
        }
        List<Player> out = new ArrayList<>();
        double r2 = bb.worldRadius() * bb.worldRadius();
        for (Player p : world.getPlayers()) {
            if (BossItems.loc(p).distanceSquared(center) <= r2) {
                out.add(p);
            }
        }
        return out;
    }

    List<Player> onlinePlayers() {
        List<Player> out = new ArrayList<>();
        for (Member m : members.values()) {
            Player p = Bukkit.getPlayer(m.uuid);
            if (p != null && !m.dropped) {
                out.add(p);
            }
        }
        return out;
    }

    Collection<Member> memberList() {
        return members.values();
    }

    // ------------------------------------------------------------------ 문구 (EncounterHud)

    public Component bossName() {
        return hud.bossName();
    }

    Component msg(String key, TagResolver... resolvers) {
        return hud.msg(key, resolvers);
    }

    void send(UUID player, String key, TagResolver... resolvers) {
        hud.send(player, key, resolvers);
    }

    void broadcast(String key, TagResolver... resolvers) {
        hud.broadcast(key, resolvers);
    }

    BossType type() {
        return def.type();
    }
}
