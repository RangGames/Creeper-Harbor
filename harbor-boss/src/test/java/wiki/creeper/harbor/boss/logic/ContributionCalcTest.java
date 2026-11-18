package wiki.creeper.harbor.boss.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 기여도 점수·상위 20%·최소 참여 (docs 20 10.4). */
class ContributionCalcTest {

    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final UUID C = new UUID(0, 3);
    private static final UUID D = new UUID(0, 4);
    private static final UUID E = new UUID(0, 5);
    private static final UUID F = new UUID(0, 6);

    @Test
    void scoreIsWeightedRatioOfPartyMax() {
        var max = new ContributionCalc.Stats(A, 100, 50, 40, 10, 0);
        var s = new ContributionCalc.Stats(B, 50, 50, 0, 5, 0);
        assertEquals(
                0.3 * 0.5 + 0.3 * 1 + 0 + 0.2 * 0.5,
                ContributionCalc.score(s, max, ContributionWeights.DEFAULT),
                1e-9);
    }

    @Test
    void topCountRoundsUp() {
        assertEquals(1, ContributionCalc.topCount(1, 0.2));
        assertEquals(1, ContributionCalc.topCount(5, 0.2));
        assertEquals(2, ContributionCalc.topCount(6, 0.2));
        assertEquals(2, ContributionCalc.topCount(10, 0.2));
        assertEquals(0, ContributionCalc.topCount(0, 0.2));
    }

    @Test
    void differentRolesCanTopTheirOwnItem() {
        List<ContributionCalc.Stats> all =
                List.of(
                        new ContributionCalc.Stats(A, 1000, 0, 0, 0, 1000), // 작살수
                        new ContributionCalc.Stats(B, 0, 120, 0, 0, 1000), // 앵커
                        new ContributionCalc.Stats(C, 0, 0, 90, 0, 1000), // 정비사
                        new ContributionCalc.Stats(D, 100, 10, 10, 1, 1000),
                        new ContributionCalc.Stats(E, 50, 5, 5, 1, 1000),
                        new ContributionCalc.Stats(F, 10, 1, 1, 1, 1000));
        var ranked = ContributionCalc.rank(all, ContributionWeights.DEFAULT, 0.2, 1000, 0.05, 0.3);
        assertEquals(6, ranked.size());
        assertEquals(1, ranked.getFirst().rank());
        // 6명 × 20% → 2명 상위
        assertEquals(2, ranked.stream().filter(ContributionCalc.Scored::top).count());
        // 피해 1위(A)와 텐션 1위(B)가 같은 점수 0.3 → 피해가 많은 A가 앞
        assertEquals(A, ranked.get(0).stats().player());
        assertEquals(B, ranked.get(1).stats().player());
    }

    @Test
    void freeRiderGetsNothing() {
        List<ContributionCalc.Stats> all =
                List.of(
                        new ContributionCalc.Stats(A, 1000, 100, 100, 10, 1000),
                        new ContributionCalc.Stats(B, 10, 0, 0, 0, 100), // 점수 0.3% · 활동 10%
                        new ContributionCalc.Stats(C, 10, 0, 0, 0, 500)); // 점수 낮지만 활동 50%
        var ranked = ContributionCalc.rank(all, ContributionWeights.DEFAULT, 0.2, 1000, 0.05, 0.3);
        var b = ranked.stream().filter(r -> r.stats().player().equals(B)).findFirst().orElseThrow();
        var c = ranked.stream().filter(r -> r.stats().player().equals(C)).findFirst().orElseThrow();
        assertFalse(b.eligible());
        assertTrue(c.eligible());
        assertFalse(b.top());
    }

    @Test
    void zeroScoreIsNotEligible() {
        var ranked =
                ContributionCalc.rank(
                        List.of(
                                new ContributionCalc.Stats(A, 10, 0, 0, 0, 1000),
                                new ContributionCalc.Stats(B, 0, 0, 0, 0, 1000)),
                        ContributionWeights.DEFAULT,
                        0.2,
                        1000,
                        0.05,
                        0.3);
        assertFalse(ranked.get(1).eligible());
    }
}
