package wiki.creeper.harbor.economy.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.economy.content.RecipeDef;

/**
 * Crafting math (docs 20 7.4 crafting). Material saving, Bait Artisan bonus, material assignment.
 * No server API (HarborItem is a record); unit tested.
 */
public final class CraftMath {

    private CraftMath() {}

    /**
     * One inventory slot.
     *
     * @param item its info if a Harbor item, otherwise null
     * @param vanillaKey vanilla key if not a Harbor item (minecraft:iron_ingot), otherwise null
     */
    public record Slot(
            int index, @Nullable HarborItem item, @Nullable String vanillaKey, int amount) {}

    /** One actually required material line (count after saving). */
    public record Need(RecipeDef.Ingredient ingredient, int count) {}

    /**
     * "Artisan's Hand": gear materials = ceil(count × (1 − saving)). Saving is clamped to [0,
     * maxSave], minimum 1.
     */
    public static int materialCount(
            int count, RecipeDef.Category category, double save, double maxSave) {
        if (category != RecipeDef.Category.GEAR || save <= 0) {
            return count;
        }
        double s = Math.clamp(save, 0.0, Math.max(0.0, maxSave));
        return Math.max(1, (int) Math.ceil(count * (1 - s) - 1e-9));
    }

    /** "Bait Artisan": +1 bait recipe result per rank. */
    public static int resultAmount(int amount, RecipeDef.Category category, double baitBonus) {
        if (category != RecipeDef.Category.BAIT || baitBonus <= 0) {
            return amount;
        }
        return amount + (int) Math.floor(baitBonus + 1e-9);
    }

    public static List<Need> needs(RecipeDef recipe, double save, double maxSave) {
        List<Need> needs = new ArrayList<>();
        for (RecipeDef.Ingredient ingredient : recipe.ingredients()) {
            needs.add(
                    new Need(
                            ingredient,
                            materialCount(ingredient.count(), recipe.category(), save, maxSave)));
        }
        return needs;
    }

    /** Whether the slot's item matches the material condition. */
    public static boolean matches(
            RecipeDef.Ingredient ingredient,
            Slot slot,
            Function<FishId, Optional<FishDef>> fishLookup) {
        HarborItem item = slot.item();
        return switch (ingredient) {
            case RecipeDef.Item(var id, var _) ->
                    item instanceof HarborItem.Plain plain && plain.id().equals(id);
            case RecipeDef.Vanilla(var key, var _) -> item == null && key.equals(slot.vanillaKey());
            case RecipeDef.FishMatch match -> {
                if (!(item instanceof HarborItem.Fish fish)) {
                    yield false;
                }
                if (!match.fishAny().isEmpty() && !match.fishAny().contains(fish.species())) {
                    yield false;
                }
                Grade min = match.gradeMin();
                if (min == null && match.region() == null) {
                    yield true;
                }
                Optional<FishDef> def = fishLookup.apply(fish.species());
                if (def.isEmpty()) {
                    yield false;
                }
                yield (min == null || def.get().grade().isAtLeast(min))
                        && (match.region() == null || def.get().region().equals(match.region()));
            }
        };
    }

    /**
     * Assigns materials to slots. Fills the most specific materials first (item/vanilla → named
     * species → higher grade conditions) so broad conditions do not take materials needed by narrow
     * ones. Slots are used from the front.
     *
     * @return slot index → count to take. Empty if insufficient
     */
    public static Optional<Map<Integer, Integer>> allocate(
            List<Need> needs, List<Slot> slots, Function<FishId, Optional<FishDef>> fishLookup) {
        Map<Integer, Integer> left = new LinkedHashMap<>();
        for (Slot slot : slots) {
            left.put(slot.index(), slot.amount());
        }
        Map<Integer, Integer> taken = new LinkedHashMap<>();
        List<Need> ordered =
                needs.stream().sorted(Comparator.comparingInt(CraftMath::specificity)).toList();
        for (Need need : ordered) {
            int remaining = need.count();
            for (Slot slot : slots) {
                if (remaining <= 0) {
                    break;
                }
                int available = left.getOrDefault(slot.index(), 0);
                if (available <= 0 || !matches(need.ingredient(), slot, fishLookup)) {
                    continue;
                }
                int use = Math.min(available, remaining);
                left.put(slot.index(), available - use);
                taken.merge(slot.index(), use, Integer::sum);
                remaining -= use;
            }
            if (remaining > 0) {
                return Optional.empty();
            }
        }
        return Optional.of(taken);
    }

    /** Lower is assigned first. */
    static int specificity(Need need) {
        return switch (need.ingredient()) {
            case RecipeDef.Item _ -> 0;
            case RecipeDef.Vanilla _ -> 0;
            case RecipeDef.FishMatch match -> {
                if (!match.fishAny().isEmpty()) {
                    yield 1;
                }
                Grade min = match.gradeMin();
                // Higher grade conditions are narrower: mythic 2 … common 7
                int grade =
                        min == null
                                ? 7
                                : switch (min) {
                                    case MYTHIC -> 1;
                                    case LEGENDARY -> 2;
                                    case EPIC -> 3;
                                    case RARE -> 4;
                                    case UNCOMMON -> 5;
                                    case COMMON -> 6;
                                };
                yield 1 + grade + (match.region() == null ? 1 : 0);
            }
        };
    }
}
