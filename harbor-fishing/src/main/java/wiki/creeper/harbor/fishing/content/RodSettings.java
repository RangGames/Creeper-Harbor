package wiki.creeper.harbor.fishing.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Rod parts and rod-only enchants (fishing/rod.yml, proposed file). The enchants '조류 읽기' (bite
 * wait) and '단단한 매듭' (line strength) are applied only by the tackle shop NPC ("8. System design" 6,
 * "13. Crafting and shop prices" 5.1: I 1,500 / II 4,000 / III 8,000). The effect values per level
 * are not in the design (proposed).
 *
 * @param enchants enchant ID → definition (file order)
 * @param toothyLinePenalty line strength subtracted when a fish tagged toothy is hooked without a
 *     bite-proof line (stat bite_proof, steel wire; proposed)
 */
public record RodSettings(Map<String, Enchant> enchants, double toothyLinePenalty) {

    /**
     * One rod-only enchant.
     *
     * @param stat gear stat key the level adds to (bite_wait, line_strength …)
     * @param values stat value per level (index 0 = level I)
     * @param prices gold price per level; buying a higher level costs the difference from the
     *     current level (proposed)
     */
    public record Enchant(String id, String stat, List<Double> values, List<Long> prices) {

        public Enchant {
            values = List.copyOf(values);
            prices = List.copyOf(prices);
        }

        public int maxLevel() {
            return values.size();
        }

        /** Stat value at a level (0 for level 0). */
        public double value(int level) {
            return level <= 0 ? 0 : values.get(Math.min(level, values.size()) - 1);
        }

        /** Total price of a level (0 for level 0). */
        public long price(int level) {
            return level <= 0 ? 0 : prices.get(Math.min(level, prices.size()) - 1);
        }

        /** Price to go from the current level to the target level. */
        public long upgradePrice(int current, int target) {
            return Math.max(0, price(target) - price(current));
        }
    }

    private static final Pattern ID = Pattern.compile("[a-z0-9_]+");

    public static final RodSettings EMPTY = new RodSettings(Map.of(), 2);

    public static final ContentType.Single<RodSettings> TYPE =
            new ContentType.Single<>("fishing_rod", "fishing/rod.yml", false, RodSettings::parse);

    public RodSettings {
        enchants = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(enchants));
    }

    public Optional<Enchant> enchant(String id) {
        return Optional.ofNullable(enchants.get(id));
    }

    static RodSettings parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(Set.of("enchants", "toothy_line_penalty"));
        Map<String, Enchant> enchants = new LinkedHashMap<>();
        for (YamlNode e : node.list("enchants")) {
            e.allowOnly(Set.of("id", "stat", "values", "prices"));
            String id = e.string("id");
            if (!ID.matcher(id).matches()) {
                e.error("인챈트 id는 소문자·숫자·밑줄이어야 합니다: '" + id + "'");
                continue;
            }
            if (enchants.containsKey(id)) {
                e.error("같은 인챈트가 두 번 정의되었습니다: " + id);
                continue;
            }
            String stat = e.string("stat");
            if (stat.isEmpty()) {
                e.error("stat이 비었습니다");
            }
            List<Double> values = new ArrayList<>();
            e.list("values").forEach(v -> values.add(v.asDouble()));
            List<Long> prices = new ArrayList<>();
            for (YamlNode p : e.list("prices")) {
                long price = p.asLong();
                if (price < 0) {
                    p.error("가격은 0 이상이어야 합니다");
                }
                prices.add(price);
            }
            if (values.isEmpty() || values.size() > 5) {
                e.error("values는 1~5단계여야 합니다");
                continue;
            }
            if (prices.size() != values.size()) {
                e.error("prices와 values의 단계 수가 같아야 합니다");
                continue;
            }
            enchants.put(id, new Enchant(id, stat, values, prices));
        }
        double penalty = node.number("toothy_line_penalty", 2);
        if (penalty < 0) {
            node.error("toothy_line_penalty는 0 이상이어야 합니다");
        }
        return new RodSettings(enchants, penalty);
    }
}
