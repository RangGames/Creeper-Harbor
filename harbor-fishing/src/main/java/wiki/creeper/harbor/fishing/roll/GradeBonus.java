package wiki.creeper.harbor.fishing.roll;

import java.util.EnumMap;
import java.util.Map;

/**
 * Combines rare-or-better spawn modifiers (docs 20 3.4 step 5, "7. Balance design" 4.2). Final
 * multiplier = (1 + bait)(1 + job)(1 + food)(1 + event), and the total multiplier never exceeds 1 +
 * cap (0.6). Per-category values are ADD sums grouped by modifier source prefix; for food only the
 * single largest is used. No server API (unit tests).
 */
public final class GradeBonus {

    /** Modifier category. */
    public enum Category {
        /** Bait and gear (bait:, rod and bait item stats). */
        BAIT,
        /** Job (job:, skill:, other sources). */
        JOB,
        /** Food, codex, aquarium (food:, codex_season, aquarium_stage). */
        FOOD,
        /** Events and fishing spots (event:, spot:). */
        EVENT
    }

    /** Per-category accumulator. */
    public static final class Sum {
        private final Map<Category, Double> sums = new EnumMap<>(Category.class);
        private double bestFood;

        /** Classifies one modifier by its source and adds it. */
        public Sum add(String source, double value) {
            Category category = categoryOf(source);
            if (source.startsWith("food:")) {
                // Food: only the single largest
                bestFood = Math.max(bestFood, value);
            } else {
                sums.merge(category, value, Double::sum);
            }
            return this;
        }

        public Sum add(Category category, double value) {
            sums.merge(category, value, Double::sum);
            return this;
        }

        public double of(Category category) {
            double v = sums.getOrDefault(category, 0.0);
            return category == Category.FOOD ? v + bestFood : v;
        }

        /** Product of categories − 1 (before the cap). */
        public double combined() {
            double mult = 1;
            for (Category c : Category.values()) {
                mult *= 1 + of(c);
            }
            return mult - 1;
        }

        /** Applies the cap. */
        public double capped(double cap) {
            return Math.clamp(combined(), -1, cap);
        }
    }

    private GradeBonus() {}

    public static Category categoryOf(String source) {
        if (source.startsWith("bait:") || source.startsWith("gear:")) {
            return Category.BAIT;
        }
        if (source.startsWith("food:")
                || source.startsWith("codex_season")
                || source.startsWith("aquarium_stage")) {
            return Category.FOOD;
        }
        if (source.startsWith("event:") || source.startsWith("spot:")) {
            return Category.EVENT;
        }
        return Category.JOB;
    }
}
