package wiki.creeper.harbor.season.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * 명예의 전당 잠정 기록 제출 (docs 20 11.4).
 *
 * @param category hall_of_fame.yml의 category (예 {@code biggest_fish}, {@code fastest_boss})
 * @param subject 대상 (어종 ID, 보스 ID). 분류에 대상이 없으면 null
 * @param player 기록한 플레이어 (파티 기록이면 파티장)
 * @param name 기록 당시 이름 (파티면 "파티장 외 N명" 등 표시 문구)
 * @param value 값 (크기 cm, 처치 시간 ms …). 좋고 나쁨은 분류의 order로 정한다
 * @param detail 상세 (Dialog에 보여줄 키-값. 예: quality=5, members=uuid,uuid)
 * @param achievedAt 달성 시각 (동점이면 먼저 달성한 기록이 앞선다)
 */
public record HallOfFameSubmission(
        String category,
        @Nullable HarborId subject,
        UUID player,
        String name,
        double value,
        Map<String, String> detail,
        Instant achievedAt) {

    public HallOfFameSubmission {
        detail = Map.copyOf(detail);
    }

    /** Collection: 어종 서버 최대어 갱신 (전체 리그 1위). */
    public static HallOfFameSubmission biggestFish(
            HarborId fish, UUID player, String name, double sizeCm, Instant caughtAt) {
        return new HallOfFameSubmission(
                "biggest_fish", fish, player, name, sizeCm, Map.of(), caughtAt);
    }

    /** Boss: CLEARED 조우의 최단 처치 기록. */
    public static HallOfFameSubmission fastestBoss(
            HarborId boss,
            UUID leader,
            String name,
            long durationMs,
            Map<String, String> detail,
            Instant clearedAt) {
        return new HallOfFameSubmission(
                "fastest_boss", boss, leader, name, durationMs, detail, clearedAt);
    }
}
