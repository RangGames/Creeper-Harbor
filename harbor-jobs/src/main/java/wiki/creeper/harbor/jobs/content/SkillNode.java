package wiki.creeper.harbor.jobs.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.SkillId;
import wiki.creeper.harbor.common.model.JobLine;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.common.yaml.YamlNode;
import wiki.creeper.harbor.core.api.Modifier;
import wiki.creeper.harbor.core.api.StatKey;

/**
 * 스킬 노드 (skill_tree.yml, '11. 데이터 설계' 2.7 + line 추가 제안).
 *
 * @param tree common · explore_2 · explore_3 · collect_2 · … · capstone
 * @param line 계열 (common 트리는 비움). 계열 트리는 트리 이름에서 정해진다
 * @param cooldown 액티브(캡스톤)만, 초
 */
public record SkillNode(
        SkillId id,
        String name,
        String tree,
        @Nullable JobLine line,
        int tier,
        int maxRank,
        int cost,
        int pointsInTree,
        List<SkillId> requiredNodes,
        List<Effect> effects,
        int cooldown) {

    /** 랭크당 효과. 퍼센트 효과는 기본값 1.0 스탯에 ADD로 더한다 (docs 20 2.4). */
    public record Effect(StatKey stat, Modifier.Op op, double valuePerRank) {}

    /** 트리 종류. */
    public enum Kind {
        COMMON,
        LINE_SECOND,
        LINE_THIRD,
        CAPSTONE
    }

    public static final ContentType.Entries<SkillNode> TYPE =
            new ContentType.Entries<>(
                    "skill",
                    List.of("skill_tree.yml"),
                    SkillId.PREFIX,
                    SkillNode::parse,
                    n -> n.id().id());

    private static final Set<String> FIELDS =
            Set.of(
                    "id",
                    "name",
                    "tree",
                    "line",
                    "tier",
                    "max_rank",
                    "cost",
                    "requires",
                    "effects",
                    "cooldown");

    public Kind kind() {
        if (tree.equals("common")) {
            return Kind.COMMON;
        }
        if (tree.equals("capstone")) {
            return Kind.CAPSTONE;
        }
        return tree.endsWith("_3") ? Kind.LINE_THIRD : Kind.LINE_SECOND;
    }

    public boolean isActive() {
        return cooldown > 0;
    }

    /** 이 노드에 rank만큼 투자했을 때의 물비늘 합계. */
    public long invested(int rank) {
        return (long) rank * cost;
    }

    static SkillNode parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(FIELDS);
        HarborId rawId =
                node.require("id")
                        .flatMap(n -> n.asId(SkillId.PREFIX))
                        .orElse(HarborId.parse("skill/invalid"));
        String tree = node.string("tree");
        JobLine line = null;
        if (!tree.equals("common") && !tree.equals("capstone")) {
            int underscore = tree.lastIndexOf('_');
            String suffix = underscore < 0 ? "" : tree.substring(underscore + 1);
            Optional<JobLine> fromTree =
                    underscore < 0
                            ? Optional.empty()
                            : Keyed.fromKey(JobLine.class, tree.substring(0, underscore));
            if (fromTree.isEmpty() || !(suffix.equals("2") || suffix.equals("3"))) {
                node.error("tree는 common · capstone · <계열>_2 · <계열>_3 중 하나여야 합니다: " + tree);
            } else {
                line = fromTree.get();
            }
        }
        if (node.has("line")) {
            JobLine explicit = node.enumValue("line", JobLine.class).orElse(null);
            if (line != null && explicit != null && line != explicit) {
                node.error("line이 tree의 계열과 다릅니다");
            }
            line = explicit;
        }
        if (tree.equals("capstone") && line == null) {
            node.error("캡스톤 노드에는 line이 필요합니다");
        }

        int pointsInTree = 0;
        List<SkillId> requiredNodes = new ArrayList<>();
        Optional<YamlNode> requires = node.get("requires");
        if (requires.isPresent()) {
            requires.get().allowOnly(Set.of("points_in_tree", "nodes"));
            pointsInTree = requires.get().integer("points_in_tree", 0, 0, 1000);
            for (YamlNode n : requires.get().list("nodes")) {
                n.asId(SkillId.PREFIX)
                        .ifPresent(
                                id -> {
                                    requiredNodes.add(new SkillId(id));
                                    ctx.ref("skill", id, n.pos());
                                });
            }
        }

        List<Effect> effects = new ArrayList<>();
        for (YamlNode e : node.list("effects")) {
            e.allowOnly(Set.of("modifier", "value_per_rank", "op"));
            String stat = e.string("modifier");
            try {
                effects.add(
                        new Effect(
                                StatKey.of(stat),
                                e.string("op", "add").equals("multiply")
                                        ? Modifier.Op.MULTIPLY
                                        : Modifier.Op.ADD,
                                e.number("value_per_rank")));
            } catch (IllegalArgumentException ex) {
                e.error("modifier 이름 형식이 틀렸습니다 (소문자·숫자·밑줄): " + stat);
            }
        }

        return new SkillNode(
                new SkillId(rawId),
                node.string("name"),
                tree,
                line,
                node.integer("tier", 1, 1, 3),
                node.integer("max_rank", 1, 1, 10),
                node.integer("cost", 1, 1, 1000),
                pointsInTree,
                List.copyOf(requiredNodes),
                List.copyOf(effects),
                node.integer("cooldown", 0, 0, 7 * 24 * 3600));
    }
}
