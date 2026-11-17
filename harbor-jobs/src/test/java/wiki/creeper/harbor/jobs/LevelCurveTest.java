package wiki.creeper.harbor.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.jobs.service.LevelCurve;

class LevelCurveTest {

    private final LevelCurve curve = new LevelCurve(2, 2.05, 10, 70);

    @Test
    void matchesBalanceTable() {
        // '7. 밸런스 기획' 3.1
        assertEquals(620, curve.cumulative(10));
        assertEquals(5_640, curve.cumulative(20));
        assertEquals(19_940, curve.cumulative(30));
        assertEquals(169_410, curve.cumulative(60));
        assertEquals(272_120, curve.cumulative(70));
        assertEquals(220, curve.need(10));
        assertEquals(8_840, curve.need(60));
    }

    @Test
    void firstLevelUpIsFree() {
        assertEquals(0, curve.need(1));
        assertEquals(0, curve.need(70));
    }
}
