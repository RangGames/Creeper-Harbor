package wiki.creeper.harbor.fishing.trap;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.Quality;

/**
 * Trap calculation (docs 20 3.4 TrapService, "7. Balance design" chapter 8, decision D1). Uses no
 * server API (unit tests).
 *
 * <ul>
 *   <li>Cycle = 8 hours × cycle_mult + trap_cycle_hours (trap turnover −1/−2 hours, floor
 *       min_cycle_h)
 *   <li>Accumulated cycles = min(floor((now − last_calc) ÷ cycle), 2). Time beyond 2 cycles of
 *       absence is discarded
 *   <li>Each cycle yields 1 fish per slot that still has bait (slots + expansion + trap_slots), 1
 *       bait per slot. A bountiful trap adds 1 fish per cycle by chance (no bait)
 *   <li>Income cap: sum of 24-hour yield value of all the account's traps ≤ manual fishing gold per
 *       hour × ratio (0.4 / production 2nd tier 0.5 / 3rd tier 0.6). Beyond it, remaining slots
 *       yield nothing and use no bait
 * </ul>
 */
public final class TrapMath {

    /**
     * Trap item stats (items with type: trap).
     *
     * @param slots base slots (wood 3 · iron 6 · coral 9 · deep sea 12)
     * @param maxGrade highest yield grade (stats.max_grade 1 common ~ 4 epic)
     * @param cycleMult cycle multiplier (coral 0.9)
     * @param tier trap tier (wood 1 · iron 2 · coral 3 · deep sea 4, slot expansion price)
     * @param deep deep sea trap (placement requires feature_deep_trap)
     */
    public record Spec(int slots, Grade maxGrade, double cycleMult, int tier, boolean deep) {

        public static Spec of(Map<String, Double> stats) {
            int maxGrade = (int) Math.round(stats.getOrDefault("max_grade", 1.0));
            Grade grade =
                    switch (Math.clamp(maxGrade, 1, 4)) {
                        case 1 -> Grade.COMMON;
                        case 2 -> Grade.UNCOMMON;
                        case 3 -> Grade.RARE;
                        default -> Grade.EPIC;
                    };
            return new Spec(
                    (int) Math.max(1, Math.round(stats.getOrDefault("slots", 3.0))),
                    grade,
                    Math.max(0.1, stats.getOrDefault("cycle_mult", 1.0)),
                    (int) Math.max(1, Math.round(stats.getOrDefault("tier", 1.0))),
                    stats.getOrDefault("deep", 0.0) > 0);
        }
    }

    /** One fish to yield (species, size, quality, value). */
    public record Rolled(FishDef fish, double sizeCm, Quality quality, double value) {}

    /** Function that rolls one species. */
    @FunctionalInterface
    public interface Roller {
        Rolled next();
    }

    /** Accumulated cycles and the new last_calc. */
    public record Accrual(int cycles, Instant lastCalc) {}

    /**
     * Yield result.
     *
     * @param baitUsed bait used
     * @param value value added this time (added to value_24h)
     * @param capReached whether it stopped at the account income cap
     */
    public record Production(
            List<TrapCatch> catches, int baitUsed, double value, boolean capReached) {}

    private TrapMath() {}

    /** Cycle length. delta is the trap_cycle_hours stat (negative with trap turnover). */
    public static Duration cycle(
            double baseHours, double cycleMult, double delta, double minHours) {
        double hours = Math.max(minHours, baseHours * cycleMult + delta);
        return Duration.ofMillis(Math.round(hours * 3_600_000));
    }

    public static Accrual accrue(Instant lastCalc, Instant now, Duration cycle, int maxCycles) {
        if (!now.isAfter(lastCalc) || cycle.isZero() || cycle.isNegative()) {
            return new Accrual(0, lastCalc);
        }
        long raw = Duration.between(lastCalc, now).toMillis() / cycle.toMillis();
        if (raw >= maxCycles) {
            // If absent beyond the maximum cycles, discard the excess time and count again from now
            return new Accrual(maxCycles, now);
        }
        return new Accrual((int) raw, lastCalc.plus(cycle.multipliedBy(raw)));
    }

    /** Value of one trap within its 24-hour window (0 if the window has passed). */
    public static double windowValue(
            double value, Instant windowStart, Instant now, Duration window) {
        return now.isBefore(windowStart.plus(window)) ? value : 0;
    }

    /** Account income cap (G). */
    public static double accountCap(double hourlyGold, double ratio) {
        return Math.max(0, hourlyGold) * Math.max(0, ratio);
    }

    /** Slots used per cycle = trap slots + expansion + job bonus (trap_slots, per trap). */
    public static int effectiveSlots(int baseSlots, int extraSlots, double bonusSlots) {
        return Math.max(0, baseSlots + extraSlots + (int) Math.floor(bonusSlots));
    }

    /** Placeable count = base + trap_count (up to max). */
    public static int maxTraps(int base, double trapCount, int max) {
        return Math.min(max, base + (int) Math.floor(Math.max(0, trapCount)));
    }

    /**
     * Yields for the given cycles.
     *
     * @param bait remaining bait
     * @param accountUsed the account's 24-hour yield value excluding this trap + this trap's
     *     in-window value
     * @param accountCap account income cap
     */
    public static Production produce(
            int cycles,
            int slots,
            int bait,
            int baitPerSlot,
            double extraChance,
            double accountUsed,
            double accountCap,
            Roller roller,
            Instant at,
            RandomGenerator random) {
        List<TrapCatch> catches = new ArrayList<>();
        int baitLeft = bait;
        double used = accountUsed;
        double added = 0;
        boolean capped = false;
        outer:
        for (int cycle = 0; cycle < cycles; cycle++) {
            int perSlot = Math.max(1, baitPerSlot);
            int filled = Math.min(slots, baitLeft / perSlot);
            for (int i = 0; i < filled; i++) {
                Rolled r = roller.next();
                if (used + r.value() > accountCap) {
                    capped = true;
                    break outer;
                }
                used += r.value();
                added += r.value();
                baitLeft -= perSlot;
                catches.add(toCatch(r, at));
            }
            // Bountiful trap: +1 fish per cycle by chance (when at least one slot used bait)
            if (filled > 0 && extraChance > 0 && random.nextDouble() < extraChance) {
                Rolled r = roller.next();
                if (used + r.value() > accountCap) {
                    capped = true;
                    break;
                }
                used += r.value();
                added += r.value();
                catches.add(toCatch(r, at));
            }
        }
        return new Production(List.copyOf(catches), bait - baitLeft, added, capped);
    }

    private static TrapCatch toCatch(Rolled r, Instant at) {
        return new TrapCatch(
                UUID.randomUUID().toString(),
                r.fish().id().toString(),
                r.sizeCm(),
                r.quality().stars(),
                at.toEpochMilli());
    }
}
