package wiki.creeper.harbor.collection.api;

import java.util.UUID;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * Aquarium theme ownership check (proposed). Aquarium theme cosmetics are owned by Shop
 * (cosmetics_owned). Since Shop depends on Collection, Collection cannot have a compile dependency
 * on Shop. If Shop registers this interface as a service, it is used; otherwise Collection looks up
 * Shop CosmeticService.owns by service name and calls it. Main thread, online players only.
 */
@FunctionalInterface
public interface AquariumThemeOwnership {

    boolean owns(UUID player, HarborId cosmetic);
}
