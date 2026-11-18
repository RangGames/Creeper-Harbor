package wiki.creeper.harbor.world.content;

import java.util.List;
import wiki.creeper.harbor.common.content.ContentType;

/** World 콘텐츠 형식. 서버 API를 쓰지 않아 harbor-tools(CI)도 같은 코드로 검사한다. */
public final class WorldContent {

    public static final List<ContentType<?>> TYPES =
            List.of(
                    RegionExt.TYPE,
                    ShipDef.TYPE,
                    SeaEventDef.TYPE,
                    TreasureTable.TYPE,
                    DecorDef.TYPE);

    private WorldContent() {}
}
