package wiki.creeper.harbor.fishing.api;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;

/** Harbor-Fishing 공개 API (docs 20 3.2). 메인 스레드에서 쓴다. */
public interface FishingService {

    SessionState session(UUID player);

    boolean isFishing(UUID player);

    /** 전설 천장 카운터 (조건을 만족한 캐스팅 수). */
    int pity(UUID player, FishId fish);

    /** 직업 품질 상한에 걸려 놓친 대어 수 (이번 접속). */
    int missedBigCount(UUID player);

    /** 도감 조회 SPI 등록 (Collection). */
    void registerCodex(CodexProvider provider);

    /** 몬테카를로 판정 (가상 스레드). 보정 없는 기본 조건으로 count회 판정해 등급별 비율과 전설 첫 포획까지 평균 캐스팅 수를 돌려준다. */
    CompletableFuture<SimulationResult> simulate(RegionId region, int count);

    /** 시뮬레이션 결과. */
    record SimulationResult(
            int casts,
            Map<Grade, Integer> grades,
            Map<FishId, Integer> species,
            double legendAverageCasts) {}
}
