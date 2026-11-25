package wiki.creeper.harbor.jobs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.id.SkillId;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.jobs.data.JobsData;

class JobsRulesTest {

    private static final Duration WEEK = Duration.ofDays(7);

    @Test
    void lineChangeCooldownCountsFromLastChange() {
        Instant changed = Instant.parse("2027-01-10T00:00:00Z");
        JobsData data = JobsData.EMPTY.withLineChangedAt(changed);
        assertEquals(
                Duration.ofDays(4),
                SkillResets.cooldownLeft(data, WEEK, changed.plus(Duration.ofDays(3))));
        assertEquals(Duration.ZERO, SkillResets.cooldownLeft(data, WEEK, changed.plus(WEEK)));
    }

    @Test
    void noCooldownWithoutPreviousChange() {
        assertEquals(Duration.ZERO, SkillResets.cooldownLeft(JobsData.EMPTY, WEEK, Instant.now()));
    }

    @Test
    void seasonJoinBonusSharesTheLevelOneReference() {
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertEquals("lvl:3:" + uuid + ":1", JoinRewards.seasonJoinRef(new SeasonId(3), uuid));
        assertEquals(
                "removed:3:harbor:skill/common/cast:" + uuid,
                JoinRewards.removedRef(
                        new SeasonId(3), SkillId.parse("harbor:skill/common/cast"), uuid));
    }
}
