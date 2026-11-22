package wiki.creeper.harbor.fishing.api;

import java.util.UUID;
import wiki.creeper.harbor.common.id.FishId;

/**
 * Collection lookup SPI (docs 20 3.2). Collection registers it via {@link
 * FishingService#registerCollection}. Used for the unregistered-species weight (collection hunter)
 * and the first-catch multiplier. Always false when not registered.
 */
@FunctionalInterface
public interface CollectionProvider {
    boolean hasCaught(UUID player, FishId fish);
}
