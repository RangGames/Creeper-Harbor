package wiki.creeper.harbor.npc.api;

import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;

/**
 * The dialog situation in which a condition or action runs.
 *
 * @param dialog dialog tree ID
 * @param node current node ID. Empty string when evaluating entry conditions (entries)
 */
public record DialogContext(Player player, NpcId npc, HarborId dialog, String node) {}
