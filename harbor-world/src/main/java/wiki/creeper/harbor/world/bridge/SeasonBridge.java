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
 * Season integration (optional). Registers the weekly fishing tournament ranking source {@code
 * tournament} with the Season RankingService (season/rankings.yml board tournament, score of the
 * most recent tournament this season). This class is loaded only when Harbor-Season is present.
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
