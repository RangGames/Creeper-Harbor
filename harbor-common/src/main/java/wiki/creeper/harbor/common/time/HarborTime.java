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
 * 서버 시간 (기본 Asia/Seoul). 시즌 주차, 일일·주간 초기화 시각을 계산한다.
 *
 * <p>"게임 날짜"는 일일 초기화 시각을 기준으로 바뀐다. 초기화가 06:00이면 05:59까지는 전날이다.
 */
public final class HarborTime {

    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Seoul");

    private final Clock clock;
    private final LocalTime dailyReset;
    private final DayOfWeek weeklyResetDay;
    private final LocalDate seasonStart;

    public HarborTime(Clock clock, LocalTime dailyReset, DayOfWeek weeklyResetDay, LocalDate seasonStart) {
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

    /** 일일 초기화 기준 게임 날짜. */
    public LocalDate gameDay() {
        ZonedDateTime now = now();
        LocalDate date = now.toLocalDate();
        return now.toLocalTime().isBefore(dailyReset) ? date.minusDays(1) : date;
    }

    /** 시즌 주차 (1부터). 시즌 시작 전이면 0. 주차 잠금 퀘스트에 쓴다. */
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
                now.with(TemporalAdjusters.nextOrSame(weeklyResetDay)).with(dailyReset).truncatedTo(ChronoUnit.MINUTES);
        return candidate.isAfter(now) ? candidate : candidate.plusWeeks(1);
    }

    /** 다음에 오는 그 시각 (예: 원장 검사 04:00). */
    public ZonedDateTime nextAt(LocalTime time) {
        ZonedDateTime now = now();
        ZonedDateTime today = now.with(time).truncatedTo(ChronoUnit.MINUTES);
        return today.isAfter(now) ? today : today.plusDays(1);
    }

    public Duration untilNext(LocalTime time) {
        return Duration.between(now(), nextAt(time));
    }
}
