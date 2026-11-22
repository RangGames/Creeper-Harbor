package wiki.creeper.harbor.shop.content;

import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.yaml.YamlNode;
import wiki.creeper.harbor.season.api.TitleId;

/** Title reference check helper (Season titles.yml, type name title). */
final class TitleRefs {

    private TitleRefs() {}

    static void ref(ParseContext ctx, String raw, YamlNode at) {
        TitleId.tryOf(raw)
                .ifPresentOrElse(
                        t -> ctx.ref("title", t.id(), at.pos()),
                        () -> at.error("칭호 ID 형식이 틀렸습니다: " + raw));
    }
}
