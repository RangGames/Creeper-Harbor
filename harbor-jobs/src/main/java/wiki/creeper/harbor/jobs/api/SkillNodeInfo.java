package wiki.creeper.harbor.jobs.api;

import java.util.List;
import java.util.Optional;
import wiki.creeper.harbor.common.id.SkillId;
import wiki.creeper.harbor.common.model.JobLine;

/**
 * Read-only view of a skill node (skill_tree.yml).
 *
 * @param tree common · explore_2 · explore_3 · … · capstone
 * @param line line of a line tree (empty for the common tree)
 * @param tier tier inside the tree (points needed in the tree come from balance.yml)
 * @param cost mulbineul per rank
 * @param requires nodes that need at least rank 1 first
 * @param active true for an active skill (capstone) instead of a passive effect
 */
public record SkillNodeInfo(
        SkillId id,
        String name,
        String tree,
        Optional<JobLine> line,
        int tier,
        int maxRank,
        int cost,
        List<SkillId> requires,
        boolean active) {

    public SkillNodeInfo {
        requires = List.copyOf(requires);
    }
}
