package wiki.creeper.harbor.npc.api;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import wiki.creeper.harbor.common.id.NpcId;

/**
 * Quest marker registration (docs 20 5.4). MarkerProvider decides the state; the NPC displays it.
 * There is no periodic task: recomputed only when an NPC enters tracking range and when {@link
 * #refresh} is called.
 */
public interface NpcMarkerRegistry {

    /**
     * Removed automatically when owner is disabled. With several, the highest-priority marker is
     * used.
     */
    void register(Plugin owner, MarkerProvider provider);

    /**
     * Recomputes markers of all NPCs this player is viewing (on quest state, level or flag
     * changes).
     */
    void refresh(Player player);

    void refresh(Player player, NpcId npc);

    /** The currently computed marker (lookup only, separate from rendering). */
    QuestMarker marker(Player player, NpcId npc);
}
