package wiki.creeper.harbor.npc.api;

import java.util.Optional;
import wiki.creeper.harbor.common.id.NpcId;

/**
 * Wanderer NPC schedule (docs 20 5.4). Each slot (default 3 hours, at 0, 3, 6... o'clock
 * Asia/Seoul) picks one candidate seeded by hash(npc, season, slot number). Nothing is saved, so a
 * restart gives the same result. Only admin forced moves override it, via server flag {@code
 * wanderer/<name>}.
 */
public interface WandererService {

    /** Empty if not role: wanderer. */
    Optional<WandererState> current(NpcId npc);

    /** Forces a move to a candidate (server flag). False if there is no such candidate. */
    boolean forceMove(NpcId npc, String candidate);

    /** Clears the forced move. */
    void reset(NpcId npc);
}
