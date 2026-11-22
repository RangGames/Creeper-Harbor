package wiki.creeper.harbor.fishing.api;

import java.time.Instant;
import wiki.creeper.harbor.common.id.ItemId;

/**
 * The active food buff of a player (fishing/food.yml, only one at a time).
 *
 * @param item food item the buff came from
 * @param expiresAt time the buff ends
 */
public record FoodBuff(ItemId item, Instant expiresAt) {}
