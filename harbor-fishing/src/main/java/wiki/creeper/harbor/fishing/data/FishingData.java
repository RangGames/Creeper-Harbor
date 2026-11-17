package wiki.creeper.harbor.fishing.data;

import java.util.HashMap;
import java.util.Map;
import wiki.creeper.harbor.common.id.FishId;

/** 플레이어 낚시 데이터 (PlayerComponent, 변경 불가). 저장하는 것은 전설 천장 카운터뿐이고, 보류 판정·매크로 통계·놓친 대어 수는 세션 메모리에 둔다. */
public record FishingData(Map<FishId, Integer> pity) {

    public static final FishingData EMPTY = new FishingData(Map.of());

    public FishingData {
        pity = Map.copyOf(pity);
    }

    public int pity(FishId fish) {
        return pity.getOrDefault(fish, 0);
    }

    public FishingData withPity(FishId fish, int casts) {
        Map<FishId, Integer> next = new HashMap<>(pity);
        next.put(fish, casts);
        return new FishingData(next);
    }
}
