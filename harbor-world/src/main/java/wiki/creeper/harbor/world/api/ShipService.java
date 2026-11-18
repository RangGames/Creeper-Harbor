package wiki.creeper.harbor.world.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * 선박 (docs 20 8.2 ShipService). 바닐라 보트 + Display 장식(Core RigInstance). 엔티티는 저장하지 않고 접속 종료·서버 종료·탑승자
 * 없는 청크 언로드 때 회수한다. 메인 스레드 전용.
 */
public interface ShipService {

    /** 접속 중인 주인의 이번 시즌 선박. */
    List<ShipInfo> ships(UUID owner);

    /** 띄워 둔 선박 (한 사람당 하나). */
    Optional<ShipInfo> afloat(UUID owner);

    /** 이 플레이어가 타고 있는 선박 (누구 것이든). */
    Optional<ShipInfo> aboard(Player player);

    /** 보트 엔티티로 선박을 찾는다 (Wildlife: 보트에 탄 플레이어 위협 반경 ×1.3). */
    Optional<ShipInfo> byEntity(Entity boat);

    /** 조종자에게 지금 적용되는 속도 배율 (선박 속도 × 업그레이드 × boat_speed 스탯 × 폭풍). 타고 있지 않으면 1. */
    double speedMultiplier(Player driver);
}
