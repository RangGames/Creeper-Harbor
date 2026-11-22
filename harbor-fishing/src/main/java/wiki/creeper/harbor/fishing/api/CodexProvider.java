package wiki.creeper.harbor.fishing.api;

import java.util.UUID;
import wiki.creeper.harbor.common.id.FishId;

/**
 * Codex lookup SPI (docs 20 3.2). Collection registers it via {@link FishingService#registerCodex}.
 * Used for the unregistered-species weight (codex hunter) and the first-catch multiplier. Always
 * false when not registered.
 */
@FunctionalInterface
public interface CodexProvider {
    boolean hasCaught(UUID player, FishId fish);
}
