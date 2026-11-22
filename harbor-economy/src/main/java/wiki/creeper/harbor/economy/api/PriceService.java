package wiki.creeper.harbor.economy.api;

import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.UUID;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.core.api.HarborItem;

/**
 * Fish sell price (docs 20 7.4): base price × quality multiplier × market factor M × (1 + sum of
 * sell_price modifiers) × region factor. Legendary is capped per fish; mythic and release_only
 * cannot be sold. Fractions are truncated.
 */
public interface PriceService {

    /**
     * Empty if not sellable. If player is given, that player's sell_price modifiers are applied.
     */
    OptionalLong quote(UUID player, HarborItem.Fish fish);

    /** Reference price without personal bonuses (auction reference price). */
    OptionalLong basePrice(HarborItem.Fish fish);

    /** Current market factor (default 1.0). */
    double coefficient(FishId fish);

    /**
     * Forecast factor for the next hour (economy line "market forecast"). Empty if never computed
     * yet.
     */
    OptionalDouble nextCoefficient(FishId fish);
}
