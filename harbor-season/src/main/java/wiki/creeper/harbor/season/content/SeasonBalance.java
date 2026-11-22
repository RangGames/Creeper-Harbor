package wiki.creeper.harbor.season.content;

import wiki.creeper.harbor.common.content.type.Balance;

/** balance.yml season section. Missing values use proposed values. */
public record SeasonBalance(Balance balance) {

    /** Interval for checking week changes and reset times (docs 20 11.4: 1 minute). */
    public int clockCheckSeconds() {
        return Math.max(5, balance.integer("season.clock.check_s", 60));
    }

    /** Interval for re-reading season_meta (week shift, state) from the DB. */
    public int metaReloadSeconds() {
        return Math.max(30, balance.integer("season.clock.meta_reload_s", 300));
    }

    /** Ranking aggregation check interval (checks whether each board's refresh has passed). */
    public int rankingCheckSeconds() {
        return Math.max(10, balance.integer("season.ranking.check_s", 60));
    }

    /**
     * harbor:lock:ranking hold time. Kept slightly shorter than the aggregation interval so only
     * one server aggregates per interval.
     */
    public int rankingLockSeconds() {
        return Math.max(30, balance.integer("season.ranking.lock_ttl_s", 50));
    }

    /** Rows per GUI page (separate from board size). */
    public int rankingPageRows() {
        return Math.clamp(balance.integer("season.ranking.page_rows", 5), 1, 5);
    }

    /** Whether to announce hall of fame updates. */
    public boolean hallOfFameAnnounce() {
        return balance.bool("season.hall_of_fame.announce", true);
    }

    /**
     * Minimum interval between announcements for the same category and subject (seconds, proposed
     * value).
     */
    public int hallOfFameAnnounceThrottleSeconds() {
        return Math.max(0, balance.integer("season.hall_of_fame.announce_throttle_s", 30));
    }

    /** Whether to show the new season intro Dialog. */
    public boolean gateNotice() {
        return balance.bool("season.gate.notice", true);
    }
}
