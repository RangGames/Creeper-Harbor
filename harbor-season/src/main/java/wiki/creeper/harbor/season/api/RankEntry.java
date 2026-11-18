package wiki.creeper.harbor.season.api;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 랭킹 한 줄.
 *
 * @param rank 1부터. 같은 값이면 먼저 달성한 사람이 앞선다 (동점 없음)
 * @param uuid 플레이어. 파티 기록(보스 최단 처치)이면 파티장
 * @param name 표시 이름 (파티면 "파티장 외 N명" 같은 문구는 화면에서 만든다)
 * @param value 점수 (크기 cm, 레벨, 도감 수, 처치 시간 ms …)
 * @param secondary 보조 값 (레벨 보드의 경험치 등, 없으면 0)
 * @param achievedAt 달성 시각 (동점 처리 기준)
 * @param members 파티 기록의 구성원 수 (개인 기록이면 1)
 */
public record RankEntry(
        int rank,
        @Nullable UUID uuid,
        String name,
        double value,
        double secondary,
        Instant achievedAt,
        int members) {}
