package wiki.creeper.harbor.jobs.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.SkillId;
import wiki.creeper.harbor.common.model.JobLine;
import wiki.creeper.harbor.common.yaml.YamlNode;
import wiki.creeper.harbor.core.api.StatKey;

/**
 * Line definition (jobs.yml, proposed): per-tier names, automatic passives, capstone.
 *
 * @param names tier (1-3) → job name (deep-sea explorer …)
 */
public record JobLineDef(
        HarborId id,
        JobLine line,
        Map<Integer, String> names,
        List<Passive> passives,
        @Nullable Capstone capstone) {

    /** Effects applied immediately on job advancement (no points). */
    public record Passive(int tier, StatKey stat, double value) {}

    /**
     * Capstone.
     *
     * @param item item used (empty for passive or screen-selection capstones)
     * @param durationSeconds effect duration (0 means instant or passive)
     * @param weeklyUses weekly uses (0 means unlimited)
     * @param radius area effect radius (proposed, 0 means the user only)
     */
    public record Capstone(
            SkillId node,
            @Nullable ItemId item,
            int durationSeconds,
            int cooldownSeconds,
            int weeklyUses,
            int radius) {}

    public static final String PREFIX = "job/";

    public static final ContentType.Entries<JobLineDef> TYPE =
            new ContentType.Entries<>(
                    "job", List.of("jobs.yml"), PREFIX, JobLineDef::parse, JobLineDef::id);

    public String name(int tier) {
        return names.getOrDefault(tier, names.getOrDefault(1, line.key()));
    }

    static JobLineDef parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(Set.of("id", "line", "names", "passives", "capstone"));
        HarborId id =
                node.require("id")
                        .flatMap(n -> n.asId(PREFIX))
                        .orElse(HarborId.parse("job/invalid"));
        JobLine line = node.enumValue("line", JobLine.class).orElse(JobLine.EXPLORE);

        Map<Integer, String> names = new TreeMap<>();
        node.get("names")
                .ifPresent(
                        n ->
                                n.entries()
                                        .forEach(
                                                (k, v) -> {
                                                    try {
                                                        names.put(
                                                                Integer.parseInt(k), v.asString());
                                                    } catch (NumberFormatException e) {
                                                        v.error("names의 키는 단계 번호(1~3)여야 합니다: " + k);
                                                    }
                                                }));

        List<Passive> passives = new ArrayList<>();
        for (YamlNode p : node.list("passives")) {
            p.allowOnly(Set.of("tier", "modifier", "value"));
            try {
                passives.add(
                        new Passive(
                                p.integer("tier", 2, 2, 3),
                                StatKey.of(p.string("modifier")),
                                p.number("value")));
            } catch (IllegalArgumentException e) {
                p.error("modifier 이름 형식이 틀렸습니다");
            }
        }

        Capstone capstone = null;
        Optional<YamlNode> c = node.get("capstone");
        if (c.isPresent()) {
            YamlNode cap = c.get();
            cap.allowOnly(
                    Set.of("node", "item", "duration_s", "cooldown_s", "weekly_uses", "radius"));
            Optional<HarborId> nodeId = cap.require("node").flatMap(n -> n.asId(SkillId.PREFIX));
            nodeId.ifPresent(n -> ctx.ref("skill", n, cap.pos()));
            ItemId item = null;
            Optional<YamlNode> itemNode = cap.get("item");
            if (itemNode.isPresent()) {
                Optional<HarborId> itemId = itemNode.get().asId(ItemId.PREFIX);
                if (itemId.isPresent()) {
                    item = new ItemId(itemId.get());
                    ctx.ref("item", itemId.get(), itemNode.get().pos());
                }
            }
            capstone =
                    new Capstone(
                            new SkillId(nodeId.orElse(HarborId.parse("skill/invalid"))),
                            item,
                            cap.integer("duration_s", 0, 0, 86400),
                            cap.integer("cooldown_s", 0, 0, 7 * 86400),
                            cap.integer("weekly_uses", 0, 0, 100),
                            cap.integer("radius", 0, 0, 256));
        }
        return new JobLineDef(id, line, Map.copyOf(names), List.copyOf(passives), capstone);
    }
}
