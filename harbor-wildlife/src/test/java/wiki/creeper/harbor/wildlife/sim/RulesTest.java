package wiki.creeper.harbor.wildlife.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 위협 감지·표시 예산·광선 판정 ('17-3' 9.4). */
class RulesTest {

    @Test
    void threatRadiusFactors() {
        assertEquals(10, ThreatRules.radius(10, false, false, false, 0.5, 1.3, 0.7), 1e-9);
        assertEquals(5, ThreatRules.radius(10, true, false, false, 0.5, 1.3, 0.7), 1e-9);
        assertEquals(13, ThreatRules.radius(10, false, true, false, 0.5, 1.3, 0.7), 1e-9);
        assertEquals(3.5, ThreatRules.radius(10, true, false, true, 0.5, 1.3, 0.7), 1e-9);
    }

    @Test
    void threatTriggersOnSpeedOrInnerRadius() {
        // 반경 8: 빠르게 다가오면 발동, 천천히면 35%(2.8블록) 안에서만
        assertTrue(ThreatRules.triggered(7, 3.0, 8, 2.5, 0.35));
        assertFalse(ThreatRules.triggered(7, 1.0, 8, 2.5, 0.35));
        assertTrue(ThreatRules.triggered(2.5, 0.0, 8, 2.5, 0.35));
        assertFalse(ThreatRules.triggered(9, 10, 8, 2.5, 0.35), "반경 밖은 무시");
        assertFalse(ThreatRules.triggered(1, 10, 0, 2.5, 0.35));
    }

    @Test
    void closingSpeedIsProjection() {
        assertEquals(3, ThreatRules.closing(0, 0, 0, 3, 0, 0, 5, 0, 0), 1e-9);
        assertEquals(-3, ThreatRules.closing(0, 0, 0, -3, 0, 0, 5, 0, 0), 1e-9);
        assertEquals(0, ThreatRules.closing(0, 0, 0, 0, 0, 3, 5, 0, 0), 1e-9);
    }

    @Test
    void budgetFillsNearestFirstUntilCountOrParts() {
        List<ViewBudget.Candidate> c = new ArrayList<>();
        UUID[] ids = new UUID[6];
        for (int i = 0; i < 6; i++) {
            ids[i] = UUID.randomUUID();
            c.add(new ViewBudget.Candidate(ids[i], Math.pow(5 + i * 5, 2), 10, false, false));
        }
        assertEquals(List.of(ids[0], ids[1], ids[2]), ViewBudget.select(c, 3, 1000, 40, 44));
        assertEquals(
                List.of(ids[0], ids[1]), ViewBudget.select(c, 24, 25, 40, 44), "부품 예산 25 → 10+10");
    }

    @Test
    void budgetHysteresisKeepsShownUntilKeepDistance() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<ViewBudget.Candidate> c =
                List.of(
                        new ViewBudget.Candidate(a, 42 * 42, 5, true, false),
                        new ViewBudget.Candidate(b, 42 * 42, 5, false, false));
        assertEquals(List.of(a), ViewBudget.select(c, 24, 180, 40, 44));
    }

    @Test
    void budgetSkipsBigRigButFitsSmaller() {
        UUID big = UUID.randomUUID();
        UUID small = UUID.randomUUID();
        List<ViewBudget.Candidate> c =
                List.of(
                        new ViewBudget.Candidate(big, 4, 30, false, false),
                        new ViewBudget.Candidate(small, 9, 4, false, false));
        assertEquals(List.of(small), ViewBudget.select(c, 24, 20, 40, 44));
    }

    @Test
    void scriptedFishComeFirst() {
        UUID far = UUID.randomUUID();
        UUID near = UUID.randomUUID();
        List<ViewBudget.Candidate> c =
                List.of(
                        new ViewBudget.Candidate(near, 4, 10, false, false),
                        new ViewBudget.Candidate(far, 900, 10, false, true));
        assertEquals(List.of(far), ViewBudget.select(c, 1, 100, 40, 44));
    }

    @Test
    void partBudgetUsesIntensityAndCoreRemainder() {
        assertEquals(180, ViewBudget.partBudget(180, 1.0, 300, 0));
        assertEquals(90, ViewBudget.partBudget(180, 0.5, 300, 0));
        assertEquals(135, ViewBudget.partBudget(180, 0.75, 300, 0));
        assertEquals(100, ViewBudget.partBudget(180, 1.0, 300, 200), "보스·수족관이 200개 쓰면 남은 100");
        assertEquals(0, ViewBudget.partBudget(180, 1.0, 300, 400));
        assertEquals(18, ViewBudget.countBudget(24, 0.75));
    }

    @Test
    void lodTiers() {
        assertEquals(ViewBudget.Tier.NEAR, ViewBudget.tier(10, 16, 40));
        assertEquals(ViewBudget.Tier.MID, ViewBudget.tier(30, 16, 40));
        assertEquals(ViewBudget.Tier.FAR, ViewBudget.tier(41, 16, 40));
    }

    @Test
    void raycastSphere() {
        // 원점에서 +X로 쏜 광선, 중심 (5, 0, 0) 반지름 1 → t = 4
        assertEquals(4, Raycast.sphere(0, 0, 0, 1, 0, 0, 5, 0, 0, 1, 10), 1e-9);
        assertEquals(-1, Raycast.sphere(0, 0, 0, 1, 0, 0, 5, 3, 0, 1, 10), 1e-9);
        assertEquals(-1, Raycast.sphere(0, 0, 0, -1, 0, 0, 5, 0, 0, 1, 10), 1e-9, "뒤쪽");
        assertEquals(-1, Raycast.sphere(0, 0, 0, 1, 0, 0, 5, 0, 0, 1, 3), 1e-9, "거리 밖");
        assertEquals(0, Raycast.sphere(5, 0, 0, 1, 0, 0, 5, 0, 0, 1, 10), 1e-9, "구 안");
    }
}
