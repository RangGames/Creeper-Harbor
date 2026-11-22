package wiki.creeper.harbor.npc.entity;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * NPC body (docs 20 5.4). The Mannequin implementation is the default; if Mannequin details (skin,
 * pose) fall short, an ItemDisplay puppet built with a Core rig is swapped in behind the same
 * interface (decided by Phase 1 verification).
 */
public interface NpcBody {

    /** Representative entity that receives tracking events (PlayerTrackEntityEvent). */
    Entity entity();

    /** Turns its gaze to this location. */
    void lookAt(Location target);

    /** Returns to the default facing. */
    void resetLook();

    /** Moves the position within the same tick (cutscene actor paths). */
    void moveTo(Location location);

    void remove();

    boolean isValid();
}
