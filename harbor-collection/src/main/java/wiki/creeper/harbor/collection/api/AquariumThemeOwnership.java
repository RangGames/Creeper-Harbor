package wiki.creeper.harbor.collection.api;

import java.util.UUID;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * 어항 테마 보유 확인 (추가 제안). 어항 테마 코스메틱의 소유는 Shop(cosmetics_owned)이다. Shop이 Collection에 의존하므로 Collection은
 * Shop을 컴파일 의존으로 둘 수 없다. Shop이 이 인터페이스를 서비스로 등록하면 그것을 쓰고, 없으면 Collection이 Shop
 * CosmeticService.owns를 서비스 이름으로 찾아 부른다. 메인 스레드, 접속 중인 플레이어만.
 */
@FunctionalInterface
public interface AquariumThemeOwnership {

    boolean owns(UUID player, HarborId cosmetic);
}
