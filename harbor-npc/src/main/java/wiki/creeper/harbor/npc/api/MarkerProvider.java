package wiki.creeper.harbor.npc.api;

import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.NpcId;

/** 플레이어에게 이 NPC 위에 어떤 표시를 보여줄지 판단한다 (Quest가 구현). 메인 스레드에서 메모리만 읽어야 한다. */
@FunctionalInterface
public interface MarkerProvider {

    QuestMarker marker(Player player, NpcId npc);
}
