package wiki.creeper.harbor.fishing.data;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.fishing.food.ActiveFood;

/**
 * 플레이어 낚시 데이터 (PlayerComponent, 변경 불가). 지연 쓰기로 저장하는 것은 전설 천장 카운터뿐이다. 요리 버프는 먹을 때 즉시
 * 쓰고(fishing_food) 접속 때 같이 읽는다. 보류 판정·매크로 통계·놓친 대어 수는 세션 메모리에 둔다.
 */
public record FishingData(Map<FishId, Integer> pity, @Nullable ActiveFood food) {

    public static final FishingData EMPTY = new FishingData(Map.of(), null);

    public FishingData {
        pity = Map.copyOf(pity);
    }

    public int pity(FishId fish) {
        return pity.getOrDefault(fish, 0);
    }

    public FishingData withPity(FishId fish, int casts) {
        Map<FishId, Integer> next = new HashMap<>(pity);
        next.put(fish, casts);
        return new FishingData(next, food);
    }

    public FishingData withFood(@Nullable ActiveFood next) {
        return new FishingData(pity, next);
    }
}
