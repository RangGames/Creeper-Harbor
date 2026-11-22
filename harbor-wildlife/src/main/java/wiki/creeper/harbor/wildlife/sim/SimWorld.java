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
 * All virtual fish and one simulation step ('17-3' 9.4). Uses no server API (media via {@link
 * MediumField}, players via {@link PlayerSnap}). Called only by the one dedicated simulation thread
 * (the engine queues command methods so they also run on that thread).
 *
 * <p>States: CRUISE (target within home radius), IDLE (rest), ACTION (special action), FLEE
 * (flight), APPROACH (bobber orbit), BITE (sunk after bite), SCHOOL (follow the leader). Each fish
 * has its own dice interval (1 second ± 0.3 seconds), first dice time and animation phase, so even
 * fish in the same school keep different beats.
 */
public final class SimWorld {

    /** Medium lookup per world. */
    @FunctionalInterface
    public interface Fields {
        MediumField field(String world);
    }

    static final double TAU = Math.PI * 2;

    // Package-private state is shared with SimBehaviour and SimMotion (same simulation thread)
    final Fields fields;
    final RandomGenerator random;
    WildlifeBalance bal;
    private final Map<UUID, Agent> agents = new LinkedHashMap<>();
    final Map<UUID, List<Agent>> schools = new HashMap<>();
    private final Map<UUID, Vec3> lastPlayer = new HashMap<>();
    final Map<UUID, Vec3> playerVelocity = new HashMap<>();
    final List<SimEvent> events = new ArrayList<>();
    private final List<UUID> pendingRemoval = new ArrayList<>();
    private final Map<UUID, SimEvent.Reason> removalReason = new HashMap<>();
    double time;
    private long seq;
    final SimBehaviour behaviour = new SimBehaviour(this);
    final SimMotion motion = new SimMotion(this);

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

    // ================================================================== commands

    /** Creates a fish (school). The first ID is the leader. Not created if outside the medium. */
    public void spawn(SpawnOrder o) {
        MediumField field = fields.field(o.world());
        SpeciesProfile p = o.profile();
        Medium cell = p.medium().cell();
        if (!field.is(cell, o.center().x(), o.center().y(), o.center().z())) {
            return;
        }
        ScriptedBehaviour script = o.scripted();
        List<Agent> members = new ArrayList<>();
        Vec3 heading = motion.randomHorizontal();
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
                                    : Steer.normalize(
                                            heading.add(motion.randomHorizontal().mul(0.3))),
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
                behaviour.startAction(members.getFirst(), act);
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

    /**
     * Removes fish within the radius (radius < 0 for the whole world, world null for every world).
     */
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
     * Approaches the bobber ('17-3' 9.4 feeding/bait reaction stages 1 and 2).
     *
     * @param seconds time left until the bite (if claimed, reaches under the bobber within this
     *     time)
     * @param claimed whether this fish stands in for the Fishing shadow
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

    /** Bite: plays bite, sinks under the bobber and hides (the Fishing shadow takes over). */
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
            behaviour.oneShot(a, anim);
        }
    }

    /** Beaten by another fish: short dart, then CRUISE ('17-3' 9.4 stage 3). */
    public void stolen(UUID id) {
        Agent a = agents.get(id);
        if (a == null || a.claimed) {
            return;
        }
        behaviour.clearApproach(a);
        a.state = a.cruiseState();
        ActionDef dart = a.profile.actions().get("dart");
        if (dart != null) {
            behaviour.startAction(a, dart);
        }
        a.retargetAt = time;
    }

    /**
     * Releases after the session ends ('17-3' 9.4 stage 4).
     *
     * @param caught removes the fish if its species was actually caught
     * @param threatFrom threat location if the line snapped (nearby fish are startled)
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
        behaviour.clearApproach(a);
        if (threatFrom != null) {
            behaviour.startle(a, threatFrom, null);
        } else {
            a.state = a.cruiseState();
            a.retargetAt = time;
        }
    }

    /** Releases every fish that was approaching this angler. */
    public void releaseAngler(UUID angler, boolean lineBroke) {
        for (Agent a : agents.values()) {
            if (angler.equals(a.angler)) {
                Vec3 hook = a.hook;
                release(a.id, false, lineBroke ? hook : null);
            }
        }
    }

    /** Triggers a threat reaction around a location (line snap). */
    public void threatAt(String world, Vec3 at, double radius) {
        double r2 = radius * radius;
        for (Agent a : agents.values()) {
            if (a.world.equals(world)
                    && !a.hidden
                    && !a.claimed
                    && Steer.distanceSq(a.pos, at) <= r2) {
                behaviour.startle(a, at, null);
            }
        }
    }

    /** Crossed the region boundary: turns it toward home. */
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

    /** Updates values for species whose rig changed (content reload). */
    public void replaceProfile(HarborId species, SpeciesProfile profile) {
        for (Agent a : agents.values()) {
            if (a.profile.species().equals(species)) {
                a.profile = profile;
                a.animation = profile.swimAnimation();
            }
        }
    }

    /** Plays an action immediately (admin command, cinematic). */
    public void play(UUID id, String action) {
        Agent a = agents.get(id);
        if (a == null) {
            return;
        }
        ActionDef def = a.profile.actions().get(action);
        if (def != null) {
            behaviour.startAction(a, def);
        }
    }

    // ================================================================== step

    /** Simulates one step and builds the output frame. */
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
            behaviour.update(a, use, ps);
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

    /** Debug/stats: fish count per state. */
    public Map<WildState, Integer> stateCounts() {
        Map<WildState, Integer> m = new HashMap<>();
        for (Agent a : agents.values()) {
            m.merge(a.state, 1, Integer::sum);
        }
        return m;
    }

    /** For tests: fish position. */
    @Nullable Vec3 position(UUID id) {
        Agent a = agents.get(id);
        return a == null ? null : a.pos;
    }

    @Nullable WildState state(UUID id) {
        Agent a = agents.get(id);
        return a == null ? null : a.state;
    }

    /** For tests: remaining fish IDs. */
    Iterator<UUID> ids() {
        return agents.keySet().iterator();
    }
}
