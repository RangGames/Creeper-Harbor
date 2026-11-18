package wiki.creeper.harbor.npc.api;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import wiki.creeper.harbor.common.id.NpcId;

/**
 * 퀘스트 표시 등록 (docs 20 5.4). 상태 판단은 MarkerProvider, 표시는 NPC가 한다. 주기 작업은 없다: NPC가 추적 범위에 들어올 때와 {@link
 * #refresh}를 부를 때만 다시 계산한다.
 */
public interface NpcMarkerRegistry {

    /** owner가 꺼지면 자동으로 지운다. 여러 개면 우선순위가 가장 높은 표시를 쓴다. */
    void register(Plugin owner, MarkerProvider provider);

    /** 이 플레이어가 보고 있는 모든 NPC의 표시를 다시 계산한다 (퀘스트 상태·레벨·플래그 변화 때). */
    void refresh(Player player);

    void refresh(Player player, NpcId npc);

    /** 지금 계산한 표시 (렌더링과 별개로 조회만). */
    QuestMarker marker(Player player, NpcId npc);
}
