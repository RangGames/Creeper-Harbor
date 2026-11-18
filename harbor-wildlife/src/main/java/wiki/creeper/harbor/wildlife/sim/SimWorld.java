package wiki.creeper.harbor.wildlife.sim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.wildlife.api.Medium;
import wiki.creeper.harbor.wildlife.api.ScriptedBehaviour;
import wiki.creeper.harbor.wildlife.api.WildState;
import wiki.creeper.harbor.wildlife.content.BehaviourBook.ActionDef;
import wiki.creeper.harbor.wildlife.content.WildlifeBalance;

/**
 * 가상 개체 전체와 한 주기 계산 ('17-3' 9.4). 서버 API를 쓰지 않는다 (매질은 {@link MediumField}, 플레이어는 {@link
 * PlayerSnap}). 전용 계산 스레드 하나만 부른다 (명령 메서드도 그 스레드에서 실행되도록 엔진이 줄을 세운다).
 *
 * <p>상태: CRUISE(집 반경 목표점) · IDLE(쉬기) · ACTION(고유 동작) · FLEE(도주) · APPROACH(찌 궤도) · BITE(입질 후 잠김) ·
 * SCHOOL(대장 추종). 개체마다 주사위 간격(1초 ± 0.3초)과 첫 주사위 시점, 애니메이션 위상이 따로라서 같은 무리라도 박자가 다르다.
 */
public final class SimWorld {

    /** 월드별 매질 조회기. */
    @FunctionalInterface
    public interface Fields {
        MediumField field(String world);
    }

    private static final double TAU = Math.PI * 2;

    private final Fields fields;
    private final RandomGenerator random;
    private WildlifeBalance bal;
    private final Map<UUID, Agent> agents = new LinkedHashMap<>();
    private final Map<UUID, List<Agent>> schools = new HashMap<>();
    private final Map<UUID, Vec3> lastPlayer = new HashMap<>();
    private final Map<UUID, Vec3> playerVelocity = new HashMap<>();
    private final List<SimEvent> events = new ArrayList<>();
    private final List<UUID> pendingRemoval = new ArrayList<>();
    private final Map<UUID, SimEvent.Reason> removalReason = new HashMap<>();
    private double time;
    private long seq;

    public SimWorld(Fields fields, WildlifeBalance bal, RandomGenerator random) {
        this.fields = fields;
        this.bal = bal;
        this.random = random;
    }

    public void balance(WildlifeBalance b) {
        this.bal = b;
    }

    public double time() {
        return time;
    }

    public int size() {
        return agents.size();
    }

    // ================================================================== 명령

    /** 개체(무리)를 만든다. 첫 번째 ID가 대장. 매질 밖이면 만들지 않는다. */
    public void spawn(SpawnOrder o) {
        MediumField field = fields.field(o.world());
        SpeciesProfile p = o.profile();
        Medium cell = p.medium().cell();
        if (!field.is(cell, o.center().x(), o.center().y(), o.center().z())) {
            return;
        }
        ScriptedBehaviour script = o.scripted();
        List<Agent> members = new ArrayList<>();
        Vec3 heading = randomHorizontal();
        for (int i = 0; i < o.ids().size(); i++) {
            Vec3 at = o.center();
            if (i > 0) {
                at = scatter(field, cell, o.center(), 1.5);
            }
            int phase = random.nextInt(1 << 16);
            Agent a =
                    new Agent(
                            o.ids().get(i),
                            p,
                            o.world(),
                            at,
                            i == 0
                                    ? heading
                                    : Steer.normalize(heading.add(randomHorizontal().mul(0.3))),
                            o.region(),
                            o.tag(),
                            phase,
                            script != null,
                            script != null && script.hold(),
                            script != null && script.ignoreThreat(),
                            script != null && script.lifetimeSeconds() > 0
                                    ? time + script.lifetimeSeconds()
                                    : 0,
                            i == 0 ? o.path() : List.of(),
                            o.airBaseY(),
                            random.nextDouble() * TAU);
            a.speed = p.baseSpeed() * 0.5;
            a.nextDice = time + random.nextDouble() * bal.diceInterval();
            a.retargetAt = time;
            if (o.school() != null && o.ids().size() > 1) {
                a.school = o.school();
                a.leader = i == 0;
                a.state = a.leader ? WildState.CRUISE : WildState.SCHOOL;
                a.resume = a.state;
            }
            agents.put(a.id, a);
            members.add(a);
        }
        if (o.school() != null && members.size() > 1) {
            schools.put(o.school(), members);
        }
        if (script != null) {
            String name = script.action();
            ActionDef act = name == null ? null : p.actions().get(name);
            if (act != null) {
                startAction(members.getFirst(), act);
            }
        }
    }

    private Vec3 scatter(MediumField field, Medium cell, Vec3 center, double r) {
        for (int i = 0; i < 6; i++) {
            Vec3 at =
                    center.add(
                            new Vec3(
                                    (random.nextDouble() * 2 - 1) * r,
                                    (random.nextDouble() * 2 - 1) * r * 0.5,
                                    (random.nextDouble() * 2 - 1) * r));
            if (field.is(cell, at.x(), at.y(), at.z())) {
                return at;
            }
        }
        return center;
    }

    public void remove(UUID id, SimEvent.Reason reason) {
        if (agents.containsKey(id) && !removalReason.containsKey(id)) {
            pendingRemoval.add(id);
            removalReason.put(id, reason);
        }
    }

    public int removeChunk(String world, int cx, int cz) {
        int n = 0;
        for (Agent a : agents.values()) {
            if (a.world.equals(world)
                    && Math.floorDiv(MediumField.floor(a.pos.x()), 16) == cx
                    && Math.floorDiv(MediumField.floor(a.pos.z()), 16) == cz) {
                remove(a.id, SimEvent.Reason.CHUNK_UNLOAD);
                n++;
            }
        }
        return n;
    }

    /** 반경 안 (radius < 0이면 그 월드 전체, world가 null이면 모든 월드) 개체를 지운다. */
    public int removeWithin(@Nullable String world, Vec3 center, double radius) {
        int n = 0;
        double r2 = radius * radius;
        for (Agent a : agents.values()) {
            if (world != null && !a.world.equals(world)) {
                continue;
            }
            if (world != null && radius >= 0 && Steer.distanceSq(a.pos, center) > r2) {
                continue;
            }
            remove(a.id, SimEvent.Reason.REQUESTED);
            n++;
        }
        return n;
    }

    /**
     * 찌로 다가온다 ('17-3' 9.4 먹이·미끼 반응 1·2단계).
     *
     * @param seconds 입질까지 남은 시간 (claimed면 이 안에 찌 아래에 닿는다)
     * @param claimed Fishing 그림자를 대행하는 개체인지
     */
    public void approach(UUID id, Vec3 hook, double seconds, boolean claimed, UUID angler) {
        Agent a = agents.get(id);
        if (a == null || a.hidden) {
            return;
        }
        a.hook = hook;
        a.deadline = time + Math.max(0.5, seconds);
        a.claimed = a.claimed || claimed;
        a.angler = angler;
        a.orbitAngle = Math.atan2(a.pos.z() - hook.z(), a.pos.x() - hook.x());
        a.state = WildState.APPROACH;
        a.action = null;
    }

    /** 입질: bite 동작 후 찌 아래로 잠기고 숨는다 (Fishing 그림자가 이어받음). */
    public void bite(UUID id) {
        Agent a = agents.get(id);
        if (a == null || a.hook == null) {
            return;
        }
        a.state = WildState.BITE;
        double len = a.profile.animationSeconds(a.profile.biteAnimation(), 0.4);
        a.biteEnds = time + len;
        String anim = a.profile.biteAnimation();
        if (anim != null) {
            oneShot(a, anim);
        }
    }

    /** 다른 개체에게 빼앗김: 짧게 dart 후 CRUISE ('17-3' 9.4 3단계). */
    public void stolen(UUID id) {
        Agent a = agents.get(id);
        if (a == null || a.claimed) {
            return;
        }
        clearApproach(a);
        a.state = a.cruiseState();
        ActionDef dart = a.profile.actions().get("dart");
        if (dart != null) {
            startAction(a, dart);
        }
        a.retargetAt = time;
    }

    /**
     * 세션이 끝나 풀어 준다 ('17-3' 9.4 4단계).
     *
     * @param caught 그 개체 어종이 실제로 낚였으면 개체를 지운다
     * @param threatFrom 줄이 끊겼으면 위협 위치 (주변이 놀란다)
     */
    public void release(UUID id, boolean caught, @Nullable Vec3 threatFrom) {
        Agent a = agents.get(id);
        if (a == null) {
            return;
        }
        if (caught && a.claimed) {
            remove(id, SimEvent.Reason.CAUGHT);
            return;
        }
        Vec3 hook = a.hook;
        if (a.hidden && hook != null) {
            a.pos = a.pos.add(new Vec3(0, -0.5, 0));
        }
        a.hidden = false;
        clearApproach(a);
        if (threatFrom != null) {
            startle(a, threatFrom, null);
        } else {
            a.state = a.cruiseState();
            a.retargetAt = time;
        }
    }

    /** 이 낚시꾼에게 다가오던 개체를 모두 풀어 준다. */
    public void releaseAngler(UUID angler, boolean lineBroke) {
        for (Agent a : agents.values()) {
            if (angler.equals(a.angler)) {
                Vec3 hook = a.hook;
                release(a.id, false, lineBroke ? hook : null);
            }
        }
    }

    /** 위치 주변에 위협 반응을 준다 (줄 끊김). */
    public void threatAt(String world, Vec3 at, double radius) {
        double r2 = radius * radius;
        for (Agent a : agents.values()) {
            if (a.world.equals(world)
                    && !a.hidden
                    && !a.claimed
                    && Steer.distanceSq(a.pos, at) <= r2) {
                startle(a, at, null);
            }
        }
    }

    /** 지역 경계를 넘었다: 집 쪽으로 돌린다. */
    public void turnBack(UUID id) {
        Agent a = agents.get(id);
        if (a == null) {
            return;
        }
        a.target = a.home;
        a.retargetAt = time + bal.retargetMax();
        a.heading = Steer.normalize(a.home.sub(a.pos));
        if (a.state == WildState.FLEE) {
            a.state = a.cruiseState();
        }
    }

    /** 리그가 바뀐 어종의 값을 바꾼다 (콘텐츠 리로드). */
    public void replaceProfile(HarborId species, SpeciesProfile profile) {
        for (Agent a : agents.values()) {
            if (a.profile.species().equals(species)) {
                a.profile = profile;
                a.animation = profile.swimAnimation();
            }
        }
    }

    /** 동작을 바로 재생한다 (관리 명령·연출). */
    public void play(UUID id, String action) {
        Agent a = agents.get(id);
        if (a == null) {
            return;
        }
        ActionDef def = a.profile.actions().get(action);
        if (def != null) {
            startAction(a, def);
        }
    }

    // ================================================================== 주기

    /** 한 주기를 계산하고 출력 면을 만든다. */
    public Frame step(double dt, List<PlayerSnap> players, long startNanos) {
        time += dt;
        seq++;
        Map<String, List<PlayerSnap>> byWorld = new HashMap<>();
        Map<UUID, Vec3> seen = new HashMap<>();
        for (PlayerSnap p : players) {
            byWorld.computeIfAbsent(p.world(), w -> new ArrayList<>()).add(p);
            Vec3 last = lastPlayer.get(p.id());
            playerVelocity.put(p.id(), last == null ? Vec3.ZERO : p.pos().sub(last).mul(1 / dt));
            seen.put(p.id(), p.pos());
        }
        lastPlayer.clear();
        lastPlayer.putAll(seen);
        playerVelocity.keySet().retainAll(seen.keySet());

        double despawn2 = bal.despawnDistance() * bal.despawnDistance();
        double farStep = 1.0 / Math.max(0.1, bal.farHz());
        for (Agent a : agents.values()) {
            if (removalReason.containsKey(a.id)) {
                continue;
            }
            List<PlayerSnap> ps = byWorld.getOrDefault(a.world, List.of());
            double nearest2 = Double.MAX_VALUE;
            for (PlayerSnap p : ps) {
                nearest2 = Math.min(nearest2, Steer.distanceSq(p.pos(), a.pos));
            }
            a.nearest = Math.sqrt(nearest2);
            if (a.expiresAt > 0) {
                if (time >= a.expiresAt) {
                    remove(a.id, SimEvent.Reason.EXPIRED);
                    continue;
                }
            } else if (nearest2 > despawn2 && !a.claimed) {
                remove(a.id, SimEvent.Reason.DESPAWN);
                continue;
            }
            double use = dt;
            if (a.nearest > bal.showDistance() && !a.claimed) {
                a.farAccum += dt;
                if (a.farAccum < farStep) {
                    continue;
                }
                use = a.farAccum;
            }
            a.farAccum = 0;
            update(a, use, ps);
        }
        for (UUID id : pendingRemoval) {
            Agent a = agents.remove(id);
            if (a != null) {
                detachSchool(a);
                SimEvent.Reason reason = removalReason.getOrDefault(id, SimEvent.Reason.REQUESTED);
                events.add(new SimEvent.Removed(id, reason));
            }
        }
        pendingRemoval.clear();
        removalReason.clear();

        List<FishOut> out = new ArrayList<>(agents.size());
        for (Agent a : agents.values()) {
            out.add(output(a));
        }
        List<SimEvent> ev = List.copyOf(events);
        events.clear();
        return new Frame(seq, out, ev, System.nanoTime() - startNanos, Frame.Index.build(out));
    }

    private void detachSchool(Agent a) {
        UUID sid = a.school;
        if (sid == null) {
            return;
        }
        List<Agent> members = schools.get(sid);
        if (members == null) {
            return;
        }
        members.remove(a);
        if (members.size() <= 1) {
            for (Agent m : members) {
                m.school = null;
                m.leader = false;
                if (m.state == WildState.SCHOOL) {
                    m.state = WildState.CRUISE;
                }
                m.resume = WildState.CRUISE;
            }
            schools.remove(sid);
        } else if (a.leader) {
            Agent next = members.getFirst();
            next.leader = true;
            if (next.state == WildState.SCHOOL) {
                next.state = WildState.CRUISE;
            }
            next.resume = WildState.CRUISE;
            next.home = next.pos;
            next.path = a.path;
            next.pathIndex = a.pathIndex;
        }
    }

    private FishOut output(Agent a) {
        return new FishOut(
                a.id,
                a.profile,
                a.world,
                a.pos.x(),
                a.pos.y(),
                a.pos.z(),
                a.yaw,
                a.pitch,
                a.state,
                a.animation,
                a.animationSpeed,
                a.oneShot,
                a.oneShotSeq,
                a.teleportSeq,
                a.hidden,
                a.school,
                a.leader,
                a.scripted,
                a.tag,
                a.region,
                a.phaseOffset,
                a.velocity.x(),
                a.velocity.y(),
                a.velocity.z(),
                a.steer.x(),
                a.steer.y(),
                a.steer.z(),
                a.nearest);
    }

    // ================================================================== 개체 하나

    private void update(Agent a, double dt, List<PlayerSnap> players) {
        if (a.hidden) {
            return;
        }
        SpeciesProfile p = a.profile;
        MediumField field = fields.field(a.world);

        // 무리 전파 (지난 주기에 이웃이 놀람)
        Vec3 pending = a.pendingThreat;
        if (pending != null) {
            a.pendingThreat = null;
            if (a.state != WildState.FLEE) {
                startle(a, pending, null);
            }
        }
        detectThreat(a, players);
        rollDice(a);

        Vec3 desired;
        double speed;
        double base = p.baseSpeed();
        switch (a.state) {
            case IDLE -> {
                if (time >= a.stateUntil) {
                    a.state = a.resume;
                }
                desired = a.heading;
                speed = base * bal.idleSpeed();
            }
            case ACTION -> {
                ActionDef act = a.action;
                if (act == null || time >= a.repEnds) {
                    a.repsLeft--;
                    if (act != null && a.repsLeft > 0) {
                        beginRep(a, act, field);
                    } else {
                        a.action = null;
                        a.state = a.resume;
                    }
                }
                ActionDef now = a.action;
                desired = a.member() ? flock(a, base).dir() : cruiseDir(a, field);
                speed = base * (1 + (now == null ? 0 : now.speedDelta()));
            }
            case FLEE -> {
                if (time >= a.stateUntil) {
                    a.state = a.cruiseState();
                    if (!a.member()) {
                        a.home = a.pos;
                    }
                    a.retargetAt = time;
                }
                Vec3 away = Steer.flee(a.pos, a.threat, 1);
                desired = new Vec3(away.x(), away.y() * 0.3, away.z());
                speed = base * (1 + bal.fleeSpeed());
            }
            case APPROACH -> {
                Vec3 hook = a.hook;
                if (hook == null || time > a.deadline + 60) {
                    clearApproach(a);
                    a.state = a.cruiseState();
                    desired = a.heading;
                    speed = base;
                } else {
                    a.orbitAngle += Math.toRadians(bal.orbitSpeed()) * dt;
                    double r = bal.orbitRadius();
                    Vec3 orbit =
                            hook.add(
                                    new Vec3(
                                            Math.cos(a.orbitAngle) * r,
                                            -1.0,
                                            Math.sin(a.orbitAngle) * r));
                    double dist = Math.sqrt(Steer.distanceSq(a.pos, orbit));
                    double remain = a.deadline - time;
                    speed =
                            a.claimed && remain > 0.1
                                    ? Math.clamp(dist / remain, base * 0.3, base * 2.5)
                                    : base * 0.6;
                    desired = Steer.arrive(a.pos, orbit, 1, 1.0);
                }
            }
            case BITE -> {
                Vec3 hook = a.hook;
                Vec3 under = hook == null ? a.pos : hook.add(new Vec3(0, -0.6, 0));
                desired = Steer.arrive(a.pos, under, 1, 0.8);
                speed = base * 1.5;
                if (time >= a.biteEnds) {
                    if (a.claimed) {
                        a.hidden = true; // Fishing 그림자가 같은 자리에서 이어받는다
                        a.pos = under;
                        a.velocity = Vec3.ZERO;
                        return;
                    }
                    clearApproach(a);
                    a.state = a.cruiseState();
                }
            }
            case SCHOOL -> {
                Flock f = flock(a, base);
                desired = f.dir();
                speed = f.speed();
            }
            default -> {
                desired = cruiseDir(a, field);
                speed = base * (a.hold ? 0.5 : 1);
            }
        }
        move(a, field, desired, speed, dt);
    }

    // ---------------------------------------------------------------- 위협

    private void detectThreat(Agent a, List<PlayerSnap> players) {
        if (a.ignoreThreat || a.state == WildState.BITE || a.claimed || a.state == WildState.FLEE) {
            return;
        }
        SpeciesProfile p = a.profile;
        for (PlayerSnap ps : players) {
            if (!ps.threat()) {
                continue;
            }
            double d = Math.sqrt(Steer.distanceSq(ps.pos(), a.pos));
            if (d > p.threatRadius() * Math.max(1, bal.boatFactor())) {
                continue;
            }
            Double last = a.startled.get(ps.id());
            boolean habituated = last != null && time - last < bal.habituateSeconds();
            double r =
                    ThreatRules.radius(
                            p.threatRadius(),
                            ps.sneaking(),
                            ps.boat(),
                            habituated,
                            bal.sneakFactor(),
                            bal.boatFactor(),
                            bal.habituateFactor());
            Vec3 v = playerVelocity.getOrDefault(ps.id(), Vec3.ZERO);
            double closing =
                    ThreatRules.closing(
                            ps.pos().x(),
                            ps.pos().y(),
                            ps.pos().z(),
                            v.x(),
                            v.y(),
                            v.z(),
                            a.pos.x(),
                            a.pos.y(),
                            a.pos.z());
            if (ThreatRules.triggered(d, closing, r, bal.closingSpeed(), bal.innerRatio())) {
                a.startled.put(ps.id(), time);
                startle(a, ps.pos(), ps.id());
                return;
            }
        }
    }

    /** 놀람 동작 후 위협 반대 방향으로 3~5초 도주. 무리 안 4블록 이내 개체는 다음 주기에 함께 도망친다. */
    private void startle(Agent a, Vec3 from, @Nullable UUID player) {
        SpeciesProfile p = a.profile;
        boolean wasApproach = a.state == WildState.APPROACH;
        if (wasApproach) {
            clearApproach(a);
        }
        a.threat = from;
        a.state = WildState.FLEE;
        a.action = null;
        a.stateUntil =
                time
                        + bal.fleeMinSeconds()
                        + random.nextDouble()
                                * Math.max(0, bal.fleeMaxSeconds() - bal.fleeMinSeconds());
        ActionDef reaction = p.threatAction();
        if (p.medium() == Medium.FLOOR) {
            // 조개류: 닫기(burrow) 동작 후 그대로 닫혀 있음
            ActionDef special = p.special();
            String anim =
                    p.hasAnimation("burrow") ? "burrow" : special != null ? special.name() : null;
            if (p.hasAnimation(anim) && anim != null) {
                oneShot(a, anim);
            }
        } else if (reaction != null) {
            oneShot(a, reaction.name());
            particle(a, reaction.particle());
            a.impulse = a.impulse.add(Steer.flee(a.pos, from, Steer.length(reaction.impulse())));
        }
        if (player != null) {
            events.add(new SimEvent.Startle(a.id, player));
        }
        UUID sid = a.school;
        if (sid != null) {
            double s2 = bal.spreadRadius() * bal.spreadRadius();
            for (Agent m : schools.getOrDefault(sid, List.of())) {
                if (m != a
                        && m.state != WildState.FLEE
                        && !m.claimed
                        && Steer.distanceSq(m.pos, a.pos) <= s2) {
                    m.pendingThreat = from;
                }
            }
        }
    }

    // ---------------------------------------------------------------- 주사위

    private void rollDice(Agent a) {
        if (time < a.nextDice) {
            return;
        }
        a.nextDice = time + bal.diceInterval() + (random.nextDouble() * 2 - 1) * bal.diceJitter();
        if (a.state != WildState.CRUISE && a.state != WildState.SCHOOL) {
            return;
        }
        SpeciesProfile p = a.profile;
        double r = random.nextDouble();
        ActionDef special = p.special();
        if (r < p.groupDef().specialChance()) {
            if (special != null) {
                startAction(a, special);
            }
        } else if (r < p.groupDef().specialChance() + p.groupDef().idleChance()) {
            a.resume = a.cruiseState();
            a.state = WildState.IDLE;
            a.stateUntil = time + p.groupDef().idleSeconds();
        }
    }

    private void startAction(Agent a, ActionDef act) {
        if (a.state != WildState.ACTION) {
            a.resume =
                    a.state == WildState.IDLE || a.state == WildState.FLEE
                            ? a.cruiseState()
                            : a.state;
            if (a.resume != WildState.SCHOOL && a.resume != WildState.CRUISE) {
                a.resume = a.cruiseState();
            }
        }
        a.state = WildState.ACTION;
        a.action = act;
        a.repsLeft = act.reps();
        beginRep(a, act, fields.field(a.world));
    }

    private void beginRep(Agent a, ActionDef act, MediumField field) {
        SpeciesProfile p = a.profile;
        a.repEnds = time + p.animationSeconds(act.name(), 1.0);
        if (p.hasAnimation(act.name())) {
            oneShot(a, act.name());
        }
        Vec3 f = Steer.normalize(new Vec3(a.heading.x(), 0, a.heading.z()));
        if (f.equals(Vec3.ZERO)) {
            f = new Vec3(0, 0, 1);
        }
        Vec3 right = new Vec3(-f.z(), 0, f.x());
        Vec3 imp = act.impulse();
        a.impulse =
                a.impulse.add(right.mul(imp.x())).add(new Vec3(0, imp.y(), 0)).add(f.mul(imp.z()));
        if (act.teleports()) {
            teleport(a, act, field);
        }
        particle(a, act.particle());
    }

    /** 순간 이동 (공중 teleport 4~6블록 + 포털 파티클). 갈 수 있는 칸이 없으면 제자리. */
    private void teleport(Agent a, ActionDef act, MediumField field) {
        Medium cell = a.profile.medium().cell();
        for (int i = 0; i < 8; i++) {
            Vec3 dir =
                    cell == Medium.AIR
                            ? randomUnit()
                            : Steer.normalize(
                                    randomHorizontal()
                                            .add(
                                                    new Vec3(
                                                            0,
                                                            (random.nextDouble() - 0.5) * 0.4,
                                                            0)));
            double d =
                    act.teleportMin()
                            + random.nextDouble() * (act.teleportMax() - act.teleportMin());
            Vec3 to = a.pos.add(dir.mul(d));
            if (Steer.open(field, cell, to) && inBand(a, to.y())) {
                particle(a, act.particle());
                a.pos = to;
                a.teleportSeq++;
                return;
            }
        }
    }

    private void oneShot(Agent a, String animation) {
        a.oneShot = animation;
        a.oneShotSeq++;
    }

    private void particle(Agent a, @Nullable String key) {
        if (key != null) {
            events.add(
                    new SimEvent.Particle(
                            a.id,
                            a.world,
                            a.pos.x(),
                            a.pos.y(),
                            a.pos.z(),
                            key,
                            a.profile.medium()));
        }
    }

    private void clearApproach(Agent a) {
        a.hook = null;
        a.claimed = false;
        a.angler = null;
        a.hidden = false;
    }

    // ---------------------------------------------------------------- 순항·무리

    private Vec3 cruiseDir(Agent a, MediumField field) {
        if (a.hold) {
            return Steer.arrive(a.pos, a.home, 1, 2.0);
        }
        List<Vec3> path = a.path;
        if (!path.isEmpty()) {
            Vec3 next = path.get(Math.min(a.pathIndex, path.size() - 1));
            if (Steer.distanceSq(a.pos, next) < 4) {
                a.pathIndex++;
                if (a.pathIndex >= path.size()) {
                    a.path = List.of();
                    a.home = a.pos;
                }
            }
            return Steer.seek(a.pos, next, 1);
        }
        if (time >= a.retargetAt || Steer.distanceSq(a.pos, a.target) < 2.25) {
            a.target = pickTarget(a, field);
            a.retargetAt =
                    time
                            + bal.retargetMin()
                            + random.nextDouble()
                                    * Math.max(0, bal.retargetMax() - bal.retargetMin());
        }
        return Steer.arrive(a.pos, a.target, 1, 2.0);
    }

    /** 집 반경 안 매질 칸 목표점 (깊이 범위·높이 띠 안). 8번 안에 못 찾으면 앞쪽 3블록. */
    private Vec3 pickTarget(Agent a, MediumField field) {
        SpeciesProfile p = a.profile;
        Medium cell = p.medium().cell();
        double r = bal.homeRadius();
        for (int i = 0; i < 8; i++) {
            double ang = random.nextDouble() * TAU;
            double dist = Math.sqrt(random.nextDouble()) * r;
            double dy = (random.nextDouble() * 2 - 1) * (cell == Medium.AIR ? 6 : 3);
            Vec3 t = a.home.add(new Vec3(Math.cos(ang) * dist, dy, Math.sin(ang) * dist));
            if (!Steer.open(field, cell, t) || !inBand(a, t.y())) {
                continue;
            }
            if (cell != Medium.AIR && (p.depthMin() > 0 || p.depthMax() > 0)) {
                double depth = Steer.surfaceY(field, cell, t, 32) - t.y();
                if (depth < p.depthMin() || (p.depthMax() > 0 && depth > p.depthMax())) {
                    continue;
                }
            }
            return t;
        }
        Vec3 ahead = a.pos.add(a.heading.mul(3));
        return Steer.open(field, cell, ahead) ? ahead : a.home;
    }

    private record Flock(Vec3 dir, double speed) {}

    /** 무리 일원: 분리·정렬·응집 + 대장 추종. 대장이 쉬면 같이 느려지고, 멀어지면 따라잡는다. */
    private Flock flock(Agent a, double base) {
        UUID sid = a.school;
        List<Agent> members = sid == null ? List.of() : schools.getOrDefault(sid, List.of());
        Agent leader = members.isEmpty() ? null : members.getFirst();
        if (leader == null || leader == a) {
            return new Flock(a.heading, base);
        }
        double r2 = bal.flockRadius() * bal.flockRadius();
        List<Vec3> positions = new ArrayList<>();
        List<Vec3> velocities = new ArrayList<>();
        for (Agent m : members) {
            if (m != a && !m.hidden && Steer.distanceSq(m.pos, a.pos) <= r2) {
                positions.add(m.pos);
                velocities.add(m.velocity);
            }
        }
        Map<String, Double> w = bal.flockWeights();
        Vec3 sep =
                Steer.separation(
                        a.pos,
                        positions,
                        Math.max(0.4, a.profile.length() * bal.separationLengths()));
        Vec3 ali = Steer.alignment(velocities);
        Vec3 coh = Steer.cohesion(a.pos, positions);
        Vec3 lead = Steer.seek(a.pos, leader.pos, 1);
        Vec3 sum =
                sep.mul(w.getOrDefault("separation", 1.6))
                        .add(ali.mul(w.getOrDefault("alignment", 0.8)))
                        .add(coh.mul(w.getOrDefault("cohesion", 0.6)))
                        .add(lead.mul(w.getOrDefault("leader", 1.0)));
        double dist = Math.sqrt(Steer.distanceSq(a.pos, leader.pos));
        double speed = leader.state == WildState.IDLE ? base * bal.idleSpeed() : base;
        if (dist > bal.flockRadius() * 1.5) {
            speed = base * 1.5;
        }
        if (leader.state == WildState.FLEE) {
            speed = base * (1 + bal.fleeSpeed());
        }
        return new Flock(Steer.normalize(sum), speed);
    }

    // ---------------------------------------------------------------- 이동

    private boolean inBand(Agent a, double y) {
        if (a.profile.medium() != Medium.AIR) {
            return true;
        }
        return y >= a.airBaseY - bal.airBandBelow() && y <= a.airBaseY + bal.airBandAbove();
    }

    private void move(Agent a, MediumField field, Vec3 desired, double targetSpeed, double dt) {
        SpeciesProfile p = a.profile;
        a.animation = a.state == WildState.IDLE ? p.idleAnimation() : p.swimAnimation();
        if (p.medium() == Medium.FLOOR) {
            // 바닥 개체: 이동·조향 없음. 닫혀 있는 동안(FLEE)은 그 자세로 정지
            a.velocity = Vec3.ZERO;
            a.animationSpeed = a.state == WildState.FLEE ? 0 : 1;
            a.animation = p.idleAnimation();
            return;
        }
        Medium cell = p.medium().cell();
        Vec3 dir = Steer.normalize(desired);
        if (dir.equals(Vec3.ZERO)) {
            dir = a.heading;
        }
        double ahead = Math.max(1.0, a.speed * bal.probeAheadSeconds());
        Vec3 push = Steer.avoid(field, cell, a.pos, dir, ahead, 1.0);
        dir = Steer.normalize(dir.add(push.mul(1.5)));
        if (dir.equals(Vec3.ZERO)) {
            dir = a.heading.mul(-1);
        }
        double vy = dir.y();
        if (cell == Medium.AIR) {
            vy +=
                    Steer.band(
                            a.pos.y(),
                            a.airBaseY - bal.airBandBelow(),
                            a.airBaseY + bal.airBandAbove());
            double bob =
                    bal.airBobSpeed()
                            * Math.sin(TAU * time / Math.max(0.5, bal.airBobPeriod()) + a.bobPhase);
            vy += bob / Math.max(0.3, targetSpeed);
        } else {
            if (Steer.surfaceKeep(field, cell, a.pos, bal.surfaceGap()) < 0) {
                vy = Math.min(vy, -0.4);
            }
            if (p.depthMin() > 0 || p.depthMax() > 0) {
                if (Double.isNaN(a.surfaceY) || time - a.surfaceAt > 1.0) {
                    a.surfaceY = Steer.surfaceY(field, cell, a.pos, 32);
                    a.surfaceAt = time;
                }
                double depth = a.surfaceY - a.pos.y();
                if (depth < p.depthMin()) {
                    vy -= 0.5;
                } else if (p.depthMax() > 0 && depth > p.depthMax()) {
                    vy += 0.5;
                }
            }
        }
        dir = Steer.normalize(new Vec3(dir.x(), vy, dir.z()));
        a.steer = dir;
        a.heading = Steer.rotateToward(a.heading, dir, Math.toRadians(p.turnRateDeg()) * dt);
        double accel = Math.max(0.5, p.baseSpeed() * 3) * dt;
        a.speed += Math.clamp(targetSpeed - a.speed, -accel, accel);
        a.velocity = a.heading.mul(a.speed).add(a.impulse);
        a.impulse = a.impulse.mul(Math.pow(0.2, dt));
        Steer.Move m = Steer.move(field, cell, a.pos, a.velocity, dt);
        a.pos = m.pos();
        if (m.blocked()) {
            a.impulse = Vec3.ZERO;
            a.retargetAt = time;
        }
        a.yaw = Steer.yawDeg(a.heading, a.yaw);
        a.pitch = Steer.pitchDeg(a.velocity, p.pitchLimitDeg());
        a.animationSpeed =
                a.state == WildState.IDLE
                        ? 1
                        : Math.clamp(a.speed / Math.max(0.1, p.baseSpeed()), 0.5, 2.5);
    }

    // ---------------------------------------------------------------- 도우미

    private Vec3 randomHorizontal() {
        double ang = random.nextDouble() * TAU;
        return new Vec3(Math.cos(ang), 0, Math.sin(ang));
    }

    private Vec3 randomUnit() {
        double z = random.nextDouble() * 2 - 1;
        double ang = random.nextDouble() * TAU;
        double r = Math.sqrt(1 - z * z);
        return new Vec3(r * Math.cos(ang), z, r * Math.sin(ang));
    }

    /** 디버그·통계: 상태별 개체 수. */
    public Map<WildState, Integer> stateCounts() {
        Map<WildState, Integer> m = new HashMap<>();
        for (Agent a : agents.values()) {
            m.merge(a.state, 1, Integer::sum);
        }
        return m;
    }

    /** 테스트용: 개체 위치. */
    @Nullable Vec3 position(UUID id) {
        Agent a = agents.get(id);
        return a == null ? null : a.pos;
    }

    @Nullable WildState state(UUID id) {
        Agent a = agents.get(id);
        return a == null ? null : a.state;
    }

    /** 테스트용: 남은 개체 ID. */
    Iterator<UUID> ids() {
        return agents.keySet().iterator();
    }
}
