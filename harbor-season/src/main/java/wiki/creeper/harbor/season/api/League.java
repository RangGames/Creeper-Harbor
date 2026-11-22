package wiki.creeper.harbor.season.api;

import java.util.Optional;
import wiki.creeper.harbor.common.model.JobTier;
import wiki.creeper.harbor.common.model.Keyed;

/**
 * Ranking league ('11. Data design' records.league). Determined by the job tier at the time of the
 * record; later job changes do not change that record's league. Keys match Collection's
 * records.league values.
 */
public enum League implements Keyed {
    TIER1,
    TIER2,
    ALL;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }

    public static Optional<League> parse(String raw) {
        return Keyed.fromKey(League.class, raw);
    }

    /** Job tier for the league (empty Optional for ALL). */
    public Optional<JobTier> tier() {
        return switch (this) {
            case TIER1 -> Optional.of(JobTier.FIRST);
            case TIER2 -> Optional.of(JobTier.SECOND);
            case ALL -> Optional.empty();
        };
    }
}
