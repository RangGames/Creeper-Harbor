package wiki.creeper.harbor.fishing.api;

import java.util.UUID;
import wiki.creeper.harbor.common.id.FishId;

/**
 * 도감 조회 SPI (docs 20 3.2). Collection이 {@link FishingService#registerCodex}로 등록한다. 미등록 가중치(도감 사냥꾼)와
 * 첫 포획 배율에 쓴다. 등록되지 않았으면 항상 false.
 */
@FunctionalInterface
public interface CodexProvider {
    boolean hasCaught(UUID player, FishId fish);
}
