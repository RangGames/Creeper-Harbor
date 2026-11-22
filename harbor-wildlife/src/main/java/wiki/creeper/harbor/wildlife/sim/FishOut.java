package wiki.creeper.harbor.wildlife.sim;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.wildlife.api.WildState;

/**
 * Result of one simulation step (one fish). The main thread applies it to the rig.
 *
 * @param animation base animation to keep playing (swim, idle)
 * @param animationSpeed playback speed multiplier (faster when swimming fast)
 * @param oneShot last started one-shot animation (replayed when oneShotSeq changes)
 * @param teleportSeq teleport number (when it changes, moves without interpolation)
 * @param hidden sunk under the bobber with the Fishing shadow taking over (not drawn)
 * @param phaseOffset animation beat (ticks). When shown again, the current frame is aligned to
 *     (server tick + this value)
 * @param nearest distance to the nearest player
 */
public record FishOut(
        UUID id,
        SpeciesProfile profile,
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        WildState state,
        String animation,
        double animationSpeed,
        @Nullable String oneShot,
        int oneShotSeq,
        int teleportSeq,
        boolean hidden,
        @Nullable UUID school,
        boolean leader,
        boolean scripted,
        @Nullable String tag,
        @Nullable String region,
        int phaseOffset,
        double vx,
        double vy,
        double vz,
        double steerX,
        double steerY,
        double steerZ,
        double nearest) {

    public double distanceSq(double px, double py, double pz) {
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        return dx * dx + dy * dy + dz * dz;
    }
}
