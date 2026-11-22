package wiki.creeper.harbor.common.time;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/**
 * Server time (default Asia/Seoul). Computes season weeks and daily/weekly reset times.
 *
 * <p>The "game date" changes at the daily reset time. If the reset is at 06:00, it is still the
 * previous day until 05:59.
 */
public final class HarborTime {

    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Seoul");

    private final Clock clock;
    private final LocalTime dailyReset;
    private final DayOfWeek weeklyResetDay;
    private final LocalDate seasonStart;

    public HarborTime(
            Clock clock, LocalTime dailyReset, DayOfWeek weeklyResetDay, LocalDate seasonStart) {
        this.clock = clock;
        this.dailyReset = dailyReset;
        this.weeklyResetDay = weeklyResetDay;
        this.seasonStart = seasonStart;
    }

    public ZoneId zone() {
        return clock.getZone();
    }

    public ZonedDateTime now() {
        return ZonedDateTime.now(clock);
    }

    /** Game date based on the daily reset. */
    public LocalDate gameDay() {
        ZonedDateTime now = now();
        LocalDate date = now.toLocalDate();
        return now.toLocalTime().isBefore(dailyReset) ? date.minusDays(1) : date;
    }

    /** Season week (starting at 1). 0 before the season starts. Used for week-locked quests. */
    public int seasonWeek() {
        LocalDate day = gameDay();
        if (day.isBefore(seasonStart)) {
            return 0;
        }
        return (int) (ChronoUnit.DAYS.between(seasonStart, day) / 7) + 1;
    }

    public ZonedDateTime nextDailyReset() {
        ZonedDateTime now = now();
        ZonedDateTime today = now.with(dailyReset).truncatedTo(ChronoUnit.MINUTES);
        return today.isAfter(now) ? today : today.plusDays(1);
    }

    public ZonedDateTime nextWeeklyReset() {
        ZonedDateTime now = now();
        ZonedDateTime candidate =
                now.with(TemporalAdjusters.nextOrSame(weeklyResetDay))
                        .with(dailyReset)
                        .truncatedTo(ChronoUnit.MINUTES);
        return candidate.isAfter(now) ? candidate : candidate.plusWeeks(1);
    }

    /** The next occurrence of that time (e.g. ledger audit at 04:00). */
    public ZonedDateTime nextAt(LocalTime time) {
        ZonedDateTime now = now();
        ZonedDateTime today = now.with(time).truncatedTo(ChronoUnit.MINUTES);
        return today.isAfter(now) ? today : today.plusDays(1);
    }

    public Duration untilNext(LocalTime time) {
        return Duration.between(now(), nextAt(time));
    }
}
