package wiki.creeper.harbor.season.clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class SeasonCalendarTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ZonedDateTime START = LocalDateTime.of(2027, 9, 6, 6, 0).atZone(SEOUL);

    private static Instant at(Duration offset) {
        return START.toInstant().plus(offset);
    }

    @Test
    void weekFollowsSevenDayBoundaries() {
        SeasonCalendar cal = new SeasonCalendar(2, "S", START, 12, 0);
        assertEquals(0, cal.weekAt(at(Duration.ofSeconds(-1))));
        assertEquals(1, cal.weekAt(at(Duration.ZERO)));
        assertEquals(1, cal.weekAt(at(Duration.ofDays(7).minusSeconds(1))));
        assertEquals(2, cal.weekAt(at(Duration.ofDays(7))));
        assertEquals(12, cal.weekAt(at(Duration.ofDays(83))));
        // After 12 weeks, overtime weeks (13 and above)
        assertEquals(13, cal.weekAt(at(Duration.ofDays(84))));
    }

    @Test
    void shiftDelaysWeekStart() {
        SeasonCalendar cal = new SeasonCalendar(2, "S", START, 12, 6);
        assertEquals(1, cal.weekAt(at(Duration.ofDays(7).plusHours(5))));
        assertEquals(2, cal.weekAt(at(Duration.ofDays(7).plusHours(6))));
        assertEquals(0, cal.weekAt(at(Duration.ofHours(5))));
        assertEquals(START.plusHours(6).plusWeeks(2), cal.weekStart(3));
        assertEquals(START.plusHours(6).plusWeeks(12), cal.endsAt());
    }

    @Test
    void rejectsNegativeShiftAndBadWeek() {
        assertThrows(
                IllegalArgumentException.class, () -> new SeasonCalendar(1, "S", START, 12, -1));
        SeasonCalendar cal = new SeasonCalendar(1, "S", START, 12, 0);
        assertThrows(IllegalArgumentException.class, () -> cal.weekStart(0));
    }
}
