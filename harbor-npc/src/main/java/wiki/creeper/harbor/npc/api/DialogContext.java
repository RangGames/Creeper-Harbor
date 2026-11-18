package wiki.creeper.harbor.npc.api;

import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;

/**
 * 조건·액션이 실행되는 대화 상황.
 *
 * @param dialog 대화 트리 ID
 * @param node 현재 노드 ID. 진입 조건(entries)을 평가할 때는 빈 문자열
 */
public record DialogContext(Player player, NpcId npc, HarborId dialog, String node) {}
