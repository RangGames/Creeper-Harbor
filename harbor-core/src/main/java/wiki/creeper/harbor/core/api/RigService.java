package wiki.creeper.harbor.core.api;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.RigId;
import wiki.creeper.harbor.common.rig.RigDef;

/**
 * Display rig playback ('16. Plugin design' 5.10). Spawns rigs/*.yml rigs as groups of Display
 * entities and plays animations. Used by: Fishing fish shadows and jumps, Collection aquariums and
 * personal fish tanks, Wildlife wild fish, Boss boss models (per-part Interaction hit detection),
 * Shop pets and ship decorations.
 *
 * <p>Rendered as real, non-persistent entities (the default until the Phase 1 decision). Private
 * rigs are hidden by default and shown only to viewers (Paper entity hide/show API). Entities are
 * not saved to chunks and disappear when the chunk unloads or Core shuts down.
 *
 * <p>Threading: all methods are main thread only (except rig definition lookup {@link #definition},
 * which works anywhere).
 */
public interface RigService {

    /** Rig IDs present in content. */
    Set<RigId> ids();

    Optional<RigDef> definition(RigId id);

    /**
     * Spawns a rig. The location's yaw and pitch are used as the heading ({@link
     * RigInstance#setHeading}).
     *
     * @throws IllegalArgumentException if the rig is not in content
     * @throws IllegalStateException if not on the main thread
     */
    RigInstance spawn(RigId rig, Location at, RigOptions options);

    default RigInstance spawn(RigId rig, Location at) {
        return spawn(rig, at, RigOptions.publicRig());
    }

    /** All live rigs. */
    Collection<? extends RigInstance> instances();

    /** Finds a rig by one of its entities (root, part or hitbox). */
    Optional<RigInstance> byEntity(Entity entity);

    /**
     * Number of rig parts this player is currently viewing (to check against the
     * 300-around-a-player budget, '16. Plugin design' chapter 7).
     */
    int visibleParts(Player player);
}
