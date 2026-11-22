package wiki.creeper.harbor.shop.content;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Creator code (shop/creators.yml, proposed, '6. BM plan' 3.6, docs 20 12.1: post-season-2 feature,
 * switch features.creator_codes). Records a share of top-ups by buyers who registered a streamer's
 * code in the payout record (creator_payout) and gives the buyer a title.
 *
 * @param code code to enter (uppercase, digits, underscore, 3–16 chars)
 * @param shareRate payout rate (proposed value, e.g. 0.05)
 * @param supporterTitle title given to buyers who registered the code
 */
public record CreatorDef(
        HarborId id, String code, String name, double shareRate, @Nullable String supporterTitle) {

    public static final String PREFIX = "creator/";

    public static final ContentType.Entries<CreatorDef> TYPE =
            new ContentType.Entries<>(
                    "creator",
                    List.of("shop/creators.yml"),
                    PREFIX,
                    CreatorDef::parse,
                    CreatorDef::id);

    static CreatorDef parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(Set.of("id", "code", "name", "share_rate", "supporter_title"));
        HarborId id =
                node.require("id")
                        .flatMap(n -> n.asId(PREFIX))
                        .orElse(HarborId.parse("creator/invalid"));
        String code = node.string("code").toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9_]{3,16}")) {
            node.error("code는 대문자·숫자·밑줄 3~16자여야 합니다: " + code);
        }
        double share = node.number("share_rate", 0.05);
        if (share < 0 || share > 0.5) {
            node.error("share_rate는 0~0.5 사이여야 합니다: " + share);
        }
        String title = node.optString("supporter_title").orElse(null);
        if (title != null) {
            TitleRefs.ref(ctx, title, node);
        }
        return new CreatorDef(id, code, node.string("name"), share, title);
    }
}
