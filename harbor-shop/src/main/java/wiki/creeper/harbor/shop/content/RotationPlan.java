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
 * Weekly rotation listing (shop/rotation.yml, docs 20 12.3). Cosmetics to list for each season week
 * (Season SeasonClock.week). Permanent items (permanent) are always listed even if not here.
 *
 * @param weeks week → listing
 * @param repeat if true, undefined weeks (including extension weeks) cycle through the defined week
 *     lists from the start
 */
public record RotationPlan(Map<Integer, List<HarborId>> weeks, boolean repeat) {

    public static final ContentType.Single<RotationPlan> TYPE =
            new ContentType.Single<>(
                    "shop_rotation", "shop/rotation.yml", false, RotationPlan::parse);

    /** Rotation listing for this week. Empty when the week is 0 (before the season starts). */
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
