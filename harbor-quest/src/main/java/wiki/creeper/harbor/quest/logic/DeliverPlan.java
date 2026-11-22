package wiki.creeper.harbor.quest.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import wiki.creeper.harbor.quest.content.Objective;

/**
 * Item selection for deliver objectives (docs 20 6.4): nothing is counted in advance; the inventory
 * is checked on turn-in. Matching items are picked lowest quality first, and one item serves only
 * one objective. Pure computation (the inventory comes in as a list of {@link Slot}).
 */
public final class DeliverPlan {

    /**
     * One inventory slot.
     *
     * @param precious ★5 or unique-ID item (goes through a confirmation Dialog)
     */
    public record Slot(int index, CatchFacts facts, int amount, boolean precious) {}

    /**
     * Result.
     *
     * @param take slot number → amount to take
     * @param missing objective key → missing amount (empty means satisfied)
     */
    public record Result(
            Map<Integer, Integer> take, Map<String, Integer> missing, boolean precious) {
        public boolean ok() {
            return missing.isEmpty();
        }
    }

    private DeliverPlan() {}

    public static Result plan(List<Objective> objectives, List<Slot> slots) {
        Map<Integer, Integer> left = new HashMap<>();
        slots.forEach(s -> left.put(s.index(), s.amount()));
        Map<Integer, Integer> take = new LinkedHashMap<>();
        Map<String, Integer> missing = new LinkedHashMap<>();
        boolean precious = false;
        List<Slot> sorted = new ArrayList<>(slots);
        // lowest quality first → precious items last → slot order
        sorted.sort(
                Comparator.comparingInt((Slot s) -> s.facts().quality())
                        .thenComparing(Slot::precious)
                        .thenComparingInt(Slot::index));
        for (Objective o : objectives) {
            if (o.tracked()) {
                continue;
            }
            int need = o.count();
            for (Slot s : sorted) {
                if (need <= 0) {
                    break;
                }
                int available = left.getOrDefault(s.index(), 0);
                if (available <= 0 || !Matchers.matchesFish(o, s.facts())) {
                    continue;
                }
                int used = Math.min(available, need);
                left.put(s.index(), available - used);
                take.merge(s.index(), used, Integer::sum);
                need -= used;
                precious |= s.precious();
            }
            if (need > 0) {
                missing.put(o.key(), need);
            }
        }
        return new Result(take, missing, precious);
    }
}
