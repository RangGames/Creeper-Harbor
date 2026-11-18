package wiki.creeper.harbor.boss.logic;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.boss.content.BossDef;

/** 패턴 고르기: 가중치, 같은 패턴 연속 금지, 패턴별 쿨타임. */
class PatternSchedulerTest {

    private static BossDef.PatternDef pattern(String id, int cooldown, int weight) {
        return new BossDef.PatternDef(
                id,
                id,
                null,
                new BossDef.Telegraph(BossDef.Shape.CIRCLE, 3, 0, 0, 0, 0, BossDef.Anchor.MEMBER),
                new BossDef.Effect(1, 0, BossDef.Debuff.NONE, 0, 0),
                cooldown,
                weight,
                false);
    }

    @Test
    void neverSamePatternTwiceInARow() {
        PatternScheduler s = new PatternScheduler();
        var list = List.of(pattern("a", 0, 10), pattern("b", 0, 1));
        SplittableRandom r = new SplittableRandom(3);
        String last = null;
        for (long t = 0; t < 1000; t += 10) {
            var p = s.next(list, t, 10, r);
            assertTrue(p.isPresent());
            assertNotEquals(last, p.get().id());
            last = p.get().id();
        }
    }

    @Test
    void respectsGapAndCooldown() {
        PatternScheduler s = new PatternScheduler();
        var a = pattern("a", 100, 1);
        var b = pattern("b", 100, 1);
        SplittableRandom r = new SplittableRandom(1);
        assertTrue(s.next(List.of(a, b), 0, 20, r).isPresent());
        assertTrue(s.next(List.of(a, b), 10, 20, r).isEmpty()); // 간격
        assertTrue(s.next(List.of(a, b), 20, 20, r).isPresent()); // 남은 하나
        assertTrue(s.next(List.of(a, b), 40, 20, r).isEmpty()); // 둘 다 쿨타임
        assertTrue(s.next(List.of(a, b), 100, 20, r).isPresent());
    }

    @Test
    void singlePatternPhaseMayRepeat() {
        PatternScheduler s = new PatternScheduler();
        var a = pattern("a", 0, 1);
        SplittableRandom r = new SplittableRandom(1);
        assertTrue(s.next(List.of(a), 0, 5, r).isPresent());
        assertTrue(s.next(List.of(a), 5, 5, r).isPresent());
    }
}
