package wiki.creeper.harbor.fishing.roll;

import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * 출현 판정 컨텍스트 (docs 20 3.4 1단계).
 *
 * @param time dawn · day · dusk · night
 * @param weather clear · rain · thunder
 * @param moon full · new · (그 밖은 빈 문자열)
 * @param serverFlags 서버 진행 플래그 확인 (unlock_flag)
 * @param caught 도감 등록 여부 (미등록 가중치)
 */
public record RollContext(
        RegionId region,
        String time,
        String weather,
        String moon,
        @Nullable ItemId bait,
        int jobTier,
        Predicate<String> serverFlags,
        Predicate<FishId> caught,
        Set<String> eventFish) {}
