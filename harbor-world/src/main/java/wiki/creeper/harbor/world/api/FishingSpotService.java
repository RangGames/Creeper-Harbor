package wiki.creeper.harbor.world.api;

import java.util.List;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.RegionId;

/** 물고기 떼 포인트 (docs 20 8.2 FishingSpotService). Wildlife가 이 자리에 물고기 무리를 보여 준다. 메인 스레드 전용. */
public interface FishingSpotService {

    /** 반경 안의 가장 가까운 공개 물고기 떼. */
    Optional<FishingSpot> spotNear(Location location, double radius);

    /** 숨은 낚시터까지 포함 (이 플레이어가 숨은 낚시터 감지를 가졌을 때). */
    Optional<FishingSpot> spotNear(Player viewer, Location location, double radius);

    List<FishingSpot> spots(RegionId region);
}
