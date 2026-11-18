package wiki.creeper.harbor.world.api;

import java.util.UUID;

/**
 * 선박 스킨 장착 여부 (추가 제안). 선박 스킨 코스메틱은 Shop 소유이고 World는 Shop에 의존하지 않는다 (docs 20 0.3). Shop이 이 인터페이스를
 * 서비스로 등록하면, 스킨을 장착한 플레이어의 보트에는 World 기본 장식 리그를 띄우지 않는다 (Shop이 탑승 때 스킨 리그를 태운다). 메인 스레드.
 */
@FunctionalInterface
public interface ShipSkinProvider {

    boolean hasBoatSkin(UUID player);
}
