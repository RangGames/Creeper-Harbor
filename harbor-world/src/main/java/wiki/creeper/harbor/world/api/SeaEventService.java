package wiki.creeper.harbor.world.api;

import java.util.List;
import java.util.Optional;
import org.bukkit.Location;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * 해상 이벤트 (docs 20 8.2 SeaEventService). 스케줄러는 harbor:lock:event를 가진 서버 하나가 돌리고, 모든 서버는
 * harbor:event로 받은 상태를 같이 본다. Wildlife(물고기 대이동 밀도), Fishing(fish_effects는 SeaEventMessage로 따로 받음)이
 * 쓴다. 메인 스레드 전용.
 */
public interface SeaEventService {

    /** 진행 중 (ACTIVE). */
    List<ActiveSeaEvent> active();

    /** 이 지역에 적용되는 진행 중 이벤트. */
    List<ActiveSeaEvent> active(RegionId region);

    /** 예고 중 (WARN). */
    List<ActiveSeaEvent> upcoming();

    /** 이 위치가 진행 중인 폭풍 해역 안인지. */
    Optional<ActiveSeaEvent> stormAt(Location location);
}
