package wiki.creeper.harbor.core.api;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.RigId;

/**
 * One spawned rig ('16. Plugin design' 5.10 RigInstance). Part Displays ride as passengers on a
 * single root (an ItemDisplay with no item), and movement is a single root teleport (interpolated
 * by teleport_duration). Animation sends interpolation commands toward the next key value only at
 * key frames.
 *
 * <p>Main thread only. Calls after removal are ignored.
 */
public interface RigInstance {

    /** Removal reason. */
    enum RemoveReason {
        /** {@link #remove()} was called. */
        REQUESTED,
        /** The chunk the entity was in was unloaded. */
        CHUNK_UNLOAD,
        /** The root entity disappeared for some other reason. */
        ENTITY_GONE,
        /** Core shutdown. */
        SHUTDOWN
    }

    /** ID of this rig instance (different from the entity UUID). */
    UUID id();

    RigId rig();

    boolean isValid();

    /** Root location (follows the vehicle while mounted). */
    Location location();

    /**
     * Root entity. For modules that need more vehicle/passenger handling (e.g. ordering passengers
     * for World ship decorations).
     */
    Entity root();

    /** All root, part and hitbox entities. */
    List<Entity> entities();

    // ------------------------------------------------------------------ movement and heading

    /**
     * Moves the rig. Interpolation ticks come from the moveTicks option. Ignored while mounted on a
     * vehicle.
     */
    void moveTo(Location to);

    /** Moves the rig. ticks is the client interpolation time (teleport_duration; 0 = instant). */
    void moveTo(Location to, int ticks);

    /**
     * Heading (Minecraft yaw and pitch). Every part gets the same entity rotation so the whole body
     * turns and tilts together. The correction for models whose head faces −Z (yaw + 180°, inverted
     * pitch sign) is applied by Core.
     */
    void setHeading(float yaw, float pitch);

    float yaw();

    float pitch();

    /**
     * Mounts the rig on the root entity (Wildlife brain, World boat decoration). From then on its
     * position follows the vehicle.
     */
    void mount(Entity vehicle);

    void dismount();

    @Nullable Entity vehicle();

    // ------------------------------------------------------------------ animation

    /**
     * Changes the animation (blending from the current pose over balance.yml rig.blend_ticks). Does
     * nothing if that animation is already playing.
     *
     * @return false if the rig has no such animation
     */
    boolean play(String animation);

    /**
     * Changes the animation. Blends from the current pose to the new animation's first pose over
     * blendTicks, then plays.
     */
    boolean play(String animation, int blendTicks);

    /**
     * Plays once, then returns to returnTo (Wildlife unique animations, boss patterns). If returnTo
     * is null, uses the rig's default_animation; if that is also absent, holds the last pose.
     */
    boolean playOnce(String animation, @Nullable String returnTo);

    /** Stops the animation and returns to the default pose. */
    void stop();

    /** Current animation name. Null in the default pose. */
    @Nullable String animation();

    /**
     * Sets the current animation's playback position in frames (frames per balance.yml rig.fps).
     * For per-entity rhythm and LOD resynchronization.
     */
    void setPhase(int frame);

    /** Current animation's playback position (frames). */
    int frame();

    /** Number of frames in the current animation. */
    int frameCount();

    /** Playback speed multiplier (1.0 = normal speed, 0 = frozen in the current pose). */
    void setSpeed(double speed);

    double speed();

    // ------------------------------------------------------------------ visibility

    /** Whether visible to everyone. */
    boolean isPublic();

    /** Players who can see it (empty set if visible to everyone). */
    Set<UUID> viewers();

    /** Changes who can see it (a rig visible to everyone also becomes a private rig). */
    void setViewers(Collection<? extends Player> players);

    void addViewer(Player player);

    void removeViewer(Player player);

    /** Makes it visible to everyone. */
    void showToAll();

    /**
     * Name for {@link #setPartGlow} that targets every part (when bone names are unknown, e.g. fish
     * shadow identification).
     */
    String ALL_PARTS = "*";

    /**
     * Part glow (boss weak points). Color is glow_color from rigs/*.yml. If bone is {@link
     * #ALL_PARTS}, every part.
     */
    void setPartGlow(String bone, boolean glow);

    /**
     * Animation events ({@code events} of an animation in rigs/*.yml, e.g. ink {@code ink}, puffing
     * {@code hiss}). listener receives (animation name, event name) on the main thread. Events are
     * not emitted while updates are skipped because no one is watching or it is beyond LOD
     * distance.
     */
    default void onEvent(BiConsumer<String, String> listener) {}

    // ------------------------------------------------------------------ lifecycle

    /** Called once on removal (including chunk unload and shutdown). */
    void onRemove(Consumer<RemoveReason> listener);

    void remove();
}
