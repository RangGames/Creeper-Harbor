package wiki.creeper.harbor.season.content;

import java.util.List;
import java.util.Set;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.yaml.YamlNode;
import wiki.creeper.harbor.season.api.TitleId;

/**
 * 칭호 정의 (titles.yml, 추가 제안, docs 20 11.3).
 *
 * @param name 표시 이름 (MiniMessage)
 * @param style 표시 등급 (common · rare · epic · legendary · special). GUI 아이콘 색에 쓴다
 * @param sourceHint 얻는 방법 설명 (GUI 설명 줄)
 * @param permanent false면 상태형 칭호 (후원자 칭호처럼 조건이 끝나면 회수될 수 있음)
 */
public record TitleDef(
        TitleId id, String name, String style, String sourceHint, boolean permanent) {

    public static final ContentType.Entries<TitleDef> TYPE =
            new ContentType.Entries<>(
                    "title",
                    List.of("titles.yml"),
                    TitleId.PREFIX,
                    TitleDef::parse,
                    d -> d.id().id());

    private static final Set<String> STYLES =
            Set.of("common", "rare", "epic", "legendary", "special");

    static TitleDef parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(Set.of("id", "name", "style", "source_hint", "permanent"));
        TitleId id =
                node.require("id")
                        .flatMap(n -> n.asId(TitleId.PREFIX))
                        .map(TitleId::new)
                        .orElse(TitleId.of("title/invalid"));
        String style = node.string("style", "common");
        if (!STYLES.contains(style)) {
            node.error("style은 " + String.join(" · ", STYLES) + " 중 하나여야 합니다: " + style);
        }
        return new TitleDef(
                id,
                node.string("name"),
                style,
                node.string("source_hint", ""),
                node.bool("permanent", true));
    }
}
