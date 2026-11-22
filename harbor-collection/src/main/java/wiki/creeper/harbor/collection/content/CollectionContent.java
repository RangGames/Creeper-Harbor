package wiki.creeper.harbor.collection.content;

import java.util.List;
import wiki.creeper.harbor.common.content.ContentType;

/**
 * Collection content types. Uses no server API, so harbor-tools (CI) validates with the same code.
 */
public final class CollectionContent {

    public static final List<ContentType<?>> TYPES =
            List.of(
                    CollectionRules.TYPE,
                    AquariumRules.TYPE,
                    DisplayCaseRules.TYPE,
                    ReleaseRules.TYPE,
                    BillboardRules.TYPE);

    private CollectionContent() {}
}
