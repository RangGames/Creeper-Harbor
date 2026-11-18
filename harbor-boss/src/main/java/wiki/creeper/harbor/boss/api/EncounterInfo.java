package wiki.creeper.harbor.boss.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import wiki.creeper.harbor.common.id.BossId;

/**
 * 조우 하나의 읽기 전용 모습 (호출 시점 값).
 *
 * @param members 참가자 (파티장 먼저). 월드 보스는 지금까지 기여한 사람
 * @param roles 참가자 역할
 * @param slot 경기장 슬롯 번호 (월드 보스 0)
 * @param hpRatio 체력(calm 보스는 분노·폭발 게이지) 비율 1 → 0
 */
public record EncounterInfo(
        UUID id,
        BossId boss,
        EncounterState state,
        UUID leader,
        List<UUID> members,
        Map<UUID, BossRole> roles,
        String server,
        int slot,
        double hpRatio,
        int hull,
        int hullMax,
        long elapsedTicks) {

    public EncounterInfo {
        members = List.copyOf(members);
        roles = Map.copyOf(roles);
    }
}
