package wiki.creeper.harbor.collection.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import wiki.creeper.harbor.common.id.FishId;

/** 최대어 기록 (Season 랭킹·명예의 전당, 도감 상세). 리그: tier1 · tier2 · all. */
public interface RecordService {

    List<String> LEAGUES = List.of("tier1", "tier2", "all");

    /** 누적 도감의 개인 최대 크기. */
    Optional<Double> personalBest(UUID player, FishId fish);

    /** 이번 시즌 서버 상위 (본 서버 메모리 캐시, 최대 10). */
    List<RecordEntry> serverTop(FishId fish, String league, int limit);
}
