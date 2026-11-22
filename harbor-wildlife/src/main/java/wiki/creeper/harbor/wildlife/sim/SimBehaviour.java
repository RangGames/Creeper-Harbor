package wiki.creeper.harbor.wildlife.sim;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.wildlife.api.Medium;
import wiki.creeper.harbor.wildlife.api.WildState;
import wiki.creeper.harbor.wildlife.content.BehaviourBook.ActionDef;

/**
 * Per-fish behaviour of one simulation step (state machine, threat detection, dice, actions), split
 * out of {@link SimWorld}. Holds no state of its own and runs only on the simulation thread via
 * {@link SimWorld}.
 */
final class SimBehaviour {

    private final SimWorld w;

    SimBehaviour(SimWorld w) {
        this.w = w;
    }

    void update(Agent a, double dt, List<PlayerSnap> players) {
        if (a.hidden) {
            return;
        }
        SpeciesProfile p = a.profile;
        MediumField field = w.fields.field(a.world);

        // school spread (a neighbour was startled last step)
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
                if (w.time >= a.stateUntil) {
                    a.state = a.resume;
                }
                desired = a.heading;
                speed = base * w.bal.idleSpeed();
            }
            case ACTION -> {
                ActionDef act = a.action;
                if (act == null || w.time >= a.repEnds) {
                    a.repsLeft--;
                    if (act != null && a.repsLeft > 0) {
                        beginRep(a, act, field);
                    } else {
                        a.action = null;
                        a.state = a.resume;
                    }
                }
                ActionDef now = a.action;
                desired = a.member() ? w.motion.flock(a, base).dir() : w.motion.cruiseDir(a, field);
                speed = base * (1 + (now == null ? 0 : now.speedDelta()));
            }
            case FLEE -> {
                if (w.time >= a.stateUntil) {
                    a.state = a.cruiseState();
                    if (!a.member()) {
                        a.home = a.pos;
                    }
                    a.retargetAt = w.time;
                }
                Vec3 away = Steer.flee(a.pos, a.threat, 1);
                desired = new Vec3(away.x(), away.y() * 0.3, away.z());
                speed = base * (1 + w.bal.fleeSpeed());
            }
            case APPROACH -> {
                Vec3 hook = a.hook;
                if (hook == null || w.time > a.deadline + 60) {
                    clearApproach(a);
                    a.state = a.cruiseState();
                    desired = a.heading;
                    speed = base;
                } else {
                    a.orbitAngle += Math.toRadians(w.bal.orbitSpeed()) * dt;
                    double r = w.bal.orbitRadius();
                    Vec3 orbit =
                            hook.add(
                                    new Vec3(
                                            Math.cos(a.orbitAngle) * r,
                                            -1.0,
                                            Math.sin(a.orbitAngle) * r));
                    double dist = Math.sqrt(Steer.distanceSq(a.pos, orbit));
                    double remain = a.deadline - w.time;
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
                if (w.time >= a.biteEnds) {
                    if (a.claimed) {
                        a.hidden = true; // the Fishing shadow takes over from the same spot
                        a.pos = under;
                        a.velocity = Vec3.ZERO;
                        return;
                    }
                    clearApproach(a);
                    a.state = a.cruiseState();
                }
            }
            case SCHOOL -> {
                SimMotion.Flock f = w.motion.flock(a, base);
                desired = f.dir();
                speed = f.speed();
            }
            default -> {
                desired = w.motion.cruiseDir(a, field);
                speed = base * (a.hold ? 0.5 : 1);
            }
        }
        w.motion.move(a, field, desired, speed, dt);
    }

    // ---------------------------------------------------------------- threat

    void detectThreat(Agent a, List<PlayerSnap> players) {
        if (a.ignoreThreat || a.state == WildState.BITE || a.claimed || a.state == WildState.FLEE) {
            return;
        }
        SpeciesProfile p = a.profile;
        for (PlayerSnap ps : players) {
            if (!ps.threat()) {
                continue;
            }
            double d = Math.sqrt(Steer.distanceSq(ps.pos(), a.pos));
            if (d > p.threatRadius() * Math.max(1, w.bal.boatFactor())) {
                continue;
            }
            Double last = a.startled.get(ps.id());
            boolean habituated = last != null && w.time - last < w.bal.habituateSeconds();
            double r =
                    ThreatRules.radius(
                            p.threatRadius(),
                            ps.sneaking(),
                            ps.boat(),
                            habituated,
                            w.bal.sneakFactor(),
                            w.bal.boatFactor(),
                            w.bal.habituateFactor());
            Vec3 v = w.playerVelocity.getOrDefault(ps.id(), Vec3.ZERO);
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
            if (ThreatRules.triggered(d, closing, r, w.bal.closingSpeed(), w.bal.innerRatio())) {
                a.startled.put(ps.id(), w.time);
                startle(a, ps.pos(), ps.id());
                return;
            }
        }
    }

    /**
     * After the startle action, flees away from the threat for 3~5 seconds. School members within 4
     * blocks flee along on the next step.
     */
    void startle(Agent a, Vec3 from, @Nullable UUID player) {
        SpeciesProfile p = a.profile;
        boolean wasApproach = a.state == WildState.APPROACH;
        if (wasApproach) {
            clearApproach(a);
        }
        a.threat = from;
        a.state = WildState.FLEE;
        a.action = null;
        a.stateUntil =
                w.time
                        + w.bal.fleeMinSeconds()
                        + w.random.nextDouble()
                                * Math.max(0, w.bal.fleeMaxSeconds() - w.bal.fleeMinSeconds());
        ActionDef reaction = p.threatAction();
        if (p.medium() == Medium.FLOOR) {
            // shellfish: play the close (burrow) action and stay closed
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
            w.events.add(new SimEvent.Startle(a.id, player));
        }
        UUID sid = a.school;
        if (sid != null) {
            double s2 = w.bal.spreadRadius() * w.bal.spreadRadius();
            for (Agent m : w.schools.getOrDefault(sid, List.of())) {
                if (m != a
                        && m.state != WildState.FLEE
                        && !m.claimed
                        && Steer.distanceSq(m.pos, a.pos) <= s2) {
                    m.pendingThreat = from;
                }
            }
        }
    }

    // ---------------------------------------------------------------- dice

    void rollDice(Agent a) {
        if (w.time < a.nextDice) {
            return;
        }
        a.nextDice =
                w.time
                        + w.bal.diceInterval()
                        + (w.random.nextDouble() * 2 - 1) * w.bal.diceJitter();
        if (a.state != WildState.CRUISE && a.state != WildState.SCHOOL) {
            return;
        }
        SpeciesProfile p = a.profile;
        double r = w.random.nextDouble();
        ActionDef special = p.special();
        if (r < p.groupDef().specialChance()) {
            if (special != null) {
                startAction(a, special);
            }
        } else if (r < p.groupDef().specialChance() + p.groupDef().idleChance()) {
            a.resume = a.cruiseState();
            a.state = WildState.IDLE;
            a.stateUntil = w.time + p.groupDef().idleSeconds();
        }
    }

    void startAction(Agent a, ActionDef act) {
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
        beginRep(a, act, w.fields.field(a.world));
    }

    void beginRep(Agent a, ActionDef act, MediumField field) {
        SpeciesProfile p = a.profile;
        a.repEnds = w.time + p.animationSeconds(act.name(), 1.0);
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

    /**
     * Teleport (air teleport 4~6 blocks + portal particles). Stays in place if no reachable cell is
     * found.
     */
    void teleport(Agent a, ActionDef act, MediumField field) {
        Medium cell = a.profile.medium().cell();
        for (int i = 0; i < 8; i++) {
            Vec3 dir =
                    cell == Medium.AIR
                            ? w.motion.randomUnit()
                            : Steer.normalize(
                                    w.motion
                                            .randomHorizontal()
                                            .add(
                                                    new Vec3(
                                                            0,
                                                            (w.random.nextDouble() - 0.5) * 0.4,
                                                            0)));
            double d =
                    act.teleportMin()
                            + w.random.nextDouble() * (act.teleportMax() - act.teleportMin());
            Vec3 to = a.pos.add(dir.mul(d));
            if (Steer.open(field, cell, to) && w.motion.inBand(a, to.y())) {
                particle(a, act.particle());
                a.pos = to;
                a.teleportSeq++;
                return;
            }
        }
    }

    void oneShot(Agent a, String animation) {
        a.oneShot = animation;
        a.oneShotSeq++;
    }

    void particle(Agent a, @Nullable String key) {
        if (key != null) {
            w.events.add(
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

    void clearApproach(Agent a) {
        a.hook = null;
        a.claimed = false;
        a.angler = null;
        a.hidden = false;
    }
}
