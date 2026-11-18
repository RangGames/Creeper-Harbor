package wiki.creeper.harbor.season.api;

/**
 * 주간 제한 결과.
 *
 * @param consumed 이번 호출로 1회 썼는지 (remaining 조회면 항상 false)
 * @param used 이번 주 사용 횟수 (호출 뒤 값)
 * @param limit 한도
 * @param week 기록한 시즌 주차
 */
public record LimitResult(boolean consumed, int used, int limit, int week) {

    /** 남은 횟수 (0 이상). */
    public int remaining() {
        return Math.max(0, limit - used);
    }

    /** 이번 주 한도에 닿았는지. */
    public boolean exhausted() {
        return used >= limit;
    }
}
