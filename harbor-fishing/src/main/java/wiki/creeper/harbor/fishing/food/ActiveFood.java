package wiki.creeper.harbor.fishing.food;

import java.time.Instant;
import wiki.creeper.harbor.common.id.ItemId;

/** The currently active food buff (only one). */
public record ActiveFood(ItemId item, Instant expiresAt) {

    /** Modifier source (food:<food name>). */
    public String source() {
        return FoodRule.source(item);
    }

    public boolean isActive(Instant now) {
        return now.isBefore(expiresAt);
    }
}
