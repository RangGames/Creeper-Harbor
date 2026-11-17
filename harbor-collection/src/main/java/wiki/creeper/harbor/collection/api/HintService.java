package wiki.creeper.harbor.collection.api;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * 힌트 (docs 20 4.4). 단계 0 실루엣·지역 → 1 시간대 → 2 날씨·미끼 → 3 구역·달·직업·hint_text. 실효 단계는 max(저장된 단계, 수집 계열
 * '출현 조건 힌트' 랭크).
 */
public interface HintService {

    int MAX_LEVEL = 3;

    int level(UUID player, FishId fish);

    /** 저장된 단계보다 높을 때만 올린다. 올렸으면 true. */
    boolean grant(UUID player, FishId fish, int level, String source);

    /**
     * 해금한 가장 높은 지역에서 미등록 어종 중 단계가 낮은 것 → 등급이 낮은 것 순으로 한 단계 올린다. 그 지역을 다 등록했으면 아래 지역으로 내려간다. 줄 것이
     * 없으면 빈 Optional (Quest가 대체 보상을 준다).
     */
    Optional<FishId> grantRandom(UUID player, @Nullable RegionId region, String source);
}
