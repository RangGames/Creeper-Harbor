package wiki.creeper.harbor.wildlife.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.wildlife.api.Medium;
import wiki.creeper.harbor.wildlife.api.WildState;

/**
 * Cruise targets, schooling and movement of one simulation step, split out of {@link SimWorld}.
 * Holds no state of its own and runs only on the simulation thread via {@link SimWorld}.
 */
final class SimMotion {

    private final SimWorld w;

    SimMotion(SimWorld w) {
        this.w = w;
    }

    Vec3 cruiseDir(Agent a, MediumField field) {
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
        if (w.time >= a.retargetAt || Steer.distanceSq(a.pos, a.target) < 2.25) {
            a.target = pickTarget(a, field);
            a.retargetAt =
                    w.time
                            + w.bal.retargetMin()
                            + w.random.nextDouble()
                                    * Math.max(0, w.bal.retargetMax() - w.bal.retargetMin());
        }
        return Steer.arrive(a.pos, a.target, 1, 2.0);
    }

    /**
     * Target medium cell within the home radius (within the depth range / height band). If none is
     * found in 8 tries, 3 blocks ahead.
     */
    Vec3 pickTarget(Agent a, MediumField field) {
        SpeciesProfile p = a.profile;
        Medium cell = p.medium().cell();
        double r = w.bal.homeRadius();
        for (int i = 0; i < 8; i++) {
            double ang = w.random.nextDouble() * SimWorld.TAU;
            double dist = Math.sqrt(w.random.nextDouble()) * r;
            double dy = (w.random.nextDouble() * 2 - 1) * (cell == Medium.AIR ? 6 : 3);
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

    record Flock(Vec3 dir, double speed) {}

    /**
     * School member: separation, alignment, cohesion + leader following. Slows down when the leader
     * rests and catches up when it falls behind.
     */
    Flock flock(Agent a, double base) {
        UUID sid = a.school;
        List<Agent> members = sid == null ? List.of() : w.schools.getOrDefault(sid, List.of());
        Agent leader = members.isEmpty() ? null : members.getFirst();
        if (leader == null || leader == a) {
            return new Flock(a.heading, base);
        }
        double r2 = w.bal.flockRadius() * w.bal.flockRadius();
        List<Vec3> positions = new ArrayList<>();
        List<Vec3> velocities = new ArrayList<>();
        for (Agent m : members) {
            if (m != a && !m.hidden && Steer.distanceSq(m.pos, a.pos) <= r2) {
                positions.add(m.pos);
                velocities.add(m.velocity);
            }
        }
        Map<String, Double> weights = w.bal.flockWeights();
        Vec3 sep =
                Steer.separation(
                        a.pos,
                        positions,
                        Math.max(0.4, a.profile.length() * w.bal.separationLengths()));
        Vec3 ali = Steer.alignment(velocities);
        Vec3 coh = Steer.cohesion(a.pos, positions);
        Vec3 lead = Steer.seek(a.pos, leader.pos, 1);
        Vec3 sum =
                sep.mul(weights.getOrDefault("separation", 1.6))
                        .add(ali.mul(weights.getOrDefault("alignment", 0.8)))
                        .add(coh.mul(weights.getOrDefault("cohesion", 0.6)))
                        .add(lead.mul(weights.getOrDefault("leader", 1.0)));
        double dist = Math.sqrt(Steer.distanceSq(a.pos, leader.pos));
        double speed = leader.state == WildState.IDLE ? base * w.bal.idleSpeed() : base;
        if (dist > w.bal.flockRadius() * 1.5) {
            speed = base * 1.5;
        }
        if (leader.state == WildState.FLEE) {
            speed = base * (1 + w.bal.fleeSpeed());
        }
        return new Flock(Steer.normalize(sum), speed);
    }

    // ---------------------------------------------------------------- movement

    boolean inBand(Agent a, double y) {
        if (a.profile.medium() != Medium.AIR) {
            return true;
        }
        return y >= a.airBaseY - w.bal.airBandBelow() && y <= a.airBaseY + w.bal.airBandAbove();
    }

    void move(Agent a, MediumField field, Vec3 desired, double targetSpeed, double dt) {
        SpeciesProfile p = a.profile;
        a.animation = a.state == WildState.IDLE ? p.idleAnimation() : p.swimAnimation();
        if (p.medium() == Medium.FLOOR) {
            // floor fish: no movement or steering. While closed (FLEE) they hold that pose
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
        double ahead = Math.max(1.0, a.speed * w.bal.probeAheadSeconds());
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
                            a.airBaseY - w.bal.airBandBelow(),
                            a.airBaseY + w.bal.airBandAbove());
            double bob =
                    w.bal.airBobSpeed()
                            * Math.sin(
                                    SimWorld.TAU * w.time / Math.max(0.5, w.bal.airBobPeriod())
                                            + a.bobPhase);
            vy += bob / Math.max(0.3, targetSpeed);
        } else {
            if (Steer.surfaceKeep(field, cell, a.pos, w.bal.surfaceGap()) < 0) {
                vy = Math.min(vy, -0.4);
            }
            if (p.depthMin() > 0 || p.depthMax() > 0) {
                if (Double.isNaN(a.surfaceY) || w.time - a.surfaceAt > 1.0) {
                    a.surfaceY = Steer.surfaceY(field, cell, a.pos, 32);
                    a.surfaceAt = w.time;
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
            a.retargetAt = w.time;
        }
        a.yaw = Steer.yawDeg(a.heading, a.yaw);
        a.pitch = Steer.pitchDeg(a.velocity, p.pitchLimitDeg());
        a.animationSpeed =
                a.state == WildState.IDLE
                        ? 1
                        : Math.clamp(a.speed / Math.max(0.1, p.baseSpeed()), 0.5, 2.5);
    }

    // ---------------------------------------------------------------- helpers

    Vec3 randomHorizontal() {
        double ang = w.random.nextDouble() * SimWorld.TAU;
        return new Vec3(Math.cos(ang), 0, Math.sin(ang));
    }

    Vec3 randomUnit() {
        double z = w.random.nextDouble() * 2 - 1;
        double ang = w.random.nextDouble() * SimWorld.TAU;
        double r = Math.sqrt(1 - z * z);
        return new Vec3(r * Math.cos(ang), z, r * Math.sin(ang));
    }
}
