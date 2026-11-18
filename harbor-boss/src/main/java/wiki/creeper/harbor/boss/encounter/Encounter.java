package wiki.creeper.harbor.boss.encounter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
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
import wiki.creeper.harbor.boss.logic.Geometry;
import wiki.creeper.harbor.boss.logic.HpScaling;
import wiki.creeper.harbor.boss.logic.PatternScheduler;
import wiki.creeper.harbor.boss.logic.Qte;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.rig.RigDef;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.core.api.Dialogs;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.core.api.RigHit;
import wiki.creeper.harbor.core.api.RigInstance;
import wiki.creeper.harbor.core.api.RigOptions;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.core.api.WorldBorderService;
import wiki.creeper.harbor.fishing.api.MinigameResult;
import wiki.creeper.harbor.fishing.api.TensionSession;
import wiki.creeper.harbor.fishing.api.TensionSpec;

/**
 * 실행 중인 조우 하나 (docs 20 10.4 조우 흐름). 정의는 시작 시점 스냅샷을 끝까지 쓴다 (리로드해도 바뀌지 않음). 페이즈 규칙은 순수 로직 {@link
 * Fight}에 두고, 여기서는 리그·판정·패턴 예고·앵커 텐션·QTE·UI를 서버 API로 돌린다. 메인 스레드 전용.
 */
public final class Encounter {

    /** 보정자 키: 보스 피해 (Core 보정자 파이프라인, 스킬 트리·버프). */
    static final StatKey BOSS_DAMAGE = StatKey.of("boss_damage");

    /** 입력 판정 여유 ±2틱 ('8. 시스템 상세 기획' 3.3). */
    private static final int INPUT_TOLERANCE = 2;

    // 공용 보스전 소리 ('15. 사운드' 4.3·4.4)
    private static final String HARPOON_HIT = "harbor:boss.harpoon_hit";
    private static final String HULL_HIT = "harbor:ship.hull_hit";
    private static final String REPAIR_SOUND = "harbor:ship.repair";

    /** 클릭 대상 (조타륜·파손 지점). */
    enum ClickKind {
        WHEEL,
        REPAIR
    }

    /** 파손 지점 하나. */
    static final class RepairPoint {
        final Location at;
        final Interaction entity;
        boolean damaged;

        RepairPoint(Location at, Interaction entity) {
            this.at = at;
            this.entity = entity;
        }
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

    private final PatternScheduler scheduler = new PatternScheduler();
    private final List<Telegraph> telegraphs = new ArrayList<>();
    private final List<RepairPoint> repairPoints = new ArrayList<>();
    private @Nullable Interaction wheel;
    private @Nullable UUID helm;
    private @Nullable RigInstance rig;

    /** 리그 판정 상자 뼈 이름 (Interaction 순서). */
    private final List<String> hitboxBones = new ArrayList<>();

    private boolean launched;
    private boolean launching;
    private long prepareDeadline;
    private Instant startedAt = Instant.now();
    private long anchorGraceUntil = -1;
    private double weakTimer;
    private boolean weakExposed;
    private long weakUntil = -1;
    private boolean latencySlow;

    // QTE
    private long qteGapUntil = -1;
    private long qteRoundEnd = -1;
    private boolean qteRunning;

    // 월드 보스
    private final Map<UUID, Long> lastWorldActivity = new HashMap<>();
    private int worldStage;
    private long nextRescale;

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
            enter(p, m, true);
            if (prepare && !m.chose) {
                askRole(p);
            }
        }
        if (prepare) {
            broadcast(
                    "boss.prepare.start",
                    Placeholder.component("boss", bossName()),
                    Placeholder.unparsed("seconds", String.valueOf(bb.prepareTicks() / 20)));
        }
    }

    /** 경기장에 들어오는 플레이어 준비 (경계·보스바·표시). */
    private void enter(Player p, Member m, boolean teleport) {
        if (teleport && !def.isWorld()) {
            Location spawn = deckSpawn();
            spawn.add(random.nextDouble(-1, 1), 0, random.nextDouble(-1, 1));
            var _ = p.teleportAsync(spawn);
        }
        if (!def.isWorld()) {
            mgr.api()
                    .worldBorders()
                    .request(
                            p,
                            WorldBorderService.Priority.BOSS_ARENA,
                            new WorldBorderService.Border(center, radius * 2, 2));
        }
        BossBar bar = m.bar;
        if (bar == null) {
            bar = BossBar.bossBar(bossName(), 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
            m.bar = bar;
        }
        p.showBossBar(bar);
        RigInstance r = rig;
        if (r != null && !r.isPublic()) {
            r.addViewer(p);
        }
        showInteractions(p);
        for (Telegraph t : telegraphs) {
            t.show(mgr.plugin(), p);
        }
    }

    private void askRole(Player p) {
        List<Dialogs.Choice> choices = new ArrayList<>();
        for (BossRole r : BossRole.values()) {
            choices.add(
                    new Dialogs.Choice(
                            msg("boss.role." + r.key()), msg("boss.role.desc." + r.key())));
        }
        UUID uuid = p.getUniqueId();
        mgr.api()
                .dialogs()
                .choose(
                        p,
                        msg("boss.prepare.title", Placeholder.component("boss", bossName())),
                        List.of(msg("boss.prepare.body")),
                        choices,
                        3,
                        idx ->
                                chooseRole(
                                        uuid,
                                        List.of(BossRole.values()).get(Math.clamp(idx, 0, 4))));
    }

    /** 준비 단계 역할 선택 (Dialog 또는 /boss role). */
    boolean chooseRole(UUID player, BossRole role) {
        Member m = members.get(player);
        if (m == null || fight.state() != EncounterState.PREPARING || launching) {
            return false;
        }
        m.role = role;
        m.chose = true;
        mgr.rememberRole(player, role);
        broadcast(
                "boss.prepare.chose",
                Placeholder.unparsed("player", m.name),
                Placeholder.component("role", msg("boss.role." + role.key())));
        return true;
    }

    /**
     * 출발: 앵커 최소 인원 채우기 → 미끼 차감 → 소환자 즉시 저장 → boss_encounter RUNNING 기록 → 등장 (10.4 3~4단계). 저장이 끝나기
     * 전에는 보스를 띄우지 않는다.
     */
    private void launch() {
        launching = true;
        fillAnchors();
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

    /** 고르지 않은 사람은 추천 역할 그대로 두고, 앵커가 모자라면 고르지 않은 사람부터 앵커로 바꾼다. */
    private void fillAnchors() {
        int need = def.isWorld() ? 0 : bb.anchorsMin(def.type());
        int have = 0;
        for (Member m : members.values()) {
            if (m.role == BossRole.ANCHOR && m.present()) {
                have++;
            }
        }
        for (int pass = 0; pass < 2 && have < need; pass++) {
            for (Member m : members.values()) {
                if (have >= need) {
                    break;
                }
                boolean candidate = pass == 0 ? !m.chose : true;
                if (candidate && m.role != BossRole.ANCHOR && m.present()) {
                    m.role = BossRole.ANCHOR;
                    have++;
                    send(m.uuid, "boss.prepare.assigned_anchor");
                }
            }
        }
    }

    // ------------------------------------------------------------------ 틱

    void tick(long now) {
        if (finished) {
            return;
        }
        if (fight.state() == EncounterState.PREPARING) {
            if (!launching && (now >= prepareDeadline || allChose())) {
                launch();
            }
            return;
        }
        if (!launched) {
            return;
        }
        if (now % 10 == 0) {
            updatePresence(now);
        }
        int active = 0;
        int safe = 0;
        for (Member m : members.values()) {
            TensionSession s = m.session;
            if (s != null && s.isActive()) {
                active++;
                if (inSafe(s)) {
                    safe++;
                    m.tensionTicks++;
                    m.activity(now);
                    lastWorldActivity.put(m.uuid, now);
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
                openSession(m);
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
                runPatterns(now, BossDef.PhaseKind.HOOK);
                updateTelegraphs(now);
            }
            case Fight.Counter _ -> {
                runPatterns(now, BossDef.PhaseKind.COUNTER);
                updateTelegraphs(now);
                tickWeak(now, safe > 0);
                if (safe > 0) {
                    double perTick = fight.maxHp() * bb.anchorReelDamagePerSecond() / 20.0;
                    List<Fight.Transition> out = new ArrayList<>();
                    fight.damage(perTick * safe, out);
                    mirrorSessions();
                    handle(out);
                }
            }
            case Fight.FinalTug _ -> tickQte(now);
            default -> {
                // 준비·등장·종료
            }
        }
        if (def.isWorld() && now >= nextRescale) {
            nextRescale = now + bb.worldRescaleTicks();
            rescaleWorld(now);
        }
        if (def.isWorld()) {
            checkWorldStage();
        }
        if (now % 5 == 0) {
            updateUi(now);
        }
    }

    private boolean allChose() {
        for (Member m : members.values()) {
            if (m.present() && !m.chose) {
                return false;
            }
        }
        return true;
    }

    private boolean inSafe(TensionSession s) {
        double t = s.tension();
        return t >= bb.anchorSafeMin() && t <= bb.anchorSafeMax();
    }

    /** 10틱마다: 경기장 안·접속 여부, 슬롯 유지 시간, 앵커 교대. */
    private void updatePresence(long now) {
        boolean slow = false;
        for (Member m : members.values()) {
            if (m.dropped) {
                continue;
            }
            Player p = Bukkit.getPlayer(m.uuid);
            boolean here = p != null && p.isOnline() && inArena(BossItems.loc(p));
            if (p != null && p.getPing() >= bb.latencyPingMs()) {
                slow = true;
            }
            if (here) {
                if (m.absentSince >= 0) {
                    m.absentSince = -1;
                }
                if (p != null && !def.isWorld() && fight.state().fighting()) {
                    double floor = deckSpawn().getY() - 3;
                    if (BossItems.loc(p).getY() < floor && !m.fallen(now)) {
                        fall(m, p);
                    }
                }
                continue;
            }
            if (m.absentSince < 0) {
                m.absentSince = now;
                onAbsent(m, now);
            } else if (now - m.absentSince >= bb.slotHoldTicks()) {
                drop(m);
            }
        }
        latencySlow = slow;
    }

    /** 자리를 비움: 앵커면 다음 후보에게 넘긴다 (5초 유예). */
    private void onAbsent(Member m, long now) {
        if (m.role != BossRole.ANCHOR) {
            return;
        }
        closeSession(m);
        if (def.isWorld()) {
            return;
        }
        boolean otherAnchor =
                members.values().stream()
                        .anyMatch(o -> o != m && o.present() && o.role == BossRole.ANCHOR);
        if (otherAnchor) {
            return;
        }
        for (Member c : members.values()) {
            if (c != m && c.present() && c.role != BossRole.ANCHOR) {
                c.role = BossRole.ANCHOR;
                anchorGraceUntil = now + bb.anchorGraceTicks();
                c.reopenAt = anchorGraceUntil;
                broadcast("boss.anchor.handover", Placeholder.unparsed("player", c.name));
                return;
            }
        }
        broadcast("boss.anchor.none");
    }

    /** 슬롯을 놓는다 (기여도는 남는다). */
    void drop(Member m) {
        if (m.dropped) {
            return;
        }
        m.dropped = true;
        closeSession(m);
        Player p = Bukkit.getPlayer(m.uuid);
        if (p != null) {
            leaveVisuals(p, m);
        }
        mgr.unbind(m.uuid, this);
    }

    private void leaveVisuals(Player p, Member m) {
        mgr.api().worldBorders().clear(p, WorldBorderService.Priority.BOSS_ARENA);
        BossBar bar = m.bar;
        if (bar != null) {
            p.hideBossBar(bar);
        }
        RigInstance r = rig;
        if (r != null && !r.isPublic()) {
            r.removeViewer(p);
        }
        p.clearTitle();
    }

    // ------------------------------------------------------------------ 상태 전환

    private void handle(List<Fight.Transition> transitions) {
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
                titleAll("boss.phase.hook.title", "boss.phase.hook.subtitle");
                for (Member m : members.values()) {
                    if (m.role == BossRole.ANCHOR && m.present()) {
                        openSession(m);
                    }
                }
            }
            case COUNTER -> {
                if ("tug_failed".equals(t.cause())) {
                    titleAll("boss.phase.recover.title", "boss.phase.recover.subtitle");
                    endQte();
                    for (Member m : members.values()) {
                        if (m.role == BossRole.ANCHOR && m.present()) {
                            openSession(m);
                        }
                    }
                } else {
                    titleAll("boss.phase.counter.title", "boss.phase.counter.subtitle");
                }
                roar();
                weakTimer = 0;
                scheduler.delay(mgr.now() + 40);
                RigInstance r = rig;
                if (r != null) {
                    r.play("idle");
                }
            }
            case FINAL_TUG -> {
                if ("tug_retry".equals(t.cause())) {
                    broadcast("boss.qte.retry");
                } else {
                    titleAll("boss.phase.tug.title", "boss.phase.tug.subtitle");
                    roar();
                }
                clearTelegraphs();
                setWeak(false);
                for (Member m : members.values()) {
                    closeSession(m);
                }
                qteRunning = false;
                qteGapUntil = mgr.now() + bb.qteGapTicks();
            }
            case CLEARED, FAILED, ABORTED -> mgr.finish(this, t.to(), t.cause());
            default -> {
                // PREPARING
            }
        }
    }

    private void onIntro() {
        Location at = bossLocation();
        RigOptions options =
                def.isWorld() ? RigOptions.publicRig() : RigOptions.privateTo(onlinePlayers());
        options = options.withScale(def.rigScale()).withHitboxes(this::onRigHit);
        try {
            RigInstance r = mgr.api().rigs().spawn(def.rig(), at, options);
            r.onRemove(reason -> rigRemoved());
            rig = r;
            RigDef rigDef = mgr.api().rigs().definition(def.rig()).orElse(null);
            hitboxBones.clear();
            if (rigDef != null) {
                for (RigDef.Bone bone : rigDef.bones()) {
                    if (bone.hitbox() != null) {
                        hitboxBones.add(bone.name());
                    }
                }
            }
            String intro = def.presentation().intro();
            if (intro != null) {
                r.playOnce(intro, null);
            }
        } catch (IllegalArgumentException e) {
            mgr.log().warn("보스 리그를 띄우지 못했습니다 {}: {}", def.rig(), String.valueOf(e.getMessage()));
        }
        spawnDeckInteractions();
        // 보스 테마: 팩에 없는 키면 Core HarborSounds가 건너뛴다 (대체 소리 없음)
        HarborSounds.play(
                onlinePlayers(),
                def.presentation().music(),
                HarborSounds.Options.DEFAULT.withSource(Sound.Source.RECORD));
        roar();
        titleAll("boss.phase.intro.title", "boss.phase.intro.subtitle");
        mgr.chunkHold(this, true);
    }

    /**
     * 포효 ('15. 사운드' 4.3): 보스 위치에서 sounds.yml 음량(3 이상)으로 내고, 들리는 범위 밖 참가자에게는 직접 보낸다.
     * presentation.roar가 없으면 생략.
     */
    private void roar() {
        RigInstance r = rig;
        Location at = r != null ? r.location() : bossLocation();
        HarborSounds.playAt(
                at,
                def.presentation().roar(),
                HarborSounds.Options.DEFAULT.withSource(Sound.Source.HOSTILE),
                onlinePlayers());
    }

    private void rigRemoved() {
        rig = null;
        if (!finished && fight.state().fighting()) {
            // 청크가 언로드되는 등으로 리그가 사라지면 같은 자리에 다시 띄운다 (청크 티켓을 걸어 두므로 드물다)
            var _ = Bukkit.getScheduler().runTask(mgr.plugin(), this::respawnRig);
        }
    }

    private void respawnRig() {
        if (finished || rig != null) {
            return;
        }
        RigOptions options =
                def.isWorld() ? RigOptions.publicRig() : RigOptions.privateTo(onlinePlayers());
        try {
            RigInstance r =
                    mgr.api()
                            .rigs()
                            .spawn(
                                    def.rig(),
                                    bossLocation(),
                                    options.withScale(def.rigScale()).withHitboxes(this::onRigHit));
            r.onRemove(reason -> rigRemoved());
            rig = r;
        } catch (IllegalArgumentException e) {
            mgr.log().warn("보스 리그 재생성 실패 {}", def.rig());
        }
    }

    private void spawnDeckInteractions() {
        if (def.isWorld()) {
            return;
        }
        BossDef.Deck deck = def.arena().deck();
        Vec3 wheelPos = deck.wheel();
        if (wheelPos != null) {
            wheel = spawnInteraction(deck(wheelPos), 1.0f, 1.2f);
            mgr.bindClick(wheel, this, ClickKind.WHEEL, 0);
        }
        for (int i = 0; i < deck.repair().size(); i++) {
            Location at = deck(deck.repair().get(i));
            Interaction e = spawnInteraction(at, 0.9f, 0.9f);
            repairPoints.add(new RepairPoint(at, e));
            mgr.bindClick(e, this, ClickKind.REPAIR, i);
        }
    }

    private Interaction spawnInteraction(Location at, float width, float height) {
        Interaction e =
                world.spawn(
                        at,
                        Interaction.class,
                        x -> {
                            x.setPersistent(false);
                            x.setVisibleByDefault(false);
                            x.setInteractionWidth(width);
                            x.setInteractionHeight(height);
                            x.setResponsive(true);
                        });
        for (Player p : onlinePlayers()) {
            p.showEntity(mgr.plugin(), e);
        }
        return e;
    }

    private void showInteractions(Player p) {
        Interaction w = wheel;
        if (w != null && w.isValid()) {
            p.showEntity(mgr.plugin(), w);
        }
        for (RepairPoint rp : repairPoints) {
            if (rp.entity.isValid()) {
                p.showEntity(mgr.plugin(), rp.entity);
            }
        }
    }

    // ------------------------------------------------------------------ 앵커 (Fishing
    // TensionSession)

    private void openSession(Member m) {
        if (finished || m.session != null || !m.present()) {
            return;
        }
        EncounterState s = fight.state();
        if (s != EncounterState.HOOK && s != EncounterState.COUNTER) {
            return;
        }
        Player p = Bukkit.getPlayer(m.uuid);
        if (p == null) {
            return;
        }
        Grade grade =
                switch (def.type()) {
                    case INTRO -> Grade.RARE;
                    case SEA -> Grade.EPIC;
                    default -> Grade.LEGENDARY;
                };
        TensionSpec spec =
                new TensionSpec(
                        bossName(),
                        grade,
                        1_000_000_000,
                        bb.anchorTensionMult(def.type()),
                        0,
                        true,
                        false);
        Optional<TensionSession> opened = mgr.fishing().openTension(p, spec);
        if (opened.isEmpty()) {
            m.reopenAt = mgr.now() + 20;
            return;
        }
        TensionSession session = opened.get();
        m.session = session;
        m.closingSession = false;
        session.onEnd(result -> sessionEnded(m, session, result));
        double ratio = fight.hpRatio();
        if (session.hpRatio() > ratio) {
            session.damage(session.hpRatio() - ratio);
        }
        send(m.uuid, "boss.anchor.hooked_on");
    }

    private void sessionEnded(Member m, TensionSession session, MinigameResult result) {
        if (java.util.Objects.equals(m.session, session)) {
            m.session = null;
        }
        if (m.closingSession || finished) {
            m.closingSession = false;
            return;
        }
        if (result == MinigameResult.LINE_BROKE || result == MinigameResult.ESCAPED) {
            fight.lineBroken();
            broadcast("boss.anchor.line_broke", Placeholder.unparsed("player", m.name));
            m.reopenAt = mgr.now() + bb.hookReopenTicks();
        }
    }

    private void closeSession(Member m) {
        TensionSession s = m.session;
        if (s != null) {
            m.closingSession = true;
            m.session = null;
            if (s.isActive()) {
                s.close(MinigameResult.ABORTED);
            }
        }
        m.reopenAt = -1;
    }

    /** 앵커 보스바 체력을 보스 체력에 맞춘다 (줄일 수만 있다). */
    private void mirrorSessions() {
        double ratio = fight.hpRatio();
        for (Member m : members.values()) {
            TensionSession s = m.session;
            if (s != null && s.isActive() && s.hpRatio() > ratio + 0.001) {
                s.damage(s.hpRatio() - ratio);
            }
        }
    }

    // ------------------------------------------------------------------ 약점 노출

    private void tickWeak(long now, boolean anchorSafe) {
        BossDef.PhaseDef counter = def.phase(BossDef.PhaseKind.COUNTER).orElseThrow();
        int interval =
                counter.weakIntervalTicks() > 0
                        ? counter.weakIntervalTicks()
                        : bb.weakIntervalTicks();
        int length = counter.weakTicks() > 0 ? counter.weakTicks() : bb.weakTicks();
        if (weakExposed) {
            if (now >= weakUntil) {
                setWeak(false);
                weakTimer = 0;
            }
            return;
        }
        weakTimer += anchorSafe ? bb.weakSafeSpeedup() : 1.0;
        if (weakTimer >= interval) {
            weakUntil = now + length;
            setWeak(true);
            for (Member m : members.values()) {
                Player p = Bukkit.getPlayer(m.uuid);
                if (p != null && m.present()) {
                    mgr.api()
                            .ui()
                            .title(
                                    p,
                                    Component.empty(),
                                    msg("boss.weak.exposed"),
                                    Duration.ZERO,
                                    Duration.ofMillis(length * 50L),
                                    Duration.ofMillis(200));
                }
            }
        }
    }

    private void setWeak(boolean on) {
        weakExposed = on;
        RigInstance r = rig;
        if (r == null) {
            return;
        }
        for (BossDef.Part part : def.parts()) {
            if (part.weakpoint()) {
                r.setPartGlow(part.bone(), on);
            }
        }
    }

    // ------------------------------------------------------------------ 피해

    /** 리그 부위 클릭 (Core RigHit). 좌클릭 + 작살 = 찌르기, 우클릭 = 월드 보스 줄 걸기. */
    private void onRigHit(RigHit hit) {
        Player p = hit.player();
        Member m = members.get(p.getUniqueId());
        if (hit.attack()) {
            Optional<ItemDef> harpoon = mgr.items().harpoon(p.getInventory().getItemInMainHand());
            if (harpoon.isEmpty()) {
                return;
            }
            if (m == null) {
                m = joinWorld(p, BossRole.HARPOONER);
            }
            if (m == null) {
                return;
            }
            long now = mgr.now();
            if (now - m.lastStab < bb.stabCooldownTicks()) {
                return;
            }
            m.lastStab = now;
            hit(p, m, harpoon.get(), hit.bone(), bb.stabMult());
            return;
        }
        // 우클릭: 월드 보스는 누구나 줄을 걸 수 있다 (인원 상한까지)
        if (def.isWorld()
                && (fight.state() == EncounterState.HOOK
                        || fight.state() == EncounterState.COUNTER)) {
            Member w = m == null ? joinWorld(p, BossRole.ANCHOR) : m;
            if (w == null || w.session != null) {
                return;
            }
            long anchors = members.values().stream().filter(x -> x.session != null).count();
            if (anchors >= bb.worldMaxAnchors()) {
                send(p.getUniqueId(), "boss.world.anchors_full");
                return;
            }
            w.role = BossRole.ANCHOR;
            openSession(w);
        }
    }

    /** 작살 투척이 판정 상자에 맞음 (EncounterManager의 궤적 판정). */
    void harpoonHit(Player p, ItemDef harpoon, String bone) {
        Member m = members.get(p.getUniqueId());
        if (m == null) {
            m = joinWorld(p, BossRole.HARPOONER);
        }
        if (m != null) {
            hit(p, m, harpoon, bone, 1.0);
        }
    }

    private void hit(Player p, Member m, ItemDef harpoon, String bone, double mult) {
        if (!m.present() || m.fallen(mgr.now())) {
            return;
        }
        if (fight.state() != EncounterState.COUNTER) {
            mgr.api().ui().actionBar(p, msg("boss.hit.not_hooked"));
            return;
        }
        BossDef.Part part = def.partByBone(bone).orElse(null);
        double partMult;
        boolean weak = false;
        if (part != null && part.weakpoint() && weakExposed) {
            partMult = bb.weakMult() * (1 + BossItems.stat(harpoon, BossItems.STAT_WEAK_BONUS, 0));
            weak = true;
        } else if (part != null && part.armor()) {
            partMult = bb.armorMult();
        } else {
            partMult = bb.normalMult();
        }
        double base = BossItems.stat(harpoon, BossItems.STAT_HARPOON, bb.harpoonBaseDamage());
        double modifier = mgr.api().modifiers().apply(p.getUniqueId(), BOSS_DAMAGE, 1.0);
        double amount = base * partMult * modifier * mult;
        List<Fight.Transition> out = new ArrayList<>();
        double dealt = fight.damage(amount, out);
        m.damage += dealt;
        m.activity(mgr.now());
        lastWorldActivity.put(m.uuid, mgr.now());
        mirrorSessions();
        mgr.api()
                .ui()
                .actionBar(
                        p,
                        msg(
                                weak
                                        ? "boss.hit.weak"
                                        : part != null && part.armor()
                                                ? "boss.hit.armor"
                                                : "boss.hit.normal",
                                Placeholder.unparsed("damage", String.valueOf(Math.round(dealt)))));
        Location fx = BossItems.loc(p);
        RigInstance r = rig;
        if (r != null) {
            fx = r.location();
        }
        world.spawnParticle(weak ? Particle.CRIT : Particle.SPLASH, fx, 12, 0.6, 0.6, 0.6, 0.1);
        if (weak) {
            // 약점 적중 (공용): 맞은 자리에서, 멀리서 던진 작살수에게도 들리게
            HarborSounds.playAt(fx, HARPOON_HIT, HarborSounds.Options.DEFAULT, List.of(p));
        }
        handle(out);
    }

    // ------------------------------------------------------------------ 패턴 예고

    private void runPatterns(long now, BossDef.PhaseKind kind) {
        BossDef.PhaseDef phase = def.phase(kind).orElse(null);
        if (phase == null || phase.patterns().isEmpty()) {
            return;
        }
        List<BossDef.PatternDef> candidates = new ArrayList<>();
        for (String pid : phase.patterns()) {
            def.pattern(pid).ifPresent(candidates::add);
        }
        int gap =
                phase.patternGapTicks() > 0
                        ? phase.patternGapTicks()
                        : bb.patternGapTicks(def.type());
        scheduler.next(candidates, now, gap, random).ifPresent(p -> startTelegraph(p, now));
    }

    private void startTelegraph(BossDef.PatternDef pattern, long now) {
        List<Member> present = new ArrayList<>();
        for (Member m : members.values()) {
            if (m.present() && Bukkit.getPlayer(m.uuid) != null) {
                present.add(m);
            }
        }
        Location spawn = deckSpawn();
        Location boss = bossLocation();
        double ox;
        double oz;
        double floorY = spawn.getY() - 1;
        double dirX = spawn.getX() - boss.getX();
        double dirZ = spawn.getZ() - boss.getZ();
        switch (pattern.telegraph().at()) {
            case MEMBER -> {
                if (present.isEmpty()) {
                    ox = spawn.getX();
                    oz = spawn.getZ();
                } else {
                    Player target =
                            Bukkit.getPlayer(present.get(random.nextInt(present.size())).uuid);
                    Location tl = target == null ? spawn : BossItems.loc(target);
                    ox = tl.getX();
                    oz = tl.getZ();
                    if (def.isWorld()) {
                        floorY = tl.getY() - 0.98;
                    }
                }
            }
            case DECK -> {
                ox = spawn.getX();
                oz = spawn.getZ();
            }
            case BOSS -> {
                ox = boss.getX();
                oz = boss.getZ();
                if (def.isWorld() && !present.isEmpty()) {
                    Player target =
                            Bukkit.getPlayer(present.get(random.nextInt(present.size())).uuid);
                    if (target != null) {
                        dirX = target.getX() - ox;
                        dirZ = target.getZ() - oz;
                        floorY = target.getY() - 0.98;
                    }
                }
            }
            default -> throw new IllegalStateException();
        }
        int ticks =
                pattern.telegraph().ticks() > 0
                        ? pattern.telegraph().ticks()
                        : bb.telegraphTicks(def.type());
        if (latencySlow) {
            ticks += bb.latencyExtraTicks();
        }
        Geometry.Area area = Geometry.Area.of(pattern.telegraph(), ox, oz, dirX, dirZ);
        Qte.Key side = null;
        if (pattern.charge()) {
            side = random.nextBoolean() ? Qte.Key.LEFT : Qte.Key.RIGHT;
        }
        Telegraph t = new Telegraph(pattern, area, now, now + ticks, floorY, world, side);
        t.spawn(
                mgr.plugin(),
                def.isWorld() ? null : onlinePlayers(),
                (float) (def.isWorld() ? bb.worldRadius() / 64.0 : 1.0));
        for (Member m : present) {
            Player p = Bukkit.getPlayer(m.uuid);
            if (p != null && t.contains(BossItems.loc(p))) {
                t.insideAtStart.add(m.uuid);
            }
        }
        telegraphs.add(t);
        RigInstance r = rig;
        String anim = pattern.animation();
        if (r != null && anim != null) {
            r.playOnce(anim, null);
        }
        Component name = MiniMessage.miniMessage().deserialize(pattern.name());
        for (Member m : present) {
            Player p = Bukkit.getPlayer(m.uuid);
            if (p == null) {
                continue;
            }
            if (side != null && m.uuid.equals(helm)) {
                mgr.api()
                        .ui()
                        .title(
                                p,
                                msg("boss.charge.title"),
                                msg("boss.charge." + side.key()),
                                Duration.ZERO,
                                Duration.ofMillis(ticks * 50L),
                                Duration.ofMillis(100));
            } else {
                mgr.api()
                        .ui()
                        .actionBar(
                                p,
                                msg("boss.pattern.warn", Placeholder.component("pattern", name)));
            }
        }
    }

    private void updateTelegraphs(long now) {
        Iterator<Telegraph> it = telegraphs.iterator();
        List<Player> viewers = viewers();
        while (it.hasNext()) {
            Telegraph t = it.next();
            if (now >= t.end) {
                resolve(t, now);
                t.remove();
                it.remove();
                if (finished) {
                    return;
                }
            } else if ((now - t.start) % 2 == 0) {
                t.particles(now, viewers);
            }
        }
    }

    /** 예고가 끝나는 틱의 판정: 넉백·디버프·배 내구도, 예고 범위 회피, 선장 회피. */
    private void resolve(Telegraph t, long now) {
        BossDef.Effect effect = t.pattern.effect();
        boolean anyInside = false;
        boolean evaded = t.chargeSide != null && t.evaded;
        for (Member m : members.values()) {
            Player p = Bukkit.getPlayer(m.uuid);
            if (p == null || !m.present() || m.fallen(now)) {
                continue;
            }
            Location loc = BossItems.loc(p);
            boolean inside = t.contains(loc);
            if (!inside) {
                if (t.insideAtStart.contains(m.uuid)) {
                    m.evasion += 1;
                    m.activity(now);
                    lastWorldActivity.put(m.uuid, now);
                }
                continue;
            }
            anyInside = true;
            if (evaded) {
                continue;
            }
            if (effect.knockback() > 0) {
                Vector push = new Vector(loc.getX() - t.area.ox(), 0, loc.getZ() - t.area.oz());
                if (push.lengthSquared() < 1e-4) {
                    push = new Vector(random.nextDouble(-1, 1), 0, random.nextDouble(-1, 1));
                }
                push.normalize().multiply(effect.knockback()).setY(0.35);
                p.setVelocity(push);
            }
            applyDebuff(m, p, effect, now);
        }
        if (effect.tension() != 0 && !evaded) {
            for (Member m : members.values()) {
                TensionSession s = m.session;
                if (s != null && s.isActive()) {
                    s.addTension(effect.tension());
                }
            }
        }
        if (t.chargeSide != null && evaded) {
            UUID h = helm;
            Member captain = h == null ? null : members.get(h);
            if (captain != null) {
                captain.evasion += 1;
                captain.activity(now);
            }
            broadcast("boss.charge.evaded");
            return;
        }
        Location spawn = deckSpawn();
        boolean shipHit =
                anyInside || (!def.isWorld() && t.area.contains(spawn.getX(), spawn.getZ()));
        if (shipHit && effect.hullDamage() > 0) {
            List<Fight.Transition> out = new ArrayList<>();
            int lost = fight.hullDamage(effect.hullDamage(), out);
            if (lost > 0) {
                damageRepairPoint();
                HarborSounds.playAt(spawn, HULL_HIT, HarborSounds.Options.DEFAULT, onlinePlayers());
                broadcastBar(
                        "boss.hull.hit",
                        Placeholder.unparsed("lost", String.valueOf(lost)),
                        Placeholder.unparsed("hull", String.valueOf(fight.hull())),
                        Placeholder.unparsed("max", String.valueOf(fight.hullMax())));
            }
            handle(out);
        }
    }

    private void applyDebuff(Member m, Player p, BossDef.Effect effect, long now) {
        int ticks = Math.max(20, effect.debuffTicks());
        PotionEffectType type =
                switch (effect.debuff()) {
                    case NONE -> null;
                    case BLIND -> PotionEffectType.BLINDNESS;
                    case DARKNESS -> PotionEffectType.DARKNESS;
                    case SLOW -> PotionEffectType.SLOWNESS;
                    case LEVITATE -> PotionEffectType.LEVITATION;
                    case INVERT -> PotionEffectType.NAUSEA;
                };
        if (type == null) {
            return;
        }
        p.addPotionEffect(new PotionEffect(type, ticks, 0, false, false, true));
        if (effect.debuff() == BossDef.Debuff.INVERT) {
            m.invertedUntil = now + ticks;
        }
        mgr.api().ui().actionBar(p, msg("boss.debuff." + effect.debuff().key()));
    }

    private void damageRepairPoint() {
        List<RepairPoint> intact = new ArrayList<>();
        for (RepairPoint rp : repairPoints) {
            if (!rp.damaged) {
                intact.add(rp);
            }
        }
        if (!intact.isEmpty()) {
            intact.get(random.nextInt(intact.size())).damaged = true;
        }
    }

    private void clearTelegraphs() {
        for (Telegraph t : telegraphs) {
            t.remove();
        }
        telegraphs.clear();
    }

    // ------------------------------------------------------------------ 선장·정비사·보급관

    /** 조타륜·파손 지점 우클릭. */
    void click(Player p, ClickKind kind, int index) {
        Member m = members.get(p.getUniqueId());
        if (m == null || !m.present()) {
            return;
        }
        switch (kind) {
            case WHEEL -> {
                boolean captainPresent =
                        members.values().stream()
                                .anyMatch(o -> o.present() && o.role == BossRole.CAPTAIN);
                if (m.role != BossRole.CAPTAIN && captainPresent) {
                    send(m.uuid, "boss.wheel.captain_only");
                    return;
                }
                helm = m.uuid;
                send(m.uuid, "boss.wheel.taken");
            }
            case REPAIR -> repair(p, m, index);
        }
    }

    /** 정비사 수리: 손에 든 수리 키트 1개로 내구도를 복구한다. 지점이 없는 갑판이면 index −1. */
    void repair(Player p, Member m, int index) {
        if (!fight.state().fighting()) {
            return;
        }
        if (!mgr.items().isRepairKit(p.getInventory().getItemInMainHand())) {
            send(m.uuid, "boss.repair.need_kit");
            return;
        }
        if (fight.hull() >= fight.hullMax()) {
            send(m.uuid, "boss.repair.full");
            return;
        }
        var hand = p.getInventory().getItemInMainHand();
        hand.setAmount(hand.getAmount() - 1);
        p.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? null : hand);
        int restored = fight.repair(def.hull().repairPerAction());
        HarborSounds.playAt(BossItems.loc(p), REPAIR_SOUND);
        m.repair += restored;
        m.activity(mgr.now());
        if (index >= 0 && index < repairPoints.size()) {
            repairPoints.get(index).damaged = false;
        }
        broadcastBar(
                "boss.repair.done",
                Placeholder.unparsed("player", m.name),
                Placeholder.unparsed("hull", String.valueOf(fight.hull())),
                Placeholder.unparsed("max", String.valueOf(fight.hullMax())));
    }

    /** 수리 지점이 없는 갑판: 손에 수리 키트를 들고 우클릭. */
    boolean freeRepair(Player p) {
        Member m = members.get(p.getUniqueId());
        if (m == null || !repairPoints.isEmpty()) {
            return false;
        }
        repair(p, m, -1);
        return true;
    }

    /** 보급관이 버프 요리를 먹음: 근처 파티원 수 × supply_seconds를 수리 항목에 더한다. */
    void supply(Player p) {
        Member m = members.get(p.getUniqueId());
        if (m == null || m.role != BossRole.QUARTERMASTER || !fight.state().fighting()) {
            return;
        }
        Location at = BossItems.loc(p);
        int near = 0;
        for (Member o : members.values()) {
            Player op = Bukkit.getPlayer(o.uuid);
            if (op != null && o.present() && BossItems.loc(op).distanceSquared(at) <= 64) {
                near++;
            }
        }
        m.repair += (double) near * bb.supplySeconds();
        m.activity(mgr.now());
        broadcast(
                "boss.supply.done",
                Placeholder.unparsed("player", m.name),
                Placeholder.unparsed("count", String.valueOf(near)));
    }

    /** 보급관이 있으면 드롭 보너스 (추가 제안). */
    boolean hasQuartermaster() {
        return members.values().stream()
                .anyMatch(m -> !m.dropped && m.role == BossRole.QUARTERMASTER);
    }

    // ------------------------------------------------------------------ 입력 (QTE·선장)

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
        if (m.uuid.equals(helm)) {
            for (Telegraph t : telegraphs) {
                Qte.Key side = t.chargeSide;
                if (side == null || t.evaded) {
                    continue;
                }
                Interaction w = wheel;
                if (w != null && BossItems.loc(w).distanceSquared(BossItems.loc(p)) > 16) {
                    send(m.uuid, "boss.wheel.too_far");
                    continue;
                }
                Qte.Key opposite = side.inverted();
                if (pressed.contains(opposite)) {
                    t.evaded = true;
                    mgr.api().ui().actionBar(p, msg("boss.charge.ok"));
                } else if (pressed.contains(side)) {
                    mgr.api().ui().actionBar(p, msg("boss.charge.wrong"));
                }
            }
        }
        Qte.Track track = m.track;
        if (track != null && qteRunning && track.press(pressed, now)) {
            if (track.results().getLast()) {
                m.activity(now);
                lastWorldActivity.put(m.uuid, now);
            }
            showQte(p, m);
        }
    }

    private void tickQte(long now) {
        if (!qteRunning) {
            if (qteGapUntil >= 0 && now >= qteGapUntil) {
                startRound(now);
            }
            return;
        }
        List<Qte.Track> tracks = new ArrayList<>();
        for (Member m : members.values()) {
            Qte.Track t = m.track;
            if (t == null) {
                continue;
            }
            if (!m.present() || m.fallen(now)) {
                t.setExcluded(true);
            }
            int before = t.index();
            t.tick(now);
            if (t.index() != before) {
                Player p = Bukkit.getPlayer(m.uuid);
                if (p != null) {
                    showQte(p, m);
                }
            }
            tracks.add(t);
        }
        if (Qte.allDone(tracks) || now >= qteRoundEnd) {
            double rate = Qte.rate(tracks);
            boolean ok = rate >= qtePass();
            broadcast(
                    ok ? "boss.qte.round_ok" : "boss.qte.round_fail",
                    Placeholder.unparsed("rate", String.valueOf(Math.round(rate * 100))));
            qteRunning = false;
            for (Member m : members.values()) {
                m.track = null;
            }
            handle(fight.roundResult(ok));
            if (!finished && fight.phase() instanceof Fight.FinalTug) {
                qteGapUntil = now + bb.qteGapTicks();
            }
        }
    }

    private void startRound(long now) {
        BossDef.Qte q =
                def.phase(BossDef.PhaseKind.FINAL_TUG)
                        .map(BossDef.PhaseDef::qte)
                        .orElse(BossDef.Qte.DEFAULT);
        int inputs = q.inputs() > 0 ? q.inputs() : bb.qteInputs();
        int window = q.windowTicks() > 0 ? q.windowTicks() : bb.qteWindowTicks();
        int eligible = 0;
        for (Member m : members.values()) {
            Player p = Bukkit.getPlayer(m.uuid);
            if (p == null || !m.present() || m.fallen(now)) {
                m.track = null;
                continue;
            }
            Qte.Track t = new Qte.Track(Qte.sequence(random, inputs), window, INPUT_TOLERANCE, now);
            t.setInverted(m.invertedUntil > now);
            m.track = t;
            eligible++;
            showQte(p, m);
        }
        qteRunning = true;
        qteGapUntil = -1;
        qteRoundEnd = now + (long) inputs * (window + INPUT_TOLERANCE) + 20;
        if (eligible == 0) {
            // 모두 낙수·이탈이면 이번 라운드는 실패로 끝난다
            qteRoundEnd = now;
        }
        if (fight.phase() instanceof Fight.FinalTug(int round, int s, int f)) {
            broadcast(
                    "boss.qte.round",
                    Placeholder.unparsed("round", String.valueOf(round)),
                    Placeholder.unparsed("ok", String.valueOf(s)),
                    Placeholder.unparsed("fail", String.valueOf(f)));
        }
    }

    private double qtePass() {
        BossDef.Qte q =
                def.phase(BossDef.PhaseKind.FINAL_TUG)
                        .map(BossDef.PhaseDef::qte)
                        .orElse(BossDef.Qte.DEFAULT);
        return q.pass() > 0 ? q.pass() : bb.qtePass();
    }

    private void endQte() {
        qteRunning = false;
        qteGapUntil = -1;
        for (Member m : members.values()) {
            m.track = null;
            Player p = Bukkit.getPlayer(m.uuid);
            if (p != null) {
                p.clearTitle();
            }
        }
    }

    /** 타이틀 글리프로 입력 순서를 보여준다 (끝난 것 초록·빨강, 지금 노랑, 남은 것 회색). */
    private void showQte(Player p, Member m) {
        Qte.Track t = m.track;
        if (t == null) {
            return;
        }
        List<Boolean> results = t.results();
        Component line = Component.empty();
        for (int i = 0; i < t.keys().size(); i++) {
            Component key = msg("boss.qte.key." + t.keys().get(i).key());
            String style =
                    i < results.size()
                            ? (results.get(i) ? "boss.qte.slot.ok" : "boss.qte.slot.fail")
                            : i == t.index() ? "boss.qte.slot.current" : "boss.qte.slot.next";
            line = line.append(msg(style, Placeholder.component("key", key)));
        }
        Component sub =
                t.done()
                        ? msg("boss.qte.wait")
                        : msg(
                                m.invertedUntil > mgr.now()
                                        ? "boss.qte.hint_inverted"
                                        : "boss.qte.hint");
        p.showTitle(
                Title.title(
                        line,
                        sub,
                        Title.Times.times(
                                Duration.ZERO, Duration.ofSeconds(3), Duration.ofMillis(150))));
    }

    // ------------------------------------------------------------------ 이탈·재접속·낙수

    /** 재접속 (슬롯 유지 시간 안). field는 갑판으로 돌려보낸다. */
    void rejoin(Player p) {
        Member m = members.get(p.getUniqueId());
        if (m == null || m.dropped) {
            return;
        }
        m.absentSince = -1;
        m.input = Qte.InputState.NONE;
        enter(p, m, true);
        send(m.uuid, "boss.rejoin");
        if (m.role == BossRole.ANCHOR) {
            m.reopenAt = mgr.now() + 20;
        }
    }

    void quit(Player p) {
        Member m = members.get(p.getUniqueId());
        if (m == null || m.dropped) {
            return;
        }
        if (m.absentSince < 0) {
            m.absentSince = mgr.now();
            onAbsent(m, mgr.now());
        }
    }

    /** 경기장 안 사망 대신 '낙수' (아이템 보존, 5초 뒤 갑판 복귀). */
    void fall(Member m, Player p) {
        long now = mgr.now();
        if (m.fallen(now)) {
            return;
        }
        m.fallenUntil = now + bb.fallTicks();
        p.setHealth(Math.max(1.0, p.getHealth()));
        p.setFireTicks(0);
        p.addPotionEffect(
                new PotionEffect(
                        PotionEffectType.BLINDNESS, bb.fallTicks(), 0, false, false, false));
        mgr.api()
                .ui()
                .title(
                        p,
                        msg("boss.fall.title"),
                        msg(
                                "boss.fall.subtitle",
                                Placeholder.unparsed(
                                        "seconds", String.valueOf(bb.fallTicks() / 20))),
                        Duration.ZERO,
                        Duration.ofMillis(bb.fallTicks() * 50L),
                        Duration.ofMillis(200));
        Qte.Track track = m.track;
        if (track != null) {
            track.setExcluded(true);
        }
    }

    public boolean isMember(UUID player) {
        return members.containsKey(player);
    }

    Optional<Member> member(UUID player) {
        return Optional.ofNullable(members.get(player));
    }

    // ------------------------------------------------------------------ 월드 보스

    /** 반경 안 플레이어가 처음 기여하면 참가자가 된다 (파티 구분 없음). */
    private @Nullable Member joinWorld(Player p, BossRole role) {
        if (!def.isWorld() || !fight.state().fighting() || !inArena(BossItems.loc(p))) {
            return null;
        }
        if (mgr.encounterOfPlayer(p.getUniqueId()).filter(e -> e != this).isPresent()) {
            return null;
        }
        Member m = new Member(p.getUniqueId(), p.getName(), role, false);
        m.chose = true;
        members.put(m.uuid, m);
        mgr.bind(m.uuid, this);
        enter(p, m, false);
        send(m.uuid, "boss.world.joined");
        return m;
    }

    /** 10초마다: 유효 참여자(최근 60초 기여, 최소 10명)로 최대 게이지를 다시 정한다. 진행률은 유지. */
    private void rescaleWorld(long now) {
        int effective =
                HpScaling.effectiveParticipants(lastWorldActivity, now, bb.worldActiveTicks());
        fight.rescale(HpScaling.worldMaxHp(def.hp().base(), effective, bb.worldMinParticipants()));
    }

    public int effectiveParticipants() {
        return HpScaling.effectiveParticipants(lastWorldActivity, mgr.now(), bb.worldActiveTicks());
    }

    /** 25% 단위 단계 (0 시작, 1 = 75% 이하, 2 = 50% 이하, 3 = 25% 이하). */
    public int stage() {
        double percent = fight.hpRatio() * 100;
        return percent <= 25 ? 3 : percent <= 50 ? 2 : percent <= 75 ? 1 : 0;
    }

    private void checkWorldStage() {
        int s = stage();
        if (s > worldStage) {
            worldStage = s;
            mgr.worldStage(this, s);
        }
    }

    /** resume: 저장한 진행률·기여도로 되살린다. */
    public void restore(double hpRatio, Map<UUID, Contribution> saved) {
        fight.restore(hpRatio, 1.0, true);
        worldStage = stage();
        for (Contribution c : saved.values()) {
            Member m =
                    new Member(
                            c.player(),
                            String.valueOf(c.player()).substring(0, 8),
                            c.role(),
                            false);
            Player p = Bukkit.getPlayer(c.player());
            if (p != null) {
                m = new Member(c.player(), p.getName(), c.role(), false);
            }
            m.damage = c.damage();
            m.tensionTicks = Math.round(c.tension() * 20);
            m.repair = c.repair();
            m.evasion = c.evasion();
            m.chose = true;
            m.absentSince = p == null ? mgr.now() : -1;
            members.put(c.player(), m);
            mgr.bind(c.player(), this);
        }
    }

    // ------------------------------------------------------------------ UI

    private void updateUi(long now) {
        float progress = (float) Math.clamp(fight.hpRatio(), 0.0, 1.0);
        String key =
                switch (fight.state()) {
                    case HOOK -> "boss.bar.hook";
                    case FINAL_TUG -> "boss.bar.tug";
                    default -> def.goal() == BossDef.Goal.CALM ? "boss.bar.calm" : "boss.bar.fight";
                };
        Component name =
                msg(
                        key,
                        Placeholder.component("boss", bossName()),
                        Placeholder.unparsed(
                                "hp", String.valueOf(Math.round(fight.hpRatio() * 100))),
                        Placeholder.unparsed(
                                "gauge", String.valueOf(Math.round(fight.hookGauge() * 100))),
                        Placeholder.unparsed("hull", String.valueOf(fight.hull())),
                        Placeholder.unparsed("max", String.valueOf(fight.hullMax())),
                        Placeholder.unparsed("time", time(fight.remainingTicks())));
        float bar = fight.state() == EncounterState.HOOK ? (float) fight.hookGauge() : progress;
        for (Member m : members.values()) {
            BossBar b = m.bar;
            if (b != null && m.present()) {
                b.name(name);
                b.progress(Math.clamp(bar, 0f, 1f));
            }
        }
        if (def.isWorld()) {
            mgr.worldUi(this, name, progress);
        }
        if (fight.state() == EncounterState.COUNTER && now % 20 == 0) {
            for (Member m : members.values()) {
                Player p = Bukkit.getPlayer(m.uuid);
                if (p == null || !m.present() || m.session != null) {
                    continue;
                }
                String hint =
                        switch (m.role) {
                            case HARPOONER ->
                                    weakExposed ? "boss.hint.harpoon_weak" : "boss.hint.harpoon";
                            case CAPTAIN -> "boss.hint.captain";
                            case MECHANIC -> "boss.hint.mechanic";
                            case QUARTERMASTER -> "boss.hint.quartermaster";
                            case ANCHOR -> "boss.hint.anchor";
                        };
                mgr.api()
                        .ui()
                        .actionBar(
                                p,
                                msg(
                                        hint,
                                        Placeholder.unparsed("hull", String.valueOf(fight.hull())),
                                        Placeholder.unparsed(
                                                "max", String.valueOf(fight.hullMax()))));
            }
        }
        // 파손 지점 연기
        if (now % 10 == 0) {
            for (RepairPoint rp : repairPoints) {
                if (rp.damaged) {
                    for (Player p : viewers()) {
                        p.spawnParticle(
                                Particle.CAMPFIRE_COSY_SMOKE,
                                rp.at.getX(),
                                rp.at.getY() + 0.5,
                                rp.at.getZ(),
                                1,
                                0.1,
                                0.1,
                                0.1,
                                0.01);
                    }
                }
            }
        }
    }

    private static String time(long ticks) {
        long s = ticks / 20;
        return String.format("%d:%02d", s / 60, s % 60);
    }

    // ------------------------------------------------------------------ 종료

    /** 리그·Interaction·예고·보스바·텐션·경계·청크 티켓을 모두 걷는다. */
    void cleanup() {
        finished = true;
        clearTelegraphs();
        RigInstance r = rig;
        rig = null;
        if (r != null) {
            r.remove();
        }
        Interaction w = wheel;
        if (w != null) {
            mgr.unbindClick(w);
            w.remove();
        }
        for (RepairPoint rp : repairPoints) {
            mgr.unbindClick(rp.entity);
            rp.entity.remove();
        }
        repairPoints.clear();
        for (Member m : members.values()) {
            closeSession(m);
            m.track = null;
            Player p = Bukkit.getPlayer(m.uuid);
            if (p != null) {
                leaveVisuals(p, m);
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
        return rig;
    }

    /** 리그 판정 상자 Interaction과 뼈 이름 (작살 궤적 판정용). */
    List<Map.Entry<Interaction, String>> hitboxes() {
        RigInstance r = rig;
        if (r == null) {
            return List.of();
        }
        List<Map.Entry<Interaction, String>> out = new ArrayList<>();
        int i = 0;
        for (Entity e : r.entities()) {
            if (e instanceof Interaction box && i < hitboxBones.size()) {
                out.add(Map.entry(box, hitboxBones.get(i++)));
            }
        }
        return out;
    }

    int telegraphEntities() {
        return telegraphs.stream().mapToInt(Telegraph::entityCount).sum();
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

    // ------------------------------------------------------------------ 문구

    public Component bossName() {
        return MiniMessage.miniMessage().deserialize(def.name());
    }

    Component msg(String key, TagResolver... resolvers) {
        return mgr.api().messages().get(key, resolvers);
    }

    void send(UUID player, String key, TagResolver... resolvers) {
        Player p = Bukkit.getPlayer(player);
        if (p != null) {
            mgr.api().messages().send(p, key, resolvers);
        }
    }

    void broadcast(String key, TagResolver... resolvers) {
        Component c = msg(key, resolvers);
        for (Player p : def.isWorld() ? viewers() : onlinePlayers()) {
            p.sendMessage(c);
        }
    }

    private void broadcastBar(String key, TagResolver... resolvers) {
        Component c = msg(key, resolvers);
        for (Player p : def.isWorld() ? viewers() : onlinePlayers()) {
            mgr.api().ui().actionBar(p, c);
        }
    }

    private void titleAll(String title, String subtitle) {
        Component t = msg(title, Placeholder.component("boss", bossName()));
        Component s = msg(subtitle, Placeholder.component("boss", bossName()));
        for (Player p : def.isWorld() ? viewers() : onlinePlayers()) {
            mgr.api()
                    .ui()
                    .title(
                            p,
                            t,
                            s,
                            Duration.ofMillis(200),
                            Duration.ofSeconds(2),
                            Duration.ofMillis(400));
        }
    }

    BossType type() {
        return def.type();
    }
}
