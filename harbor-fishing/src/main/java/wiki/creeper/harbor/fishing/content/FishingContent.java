package wiki.creeper.harbor.fishing.content;

import java.util.List;
import wiki.creeper.harbor.common.content.ContentType;

/**
 * Fishing content types. They use no server API, so harbor-tools (CI) validates with the same code.
 */
public final class FishingContent {

    public static final List<ContentType<?>> TYPES =
            List.of(JunkTable.TYPE, FoodBuffs.TYPE, FishingPresets.TYPE, SeaEventPresets.TYPE);

    private FishingContent() {}
}
