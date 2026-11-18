package wiki.creeper.harbor.proxy;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 다계정 판정 규칙 (docs 20 1.4 AltDetector). 서버 API·DB 없는 순수 로직.
 *
 * <ul>
 *   <li>같은 ip_hash로 최근 window_days(30일, 제안값) 안에 들어온 다른 계정이 연결 계정 후보
 *   <li>그 IP의 계정 수(자신 포함)가 alert_accounts(3, 제안값) 이상이고 공유 IP가 아니면 운영진 경보
 *   <li>자동 차단은 하지 않는다 (PC방 공유 IP 오탐 방지)
 *   <li>같은 IP에 대해 경보는 계정 수가 늘었을 때만 다시 보낸다 (로그인마다 반복하지 않음)
 * </ul>
 */
final class AltHeuristics {

    private AltHeuristics() {}

    /** 같은 IP로 들어온 계정 중 자신을 뺀 연결 후보 (중복 제거, 순서 유지). */
    static Set<UUID> partners(UUID self, Collection<UUID> sameIp) {
        Set<UUID> result = new LinkedHashSet<>(sameIp);
        result.remove(self);
        return result;
    }

    /** 그 IP의 계정 수 (자신 포함). */
    static int accounts(UUID self, Collection<UUID> sameIp) {
        return partners(self, sameIp).size() + 1;
    }

    /**
     * 경보를 보낼지.
     *
     * @param accounts 그 IP의 계정 수 (자신 포함)
     * @param sharedIp ip_shared 등록 여부
     * @param lastAlerted 이 IP로 마지막 경보 때의 계정 수 (없으면 0)
     * @param threshold 경보 기준 계정 수
     */
    static boolean shouldAlert(int accounts, boolean sharedIp, int lastAlerted, int threshold) {
        return !sharedIp && accounts >= threshold && accounts > lastAlerted;
    }
}
