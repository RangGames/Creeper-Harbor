package wiki.creeper.harbor.shop.content;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * 주간 로테이션 진열 (shop/rotation.yml, docs 20 12.3). 시즌 주차(Season SeasonClock.week)마다 진열할 코스메틱 목록. 상시
 * 판매(permanent)는 여기에 없어도 늘 진열된다.
 *
 * @param weeks 주차 → 진열 목록
 * @param repeat true면 정의되지 않은 주차(연장 주 포함)는 정의된 주차 목록을 처음부터 돌아가며 쓴다
 */
public record RotationPlan(Map<Integer, List<HarborId>> weeks, boolean repeat) {

    public static final ContentType.Single<RotationPlan> TYPE =
            new ContentType.Single<>(
                    "shop_rotation", "shop/rotation.yml", false, RotationPlan::parse);

    /** 이 주차의 로테이션 진열. 주차가 0(시즌 시작 전)이면 비어 있다. */
    public List<HarborId> itemsFor(int week) {
        if (week <= 0 || weeks.isEmpty()) {
            return List.of();
        }
        List<HarborId> exact = weeks.get(week);
        if (exact != null) {
            return exact;
        }
        if (!repeat) {
            return List.of();
        }
        List<Integer> defined = new ArrayList<>(weeks.keySet());
        List<HarborId> cycled = weeks.get(defined.get((week - 1) % defined.size()));
        return cycled == null ? List.of() : cycled;
    }

    static RotationPlan parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(Set.of("repeat", "weeks"));
        TreeMap<Integer, List<HarborId>> weeks = new TreeMap<>();
        for (YamlNode w : node.list("weeks")) {
            w.allowOnly(Set.of("week", "items"));
            int week = w.integer("week", 1, 1, 52);
            List<HarborId> items = new ArrayList<>();
            for (YamlNode i : w.list("items")) {
                i.asId(CosmeticDef.PREFIX)
                        .ifPresent(
                                c -> {
                                    items.add(c);
                                    ctx.ref(CosmeticDef.TYPE.name(), c, i.pos());
                                });
            }
            if (weeks.put(week, List.copyOf(items)) != null) {
                w.error("같은 주차가 두 번 있습니다: " + week);
            }
        }
        return new RotationPlan(Collections.unmodifiableMap(weeks), node.bool("repeat", true));
    }
}
