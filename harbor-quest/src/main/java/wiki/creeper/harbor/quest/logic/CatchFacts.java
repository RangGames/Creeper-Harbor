package wiki.creeper.harbor.quest.logic;

import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;

/**
 * Item and catch information used to judge objectives (kept separate from the server API so it can
 * be unit tested).
 *
 * @param fish the fish species (null if not a fish item)
 * @param item plain item ID (null for fish)
 * @param region catch region (catch), the species' spawn region (deliver, donate)
 * @param trap whether it is a trap harvest
 */
public record CatchFacts(
        @Nullable FishId fish,
        @Nullable ItemId item,
        @Nullable Grade grade,
        @Nullable RegionId region,
        int quality,
        double size,
        boolean trap) {

    public static CatchFacts fish(
            FishId fish,
            Grade grade,
            @Nullable RegionId region,
            int quality,
            double size,
            boolean trap) {
        return new CatchFacts(fish, null, grade, region, quality, size, trap);
    }

    public static CatchFacts item(ItemId item) {
        return new CatchFacts(null, item, null, null, 0, 0, false);
    }
}
