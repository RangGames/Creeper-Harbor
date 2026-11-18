package wiki.creeper.harbor.wildlife.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * Harbor-Wildlife 공개 API ('17-3' 9.2 WildlifeService). ServicesManager에서 찾는다. 메인 스레드에서 쓴다.
 *
 * <p>야생 개체는 출현 판정에 영향을 주지 않는다 (판정은 Fishing 단독, '17-3' 9.1 원칙). 입질 인계는 Fishing api의 claimShadow로만
 * 한다. 조회 결과는 마지막 계산 주기(100ms) 기준이다.
 */
public interface WildlifeService {

    /** 위치 반경 안의 개체 (가까운 순). */
    List<WildFish> nearby(Location location, double radius);

    Optional<WildFish> fish(UUID id);

    /** 살아 있는 개체 수 (이 서버). */
    int count();

    /**
     * 연출용 개체를 띄운다. 전설·신화도 띄울 수 있다 (자연 스폰은 하지 않음). 어종이 species.yml에 없거나 리그가 없으면, 또는
     * WildFishSpawnEvent가 취소되면 빈 값.
     */
    Optional<UUID> spawnScripted(FishId fish, Location at, ScriptedBehaviour behaviour);

    /**
     * 경로를 따라 헤엄치는 무리를 띄운다 (물고기 대이동 연출 등). 마지막 지점에 닿으면 흩어져 보통 개체가 된다.
     *
     * @param path 지나갈 지점 (같은 월드, 첫 지점에서 생성)
     * @param count 마릿수 (0 이하면 어종 기본 무리 크기)
     * @return 무리 ID
     */
    Optional<UUID> spawnSchool(FishId fish, List<Location> path, int count);

    /**
     * 지역의 대상 어종 밀도를 올린다 (물고기 대이동 ×2 등). 같은 출처로 다시 부르면 바꾼다.
     *
     * @param source 출처 (예: event:great_migration_202611181930)
     * @param fish 대상 어종 (비면 지역 전체 어종)
     * @param multiplier 플레이어당 목표 마릿수와 대상 어종 스폰 가중치에 곱한다
     */
    void boost(String source, RegionId region, Set<FishId> fish, double multiplier, Instant until);

    void clearBoost(String source);

    /** 시점에서 방향으로 광선을 쏴 처음 맞는 개체 (몸 반지름 구 판정). */
    Optional<WildHit> raycast(Location origin, Vector direction, double distance);

    /** 개체를 없앤다 (다음 계산 주기에 반영). */
    void remove(UUID fish);
}
