package wiki.creeper.harbor.npc.api;

import java.time.Instant;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;

/** 열려 있는 대화 세션 (저장하지 않음). */
public record DialogSession(NpcId npc, HarborId dialog, String node, int page, Instant openedAt) {}
