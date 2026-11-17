package wiki.creeper.harbor.core.api;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * 스킬·버프·이벤트·도감 보상이 수치를 바꾸는 표준 방법.
 *
 * <p>계산 순서: 기본값 → ADD 합 → MULTIPLY 곱 → 상한·하한. MULTIPLY의 값은 배율이다 (1.1 = +10%).
 *
 * @param source 출처 (예: {@code skill:collect/legend_breath}, {@code event:storm}). 같은 출처는 한 번에 지울 수
 *     있다.
 * @param expiresAt 만료 시각 (null = 영구)
 */
public record Modifier(
        StatKey stat, Op op, double value, String source, @Nullable Instant expiresAt) {

    /** 연산. */
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
