package wiki.creeper.harbor.boss.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.boss.content.BossDef;

/** 작살 궤적 선분 ↔ 판정 상자, 예고 범위 모양. */
class GeometryTest {

    @Test
    void segmentHitsBox() {
        Geometry.Aabb box = Geometry.Aabb.interaction(0, 0, 0, 2, 2); // x,z −1~1, y 0~2
        // 한 틱에 상자를 통째로 지나가는 빠른 투사체도 잡는다
        double t = box.intersect(-5, 1, 0, 5, 1, 0);
        assertEquals(0.4, t, 1e-9);
        assertTrue(box.intersect(-5, 3, 0, 5, 3, 0) < 0); // 위로 지나감
        assertTrue(box.intersect(-5, 1, 0, -2, 1, 0) < 0); // 못 미침
        assertEquals(0, box.intersect(0, 1, 0, 0, 1, 0), 1e-9); // 안에서 멈춤
    }

    private static BossDef.Telegraph tel(
            BossDef.Shape shape, double size, double length, double angle, double inner) {
        return new BossDef.Telegraph(shape, size, length, angle, inner, 0, BossDef.Anchor.BOSS);
    }

    @Test
    void shapes() {
        var circle = Geometry.Area.of(tel(BossDef.Shape.CIRCLE, 3, 0, 0, 0), 0, 0, 0, 1);
        assertTrue(circle.contains(2, 2));
        assertFalse(circle.contains(3, 3));

        var ring = Geometry.Area.of(tel(BossDef.Shape.RING, 6, 0, 0, 3), 0, 0, 0, 1);
        assertFalse(ring.contains(1, 0));
        assertTrue(ring.contains(4, 0));
        assertFalse(ring.contains(7, 0));

        var line = Geometry.Area.of(tel(BossDef.Shape.LINE, 4, 10, 0, 0), 0, 0, 0, 1); // +Z 방향
        assertTrue(line.contains(1.5, 9));
        assertFalse(line.contains(2.5, 5));
        assertFalse(line.contains(0, -1));
        assertFalse(line.contains(0, 11));

        var cone = Geometry.Area.of(tel(BossDef.Shape.CONE, 8, 0, 90, 0), 0, 0, 1, 0); // +X, ±45°
        assertTrue(cone.contains(5, 4));
        assertFalse(cone.contains(4, 5));
        assertFalse(cone.contains(-1, 0));
        assertTrue(cone.contains(0, 0));
    }

    @Test
    void outlineShrinks() {
        var circle = Geometry.Area.of(tel(BossDef.Shape.CIRCLE, 4, 0, 0, 0), 10, 10, 0, 1);
        double[] p = circle.outline(16, 0.5).getFirst();
        assertEquals(2, Math.hypot(p[0] - 10, p[1] - 10), 1e-9);
        assertFalse(circle.cells(1).isEmpty());
    }
}
