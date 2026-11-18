package wiki.creeper.harbor.season.clock;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;

/**
 * 시즌 주차 계산 (순수 계산, 서버 API 없음). week = floor((지금 − 시작 − 보정) ÷ 7일) + 1, 시작 전이면 0.
 *
 * @param season 시즌 번호
 * @param name 표시 이름
 * @param startsAt 시즌 시작 시각
 * @param weeks 시즌 길이 (주)
 * @param shiftHours 주차 보정 (시간, 0 이상)
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

    /** 보정을 반영한 1주차 시작 시각. */
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

    /** n주차 시작 시각. */
    public ZonedDateTime weekStart(int week) {
        if (week < 1) {
            throw new IllegalArgumentException("주차는 1부터입니다: " + week);
        }
        return shiftedStart().plusWeeks(week - 1L);
    }

    /** 정해진 시즌 길이가 끝나는 시각 (연장 주 시작). */
    public ZonedDateTime endsAt() {
        return weekStart(weeks + 1);
    }

    public SeasonCalendar withShift(int hours) {
        return new SeasonCalendar(season, name, startsAt, weeks, hours);
    }
}
