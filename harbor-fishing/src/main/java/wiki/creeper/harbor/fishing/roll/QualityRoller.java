package wiki.creeper.harbor.fishing.roll;

import java.util.random.RandomGenerator;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.model.Quality;

/**
 * Size and quality roll ("7. Balance design" 4.5, docs 20 3.4 result handling steps 1~3).
 *
 * <p>X = uniform(0, 1), X' = X^(1 / (1 + luck)), size = min + (max − min) × X'. Quality thresholds
 * 0.40, 0.70, 0.90, 0.98. If above the job cap, it is pinned to the top of the cap quality's range
 * ("the big one that got away").
 */
public final class QualityRoller {

    /** Result. capped means it hit the job cap. */
    public record Rolled(double sizeCm, Quality quality, boolean capped) {}

    private QualityRoller() {}

    public static Rolled roll(
            FishDef fish, double luck, Quality cap, double[] bounds, RandomGenerator random) {
        double x = Math.pow(random.nextDouble(), 1 / (1 + Math.max(0, luck)));
        int stars = starsFor(x, bounds);
        boolean capped = stars > cap.stars();
        if (capped) {
            stars = cap.stars();
            // Just below the top of the cap quality's range (for ★3, just below 0.90)
            x = Math.nextDown(bounds[stars - 1]);
        }
        double size = fish.sizeMin() + (fish.sizeMax() - fish.sizeMin()) * x;
        return new Rolled(Math.round(size * 10) / 10.0, Quality.of(stars), capped);
    }

    static int starsFor(double x, double[] bounds) {
        int stars = 1;
        for (double bound : bounds) {
            if (x >= bound) {
                stars++;
            }
        }
        return Math.min(stars, Quality.MAX);
    }
}
