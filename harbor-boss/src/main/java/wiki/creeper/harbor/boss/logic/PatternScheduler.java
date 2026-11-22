package wiki.creeper.harbor.boss.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.boss.content.BossDef;

/**
 * Per-phase pattern selection (docs 20 10.4): picks by YAML weight, never the same pattern twice in
 * a row, cooldowns counted per pattern. The phase's minimum gap is kept between patterns.
 */
public final class PatternScheduler {

    private final Map<String, Long> readyAt = new HashMap<>();
    private @Nullable String last;
    private long nextAllowed;

    /** Next pattern. Empty if none can be picked now because of gaps or cooldowns. */
    public Optional<BossDef.PatternDef> next(
            List<BossDef.PatternDef> candidates, long now, int gapTicks, SplittableRandom random) {
        if (now < nextAllowed || candidates.isEmpty()) {
            return Optional.empty();
        }
        List<BossDef.PatternDef> ready = new ArrayList<>();
        for (BossDef.PatternDef p : candidates) {
            if (readyAt.getOrDefault(p.id(), Long.MIN_VALUE) > now) {
                continue;
            }
            // No repeats: phases with only one pattern are exempt
            if (candidates.size() > 1 && p.id().equals(last)) {
                continue;
            }
            ready.add(p);
        }
        if (ready.isEmpty()) {
            return Optional.empty();
        }
        int total = 0;
        for (BossDef.PatternDef p : ready) {
            total += p.weight();
        }
        int pick = random.nextInt(total);
        BossDef.PatternDef chosen = ready.getLast();
        for (BossDef.PatternDef p : ready) {
            pick -= p.weight();
            if (pick < 0) {
                chosen = p;
                break;
            }
        }
        readyAt.put(chosen.id(), now + chosen.cooldownTicks());
        last = chosen.id();
        nextAllowed = now + Math.max(1, gapTicks);
        return Optional.of(chosen);
    }

    /**
     * Pushes back the minimum wait until the next pattern (breathing room right after a phase
     * change).
     */
    public void delay(long until) {
        nextAllowed = Math.max(nextAllowed, until);
    }

    public @Nullable String last() {
        return last;
    }
}
