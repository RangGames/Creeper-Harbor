package wiki.creeper.harbor.jobs.api;

import java.time.Duration;
import java.util.Optional;
import wiki.creeper.harbor.common.id.SkillId;

/**
 * 캡스톤 상태.
 *
 * @param node 보유한 캡스톤 노드 (없으면 빈 값)
 * @param cooldownLeft 남은 쿨타임
 * @param weeklyUsesLeft 이번 주 남은 사용 횟수 (주간 제한이 없는 캡스톤은 -1)
 */
public record CapstoneState(Optional<SkillId> node, Duration cooldownLeft, int weeklyUsesLeft) {}
