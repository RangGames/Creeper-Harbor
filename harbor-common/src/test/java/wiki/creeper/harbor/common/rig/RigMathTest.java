package wiki.creeper.harbor.common.rig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RigMathTest {

    private static final double EPS = 1e-9;

    static void assertVec(Vec3 expected, Vec3 actual, double eps) {
        assertTrue(
                expected.distance(actual) <= eps,
                () -> "expected " + expected + " but was " + actual);
    }

    @Test
    void quaternionRotatesLikeRightHandRule() {
        // Y축 +90°: +X → −Z
        assertVec(new Vec3(0, 0, -1), Quat.rotY(90).rotate(new Vec3(1, 0, 0)), 1e-12);
        // X축 +90°: +Y → +Z
        assertVec(new Vec3(0, 0, 1), Quat.rotX(90).rotate(new Vec3(0, 1, 0)), 1e-12);
        // Z축 +90°: +X → +Y
        assertVec(new Vec3(0, 1, 0), Quat.rotZ(90).rotate(new Vec3(1, 0, 0)), 1e-12);
    }

    @Test
    void eulerAppliesXThenYThenZ() {
        Vec3 v = new Vec3(0, 1, 0);
        Vec3 stepwise = Quat.rotZ(30).rotate(Quat.rotY(20).rotate(Quat.rotX(10).rotate(v)));
        assertVec(stepwise, Quat.euler(new Vec3(10, 20, 30)).rotate(v), 1e-12);
    }

    @Test
    void entityRotationMatchesMinecraftYawConvention() {
        Vec3 forward = new Vec3(0, 0, 1);
        // yaw 0 = 남쪽(+Z), 90 = 서쪽(−X), −90 = 동쪽(+X)
        assertVec(new Vec3(0, 0, 1), Quat.entity(0, 0).rotate(forward), 1e-12);
        assertVec(new Vec3(-1, 0, 0), Quat.entity(90, 0).rotate(forward), 1e-12);
        assertVec(new Vec3(1, 0, 0), Quat.entity(-90, 0).rotate(forward), 1e-12);
        // pitch 양수 = 아래
        Vec3 down = Quat.entity(0, 30).rotate(forward);
        assertTrue(down.y() < 0);
    }

    @Test
    void itemFlipIsHalfTurnAroundY() {
        assertVec(new Vec3(-1, 2, -3), Quat.ITEM_FLIP.rotate(new Vec3(1, 2, 3)), 1e-12);
        assertEquals(180, Quat.ITEM_FLIP.angleTo(Quat.IDENTITY), 1e-9);
    }

    @Test
    void slerpTakesShortestPathAndHitsEndpoints() {
        Quat a = Quat.rotY(10);
        Quat b = Quat.rotY(80);
        assertEquals(0, Quat.slerp(a, b, 0).angleTo(a), 1e-4);
        assertEquals(0, Quat.slerp(a, b, 1).angleTo(b), 1e-4);
        assertEquals(0, Quat.slerp(a, b, 0.5).angleTo(Quat.rotY(45)), 1e-4);
        // −q는 같은 회전: 반대 부호로 줘도 짧은 쪽으로 간다
        Quat negB = new Quat(-b.x(), -b.y(), -b.z(), -b.w());
        assertEquals(0, Quat.slerp(a, negB, 0.5).angleTo(Quat.rotY(45)), 1e-4);
    }

    @Test
    void transformCompositionAppliesParentAfterChild() {
        Xform parent = new Xform(new Vec3(1, 0, 0), Quat.rotY(90), new Vec3(2, 2, 2));
        Xform child = new Xform(new Vec3(0, 0, 1), Quat.rotX(90), Vec3.ONE);
        Vec3 p = new Vec3(0, 1, 0);
        Vec3 viaChain = parent.apply(child.apply(p));
        assertVec(viaChain, parent.then(child).apply(p), 1e-12);
        // 자식 원점은 부모 공간에서 (0,0,1)×2를 Y 90° 돌린 (2,0,0)에 부모 이동 (1,0,0)
        assertVec(new Vec3(3, 0, 0), parent.then(child).translation(), 1e-12);
    }

    @Test
    void trackInterpolatesWithEasing() {
        Track linear =
                new Track(
                        List.of(
                                new Track.Keyframe(0, Vec3.ZERO, Easing.LINEAR),
                                new Track.Keyframe(10, new Vec3(10, 0, 0), Easing.LINEAR)));
        assertVec(new Vec3(5, 0, 0), linear.sample(5, Vec3.ZERO), EPS);
        assertVec(Vec3.ZERO, linear.sample(-3, Vec3.ONE), EPS);
        assertVec(new Vec3(10, 0, 0), linear.sample(99, Vec3.ONE), EPS);
        assertVec(Vec3.ONE, Track.EMPTY.sample(3, Vec3.ONE), EPS);

        Track step =
                new Track(
                        List.of(
                                new Track.Keyframe(0, Vec3.ZERO, Easing.STEP),
                                new Track.Keyframe(10, new Vec3(10, 0, 0), Easing.LINEAR)));
        assertVec(Vec3.ZERO, step.sample(9.9, Vec3.ONE), EPS);
        assertVec(new Vec3(10, 0, 0), step.sample(10, Vec3.ONE), EPS);

        Track eased =
                new Track(
                        List.of(
                                new Track.Keyframe(0, Vec3.ZERO, Easing.EASE_IN_OUT),
                                new Track.Keyframe(10, new Vec3(10, 0, 0), Easing.LINEAR)));
        assertVec(new Vec3(5, 0, 0), eased.sample(5, Vec3.ZERO), 1e-9);
        assertTrue(eased.sample(2, Vec3.ZERO).x() < 2, "ease_in_out은 시작이 느리다");
        assertTrue(eased.sample(8, Vec3.ZERO).x() > 8, "ease_in_out은 끝이 느리다");
    }

    @Test
    void catmullRomPassesThroughKeys() {
        Track spline =
                new Track(
                        List.of(
                                new Track.Keyframe(0, Vec3.ZERO, Easing.CATMULLROM),
                                new Track.Keyframe(10, new Vec3(0, 4, 0), Easing.CATMULLROM),
                                new Track.Keyframe(20, new Vec3(0, 0, 0), Easing.CATMULLROM)));
        assertVec(new Vec3(0, 4, 0), spline.sample(10, Vec3.ZERO), EPS);
        // 꼭짓점 근처가 선형보다 둥글다 (선형이면 t=15에서 2)
        assertTrue(spline.sample(15, Vec3.ZERO).y() > 2);
    }

    @Test
    void compressionDropsLinearFramesButKeepsEndpointsAndMaxGap() {
        int[] ticks = RigBaker.frameTicks(40, 2);
        assertEquals(21, ticks.length);
        assertEquals(40, ticks[20]);
        Xform[] values = new Xform[ticks.length];
        for (int i = 0; i < ticks.length; i++) {
            values[i] = new Xform(new Vec3(ticks[i] * 0.1, 0, 0), Quat.IDENTITY, Vec3.ONE);
        }
        Xform.Tolerance tol = new Xform.Tolerance(0.005, 1.2, 0.005);
        BakedRig.KeyTrack keys = RigBaker.compress(ticks, values, tol, 8);
        // 직선 운동이라 최대 간격(8프레임)마다만 키: 0, 8, 16, 20
        assertEquals(4, keys.size());
        assertEquals(0, keys.tick(0));
        assertEquals(16, keys.tick(1));
        assertEquals(32, keys.tick(2));
        assertEquals(40, keys.tick(3));
        // 보간 결과는 원래 프레임과 같다
        for (int i = 0; i < ticks.length; i++) {
            assertTrue(keys.sample(ticks[i]).near(values[i], tol));
        }
    }

    @Test
    void compressionStaysWithinToleranceForCurves() {
        int[] ticks = RigBaker.frameTicks(20, 2);
        Xform[] values = new Xform[ticks.length];
        for (int i = 0; i < ticks.length; i++) {
            double angle = 25 * Math.sin(2 * Math.PI * ticks[i] / 20.0);
            values[i] = new Xform(Vec3.ZERO, Quat.rotY(angle), Vec3.ONE);
        }
        Xform.Tolerance tol = new Xform.Tolerance(0.005, 1.2, 0.005);
        BakedRig.KeyTrack keys = RigBaker.compress(ticks, values, tol, 8);
        assertTrue(keys.size() < ticks.length, "곡선이어도 일부 프레임은 버려진다");
        for (int i = 0; i < ticks.length; i++) {
            assertTrue(keys.sample(ticks[i]).near(values[i], tol), "프레임 " + i);
        }
    }

    @Test
    void keyTrackSegmentLookup() {
        BakedRig.KeyTrack track =
                new BakedRig.KeyTrack(
                        new int[] {0, 4, 10},
                        new Xform[] {Xform.IDENTITY, Xform.IDENTITY, Xform.IDENTITY});
        assertEquals(0, track.segment(0));
        assertEquals(0, track.segment(3.9));
        assertEquals(1, track.segment(4));
        assertEquals(1, track.segment(9.5));
        assertEquals(2, track.segment(10));
        assertEquals(2, track.segment(50));
    }

    @Test
    void settingsRejectFpsThatDoesNotDivideTwenty() {
        Xform.Tolerance tol = new Xform.Tolerance(0.005, 1.2, 0.005);
        assertThrows(IllegalArgumentException.class, () -> new RigBaker.Settings(3, tol, 8));
        assertEquals(2, new RigBaker.Settings(10, tol, 8).ticksPerFrame());
    }

    @Test
    void headingCorrectionForNegativeZModels() {
        assertEquals(-170f, RigSampler.wrapDegrees(190f), 1e-6);
        assertEquals(179f, RigSampler.wrapDegrees(-181f), 1e-6);
    }
}
