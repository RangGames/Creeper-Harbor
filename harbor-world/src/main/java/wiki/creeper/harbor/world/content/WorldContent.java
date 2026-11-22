package wiki.creeper.harbor.world.content;

import java.util.List;
import wiki.creeper.harbor.common.content.ContentType;

/**
 * World content types. They use no server API, so harbor-tools (CI) checks them with the same code.
 */
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
