package wiki.creeper.harbor.wildlife.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.wildlife.api.Medium;
import wiki.creeper.harbor.wildlife.api.ScriptedBehaviour;
import wiki.creeper.harbor.wildlife.api.WildState;
import wiki.creeper.harbor.wildlife.content.WildTemplate;

/** 개체 상태 기계·조향 통합 ('17-3' 9.4). 서버 없이 가짜 매질로 돌린다. */
class SimWorldTest {

    private static final double DT = 0.1;
    private static final String WORLD = "harbor_main";

    private static SimWorld world(MediumField field) {
        return new SimWorld(w -> field, Fixtures.BAL, RandomGenerator.of("L64X128MixRandom"));
    }

    private static List<UUID> spawn(
            SimWorld w, SpeciesProfile p, Vec3 at, int count, ScriptedBehaviour script) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(UUID.randomUUID());
        }
        w.spawn(
                new SpawnOrder(
                        ids,
                        p,
                        WORLD,
                        at,
                        count > 1 ? UUID.randomUUID() : null,
                        script,
                        List.of(),
                        null,
                        null,
                        64));
        return ids;
    }

    private static PlayerSnap player(UUID id, Vec3 pos, boolean sneaking) {
        return new PlayerSnap(id, WORLD, pos, sneaking, false, true);
    }

    @Test
    void waterFishStayInWaterAndBelowSurface() {
        MediumField field = Fixtures.box(MediumField.WATER, -30, 30, 50, 62, -30, 30);
        SimWorld w = world(field);
        List<UUID> ids = spawn(w, Fixtures.water("mackerel"), new Vec3(0.5, 56.5, 0.5), 5, null);
        List<PlayerSnap> players = List.of(player(UUID.randomUUID(), new Vec3(0, 70, 0), false));
        for (int i = 0; i < 600; i++) {
            Frame f = w.step(DT, players, System.nanoTime());
            for (FishOut o : f.fish()) {
                assertTrue(field.is(Medium.WATER, o.x(), o.y(), o.z()), "물 밖: " + o);
                assertTrue(Math.abs(o.pitch()) <= 45.0001f);
            }
        }
        assertEquals(5, w.size());
        // 같은 무리라도 위상이 다르다
        Frame f = w.step(DT, players, System.nanoTime());
        Set<Integer> phases = new HashSet<>();
        f.fish().forEach(o -> phases.add(o.phaseOffset()));
        assertTrue(phases.size() > 1);
        assertNotNull(f.get(ids.getFirst()));
    }

    @Test
    void lavaFishNeverLeaveLava() {
        MediumField field = Fixtures.box(MediumField.LAVA, -10, 10, 20, 30, -10, 10);
        SimWorld w = world(field);
        spawn(
                w,
                Fixtures.profile(Fixtures.fish("magma"), Medium.LAVA, WildTemplate.FISH),
                new Vec3(0.5, 25.5, 0.5),
                3,
                null);
        List<PlayerSnap> players = List.of(player(UUID.randomUUID(), new Vec3(0, 35, 0), false));
        for (int i = 0; i < 600; i++) {
            for (FishOut o : w.step(DT, players, System.nanoTime()).fish()) {
                assertTrue(field.is(Medium.LAVA, o.x(), o.y(), o.z()), "용암 밖: " + o);
            }
        }
    }

    @Test
    void airFishStayInBandAndAvoidSolids() {
        // 공기 칸 (섬 기준 64: 띠 54~84), y < 60은 섬(막힘)
        MediumField field = (x, y, z) -> y >= 60 && y <= 120 ? MediumField.AIR : 0;
        SimWorld w = world(field);
        SpeciesProfile flyer =
                Fixtures.profile(Fixtures.fish("void_ray"), Medium.AIR, WildTemplate.RAY);
        spawn(w, flyer, new Vec3(0.5, 70.5, 0.5), 2, null);
        List<PlayerSnap> players = List.of(player(UUID.randomUUID(), new Vec3(0, 70, 0), false));
        for (int i = 0; i < 1200; i++) {
            for (FishOut o : w.step(DT, players, System.nanoTime()).fish()) {
                assertTrue(o.y() >= 60 && o.y() <= 88, "띠 밖: " + o.y());
            }
        }
    }

    @Test
    void fastApproachStartlesButSneakingDoesNot() {
        MediumField field = Fixtures.slab(MediumField.WATER, 40, 62);
        SimWorld w = world(field);
        UUID fish =
                spawn(
                                w,
                                Fixtures.water("goby"),
                                new Vec3(0.5, 55.5, 0.5),
                                1,
                                ScriptedBehaviour.show(null, 0))
                        .getFirst();
        // hold 개체라도 ignoreThreat=true면 놀라지 않는다
        UUID p = UUID.randomUUID();
        w.step(DT, List.of(player(p, new Vec3(9, 56, 0.5), false)), System.nanoTime());
        w.step(DT, List.of(player(p, new Vec3(6, 56, 0.5), false)), System.nanoTime());
        assertNotEquals(WildState.FLEE, w.state(fish));

        SimWorld w2 = world(field);
        UUID f2 =
                spawn(
                                w2,
                                Fixtures.water("goby2"),
                                new Vec3(0.5, 55.5, 0.5),
                                1,
                                new ScriptedBehaviour(null, true, 0, false))
                        .getFirst();
        // 웅크리고 천천히 (초당 1블록) 다가가면 반경 ×0.5 = 5블록, 35% = 1.75블록까지 괜찮다
        double x = 9;
        for (int i = 0; i < 40; i++) {
            x -= 0.1;
            w2.step(DT, List.of(player(p, new Vec3(x, 55.5, 0.5), true)), System.nanoTime());
        }
        assertNotEquals(WildState.FLEE, w2.state(f2));
        // 뛰어서 (초당 5블록) 다가가면 도망
        for (int i = 0; i < 5; i++) {
            x -= 0.5;
            w2.step(DT, List.of(player(p, new Vec3(x, 55.5, 0.5), false)), System.nanoTime());
        }
        assertEquals(WildState.FLEE, w2.state(f2));
    }

    @Test
    void startleEventAndSchoolSpread() {
        MediumField field = Fixtures.slab(MediumField.WATER, 40, 62);
        SimWorld w = world(field);
        List<UUID> school = spawn(w, Fixtures.water("anchovy"), new Vec3(0.5, 55.5, 0.5), 4, null);
        UUID p = UUID.randomUUID();
        boolean startled = false;
        double x = 12;
        for (int i = 0; i < 20 && !startled; i++) {
            x -= 0.6;
            Frame f =
                    w.step(
                            DT,
                            List.of(player(p, new Vec3(x, 55.5, 0.5), false)),
                            System.nanoTime());
            startled =
                    f.events().stream()
                            .anyMatch(e -> e instanceof SimEvent.Startle s && s.player().equals(p));
        }
        assertTrue(startled);
        // 다음 주기에 무리 전파
        w.step(DT, List.of(player(p, new Vec3(x, 55.5, 0.5), false)), System.nanoTime());
        w.step(DT, List.of(player(p, new Vec3(x, 55.5, 0.5), false)), System.nanoTime());
        long fleeing = school.stream().filter(id -> w.state(id) == WildState.FLEE).count();
        assertTrue(fleeing >= 2, "무리 안 4블록 이내 개체도 도망: " + fleeing);
    }

    @Test
    void despawnWhenEveryoneIsFar() {
        MediumField field = Fixtures.slab(MediumField.WATER, 40, 62);
        SimWorld w = world(field);
        UUID fish = spawn(w, Fixtures.water("far"), new Vec3(0.5, 55.5, 0.5), 1, null).getFirst();
        w.step(
                DT,
                List.of(player(UUID.randomUUID(), new Vec3(80, 60, 0), false)),
                System.nanoTime());
        Frame f =
                w.step(
                        DT,
                        List.of(player(UUID.randomUUID(), new Vec3(80, 60, 0), false)),
                        System.nanoTime());
        assertNull(w.position(fish));
        assertTrue(
                f.events().stream()
                                .anyMatch(
                                        e ->
                                                e instanceof SimEvent.Removed r
                                                        && r.reason() == SimEvent.Reason.DESPAWN)
                        || w.size() == 0);
    }

    @Test
    void scriptedLifetimeExpires() {
        MediumField field = Fixtures.slab(MediumField.WATER, 40, 62);
        SimWorld w = world(field);
        spawn(
                w,
                Fixtures.water("show"),
                new Vec3(0.5, 55.5, 0.5),
                1,
                ScriptedBehaviour.show("leap", 1));
        List<PlayerSnap> ps = List.of(player(UUID.randomUUID(), new Vec3(500, 60, 0), false));
        w.step(DT, ps, System.nanoTime());
        assertEquals(1, w.size(), "연출 개체는 거리 정리 대상이 아님");
        for (int i = 0; i < 15; i++) {
            w.step(DT, ps, System.nanoTime());
        }
        assertEquals(0, w.size());
    }

    @Test
    void approachBiteHideAndRelease() {
        MediumField field = Fixtures.slab(MediumField.WATER, 40, 62);
        SimWorld w = world(field);
        UUID fish = spawn(w, Fixtures.water("bait"), new Vec3(6.5, 58.5, 0.5), 1, null).getFirst();
        UUID angler = UUID.randomUUID();
        List<PlayerSnap> ps = List.of(player(angler, new Vec3(0, 66, -10), false));
        Vec3 hook = new Vec3(0.5, 62.9, 0.5);
        w.approach(fish, hook, 3, true, angler);
        for (int i = 0; i < 30; i++) {
            w.step(DT, ps, System.nanoTime());
        }
        assertEquals(WildState.APPROACH, w.state(fish));
        Vec3 near = w.position(fish);
        assertNotNull(near);
        assertTrue(Math.hypot(near.x() - hook.x(), near.z() - hook.z()) < 3.0, "찌 가까이: " + near);
        w.bite(fish);
        Frame f = null;
        for (int i = 0; i < 10; i++) {
            f = w.step(DT, ps, System.nanoTime());
        }
        FishOut out = f.get(fish);
        assertNotNull(out);
        assertTrue(out.hidden(), "입질 후 찌 아래로 잠김");
        w.release(fish, false, null);
        f = w.step(DT, ps, System.nanoTime());
        assertFalse(f.get(fish).hidden());
        // 낚이면 지운다
        w.approach(fish, hook, 1, true, angler);
        w.release(fish, true, null);
        w.step(DT, ps, System.nanoTime());
        assertNull(w.position(fish));
    }

    @Test
    void teleportActionMovesWithinMedium() {
        MediumField field = (x, y, z) -> y >= 60 && y <= 120 ? MediumField.AIR : 0;
        SimWorld w = world(field);
        UUID fish =
                spawn(
                                w,
                                Fixtures.profile(
                                        Fixtures.fish("blinker"), Medium.AIR, WildTemplate.FISH),
                                new Vec3(0.5, 70.5, 0.5),
                                1,
                                null)
                        .getFirst();
        List<PlayerSnap> ps = List.of(player(UUID.randomUUID(), new Vec3(0, 70, 30), false));
        Vec3 before = w.position(fish);
        w.play(fish, "teleport");
        Frame f = w.step(DT, ps, System.nanoTime());
        FishOut o = f.get(fish);
        assertEquals(1, o.teleportSeq());
        assertTrue(Math.sqrt(Steer.distanceSq(before, new Vec3(o.x(), o.y(), o.z()))) >= 3.5);
        assertTrue(
                f.events().stream()
                        .anyMatch(
                                e -> e instanceof SimEvent.Particle p && p.key().equals("portal")));
    }

    @Test
    void floorFishDoNotMove() {
        MediumField field = Fixtures.slab(MediumField.WATER, 40, 62);
        SimWorld w = world(field);
        UUID clam =
                spawn(
                                w,
                                Fixtures.profile(
                                        Fixtures.fish("clam"), Medium.FLOOR, WildTemplate.CLAM),
                                new Vec3(0.5, 40.15, 0.5),
                                1,
                                null)
                        .getFirst();
        List<PlayerSnap> ps = List.of(player(UUID.randomUUID(), new Vec3(0, 50, 20), false));
        Vec3 start = w.position(clam);
        for (int i = 0; i < 100; i++) {
            w.step(DT, ps, System.nanoTime());
        }
        assertEquals(start, w.position(clam));
    }

    @Test
    void chunkUnloadRemovesFish() {
        MediumField field = Fixtures.slab(MediumField.WATER, 40, 62);
        SimWorld w = world(field);
        spawn(w, Fixtures.water("c1"), new Vec3(0.5, 55.5, 0.5), 1, null);
        spawn(w, Fixtures.water("c2"), new Vec3(20.5, 55.5, 0.5), 1, null);
        w.removeChunk(WORLD, 0, 0);
        w.step(
                DT,
                List.of(player(UUID.randomUUID(), new Vec3(10, 60, 0), false)),
                System.nanoTime());
        assertEquals(1, w.size());
    }
}
