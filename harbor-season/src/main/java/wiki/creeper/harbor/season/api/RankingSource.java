package wiki.creeper.harbor.season.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.season.content.RankingBoardDef;

/**
 * 랭킹 원천 (추가 제안). 기본 원천(records · level · codex_season · boss_records)에 없는 집계를 다른 모듈이 등록한다. 예:
 * World의 주간 낚시 대회 점수 ({@code source: tournament}). Season은 그 모듈의 코드를 모르고 이 인터페이스로만 읽는다.
 *
 * <p>{@link #query}는 랭킹 집계 서버 하나에서 10분마다 가상 스레드로 불린다. 정렬된 상위 {@code limit}개를 돌려준다.
 */
public interface RankingSource {

    /** rankings.yml의 source 값. 소문자·숫자·밑줄. */
    String id();

    /** 낮은 값이 앞서는지 (처치 시간 등). */
    default boolean ascending() {
        return false;
    }

    /**
     * @param schema 읽을 스키마 (null이면 지금 DB, 시즌 마감 때는 {@code archive_s<n>})
     */
    List<Row> query(
            Connection connection,
            RankingBoardDef board,
            League league,
            int season,
            @Nullable String schema,
            int limit)
            throws SQLException;

    /** 원천이 돌려주는 한 줄 (순위는 Season이 매긴다). */
    record Row(
            @Nullable UUID uuid,
            String name,
            double value,
            double secondary,
            Instant achievedAt,
            int members) {}
}
