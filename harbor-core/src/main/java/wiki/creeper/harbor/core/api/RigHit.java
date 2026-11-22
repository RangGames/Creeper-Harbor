package wiki.creeper.harbor.core.api;

import org.bukkit.entity.Player;

/**
 * Click on a rig part hitbox (Interaction) ('8. System detailed design' chapter 9, per-part boss
 * hit detection). Delivered on the main thread; the original event is cancelled.
 *
 * @param bone name of the clicked bone (rigs/*.yml bones[].name)
 * @param attack true for left click (attack), false for right click (interact)
 */
public record RigHit(Player player, RigInstance rig, String bone, boolean attack) {}
