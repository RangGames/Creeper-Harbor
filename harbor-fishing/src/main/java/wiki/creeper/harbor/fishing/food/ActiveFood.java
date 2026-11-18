package wiki.creeper.harbor.fishing.food;

import java.time.Instant;
import wiki.creeper.harbor.common.id.ItemId;

/** 지금 걸린 요리 버프 (1종만). */
public record ActiveFood(ItemId item, Instant expiresAt) {

    /** 보정자 출처 (food:<요리 이름>). */
    public String source() {
        return FoodRule.source(item);
    }

    public boolean isActive(Instant now) {
        return now.isBefore(expiresAt);
    }
}
