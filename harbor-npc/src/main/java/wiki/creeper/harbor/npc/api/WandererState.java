package wiki.creeper.harbor.npc.api;

import java.time.Instant;
import org.bukkit.Location;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.NpcId;

/**
 * Current schedule of a wanderer NPC (docs 20 5.4).
 *
 * @param candidate candidate ID for this slot (null if there are no candidates)
 * @param location candidate location (null if the world is not on this server)
 * @param visible whether spawn conditions (time, weather) match so it is standing now
 * @param nextMoveAt when the next slot starts
 * @param forced under an admin forced move (server flag wanderer/<name>)
 */
public record WandererState(
        NpcId npc,
        @Nullable String candidate,
        @Nullable Location location,
        boolean visible,
        Instant nextMoveAt,
        boolean forced) {}
