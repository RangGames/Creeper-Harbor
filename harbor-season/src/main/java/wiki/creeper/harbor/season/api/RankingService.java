package wiki.creeper.harbor.season.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import wiki.creeper.harbor.season.content.RankingBoardDef;

/**
 * 랭킹 (docs 20 11.4). harbor:lock:ranking을 얻은 서버 하나가 보드별 refresh 주기(기본 10분)로 상위 N명을 집계해 harbor:rank
 * 채널로 모든 서버에 보낸다. 다른 서버와 전광판(Collection)은 이 캐시만 읽으므로 {@link #board}는 DB에 접근하지 않는다 (어느 스레드에서도 가능).
 *
 * <pre>{@code
 * RankingBoard top = SeasonServices.rankings().board("level", League.ALL);
 * top.entries().stream().limit(10).forEach(e -> draw(e.rank(), e.name(), e.value()));
 * }</pre>
 */
public interface RankingService {

    /** 보드 정의 (season/rankings.yml). */
    List<RankingBoardDef> boards();

    Optional<RankingBoardDef> boardDef(String board);

    /** 캐시된 순위표. 아직 집계 전이거나 없는 보드면 빈 표. */
    RankingBoard board(String board, League league);

    /** 캐시된 상위 N명 안의 순위. 밖이면 빈 Optional. */
    Optional<RankEntry> rankOf(UUID player, String board, League league);

    /** 바로 다시 집계한다 (관리자 명령). 잠금을 얻지 못하면 false. 메인 스레드에서 완료. */
    CompletableFuture<Boolean> refreshNow();

    /** 다른 모듈의 랭킹 원천 등록 (모듈 enable에서). 같은 id면 덮어쓴다. */
    void registerSource(RankingSource source);
}
