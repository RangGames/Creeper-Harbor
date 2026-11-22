package wiki.creeper.harbor.fishing.roll;

import java.util.Map;
import wiki.creeper.harbor.common.model.Grade;

/**
 * Modifier values used in rolls (precomputed from Core modifiers on the main thread and passed in).
 *
 * @param gradeBonus per-grade modifier multiplier − 1 for rare-or-better (category product, {@link
 *     GradeBonus}). RollEngine applies the cap once more
 * @param uncaughtWeight weight modifier for species not in the codex (codex hunter)
 * @param legendPityStartOffset legendary pity start offset (breath of legends −30, −60)
 * @param legendChanceMult legendary chance multiplier (sign of legends ×2)
 * @param speciesMult event species multipliers (great migration ×3, species ID string → multiplier)
 * @param mythicChanceBonus value added to the mythic chance (mythic premonition +0.5%p)
 */
public record RollModifiers(
        Map<Grade, Double> gradeBonus,
        double uncaughtWeight,
        int legendPityStartOffset,
        double legendChanceMult,
        Map<String, Double> speciesMult,
        double mythicChanceBonus) {

    public static final RollModifiers NONE = new RollModifiers(Map.of(), 0, 0, 1, Map.of(), 0);

    public RollModifiers {
        gradeBonus = Map.copyOf(gradeBonus);
        speciesMult = Map.copyOf(speciesMult);
    }
}
