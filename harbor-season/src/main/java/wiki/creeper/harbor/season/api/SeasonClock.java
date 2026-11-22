package wiki.creeper.harbor.season.api;

import java.time.ZonedDateTime;
import wiki.creeper.harbor.common.model.SeasonId;

/**
 * Season clock (docs 20 11.2, 11.4). Computes in memory only and never accesses the DB, so it may
 * be called from any thread.
 *
 * <p>week = floor((now − start time − week_shift_hours) ÷ 7 days) + 1. The start time is starts_at
 * in season/seasons.yml (if only a date is given, that day's daily reset time). Each server
 * computes it independently and gets the same value.
 *
 * <pre>{@code
 * SeasonClock clock = SeasonServices.clock();
 * if (clock.week() >= quest.requires().week()) { ... }
 * }</pre>
 */
public interface SeasonClock {

    /** The season this server is currently running (harbor-infra.yml season.id). */
    SeasonId current();

    /**
     * Season week. 1 to {@link #weeks()}, then overtime weeks (13 and above). 0 before the season
     * starts.
     */
    int week();

    /** Season length (weeks, seasons.yml weeks, default 12). */
    int weeks();

    /** Whether this is an overtime week past the scheduled weeks. */
    default boolean extended() {
        return week() > weeks();
    }

    /** Time week n starts (including the week shift). n is 1 or more. */
    ZonedDateTime weekStart(int week);

    /** Season start time (before the week shift). */
    ZonedDateTime startsAt();

    /**
     * Scheduled season end time (start + weeks + shift). If extended, the season continues past it.
     */
    ZonedDateTime endsAt();

    /** How much the week start has been delayed by operator shift (in hours, 0 or more). */
    int weekShiftHours();

    /** Next daily reset time (Core HarborTime, default 06:00). */
    ZonedDateTime nextDailyReset();

    /** Next weekly reset time (Core HarborTime, default Monday 06:00). */
    ZonedDateTime nextWeeklyReset();

    /** Season state (season_meta cache). */
    SeasonPhase state();

    /** Season name from seasons.yml ("S<number>" if none). */
    String displayName();
}
