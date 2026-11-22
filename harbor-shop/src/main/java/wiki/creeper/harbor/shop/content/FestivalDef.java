package wiki.creeper.harbor.shop.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Harbor festival sponsorship (shop/festivals.yml, proposed, docs 20 12.4 'Harbor festival', '6. BM
 * plan' 3.5). When the server-wide gauge reaches the goal, every player gets the same festival
 * cosmetic. No performance buffs.
 *
 * @param goalKrw goal amount (KRW)
 * @param rewards cosmetics given to every player on completion
 * @param contributorTitle contributor title for sponsors (Season titles.yml)
 */
public record FestivalDef(
        HarborId id,
        String name,
        int season,
        long goalKrw,
        List<HarborId> rewards,
        @Nullable String contributorTitle) {

    public static final String PREFIX = "festival/";

    public static final ContentType.Entries<FestivalDef> TYPE =
            new ContentType.Entries<>(
                    "festival",
                    List.of("shop/festivals.yml"),
                    PREFIX,
                    FestivalDef::parse,
                    FestivalDef::id);

    static FestivalDef parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(Set.of("id", "name", "season", "goal_krw", "rewards", "contributor_title"));
        HarborId id =
                node.require("id")
                        .flatMap(n -> n.asId(PREFIX))
                        .orElse(HarborId.parse("festival/invalid"));
        long goal = node.longValue("goal_krw", 0);
        if (goal <= 0) {
            node.error("goal_krw가 있어야 합니다 (양의 정수, 원)");
        }
        List<HarborId> rewards = new ArrayList<>();
        for (YamlNode r : node.list("rewards")) {
            r.asId(CosmeticDef.PREFIX)
                    .ifPresent(
                            c -> {
                                rewards.add(c);
                                ctx.ref(CosmeticDef.TYPE.name(), c, r.pos());
                            });
        }
        String title = node.optString("contributor_title").orElse(null);
        if (title != null) {
            TitleRefs.ref(ctx, title, node);
        }
        return new FestivalDef(
                id,
                node.string("name"),
                node.integer("season", 0, 0, 1000),
                goal,
                List.copyOf(rewards),
                title);
    }
}
