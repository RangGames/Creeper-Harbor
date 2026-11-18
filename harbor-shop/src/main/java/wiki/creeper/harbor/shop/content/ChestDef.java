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
 * 진주 보물함 확률표 (shop/chests.yml, docs 20 12.3·12.4, '6. BM 기획' 3.4). 시즌 2 이후 기능이라 shop/shop.yml
 * features.chest로 켠다.
 *
 * <p>확률은 백분율 문자열(소수점 4자리까지, 예 {@code "0.5000"})로 적고 백만분율(ppm)로 바꿔 쓴다. 합계는 정확히 100%여야 하며 (부동소수 오차 없이
 * 정수로 검사), 구성품은 코스메틱만 받는다 (성능 아이템 금지: 참조 형식이 cosmetic뿐). 게임 안 확률표는 이 파일에서 그대로 만들어 실제 추첨표와 어긋나지 않는다
 * (확률형 아이템 정보 공개 의무).
 *
 * @param tableVersion 확률표 버전. 표를 바꾸면 올리고, 바꾸기 전에 공지한다. chest_open_log에 함께 기록
 * @param pity 천장: count회째에 grade 이상 확정. 카운터는 시즌 안에서 이월 (chest_pity)
 * @param shards 중복 획득 때 주는 진주 조각 (등급별, 진주로 되바꿀 수 없음)
 * @param exchange 진주 조각으로 이 보물함 구성품을 바꾸는 가격 (등급별, 없으면 교환 불가)
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

    /** 백만분율 합계. */
    public static final int PPM_TOTAL = 1_000_000;

    /** 천장. */
    public record Pity(int count, Grade grade) {}

    /**
     * 구성품 하나.
     *
     * @param ppm 확률 (백만분율)
     * @param display 원본 표기 (백분율, 게임 안 확률표에 그대로 보여 줌)
     */
    public record Entry(HarborId cosmetic, Grade grade, int ppm, String display) {}

    public static final String PREFIX = "chest/";

    public static final ContentType.Entries<ChestDef> TYPE =
            new ContentType.Entries<>(
                    "chest", List.of("shop/chests.yml"), PREFIX, ChestDef::parse, ChestDef::id);

    /** 천장 등급 이상인 구성품. */
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

    /** "0.5000" (백분율, 소수점 4자리까지) → 5000 ppm. */
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
