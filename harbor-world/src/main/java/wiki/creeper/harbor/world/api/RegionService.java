package wiki.creeper.harbor.world.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.fishing.api.RegionKeyResolver;

/**
 * 지역 (docs 20 8.2 RegionService). 지역 판정은 청크 색인으로 하고, 진입 조건·밀어내기·첫 방문 기록은 World가 처리한다.
 * ServicesManager에서 찾는다. 메인 스레드에서 쓴다 (CompletableFuture를 돌려주는 메서드는 어디서나, 결과는 메인 스레드에서 완료).
 *
 * <p>Economy(레시피 '방문' 해금, 지역별 체류 인원), Quest(visit 목표), Wildlife(지역·세부 구역)가 쓴다.
 */
public interface RegionService {

    /** 해금 순서대로. */
    List<RegionInfo> regions();

    Optional<RegionInfo> region(RegionId id);

    /** 위치의 지역 (월드 이름은 balance.yml world_alias 적용). */
    Optional<RegionInfo> regionAt(Location location);

    /** 위치의 세부 구역 ID (어종 area와 같은 값). */
    List<String> areasAt(Location location);

    /** 찌 위치의 낚시 키 (Fishing에 등록한 RegionKeyResolver와 같은 결과). */
    Optional<RegionKeyResolver.RegionKey> fishingKey(Location bobber);

    /** 접속 중인 플레이어가 지금 있는 지역. */
    Optional<RegionId> current(UUID player);

    /** 이번 시즌 방문한 지역 (접속 중일 때). */
    Set<RegionId> visited(UUID player);

    /** 이번 시즌 방문 여부 (접속 중일 때, 아니면 false). */
    boolean hasVisited(UUID player, RegionId region);

    /** 오프라인 플레이어도 DB에서 확인한다. */
    CompletableFuture<Boolean> loadVisited(UUID player, RegionId region);

    /** 진입 조건 판정. */
    UnlockStatus unlockStatus(Player player, RegionId region);

    /**
     * 해금한 가장 높은 지역 (탈것 조건은 보지 않는다). Quest 의뢰 보상·Fishing 통발 상한처럼 "해금한 가장 높은 지역" 기준에 쓴다 ('13. 제작 · 상점
     * 가격' 6장).
     */
    RegionInfo highestUnlocked(Player player);

    /** 이 서버에 있는 지역별 인원. */
    int occupancy(RegionId region);

    /** 모든 서버의 지역별 인원 (Redis, 1분 주기 집계). Economy 시세 S₀용. */
    CompletableFuture<Map<RegionId, Integer>> occupancyAll();

    /**
     * 지역의 안전 지점으로 보낸다 (그 지역 월드가 다른 서버에 있으면 ProxyBridge로 서버 이동 후 도착 서버가 옮긴다). 진입 조건은 보지 않는다.
     *
     * @return 이동을 시작했으면 true
     */
    boolean travel(Player player, RegionId region, String reason);
}
