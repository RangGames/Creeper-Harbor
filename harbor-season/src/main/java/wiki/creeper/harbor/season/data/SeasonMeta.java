package wiki.creeper.harbor.season.data;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.season.api.SeasonPhase;

/** season_meta 한 행 (추가 제안). */
public record SeasonMeta(
        int season,
        Instant startsAt,
        @Nullable Instant endsAt,
        SeasonPhase state,
        int weekShiftHours) {}
