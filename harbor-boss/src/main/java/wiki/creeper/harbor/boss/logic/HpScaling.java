package wiki.creeper.harbor.boss.logic;

import java.util.Map;
import java.util.UUID;

/** 체력 공식 ('7. 밸런스 기획' 9장, docs 20 10.4). */
public final class HpScaling {

    private HpScaling() {}

    /**
     * 파티 보스 최대 체력 = hp.base × (1 + 인원당 증가율 × (인원 − 1)). 인원은 시작 시점 파티 인원으로 고정한다.
     *
     * @param players 1 이상 (0 이하는 1로 본다)
     */
    public static double partyMaxHp(double base, double perPlayerRate, int players) {
        int n = Math.max(1, players);
        return base * (1 + perPlayerRate * (n - 1));
    }

    /** 월드 보스 최대 게이지 = 1인 기준 × max(유효 참여자 수, 최소 인원) (D13, 최소 10명 확정). */
    public static double worldMaxHp(double base, int effectiveParticipants, int minParticipants) {
        return base * Math.max(Math.max(1, minParticipants), effectiveParticipants);
    }

    /**
     * 유효 참여자 = 최근 window틱 안에 기여 활동이 있는 사람.
     *
     * @param lastActivity 플레이어 → 마지막 기여 틱
     */
    public static int effectiveParticipants(Map<UUID, Long> lastActivity, long now, long window) {
        int n = 0;
        for (long at : lastActivity.values()) {
            if (now - at <= window) {
                n++;
            }
        }
        return n;
    }
}
