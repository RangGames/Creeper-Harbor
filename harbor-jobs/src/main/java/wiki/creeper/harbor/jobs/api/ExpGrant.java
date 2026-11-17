package wiki.creeper.harbor.jobs.api;

import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * 경험치 지급 요청 (docs 20 2.2).
 *
 * @param base 출처 모듈이 계산한 기본 EXP (Fishing: 등급 기본 EXP × 지역 배율 × 첫 포획 배율)
 * @param region 지역 감쇠 판정용 (FISHING만)
 * @param refId 기록용 참조 ID
 */
public record ExpGrant(
        ExpSource source, double base, @Nullable RegionId region, @Nullable String refId) {

    public static ExpGrant of(ExpSource source, double base) {
        return new ExpGrant(source, base, null, null);
    }
}
