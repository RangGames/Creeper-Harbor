package wiki.creeper.harbor.season.hof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class HofMathTest {

    private static final Instant T1 = Instant.ofEpochSecond(100);
    private static final Instant T2 = Instant.ofEpochSecond(200);

    @Test
    void betterRespectsOrderAndTies() {
        assertTrue(HofMath.better(false, 60.5, T2, 60.0, T1));
        assertFalse(HofMath.better(false, 59.9, T1, 60.0, T2));
        assertTrue(HofMath.better(true, 50_000, T2, 60_000, T1));
        // 같은 값이면 먼저 달성한 기록이 이긴다
        assertFalse(HofMath.better(false, 60.0, T2, 60.0, T1));
        assertTrue(HofMath.better(false, 60.0, T1, 60.0, T2));
    }

    @Test
    void formatsValues() {
        assertEquals("62.4cm", HofMath.format(62.4, "cm"));
        assertEquals("70", HofMath.format(70, ""));
        assertEquals("1:05.3", HofMath.format(65_300, "time_ms"));
        assertEquals(
                "hof:3:biggest_fish:harbor:fish/x",
                HofMath.titleRef(3, "biggest_fish", "harbor:fish/x"));
        assertEquals(64, HofMath.titleRef(3, "c".repeat(80), "s").length());
    }
}
