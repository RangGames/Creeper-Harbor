package wiki.creeper.harbor.fishing.roll;

import java.util.List;
import java.util.Map;
import java.util.Set;
import wiki.creeper.harbor.common.model.Grade;

/**
 * Modifier values used in rolls (precomputed from Core modifiers on the main thread and passed in).
 *
 * @param gradeBonus per-grade modifier multiplier − 1 for rare-or-better (category product, {@link
 *     GradeBonus}). RollEngine applies the cap once more
 * @param uncaughtWeight weight modifier for species not in the collection (collection hunter)
 * @param legendPityStartOffset legendary pity start offset (breath of legends −30, −60)
 * @param legendChanceMult legendary chance multiplier (sign of legends ×2)
 * @param speciesMult event species multipliers (great migration ×3, species ID string → multiplier)
 * @param mythicChanceBonus value added to the mythic chance (mythic premonition +0.5%p)
 * @param weightBonus conditional bait and part bonuses (BaitStats weight_bonus, docs 20 17-1 3.3)
 */
public record RollModifiers(
        Map<Grade, Double> gradeBonus,
        double uncaughtWeight,
        int legendPityStartOffset,
        double legendChanceMult,
        Map<String, Double> speciesMult,
        double mythicChanceBonus,
        List<WeightBonus> weightBonus) {

    public static final RollModifiers NONE =
            new RollModifiers(Map.of(), 0, 0, 1, Map.of(), 0, List.of());

    public RollModifiers {
        gradeBonus = Map.copyOf(gradeBonus);
        speciesMult = Map.copyOf(speciesMult);
        weightBonus = List.copyOf(weightBonus);
    }

    /**
     * One conditional spawn bonus from an item stat {@code weight_bonus.<token>[+<token>…]}
     * (proposed encoding of BaitStats weight_bonus). It applies to a species when every token is
     * either a tag of the species or a cast token (time key, region name): flour paste {@code
     * weight_bonus.fresh}, shiny lure {@code weight_bonus.carnivore+day}, ice worm {@code
     * weight_bonus.frost_fjord}.
     *
     * @param tokens condition tokens (all must match)
     * @param value species weight bonus (+0.15 = ×1.15)
     */
    public record WeightBonus(Set<String> tokens, double value) {

        public static final String PREFIX = "weight_bonus.";

        public WeightBonus {
            tokens = Set.copyOf(tokens);
        }

        /** Parses a stat key; empty if it is not a weight_bonus key. */
        public static java.util.Optional<WeightBonus> parse(String statKey, double value) {
            if (!statKey.startsWith(PREFIX) || statKey.length() == PREFIX.length()) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(
                    new WeightBonus(
                            java.util.Arrays.stream(
                                            statKey.substring(PREFIX.length()).split("\\+", -1))
                                    .filter(t -> !t.isBlank())
                                    .collect(java.util.stream.Collectors.toSet()),
                            value));
        }

        /** Whether all tokens match the species tags or the cast tokens. */
        public boolean matches(List<String> tags, Set<String> castTokens) {
            for (String token : tokens) {
                if (!tags.contains(token) && !castTokens.contains(token)) {
                    return false;
                }
            }
            return true;
        }
    }
}
