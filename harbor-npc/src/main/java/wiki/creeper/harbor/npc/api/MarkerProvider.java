package wiki.creeper.harbor.npc.api;

import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.NpcId;

/**
 * Decides which marker to show a player above this NPC (implemented by Quest). Must read only
 * memory on the main thread.
 */
@FunctionalInterface
public interface MarkerProvider {

    QuestMarker marker(Player player, NpcId npc);
}
