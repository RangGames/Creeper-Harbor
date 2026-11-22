package wiki.creeper.harbor.collection.view;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import wiki.creeper.harbor.collection.content.AquariumRules;
import wiki.creeper.harbor.common.id.FishId;

/**
 * Aquarium progress screen computation (proposed, no server API). Donation rate threshold and
 * achievement per stage, species remaining until the next stage, donated/remaining species filters,
 * and which species can be handed in straight from the inventory.
 */
public final class AquariumView {

    /** Species filter. */
    public enum Filter {
        MISSING,
        DONATED,
        ALL;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public Filter next() {
            return switch (this) {
                case MISSING -> DONATED;
                case DONATED -> ALL;
                case ALL -> MISSING;
            };
        }
    }

    /**
     * One stage row. needed is the species count required for this stage (donatable species ×
     * threshold, rounded up).
     */
    public record StageRow(AquariumRules.Stage stage, int needed, boolean reached, boolean next) {}

    /**
     * One species cell.
     *
     * @param inventorySlot first player inventory slot holding this species (-1 if none)
     */
    public record SpeciesRow(FishId fish, int no, boolean donated, int inventorySlot) {

        public boolean inInventory() {
            return inventorySlot >= 0;
        }
    }

    private AquariumView() {}

    /**
     * Stage table (by stage number). Stages up to the current one are reached; the one after is
     * next.
     */
    public static List<StageRow> stages(AquariumRules rules, int currentStage, int donatable) {
        List<StageRow> out = new ArrayList<>();
        boolean nextMarked = false;
        List<AquariumRules.Stage> sorted = new ArrayList<>(rules.stages());
        sorted.sort(Comparator.comparingInt(AquariumRules.Stage::stage));
        for (AquariumRules.Stage s : sorted) {
            boolean reached = s.stage() <= currentStage;
            boolean next = !reached && !nextMarked;
            if (next) {
                nextMarked = true;
            }
            out.add(new StageRow(s, needed(s.rate(), donatable), reached, next));
        }
        return List.copyOf(out);
    }

    /** Species count needed to pass the threshold (rounded up). */
    public static int needed(double rate, int donatable) {
        return (int) Math.ceil(rate * donatable - 1e-9);
    }

    /** Additional species needed for the next stage (empty if there is no next stage). */
    public static Optional<Integer> remaining(List<StageRow> stages, int species) {
        return stages.stream()
                .filter(StageRow::next)
                .findFirst()
                .map(r -> Math.max(0, r.needed() - species));
    }

    /**
     * Species list (by number). Species in the inventory come first (MISSING·ALL).
     *
     * @param donatable donatable species and their numbers
     * @param inventory species in the inventory → first slot
     */
    public static List<SpeciesRow> species(
            Map<FishId, Integer> donatable,
            Set<FishId> donated,
            Map<FishId, Integer> inventory,
            Filter filter) {
        List<SpeciesRow> out = new ArrayList<>();
        donatable.forEach(
                (fish, no) -> {
                    boolean d = donated.contains(fish);
                    boolean keep =
                            switch (filter) {
                                case ALL -> true;
                                case DONATED -> d;
                                case MISSING -> !d;
                            };
                    if (keep) {
                        out.add(new SpeciesRow(fish, no, d, inventory.getOrDefault(fish, -1)));
                    }
                });
        out.sort(
                Comparator.comparing((SpeciesRow r) -> !r.inInventory())
                        .thenComparingInt(SpeciesRow::no));
        return out;
    }
}
