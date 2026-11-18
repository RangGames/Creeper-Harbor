package wiki.creeper.harbor.season.content;

import java.util.List;
import wiki.creeper.harbor.common.content.ContentType;

/** Season 콘텐츠 형식. 서버 API를 쓰지 않아 harbor-tools(CI)도 같은 코드로 검사한다. */
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
