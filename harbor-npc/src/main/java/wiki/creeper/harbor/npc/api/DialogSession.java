package wiki.creeper.harbor.npc.api;

import java.time.Instant;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;

/** An open dialog session (not saved). */
public record DialogSession(NpcId npc, HarborId dialog, String node, int page, Instant openedAt) {}
