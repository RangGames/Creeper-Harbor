package wiki.creeper.harbor.npc.api;

import java.util.List;
import java.util.UUID;
import wiki.creeper.harbor.common.id.NpcId;

/**
 * NPC별 호감도 (docs 20 5.4). 시즌마다 초기화된다 (D11). 값은 접속 중인 플레이어의 메모리에 있고 지연 쓰기 큐로 저장한다. 메인 스레드에서 쓴다. 접속
 * 중이 아니면 조회는 0, 변경은 거부한다.
 */
public interface AffinityService {

    int points(UUID player, NpcId npc);

    /** 단계 번호 (0부터). */
    int level(UUID player, NpcId npc);

    AffinityLevel levelInfo(UUID player, NpcId npc);

    /** 이 NPC의 단계표 (npcs.yml affinity_levels, 없으면 balance.yml npc.affinity.levels). */
    List<AffinityLevel> levels(NpcId npc);

    /** 지금 단계까지 해금된 태그에 unlock이 있는지. */
    boolean hasUnlock(UUID player, NpcId npc, String unlock);

    /** 지금 단계의 상점 할인 %. */
    int discountPercent(UUID player, NpcId npc);

    /** 더하거나 뺀다 (0 아래로는 내려가지 않음). AffinityChangedEvent, 단계가 오르면 AffinityLevelUpEvent. */
    AffinityChange add(UUID player, NpcId npc, int delta, AffinitySource source);
}
