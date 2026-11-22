package wiki.creeper.harbor.season.clock;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;

/**
 * Season week computation (pure computation, no server API). week = floor((now − start − shift) ÷ 7
 * days) + 1, or 0 before the start.
 *
 * @param season season number
 * @param name display name
 * @param startsAt season start time
 * @param weeks season length (weeks)
 * @param shiftHours week shift (hours, 0 or more)
 */
public record SeasonCalendar(
        int season, String name, ZonedDateTime startsAt, int weeks, int shiftHours) {

    private static final long WEEK_SECONDS = Duration.ofDays(7).toSeconds();

    public SeasonCalendar {
        if (weeks < 1) {
            throw new IllegalArgumentException("시즌 길이는 1주 이상이어야 합니다: " + weeks);
        }
        if (shiftHours < 0) {
            throw new IllegalArgumentException("주차 보정은 앞당길 수 없습니다: " + shiftHours);
        }
    }

    /** Week 1 start time with the shift applied. */
    public ZonedDateTime shiftedStart() {
        return startsAt.plusHours(shiftHours);
    }

    public int weekAt(Instant now) {
        long seconds = Duration.between(shiftedStart().toInstant(), now).toSeconds();
        if (seconds < 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, seconds / WEEK_SECONDS + 1);
    }

    /** Week n start time. */
    public ZonedDateTime weekStart(int week) {
        if (week < 1) {
            throw new IllegalArgumentException("주차는 1부터입니다: " + week);
        }
        return shiftedStart().plusWeeks(week - 1L);
    }

    /** Time the scheduled season length ends (start of overtime weeks). */
    public ZonedDateTime endsAt() {
        return weekStart(weeks + 1);
    }

    public SeasonCalendar withShift(int hours) {
        return new SeasonCalendar(season, name, startsAt, weeks, hours);
    }
}
