package wiki.creeper.harbor.collection.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import wiki.creeper.harbor.common.id.FishId;

/**
 * Biggest-catch records (Season rankings and hall of fame, collection details). Leagues: tier1 ·
 * tier2 · all.
 */
public interface RecordService {

    List<String> LEAGUES = List.of("tier1", "tier2", "all");

    /** Personal max size from the cumulative collection. */
    Optional<Double> personalBest(UUID player, FishId fish);

    /** This season's server top list (main server memory cache, up to 10). */
    List<RecordEntry> serverTop(FishId fish, String league, int limit);

    /**
     * Server records broken this week, newest first (only those this server received since it
     * started; cleared on the weekly reset, up to 30).
     */
    List<WeeklyRecord> weeklyRecords();
}
