package wiki.creeper.harbor.economy.api;

import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.UUID;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.core.api.HarborItem;

/**
 * 어종 판매가 (docs 20 7.4): 기본가 × 품질 배율 × 시세 계수 M × (1 + sell_price 보정 합) × 지역 계수. 전설은 마리당 상한, 신화·
 * release_only는 판매 불가. 소수점 아래는 버린다.
 */
public interface PriceService {

    /** 판매 불가면 빈 값. player가 있으면 그 사람의 sell_price 보정을 넣는다. */
    OptionalLong quote(UUID player, HarborItem.Fish fish);

    /** 개인 보너스 없는 기준가 (경매 기준가). */
    OptionalLong basePrice(HarborItem.Fish fish);

    /** 현재 시세 계수 (기본 1.0). */
    double coefficient(FishId fish);

    /** 다음 시간 예측 계수 (경제 계열 '시세 예측'). 아직 계산된 적이 없으면 빈 값. */
    OptionalDouble nextCoefficient(FishId fish);
}
