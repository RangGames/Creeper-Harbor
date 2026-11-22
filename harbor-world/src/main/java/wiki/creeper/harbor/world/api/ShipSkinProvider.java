package wiki.creeper.harbor.world.api;

import java.util.UUID;

/**
 * Whether a ship skin is equipped (proposed). Ship skin cosmetics are owned by Shop, and World does
 * not depend on Shop (docs 20 0.3). When Shop registers this interface as a service, the World
 * default decoration rig is not spawned on the boat of a player with a skin equipped (Shop mounts
 * the skin rig on boarding). Main thread.
 */
@FunctionalInterface
public interface ShipSkinProvider {

    boolean hasBoatSkin(UUID player);
}
