package wiki.creeper.harbor.season.content;

import java.util.List;
import wiki.creeper.harbor.common.content.ContentType;

/** Season content types. Uses no server API, so harbor-tools (CI) validates with the same code. */
public final class SeasonContent {

    public static final List<ContentType<?>> TYPES =
            List.of(
                    SeasonDefs.TYPE,
                    ResetPlan.TYPE,
                    RankingBoards.TYPE,
                    HallOfFameRules.TYPE,
                    TitleDef.TYPE);

    private SeasonContent() {}
}
