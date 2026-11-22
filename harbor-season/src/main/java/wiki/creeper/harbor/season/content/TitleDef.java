package wiki.creeper.harbor.season.content;

import java.util.List;
import java.util.Set;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.yaml.YamlNode;
import wiki.creeper.harbor.season.api.TitleId;

/**
 * Title definition (titles.yml, proposed, docs 20 11.3).
 *
 * @param name display name (MiniMessage)
 * @param style display tier (common · rare · epic · legendary · special). Used for the GUI icon
 *     color
 * @param sourceHint description of how to obtain it (GUI lore line)
 * @param permanent false for status titles (may be revoked when the condition ends, like donor
 *     titles)
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
