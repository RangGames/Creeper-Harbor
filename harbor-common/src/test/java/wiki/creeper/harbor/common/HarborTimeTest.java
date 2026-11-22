package wiki.creeper.harbor.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.time.HarborTime;

class HarborTimeTest {

    private static HarborTime at(String dateTime) {
        ZonedDateTime now = ZonedDateTime.parse(dateTime + "+09:00[Asia/Seoul]");
        return new HarborTime(
                Clock.fixed(now.toInstant(), HarborTime.DEFAULT_ZONE),
                LocalTime.of(6, 0),
                DayOfWeek.MONDAY,
                LocalDate.of(2027, 9, 1));
    }

    @Test
    void gameDayChangesAtDailyReset() {
        assertEquals(LocalDate.of(2027, 9, 9), at("2027-09-10T05:59").gameDay());
        assertEquals(LocalDate.of(2027, 9, 10), at("2027-09-10T06:00").gameDay());
    }

    @Test
    void seasonWeek() {
        assertEquals(0, at("2027-08-31T12:00").seasonWeek());
        assertEquals(1, at("2027-09-07T12:00").seasonWeek());
        assertEquals(2, at("2027-09-08T12:00").seasonWeek());
    }

    @Test
    void nextWeeklyResetIsMonday0600() {
        // 2027-09-08 is a Wednesday
        assertEquals(
                ZonedDateTime.parse("2027-09-13T06:00+09:00[Asia/Seoul]"),
                at("2027-09-08T12:00").nextWeeklyReset());
    }
}
