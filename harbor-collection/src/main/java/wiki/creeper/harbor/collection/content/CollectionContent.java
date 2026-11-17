package wiki.creeper.harbor.collection.content;

import java.util.List;
import wiki.creeper.harbor.common.content.ContentType;

/** Collection 콘텐츠 형식. 서버 API를 쓰지 않아 harbor-tools(CI)도 같은 코드로 검사한다. */
public final class CollectionContent {

    public static final List<ContentType<?>> TYPES = List.of(CodexRules.TYPE, AquariumRules.TYPE);

    private CollectionContent() {}
}
