package wiki.creeper.harbor.world.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Ships (docs 20 8.2 ShipService). Vanilla boat + Display decoration (Core RigInstance). Entities
 * are not saved and are recalled on quit, server shutdown and when a chunk without riders unloads.
 * Main thread only.
 */
public interface ShipService {

    /** The online owner's ships this season. */
    List<ShipInfo> ships(UUID owner);

    /** The ship afloat (one per person). */
    Optional<ShipInfo> afloat(UUID owner);

    /** The ship this player is riding (whoever owns it). */
    Optional<ShipInfo> aboard(Player player);

    /** Finds a ship by its boat entity (Wildlife: threat radius ×1.3 for players in a boat). */
    Optional<ShipInfo> byEntity(Entity boat);

    /**
     * Speed multiplier currently applied to the driver (ship speed × upgrade × boat_speed stat ×
     * storm). 1 if not riding.
     */
    double speedMultiplier(Player driver);

    /**
     * Launches one of the player's ships, the same flow as using its deed: nearby water,
     * ShipSummonEvent (cancellable), the previous afloat ship recalled. The player gets the usual
     * notices.
     *
     * @param shipId 0 or less = the most recent ship that is not wrecked
     * @param ignoreCooldown skip the summon cooldown (admin)
     * @return true if launched
     */
    boolean summon(Player player, long shipId, boolean ignoreCooldown);

    /**
     * Recalls the owner's afloat ship (passengers dismounted, durability and state saved).
     *
     * @return false if nothing was afloat
     */
    boolean recall(UUID owner);

    /**
     * Admin: sets the durability of an owned ship (owner online on this server), clamped to 1 ..
     * max durability. A wrecked ship in storage becomes usable again. Saved to the DB right away.
     *
     * @return false if the ship is not found
     */
    boolean setDurability(UUID owner, long shipId, int durability);
}
