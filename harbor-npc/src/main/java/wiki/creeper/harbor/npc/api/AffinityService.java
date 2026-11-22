package wiki.creeper.harbor.npc.api;

import java.util.List;
import java.util.UUID;
import wiki.creeper.harbor.common.id.NpcId;

/**
 * Per-NPC affinity (docs 20 5.4). Reset every season (D11). Values live in online players' memory
 * and are saved through the write-behind queue. Use on the main thread. For offline players,
 * queries return 0 and changes are rejected.
 */
public interface AffinityService {

    int points(UUID player, NpcId npc);

    /** Level number (0-based). */
    int level(UUID player, NpcId npc);

    AffinityLevel levelInfo(UUID player, NpcId npc);

    /**
     * This NPC's level table (npcs.yml affinity_levels, otherwise balance.yml npc.affinity.levels).
     */
    List<AffinityLevel> levels(NpcId npc);

    /** Whether unlock is among the tags unlocked up to the current level. */
    boolean hasUnlock(UUID player, NpcId npc, String unlock);

    /** Shop discount % of the current level. */
    int discountPercent(UUID player, NpcId npc);

    /**
     * Adds or subtracts (never below 0). Fires AffinityChangedEvent, plus AffinityLevelUpEvent on
     * level up.
     */
    AffinityChange add(UUID player, NpcId npc, int delta, AffinitySource source);
}
