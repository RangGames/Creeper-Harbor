package wiki.creeper.harbor.npc.entity;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * NPC 모습 (docs 20 5.4). Mannequin 구현이 기본이고, Mannequin 세부 동작(스킨·자세)이 부족하면 Core 리그로 만든 ItemDisplay
 * 인형을 같은 인터페이스로 바꿔 끼운다 (Phase 1 검증 결과로 결정).
 */
public interface NpcBody {

    /** 추적 이벤트(PlayerTrackEntityEvent)를 받을 대표 엔티티. */
    Entity entity();

    /** 시선을 이 위치로 돌린다. */
    void lookAt(Location target);

    /** 기본 방향으로 되돌린다. */
    void resetLook();

    /** 같은 틱 안에서 위치를 옮긴다 (컷씬 배우 경로). */
    void moveTo(Location location);

    void remove();

    boolean isValid();
}
