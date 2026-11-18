package wiki.creeper.harbor.common.donor;

import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.redis.Redis;

/**
 * 후원 등급 ('6. BM 기획' 3.2, docs 20 0.1). 구독 정의(subscriptions)는 Shop이 갖고, 조회는 여기서 한다. Proxy 대기열과
 * World·Collection 어항 테마 슬롯이 Shop에 의존하지 않고 쓴다. 모든 혜택은 비성능이다.
 *
 * <p>Shop은 구독 상태가 바뀔 때마다 Redis 해시 {@code harbor:donor}(필드 = UUID, 값 = 등급 이름)를 갱신한다. 3일 유예는 Shop이 값을
 * 지우는 시점에 반영한다. Redis에 값이 없으면 NONE으로 본다 (DB 대조는 Shop이 시작할 때 다시 채운다).
 */
public enum DonorTier {
    NONE(0, 0),
    /** 항구 후원자: 이름 색·칭호, 채팅 이모지, 대기열 우선, 월 진주 300. */
    HARBOR(1, 0),
    /** 선단 후원자: 위 혜택 + 깃발·돛 문양, 어항 테마 슬롯 +2(외형 칸), 월 진주 700. */
    FLEET(2, 2);

    public static final String REDIS_KEY = "harbor:donor";

    private final int rank;
    private final int extraThemeSlots;

    DonorTier(int rank, int extraThemeSlots) {
        this.rank = rank;
        this.extraThemeSlots = extraThemeSlots;
    }

    public int rank() {
        return rank;
    }

    /** 어항 꾸미기 테마 칸 추가 수 (전시 칸 수와 별개, D14). */
    public int extraThemeSlots() {
        return extraThemeSlots;
    }

    public boolean atLeast(DonorTier other) {
        return rank >= other.rank;
    }

    /** 대기열 우선 대상인지. */
    public boolean queuePriority() {
        return rank >= HARBOR.rank;
    }

    public static DonorTier parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }

    /** Redis에서 읽는다 (입출력이므로 메인 스레드에서 부르지 않는다). 장애 시 NONE. */
    public static DonorTier lookup(Redis redis, UUID player) {
        try {
            return parse(redis.sync().hget(REDIS_KEY, player.toString()));
        } catch (RuntimeException e) {
            return NONE;
        }
    }
}
