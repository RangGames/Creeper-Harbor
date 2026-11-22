package wiki.creeper.harbor.season.data;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.season.api.SeasonPhase;

/** One season_meta row (proposed). */
public record SeasonMeta(
        int season,
        Instant startsAt,
        @Nullable Instant endsAt,
        SeasonPhase state,
        int weekShiftHours) {}
