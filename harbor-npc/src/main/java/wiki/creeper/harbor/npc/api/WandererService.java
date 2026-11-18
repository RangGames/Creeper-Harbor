package wiki.creeper.harbor.npc.api;

import java.util.Optional;
import wiki.creeper.harbor.common.id.NpcId;

/**
 * 떠돌이 NPC 일정 (docs 20 5.4). 칸(기본 3시간, Asia/Seoul 0·3·6…시)마다 hash(npc, 시즌, 칸 번호) 시드로 후보 하나를 고른다.
 * 저장하지 않아 재시작해도 같은 결과다. 관리자 강제 이동만 서버 플래그 {@code wanderer/<이름>}으로 덮어쓴다.
 */
public interface WandererService {

    /** role: wanderer가 아니면 빈 값. */
    Optional<WandererState> current(NpcId npc);

    /** 후보로 강제 이동 (서버 플래그). 후보가 없으면 false. */
    boolean forceMove(NpcId npc, String candidate);

    /** 강제 이동 해제. */
    void reset(NpcId npc);
}
