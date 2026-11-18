package wiki.creeper.harbor.world.api;

import java.util.List;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * 지역 진입 조건 판정 결과 ('1. 기획서 본문' 2장, docs 20 8.4 '진입 조건'). 밀어내기 Dialog가 "Lv 18 / 20"처럼 진행도를 보여 준다.
 *
 * @param missing 못 채운 조건 (비면 들어갈 수 있다)
 */
public record UnlockStatus(RegionId region, List<Requirement> missing) {

    /** 조건 종류. */
    public enum Kind {
        LEVEL,
        JOB_TIER,
        QUEST,
        FLAG,
        ITEM,
        VEHICLE
    }

    /**
     * 못 채운 조건 하나.
     *
     * @param detail 대상 (아이템 이름, 플래그, 선박 이름 …)
     * @param have 지금 값 (레벨·단계, 그 밖에는 0)
     * @param need 필요한 값 (레벨·단계, 그 밖에는 1)
     */
    public record Requirement(Kind kind, String detail, int have, int need) {}

    public UnlockStatus {
        missing = List.copyOf(missing);
    }

    public boolean allowed() {
        return missing.isEmpty();
    }

    /** 탈것 조건만 빠졌는지 (비행선으로는 못 가지만 배로는 갈 수 있는 상태). */
    public boolean onlyVehicleMissing() {
        return !missing.isEmpty() && missing.stream().allMatch(r -> r.kind() == Kind.VEHICLE);
    }
}
