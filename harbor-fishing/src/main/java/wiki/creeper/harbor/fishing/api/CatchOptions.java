package wiki.creeper.harbor.fishing.api;

/**
 * {@link FishingService#createCatch} 설정 (Boss 보스 어종 확정 포획).
 *
 * @param qualityBonusStars 판정된 품질에 더하는 별 수 (보스전 품질 보너스). 직업 상한은 넘지 않는다
 * @param extraLuck 크기 판정 행운에 더하는 값
 * @param fireCaughtEvent FishCaughtEvent(source BOSS)를 보낼지 (Collection 도감·Quest 목표 처리)
 */
public record CatchOptions(int qualityBonusStars, double extraLuck, boolean fireCaughtEvent) {

    public static final CatchOptions BOSS = new CatchOptions(1, 0, true);

    public CatchOptions {
        if (qualityBonusStars < 0) {
            throw new IllegalArgumentException("qualityBonusStars >= 0");
        }
    }
}
