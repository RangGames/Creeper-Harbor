package wiki.creeper.harbor.core.api;

import org.bukkit.entity.Player;

/**
 * 리그 부위 판정 상자(Interaction) 클릭 ('8. 시스템 상세 기획' 9장 보스 부위별 피격 판정). 메인 스레드에서 전달되고, 원래 이벤트는 취소된다.
 *
 * @param bone 클릭된 뼈 이름 (rigs/*.yml bones[].name)
 * @param attack 참이면 좌클릭(공격), 거짓이면 우클릭(상호작용)
 */
public record RigHit(Player player, RigInstance rig, String bone, boolean attack) {}
