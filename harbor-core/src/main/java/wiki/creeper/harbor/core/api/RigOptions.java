package wiki.creeper.harbor.core.api;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Options for spawning a rig. Build them by chaining, like {@code
 * RigOptions.privateTo(player).withAnimation("swim").withPhase(-1)}.
 *
 * @param viewers players who can see it. Null means visible to everyone (bosses, aquariums). For
 *     personal effects (shadows, cutscenes), include only the target
 * @param scale rig scale multiplier (multiplied with the rig definition's scale; larger individuals
 *     are bigger)
 * @param animation animation to play first. Null means the rig's default_animation
 * @param phase start frame of the first animation. −1 means random (so members of the same school
 *     are out of step, '17-3' 9.4)
 * @param onHit click callback for part hitboxes. If null, no hitboxes (Interaction) are created
 * @param moveTicks movement/heading interpolation ticks (teleport_duration). −1 means balance.yml
 *     rig.move_ticks
 * @param blockLight fixed brightness (block, 0~15). −1 means the part setting or ambient light
 * @param skyLight fixed brightness (sky, 0~15)
 */
public record RigOptions(
        @Nullable Set<UUID> viewers,
        double scale,
        @Nullable String animation,
        int phase,
        @Nullable Consumer<RigHit> onHit,
        int moveTicks,
        int blockLight,
        int skyLight) {

    public RigOptions {
        if (viewers != null) {
            viewers = Set.copyOf(viewers);
        }
        if (scale <= 0) {
            throw new IllegalArgumentException("scale은 0보다 커야 합니다: " + scale);
        }
    }

    /** Rig visible to everyone. */
    public static RigOptions publicRig() {
        return new RigOptions(null, 1.0, null, 0, null, -1, -1, -1);
    }

    /** Rig visible only to these players. */
    public static RigOptions privateTo(Collection<? extends Player> players) {
        Set<UUID> ids = new HashSet<>();
        players.forEach(p -> ids.add(p.getUniqueId()));
        return new RigOptions(ids, 1.0, null, 0, null, -1, -1, -1);
    }

    public static RigOptions privateTo(Player player) {
        return new RigOptions(Set.of(player.getUniqueId()), 1.0, null, 0, null, -1, -1, -1);
    }

    public boolean isPublic() {
        return viewers == null;
    }

    public RigOptions withScale(double newScale) {
        return new RigOptions(
                viewers, newScale, animation, phase, onHit, moveTicks, blockLight, skyLight);
    }

    public RigOptions withAnimation(@Nullable String newAnimation) {
        return new RigOptions(
                viewers, scale, newAnimation, phase, onHit, moveTicks, blockLight, skyLight);
    }

    public RigOptions withPhase(int newPhase) {
        return new RigOptions(
                viewers, scale, animation, newPhase, onHit, moveTicks, blockLight, skyLight);
    }

    /** Creates per-part hitboxes (Interaction) and forwards clicks (Boss). */
    public RigOptions withHitboxes(Consumer<RigHit> handler) {
        return new RigOptions(
                viewers, scale, animation, phase, handler, moveTicks, blockLight, skyLight);
    }

    public RigOptions withMoveTicks(int ticks) {
        return new RigOptions(viewers, scale, animation, phase, onHit, ticks, blockLight, skyLight);
    }

    /** Fixed brightness (so fish in lava aren't dark, etc.). */
    public RigOptions withBrightness(int block, int sky) {
        if (block < 0 || block > 15 || sky < 0 || sky > 15) {
            throw new IllegalArgumentException("밝기는 0~15여야 합니다");
        }
        return new RigOptions(viewers, scale, animation, phase, onHit, moveTicks, block, sky);
    }
}
