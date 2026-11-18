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
 * 크리에이터 코드 (shop/creators.yml, 추가 제안, '6. BM 기획' 3.6, docs 20 12.1: 시즌 2 이후 기능, 스위치
 * features.creator_codes). 방송인 코드를 등록한 구매자의 충전액 일부를 정산 기록(creator_payout)에 남기고, 구매자에게 칭호를 준다.
 *
 * @param code 입력 코드 (대문자·숫자·밑줄, 3~16자)
 * @param shareRate 정산 비율 (제안값, 예 0.05)
 * @param supporterTitle 코드를 등록한 구매자에게 주는 칭호
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
