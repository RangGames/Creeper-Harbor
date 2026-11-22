package wiki.creeper.harbor.wildlife.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.wildlife.api.Medium;

class SteerTest {

    private static final double EPS = 1e-6;

    @Test
    void rotateTowardIsLimitedByMaxAngle() {
        Vec3 east = new Vec3(1, 0, 0);
        Vec3 north = new Vec3(0, 0, -1);
        Vec3 r = Steer.rotateToward(east, north, Math.toRadians(30));
        assertEquals(30, Math.toDegrees(Steer.angle(east, r)), 1e-4);
        assertEquals(1, Steer.length(r), EPS);
        // within the limit, arrives as-is
        assertEquals(north, Steer.rotateToward(east, north, Math.toRadians(100)));
    }

    @Test
    void rotateTowardOppositeTurnsHorizontally() {
        Vec3 r = Steer.rotateToward(new Vec3(1, 0, 0), new Vec3(-1, 0, 0), Math.toRadians(45));
        assertEquals(0, r.y(), EPS);
        assertEquals(45, Math.toDegrees(Steer.angle(new Vec3(1, 0, 0), r)), 1e-4);
    }

    @Test
    void yawFollowsMinecraftConvention() {
        assertEquals(0, Steer.yawDeg(new Vec3(0, 0, 1), 7), 1e-4); // south +Z
        assertEquals(90, Steer.yawDeg(new Vec3(-1, 0, 0), 7), 1e-4); // west −X
        assertEquals(-90, Steer.yawDeg(new Vec3(1, 0, 0), 7), 1e-4);
        assertEquals(
                7,
                Steer.yawDeg(new Vec3(0, 1, 0), 7),
                1e-4); // no horizontal component → previous value
    }

    @Test
    void pitchIsClampedAndUpIsNegative() {
        assertEquals(-45, Steer.pitchDeg(new Vec3(0, 5, 0.1), 45), 1e-4);
        assertEquals(20, Steer.pitchDeg(new Vec3(0, -5, 0.1), 20), 1e-4);
        assertEquals(
                -Math.toDegrees(Math.atan2(1, 2)), Steer.pitchDeg(new Vec3(2, 1, 0), 45), 1e-3);
        assertEquals(0, Steer.pitchDeg(Vec3.ZERO, 45), EPS);
    }

    @Test
    void seekFleeArrive() {
        Vec3 pos = Vec3.ZERO;
        Vec3 target = new Vec3(10, 0, 0);
        assertEquals(new Vec3(2, 0, 0), Steer.seek(pos, target, 2));
        assertEquals(new Vec3(-2, 0, 0), Steer.flee(pos, target, 2));
        Vec3 slow = Steer.arrive(new Vec3(9, 0, 0), target, 2, 4);
        assertEquals(0.5, Steer.length(slow), EPS); // 1 block left / slow radius 4 × speed 2
        assertEquals(Vec3.ZERO, Steer.arrive(target, target, 2, 4));
    }

    @Test
    void flockingVectors() {
        Vec3 pos = Vec3.ZERO;
        List<Vec3> neighbors = List.of(new Vec3(1, 0, 0), new Vec3(0, 0, 3));
        Vec3 sep = Steer.separation(pos, neighbors, 2);
        assertTrue(sep.x() < 0, "가까운 이웃 반대쪽");
        assertEquals(0, sep.z(), EPS, "minDist 밖 이웃은 무시");
        Vec3 coh = Steer.cohesion(pos, neighbors);
        assertTrue(coh.x() > 0 && coh.z() > 0);
        Vec3 ali = Steer.alignment(List.of(new Vec3(0, 0, 2), new Vec3(0, 0, 5)));
        assertEquals(new Vec3(0, 0, 1), ali);
        assertEquals(Vec3.ZERO, Steer.cohesion(pos, List.of()));
    }

    @Test
    void wanderStaysUnitAndNearHeading() {
        RandomGenerator r = RandomGenerator.of("L64X128MixRandom");
        Vec3 h = new Vec3(0, 0, 1);
        for (int i = 0; i < 100; i++) {
            Vec3 w = Steer.wander(h, Math.toRadians(20), Math.toRadians(10), r);
            assertEquals(1, Steer.length(w), 1e-6);
            assertTrue(Math.toDegrees(Steer.angle(h, w)) <= 23);
        }
    }

    @Test
    void avoidTurnsAwayFromWall() {
        // x ≥ 3 is a wall
        MediumField field = Fixtures.box(MediumField.WATER, -50, 2, 0, 20, -50, 50);
        Vec3 push =
                Steer.avoid(field, Medium.WATER, new Vec3(1.5, 10, 0.5), new Vec3(1, 0, 0), 3, 1);
        assertTrue(Steer.length(push) > 0);
        Vec3 next = Steer.normalize(new Vec3(1, 0, 0).add(push.mul(1.5)));
        assertTrue(next.x() < 0.9, "앞이 막히면 크게 꺾는다");
        // no push in open water
        assertEquals(
                Vec3.ZERO,
                Steer.avoid(field, Medium.WATER, new Vec3(-20, 10, 0), new Vec3(1, 0, 0), 3, 1));
    }

    @Test
    void moveNeverLeavesMedium() {
        MediumField field = Fixtures.box(MediumField.LAVA, 0, 4, 0, 4, 0, 4);
        Vec3 pos = new Vec3(4.5, 2.5, 2.5);
        Steer.Move m = Steer.move(field, Medium.LAVA, pos, new Vec3(5, 0, 1), 1);
        assertTrue(m.blocked());
        assertTrue(field.is(Medium.LAVA, m.pos().x(), m.pos().y(), m.pos().z()));
        assertEquals(4.5, m.pos().x(), EPS, "x는 막히고 z로만 미끄러진다");
        Steer.Move free =
                Steer.move(field, Medium.LAVA, new Vec3(1.5, 1.5, 1.5), new Vec3(1, 0, 0), 1);
        assertFalse(free.blocked());
        assertEquals(new Vec3(2.5, 1.5, 1.5), free.pos());
    }

    @Test
    void surfaceKeepAndBand() {
        MediumField field = Fixtures.slab(MediumField.WATER, 50, 62);
        assertTrue(Steer.surfaceKeep(field, Medium.WATER, new Vec3(0, 62.7, 0), 0.5) < 0);
        assertEquals(0, Steer.surfaceKeep(field, Medium.WATER, new Vec3(0, 60, 0), 0.5));
        assertEquals(0, Steer.surfaceKeep(field, Medium.AIR, new Vec3(0, 62.7, 0), 0.5));
        assertEquals(63, Steer.surfaceY(field, Medium.WATER, new Vec3(0, 55, 0), 32), EPS);
        assertTrue(Steer.band(40, 54, 84) > 0);
        assertTrue(Steer.band(90, 54, 84) < 0);
        assertEquals(0, Steer.band(60, 54, 84));
    }
}
