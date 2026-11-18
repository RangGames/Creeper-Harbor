package wiki.creeper.harbor.boss.api;

import wiki.creeper.harbor.common.id.BossId;

/**
 * 월드 보스 진행 (harbor:worldboss 방송 내용과 같다).
 *
 * @param eventId 이번 월드 보스 ID (조우 ID)
 * @param stage 0~4 (25% 단위 단계: 0 = 시작, 1 = 75% 이하, 2 = 50% 이하, 3 = 25% 이하, 4 = 끝)
 * @param percent 남은 게이지 (100 → 0)
 * @param participants 유효 참여자 수 (최근 60초 기여)
 * @param server 실행 서버
 */
public record WorldBossStatus(
        BossId boss,
        String eventId,
        int stage,
        double percent,
        int participants,
        String server,
        EncounterState state) {}
