package wiki.creeper.harbor.season.content;

import java.util.List;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.season.api.League;

/**
 * Ranking board definition (season/rankings.yml, docs 20 11.3).
 *
 * @param id board ID (lowercase letters, digits, underscores). Used for the Redis cache name and
 *     commands
 * @param name display name (MiniMessage)
 * @param source source: records · level · codex_season · boss_records, or a source registered by
 *     another module
 * @param subject subject (records = species ID, boss_records = boss ID). null for sources that do
 *     not need one
 * @param leagues leagues to aggregate (the first is the default)
 * @param size how many top players (default 100)
 * @param refreshMinutes aggregation interval (minutes, default 10)
 * @param icon GUI icon item model (default if none)
 */
public record RankingBoardDef(
        String id,
        String name,
        String source,
        @Nullable HarborId subject,
        List<League> leagues,
        int size,
        int refreshMinutes,
        @Nullable String icon) {

    public RankingBoardDef {
        leagues = List.copyOf(leagues);
    }

    public League defaultLeague() {
        return leagues.isEmpty() ? League.ALL : leagues.getFirst();
    }
}
