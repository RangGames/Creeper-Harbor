package wiki.creeper.harbor.fishing.data;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.model.Grade;

/**
 * Today's catch summary ("4. Fishing presentation" 1.2 "today's catch" and the 1.3 quick action,
 * proposed): number of catches, best grade and biggest fish of the day. Saved with the player data
 * (fishing_daily), so it follows server moves. Immutable.
 *
 * @param day the day (balance.yml fishing.daily_zone)
 * @param catches number of fish caught by fishing that day
 * @param best best grade (null if none)
 * @param bigFish species of the biggest fish (null if none)
 * @param bigCm size of the biggest fish
 */
public record DailyCatch(
        LocalDate day, int catches, @Nullable Grade best, @Nullable FishId bigFish, double bigCm) {

    public static DailyCatch empty(LocalDate day) {
        return new DailyCatch(day, 0, null, null, 0);
    }

    /** Adds one catch (starts over on a new day). */
    public DailyCatch add(LocalDate today, FishId fish, Grade grade, double sizeCm) {
        DailyCatch base = today.equals(day) ? this : empty(today);
        Grade top = base.best;
        Grade nextBest = top == null || grade.compareTo(top) > 0 ? grade : top;
        boolean bigger = base.bigFish == null || sizeCm > base.bigCm;
        return new DailyCatch(
                today,
                base.catches + 1,
                nextBest,
                bigger ? fish : base.bigFish,
                bigger ? sizeCm : base.bigCm);
    }

    /** The summary as of today (empty if the stored day has passed). */
    public DailyCatch asOf(LocalDate today) {
        return today.equals(day) ? this : empty(today);
    }
}
