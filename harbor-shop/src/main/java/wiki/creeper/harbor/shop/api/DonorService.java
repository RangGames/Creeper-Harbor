package wiki.creeper.harbor.shop.api;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.donor.DonorTier;

/**
 * 후원 등급 (docs 20 12.2). 구독 상태는 Shop의 subscriptions가 원본이고, Redis 해시 harbor:donor를 늘 맞춰 둔다 (Proxy
 * 대기열·World·Collection은 Common {@link DonorTier#lookup}으로 읽는다). 모든 혜택은 비성능이다.
 */
public interface DonorService {

    /**
     * 상태형 혜택 (구독 기간에만).
     *
     * @param nameColor 이름 색 MiniMessage 여는 태그 (없으면 빈 문자열)
     * @param chatIcon 채팅 아이콘 글리프
     * @param title 후원자 칭호 ID
     */
    record Perks(
            DonorTier tier,
            String nameColor,
            String chatIcon,
            @Nullable String title,
            boolean queuePriority,
            int extraThemeSlots) {

        public static final Perks NONE = new Perks(DonorTier.NONE, "", "", null, false, 0);
    }

    /** 접속 중인 플레이어의 등급 (캐시, 메인 스레드). 접속 중이 아니면 NONE (그때는 DonorTier.lookup을 쓴다). */
    DonorTier tier(UUID player);

    Perks perks(UUID player);
}
