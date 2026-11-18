package wiki.creeper.harbor.quest.api;

import java.util.Optional;
import java.util.UUID;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * 해금한 가장 높은 지역 (의뢰 골드·게시판 후보·힌트 지역). 기본 구현은 Quest (regions.yml unlock의 레벨 + 해금 퀘스트, 순서는 board.yml
 * gold_by_region). World가 이 인터페이스를 ServicesManager에 더 높은 우선순위로 등록하면 그 구현을 쓴다.
 */
@FunctionalInterface
public interface RegionProgressProvider {

    /** 접속 중인 플레이어 (메인 스레드). 하나도 없으면 빈 값. */
    Optional<RegionId> highestUnlockedRegion(UUID player);
}
