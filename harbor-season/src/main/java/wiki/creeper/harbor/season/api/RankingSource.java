package wiki.creeper.harbor.season.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.season.content.RankingBoardDef;

/**
 * Ranking source (proposed). Lets other modules register aggregations not covered by the built-in
 * sources (records · level · collection_season · boss_records). Example: World's weekly fishing
 * tournament score ({@code source: tournament}). Season does not know that module's code and reads
 * only through this interface.
 *
 * <p>{@link #query} is called on a virtual thread every 10 minutes on the single ranking
 * aggregation server. It returns the sorted top {@code limit} entries.
 */
public interface RankingSource {

    /** source value in rankings.yml. Lowercase letters, digits, underscores. */
    String id();

    /** Whether lower values rank higher (kill time etc.). */
    default boolean ascending() {
        return false;
    }

    /**
     * @param schema schema to read (null = current DB; {@code archive_s<n>} at season close)
     */
    List<Row> query(
            Connection connection,
            RankingBoardDef board,
            League league,
            int season,
            @Nullable String schema,
            int limit)
            throws SQLException;

    /** One line returned by the source (Season assigns the ranks). */
    record Row(
            @Nullable UUID uuid,
            String name,
            double value,
            double secondary,
            Instant achievedAt,
            int members) {}
}
