package wiki.creeper.harbor.shop.donor;

import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.donor.DonorTier;
import wiki.creeper.harbor.common.model.Keyed;

/**
 * 후원 구독 상태 계산 (docs 20 12.4 '후원 등급', 서버 없이 테스트한다). 혜택은 period_end + 유예(3일) 전까지 유지된다. 해지
 * 요청(CANCEL_PENDING)도 기간 끝까지 유지하고, 그 뒤 유예가 지나면 ENDED로 바꾼다. 갱신 결제가 늦어도 유예 동안은 혜택이 남는다.
 */
public final class DonorGrace {

    /** subscriptions.state. */
    public enum State implements Keyed {
        ACTIVE,
        CANCEL_PENDING,
        ENDED;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }

        public static State parse(@Nullable String raw) {
            if (raw == null) {
                return ENDED;
            }
            try {
                return valueOf(raw);
            } catch (IllegalArgumentException e) {
                return ENDED;
            }
        }
    }

    private DonorGrace() {}

    /** 지금 적용할 등급. */
    public static DonorTier effective(
            State state, DonorTier tier, @Nullable Instant periodEnd, Instant now, Duration grace) {
        if (state == State.ENDED || tier == DonorTier.NONE || periodEnd == null) {
            return DonorTier.NONE;
        }
        return now.isBefore(periodEnd.plus(grace)) ? tier : DonorTier.NONE;
    }

    /** 정리 작업이 ENDED로 바꿔야 하는지 (유예까지 지남). */
    public static boolean shouldEnd(
            State state, @Nullable Instant periodEnd, Instant now, Duration grace) {
        if (state == State.ENDED) {
            return false;
        }
        return periodEnd == null || !now.isBefore(periodEnd.plus(grace));
    }

    /** 갱신 결제 뒤 새 기간 끝. 남은 기간이 있으면 그 뒤에 이어 붙인다 (일찍 갱신해도 손해 없음). */
    public static Instant renewedEnd(@Nullable Instant periodEnd, Instant now, Duration period) {
        Instant base = periodEnd != null && periodEnd.isAfter(now) ? periodEnd : now;
        return base.plus(period);
    }
}
