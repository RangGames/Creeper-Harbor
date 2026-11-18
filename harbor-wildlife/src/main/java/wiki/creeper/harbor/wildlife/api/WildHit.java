package wiki.creeper.harbor.wildlife.api;

import org.bukkit.Location;

/**
 * {@link WildlifeService#raycast} 명중 결과. 무엇을 줄지는 호출한 모듈이 정한다 (작살 투척·보스 연출).
 *
 * @param point 광선이 몸 구에 처음 닿은 지점
 * @param distance 시점에서 명중 지점까지 거리
 */
public record WildHit(WildFish fish, Location point, double distance) {

    public WildHit {
        point = point.clone();
    }

    @Override
    public Location point() {
        return point.clone();
    }
}
