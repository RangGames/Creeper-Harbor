package wiki.creeper.harbor.shop.content;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Pearl treasure chest drop table (shop/chests.yml, docs 20 12.3·12.4, '6. BM plan' 3.4). A
 * post-season-2 feature, enabled by shop/shop.yml features.chest.
 *
 * <p>Probabilities are written as percentage strings (up to 4 decimal places, e.g. {@code
 * "0.5000"}) and converted to parts per million (ppm). The total must be exactly 100% (checked as
 * integers, without floating-point error), and entries may only be cosmetics (no performance items:
 * the only reference type is cosmetic). The in-game drop table is built directly from this file so
 * it never diverges from the actual roll table (loot box disclosure obligation).
 *
 * @param tableVersion drop table version. Bump it when the table changes, and announce before
 *     changing. Recorded in chest_open_log
 * @param pity pity: guarantees grade or higher on the count-th open. The counter carries over
 *     within the season (chest_pity)
 * @param shards pearl shards given for duplicates (per grade, cannot be converted back to pearls)
 * @param exchange price in pearl shards to exchange for an entry of this chest (per grade, no
 *     exchange if absent)
 */
public record ChestDef(
        HarborId id,
        String name,
        int season,
        int priceSingle,
        int priceTen,
        int tableVersion,
        Pity pity,
        Map<Grade, Integer> shards,
        Map<Grade, Integer> exchange,
        List<Entry> entries) {

    /** Total in parts per million. */
    public static final int PPM_TOTAL = 1_000_000;

    /** Pity. */
    public record Pity(int count, Grade grade) {}

    /**
     * A single entry.
     *
     * @param ppm probability (parts per million)
     * @param display original notation (percentage, shown as-is in the in-game drop table)
     */
    public record Entry(HarborId cosmetic, Grade grade, int ppm, String display) {}

    public static final String PREFIX = "chest/";

    public static final ContentType.Entries<ChestDef> TYPE =
            new ContentType.Entries<>(
                    "chest", List.of("shop/chests.yml"), PREFIX, ChestDef::parse, ChestDef::id);

    /** Entries at or above the pity grade. */
    public List<Entry> topEntries() {
        return entries.stream().filter(e -> e.grade().isAtLeast(pity.grade())).toList();
    }

    static ChestDef parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(
                Set.of(
                        "id",
                        "name",
                        "season",
                        "price",
                        "table_version",
                        "pity",
                        "shards",
                        "exchange",
                        "entries"));
        HarborId id =
                node.require("id")
                        .flatMap(n -> n.asId(PREFIX))
                        .orElse(HarborId.parse("chest/invalid"));
        YamlNode price = node.require("price").orElse(node);
        price.allowOnly(Set.of("single", "ten"));
        YamlNode pityNode = node.require("pity").orElse(node);
        pityNode.allowOnly(Set.of("count", "grade"));
        Pity pity =
                new Pity(
                        pityNode.integer("count", 50, 1, 1000),
                        pityNode.enumValue("grade", Grade.class).orElse(Grade.LEGENDARY));
        List<Entry> entries = new ArrayList<>();
        Set<HarborId> seen = new HashSet<>();
        long total = 0;
        for (YamlNode e : node.list("entries")) {
            e.allowOnly(Set.of("cosmetic", "grade", "rate"));
            HarborId cosmetic =
                    e.require("cosmetic")
                            .flatMap(n -> n.asId(CosmeticDef.PREFIX))
                            .orElse(HarborId.parse("cosmetic/invalid"));
            e.get("cosmetic").ifPresent(n -> ctx.ref(CosmeticDef.TYPE.name(), cosmetic, n.pos()));
            if (!seen.add(cosmetic)) {
                e.error("같은 구성품이 두 번 있습니다: " + cosmetic);
            }
            String raw = e.string("rate", "0");
            int ppm = toPpm(raw, e);
            total += ppm;
            entries.add(
                    new Entry(
                            cosmetic,
                            e.enumValue("grade", Grade.class).orElse(Grade.COMMON),
                            ppm,
                            raw));
        }
        if (entries.isEmpty()) {
            node.error("entries가 비어 있습니다");
        } else if (total != PPM_TOTAL) {
            node.error(
                    "확률 합계가 정확히 100%여야 합니다 (지금 "
                            + BigDecimal.valueOf(total, 4).toPlainString()
                            + "%)");
        }
        if (entries.stream().noneMatch(e -> e.grade().isAtLeast(pity.grade()))) {
            node.error("천장 등급(" + pity.grade().key() + ") 이상인 구성품이 없습니다");
        }
        return new ChestDef(
                id,
                node.string("name"),
                node.integer("season", 2, 1, 1000),
                price.integer("single", 150, 1, 100_000),
                price.integer("ten", 1350, 1, 1_000_000),
                node.integer("table_version", 1, 1, 1_000_000),
                pity,
                grades(node, "shards"),
                grades(node, "exchange"),
                List.copyOf(entries));
    }

    /** "0.5000" (percentage, up to 4 decimal places) → 5000 ppm. */
    static int toPpm(String raw, YamlNode at) {
        try {
            BigDecimal percent = new BigDecimal(raw.trim());
            if (percent.scale() > 4) {
                at.error("확률은 소수점 4자리까지 적습니다: " + raw);
                return 0;
            }
            if (percent.signum() <= 0 || percent.compareTo(BigDecimal.valueOf(100)) > 0) {
                at.error("확률은 0보다 크고 100 이하여야 합니다: " + raw);
                return 0;
            }
            return percent.movePointRight(4).intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            at.error("확률 형식이 틀렸습니다 (예 \"0.5000\"): " + raw);
            return 0;
        }
    }

    private static Map<Grade, Integer> grades(YamlNode node, String key) {
        Map<Grade, Integer> map = new EnumMap<>(Grade.class);
        YamlNode section = node.get(key).orElse(null);
        if (section == null) {
            return Map.of();
        }
        for (Map.Entry<String, YamlNode> e : section.entries().entrySet()) {
            Optional<Grade> grade = Keyed.fromKey(Grade.class, e.getKey());
            if (grade.isEmpty()) {
                e.getValue().error("알 수 없는 등급: " + e.getKey());
            } else {
                map.put(grade.get(), e.getValue().asInt());
            }
        }
        return Map.copyOf(map);
    }
}
