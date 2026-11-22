package wiki.creeper.harbor.wildlife.sim;

import java.util.UUID;
import wiki.creeper.harbor.common.rig.Vec3;

/**
 * Player state copied by the main thread at the end of each tick ('17-3' 9.4 thread stage 1).
 * Velocity is derived by the simulation thread by comparing with the previous position.
 *
 * @param world actual world name
 * @param threat whether the player can be a threat (excludes spectators and vanished admins)
 */
public record PlayerSnap(
        UUID id, String world, Vec3 pos, boolean sneaking, boolean boat, boolean threat) {}
