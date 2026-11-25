package wiki.creeper.harbor.fishing.data;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.fishing.food.ActiveFood;

/**
 * Player fishing data (PlayerComponent, immutable). The legendary pity counter, the daily summary
 * and the missed big fish count are saved via write-behind. Food buffs are written immediately when
 * eaten (fishing_food) and read together on join. Pending rolls and macro statistics are kept in
 * session memory.
 *
 * @param missedBig fish caught above the job quality cap this season ('1. Main design' 3.2 missed
 *     big fish, read by the guild master through FishingService.missedBigCount)
 */
public record FishingData(
        Map<FishId, Integer> pity,
        @Nullable ActiveFood food,
        @Nullable DailyCatch daily,
        int missedBig) {

    public static final FishingData EMPTY = new FishingData(Map.of(), null, null, 0);

    public FishingData {
        pity = Map.copyOf(pity);
    }

    public int pity(FishId fish) {
        return pity.getOrDefault(fish, 0);
    }

    public FishingData withPity(FishId fish, int casts) {
        Map<FishId, Integer> next = new HashMap<>(pity);
        next.put(fish, casts);
        return new FishingData(next, food, daily, missedBig);
    }

    public FishingData withFood(@Nullable ActiveFood next) {
        return new FishingData(pity, next, daily, missedBig);
    }

    public FishingData withDaily(DailyCatch next) {
        return new FishingData(pity, food, next, missedBig);
    }

    public FishingData withMissedBig(int count) {
        return new FishingData(pity, food, daily, Math.max(0, count));
    }
}
