package wiki.creeper.harbor.jobs.data;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.SkillId;

/**
 * Player job data (PlayerComponent, immutable). Tier, line, level and EXP live in Core's
 * HarborPlayer (player_season); only skill investments and jobs_state are kept here.
 *
 * @param capstoneWeek season week in which weekly uses were counted
 */
public record JobsData(
        Map<SkillId, Integer> skills,
        long overflowExp,
        boolean freeResetUsed,
        @Nullable Instant capstoneUsedAt,
        int capstoneWeek,
        int capstoneWeekUses,
        boolean tierFirstQ4,
        boolean tierFirstQ5) {

    public static final JobsData EMPTY = new JobsData(Map.of(), 0, false, null, 0, 0, false, false);

    public JobsData {
        skills = Map.copyOf(skills);
    }

    public int rank(SkillId node) {
        return skills.getOrDefault(node, 0);
    }

    public JobsData withRank(SkillId node, int rank) {
        Map<SkillId, Integer> next = new HashMap<>(skills);
        if (rank <= 0) {
            next.remove(node);
        } else {
            next.put(node, rank);
        }
        return new JobsData(
                next,
                overflowExp,
                freeResetUsed,
                capstoneUsedAt,
                capstoneWeek,
                capstoneWeekUses,
                tierFirstQ4,
                tierFirstQ5);
    }

    public JobsData withSkills(Map<SkillId, Integer> nextSkills) {
        return new JobsData(
                nextSkills,
                overflowExp,
                freeResetUsed,
                capstoneUsedAt,
                capstoneWeek,
                capstoneWeekUses,
                tierFirstQ4,
                tierFirstQ5);
    }

    public JobsData withOverflow(long value) {
        return new JobsData(
                skills,
                value,
                freeResetUsed,
                capstoneUsedAt,
                capstoneWeek,
                capstoneWeekUses,
                tierFirstQ4,
                tierFirstQ5);
    }

    public JobsData withFreeResetUsed() {
        return new JobsData(
                skills,
                overflowExp,
                true,
                capstoneUsedAt,
                capstoneWeek,
                capstoneWeekUses,
                tierFirstQ4,
                tierFirstQ5);
    }

    public JobsData withCapstoneUse(Instant at, int week, int weekUses) {
        return new JobsData(
                skills, overflowExp, freeResetUsed, at, week, weekUses, tierFirstQ4, tierFirstQ5);
    }

    public JobsData withCapstoneCleared() {
        return new JobsData(
                skills,
                overflowExp,
                freeResetUsed,
                null,
                capstoneWeek,
                capstoneWeekUses,
                tierFirstQ4,
                tierFirstQ5);
    }

    public JobsData withTierFirst(int quality) {
        return new JobsData(
                skills,
                overflowExp,
                freeResetUsed,
                capstoneUsedAt,
                capstoneWeek,
                capstoneWeekUses,
                tierFirstQ4 || quality == 4,
                tierFirstQ5 || quality == 5);
    }
}
