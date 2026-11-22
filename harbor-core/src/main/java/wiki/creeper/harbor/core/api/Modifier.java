package wiki.creeper.harbor.core.api;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The standard way for skills, buffs, events and collection rewards to change values.
 *
 * <p>Calculation order: base → sum of ADD → product of MULTIPLY → max/min clamp. MULTIPLY values
 * are multipliers (1.1 = +10%).
 *
 * @param source source (e.g. {@code skill:collect/legend_breath}, {@code event:storm}). All
 *     modifiers with the same source can be removed at once.
 * @param expiresAt expiry time (null = permanent)
 */
public record Modifier(
        StatKey stat, Op op, double value, String source, @Nullable Instant expiresAt) {

    /** Operation. */
    public enum Op {
        ADD,
        MULTIPLY
    }

    public Modifier {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("보정 값이 숫자가 아닙니다: " + value);
        }
        if (source.isBlank()) {
            throw new IllegalArgumentException("출처가 비었습니다");
        }
    }

    public static Modifier add(StatKey stat, double value, String source) {
        return new Modifier(stat, Op.ADD, value, source, null);
    }

    public static Modifier multiply(StatKey stat, double factor, String source) {
        return new Modifier(stat, Op.MULTIPLY, factor, source, null);
    }

    public Modifier until(Instant expiresAt) {
        return new Modifier(stat, op, value, source, expiresAt);
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}
