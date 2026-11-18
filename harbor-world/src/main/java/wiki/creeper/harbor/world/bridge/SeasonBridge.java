package wiki.creeper.harbor.world.bridge;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.season.api.League;
import wiki.creeper.harbor.season.api.RankingSource;
import wiki.creeper.harbor.season.api.SeasonServices;
import wiki.creeper.harbor.season.content.RankingBoardDef;
import wiki.creeper.harbor.world.sea.TournamentManager;

/**
 * Season 연결 (선택). 주간 낚시 대회 순위 원천 {@code tournament}를 Season RankingService에 등록한다
 * (season/rankings.yml 보드 tournament, 이번 시즌 가장 최근 대회 점수). Harbor-Season이 있을 때만 이 클래스를 불러온다.
 */
public final class SeasonBridge {

    private SeasonBridge() {}

    public static void register() {
        SeasonServices.find(wiki.creeper.harbor.season.api.RankingService.class)
                .ifPresent(r -> r.registerSource(new TournamentSource()));
    }

    private static final class TournamentSource implements RankingSource {

        @Override
        public String id() {
            return "tournament";
        }

        @Override
        public List<Row> query(
                Connection connection,
                RankingBoardDef board,
                League league,
                int season,
                @Nullable String schema,
                int limit)
                throws SQLException {
            List<Row> rows = new ArrayList<>();
            for (TournamentManager.Row r :
                    TournamentManager.top(connection, season, schema, limit)) {
                rows.add(new Row(r.uuid(), r.name(), r.score(), r.catches(), r.updatedAt(), 1));
            }
            return rows;
        }
    }
}
