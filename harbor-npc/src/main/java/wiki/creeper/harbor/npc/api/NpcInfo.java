package wiki.creeper.harbor.npc.api;

import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;

/**
 * 다른 모듈에 보여주는 NPC 정보.
 *
 * @param name 기본 이름 (이름 변형 전). 플레이어별 이름은 {@link NpcService#displayName}
 * @param dialog 대화 트리 ID ({@code harbor:dialog/<이름>}), 없으면 null
 * @param shop 상점 ID (Economy shops.yml), 없으면 null
 * @param spawned 지금 엔티티가 서 있는지 (청크 로드·등장 조건·떠돌이 일정 반영)
 */
public record NpcInfo(
        NpcId id,
        String name,
        NpcRole role,
        @Nullable HarborId dialog,
        @Nullable HarborId shop,
        boolean spawned) {}
