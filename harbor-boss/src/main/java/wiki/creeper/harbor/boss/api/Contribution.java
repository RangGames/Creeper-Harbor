package wiki.creeper.harbor.boss.api;

import java.util.UUID;

/**
 * 참가자 한 명의 기여도 (boss_participation 한 행).
 *
 * @param tension 텐션 안전 구간 유지 시간 (초)
 * @param repair 복구 내구도 + 보급관 지원량
 * @param evasion 회피 성공 + 예고 범위 회피
 * @param rank 1부터
 * @param top 상위 20% 추가 보상 대상
 * @param eligible 최소 참여를 넘겼는지
 */
public record Contribution(
        UUID player,
        BossRole role,
        double damage,
        double tension,
        double repair,
        double evasion,
        double score,
        int rank,
        boolean top,
        boolean eligible) {}
