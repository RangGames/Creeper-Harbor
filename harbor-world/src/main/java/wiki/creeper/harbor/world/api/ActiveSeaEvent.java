package wiki.creeper.harbor.world.api;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.fishing.api.SeaEventMessage.FishEffect;

/**
 * 예고 중이거나 진행 중인 해상 이벤트 한 번.
 *
 * @param runId 실행 ID (예: great_migration_202611181930). harbor:event의 eventId와 같다
 * @param eventId 이벤트 정의 ID (event/great_migration)
 * @param region 해역 (null = 서버 전체)
 * @param area 지역 안 세부 구역 (null = 지역 전체)
 * @param wildlifeBoost Wildlife 밀도 배율 (1 = 없음)
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

    /** 단계. */
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
