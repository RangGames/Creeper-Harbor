package wiki.creeper.harbor.wildlife.sim;

import java.util.UUID;
import wiki.creeper.harbor.wildlife.api.Medium;

/**
 * Notifications produced by the simulation thread during one step. The main thread applies them.
 */
public sealed interface SimEvent {

    /** A fish first startled by a player (not those reached by school spread). */
    record Startle(UUID fish, UUID player) implements SimEvent {}

    /**
     * Action particle (only for viewers within 16 blocks, '17-3' 9.4 LOD table).
     *
     * @param key vanilla particle key (in lava, main swaps it for lava/smoke)
     */
    record Particle(
            UUID fish, String world, double x, double y, double z, String key, Medium medium)
            implements SimEvent {}

    /** The fish is gone. */
    record Removed(UUID fish, Reason reason) implements SimEvent {}

    /** Why it is gone. */
    enum Reason {
        DESPAWN,
        EXPIRED,
        CHUNK_UNLOAD,
        CAUGHT,
        REQUESTED,
        PATH_END
    }
}
