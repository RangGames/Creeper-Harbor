package wiki.creeper.harbor.season.api;

import java.time.ZonedDateTime;
import wiki.creeper.harbor.common.model.SeasonId;

/**
 * 시즌 시계 (docs 20 11.2·11.4). 메모리 계산만 하고 DB에 접근하지 않으므로 어느 스레드에서 불러도 된다.
 *
 * <p>주차 = floor((지금 − 시작 시각 − week_shift_hours) ÷ 7일) + 1. 시작 시각은 season/seasons.yml의 starts_at
 * (날짜만 쓰면 그날 일일 초기화 시각). 서버마다 따로 계산해도 같은 값이 나온다.
 *
 * <pre>{@code
 * SeasonClock clock = SeasonServices.clock();
 * if (clock.week() >= quest.requires().week()) { ... }
 * }</pre>
 */
public interface SeasonClock {

    /** 지금 서버가 돌리는 시즌 (harbor-infra.yml season.id). */
    SeasonId current();

    /** 시즌 주차. 1~{@link #weeks()}, 그 뒤는 연장 주(13 이상). 시즌 시작 전이면 0. */
    int week();

    /** 시즌 길이 (주, seasons.yml weeks, 기본 12). */
    int weeks();

    /** 정해진 주차를 넘긴 연장 주인지. */
    default boolean extended() {
        return week() > weeks();
    }

    /** n주차가 시작하는 시각 (주차 보정 포함). n은 1 이상. */
    ZonedDateTime weekStart(int week);

    /** 시즌 시작 시각 (주차 보정 전). */
    ZonedDateTime startsAt();

    /** 시즌이 끝나는 예정 시각 (시작 + weeks주 + 보정). 연장되면 지나도 시즌은 계속된다. */
    ZonedDateTime endsAt();

    /** 운영 보정으로 주차 시작을 늦춘 시간 (시간 단위, 0 이상). */
    int weekShiftHours();

    /** 다음 일일 초기화 시각 (Core HarborTime, 기본 06:00). */
    ZonedDateTime nextDailyReset();

    /** 다음 주간 초기화 시각 (Core HarborTime, 기본 월요일 06:00). */
    ZonedDateTime nextWeeklyReset();

    /** 시즌 상태 (season_meta 캐시). */
    SeasonPhase state();

    /** seasons.yml의 시즌 이름 (없으면 "S<번호>"). */
    String displayName();
}
