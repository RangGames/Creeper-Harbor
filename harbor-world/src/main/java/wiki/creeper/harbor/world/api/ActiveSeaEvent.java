package wiki.creeper.harbor.world.api;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.fishing.api.SeaEventMessage.FishEffect;

/**
 * One sea event that is announced or in progress.
 *
 * @param runId run ID (e.g. great_migration_202611181930). Same as the eventId of harbor:event
 * @param eventId event definition ID (event/great_migration)
 * @param region waters (null = whole server)
 * @param area sub-area within the region (null = whole region)
 * @param wildlifeBoost Wildlife density multiplier (1 = none)
 */
public record ActiveSeaEvent(
        String runId,
        HarborId eventId,
        String name,
        SeaEventType type,
        @Nullable RegionId region,
        @Nullable String area,
        Instant startsAt,
        Instant endsAt,
        Phase phase,
        double wildlifeBoost,
        List<FishEffect> fishEffects) {

    /** Phase. */
    public enum Phase {
        WARN,
        ACTIVE
    }

    public ActiveSeaEvent {
        fishEffects = List.copyOf(fishEffects);
    }

    public boolean covers(RegionId at) {
        return region == null || region.equals(at);
    }

    public ActiveSeaEvent withPhase(Phase next) {
        return new ActiveSeaEvent(
                runId,
                eventId,
                name,
                type,
                region,
                area,
                startsAt,
                endsAt,
                next,
                wildlifeBoost,
                fishEffects);
    }
}
