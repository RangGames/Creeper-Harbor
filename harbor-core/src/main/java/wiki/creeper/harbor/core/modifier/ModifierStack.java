package wiki.creeper.harbor.core.modifier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import wiki.creeper.harbor.core.api.Modifier;
import wiki.creeper.harbor.core.api.ModifierService.Breakdown;

/**
 * Modifier calculation (no server API, unit-tested). Base value → ADD sum → MULTIPLY product →
 * upper/lower bounds.
 */
public final class ModifierStack {

    private ModifierStack() {}

    public static Breakdown compute(
            double base, List<Modifier> modifiers, double min, double max, Instant now) {
        double add = 0;
        double multiply = 1;
        List<Modifier> applied = new ArrayList<>();
        for (Modifier modifier : modifiers) {
            if (modifier.isExpired(now)) {
                continue;
            }
            switch (modifier.op()) {
                case ADD -> add += modifier.value();
                case MULTIPLY -> multiply *= modifier.value();
            }
            applied.add(modifier);
        }
        double result = Math.clamp((base + add) * multiply, min, max);
        return new Breakdown(base, result, List.copyOf(applied), min, max);
    }
}
