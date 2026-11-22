package wiki.creeper.harbor.season.content;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.yaml.YamlNode;
import wiki.creeper.harbor.season.api.TitleId;

/** season/hall_of_fame.yml: list of hall of fame categories. */
public record HallOfFameRules(List<HallOfFameCategory> categories) {

    public static final ContentType.Single<HallOfFameRules> TYPE =
            new ContentType.Single<>(
                    "hall_of_fame", "season/hall_of_fame.yml", false, HallOfFameRules::parse);

    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,48}");

    public HallOfFameRules {
        categories = List.copyOf(categories);
    }

    public Optional<HallOfFameCategory> get(String category) {
        return categories.stream().filter(c -> c.category().equals(category)).findFirst();
    }

    static HallOfFameRules parse(YamlNode root, ParseContext ctx) {
        root.allowOnly(Set.of("categories"));
        List<HallOfFameCategory> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (YamlNode node : root.list("categories")) {
            node.allowOnly(
                    Set.of(
                            "category",
                            "name",
                            "source",
                            "rule",
                            "order",
                            "count",
                            "level",
                            "title",
                            "unit"));
            String id = node.string("category");
            if (!ID.matcher(id).matches()) {
                node.error("분류 ID는 소문자·숫자·밑줄 1~48자여야 합니다: " + id);
            }
            if (!seen.add(id)) {
                node.error("분류 " + id + "이(가) 중복됩니다");
            }
            HallOfFameCategory.Source source =
                    node.enumValue("source", HallOfFameCategory.Source.class)
                            .orElse(HallOfFameCategory.Source.SUBMITTED);
            HallOfFameCategory.Rule rule =
                    node.enumValue("rule", HallOfFameCategory.Rule.class)
                            .orElse(HallOfFameCategory.Rule.TOP1);
            String order = node.string("order", "desc");
            if (!order.equals("desc") && !order.equals("asc")) {
                node.error("order는 desc 또는 asc여야 합니다");
            }
            int count =
                    switch (rule) {
                        case TOP1 -> 1;
                        case FIRST_N -> node.integer("count", 10, 1, 1000);
                        case ALL -> 0;
                    };
            int level = node.integer("level", 70, 1, 1000);
            if (source == HallOfFameCategory.Source.LEVEL_FIRST
                    && rule != HallOfFameCategory.Rule.FIRST_N) {
                node.error("source: level_first는 rule: first_n과 함께 써야 합니다");
            }
            if (source == HallOfFameCategory.Source.CODEX_FIRST
                    && rule != HallOfFameCategory.Rule.FIRST_N) {
                node.error("source: codex_first는 rule: first_n과 함께 써야 합니다");
            }
            @Nullable String title = node.optString("title").orElse(null);
            if (title != null) {
                Optional<TitleId> probe = TitleId.tryOf(title.replace("<subject>", "x"));
                if (probe.isEmpty()) {
                    node.error("칭호 ID 형식이 틀렸습니다: " + title);
                } else if (!title.contains("<subject>")) {
                    ctx.ref(TitleDef.TYPE.name(), probe.get().id(), node.pos());
                }
            }
            result.add(
                    new HallOfFameCategory(
                            id,
                            node.string("name", id),
                            source,
                            rule,
                            order.equals("asc"),
                            count,
                            level,
                            title,
                            node.string("unit", "")));
        }
        return new HallOfFameRules(result);
    }
}
