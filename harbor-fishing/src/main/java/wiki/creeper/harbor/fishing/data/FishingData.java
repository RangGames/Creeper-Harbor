package wiki.creeper.harbor.fishing.data;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.fishing.food.ActiveFood;

/**
 * Player fishing data (PlayerComponent, immutable). Only the legendary pity counter is saved via
 * write-behind. Food buffs are written immediately when eaten (fishing_food) and read together on
 * join. Pending rolls, macro statistics and the lost-big-fish count are kept in session memory.
 */
public record FishingData(
        Map<FishId, Integer> pity, @Nullable ActiveFood food, @Nullable DailyCatch daily) {

    public static final FishingData EMPTY = new FishingData(Map.of(), null, null);

    public FishingData {
        pity = Map.copyOf(pity);
    }

    public int pity(FishId fish) {
        return pity.getOrDefault(fish, 0);
    }

    public FishingData withPity(FishId fish, int casts) {
        Map<FishId, Integer> next = new HashMap<>(pity);
        next.put(fish, casts);
        return new FishingData(next, food, daily);
    }

    public FishingData withFood(@Nullable ActiveFood next) {
        return new FishingData(pity, next, daily);
    }

    public FishingData withDaily(DailyCatch next) {
        return new FishingData(pity, food, next);
    }
}
