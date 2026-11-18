package wiki.creeper.harbor.core.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 파티 (docs 20 0.2). 파티는 Boss 소유다. 인터페이스만 Core에 두고 Boss가 구현을 등록해, Boss보다 먼저 만드는 Jobs(캡스톤·연회 요리)와
 * Proxy 파티 채팅이 쓴다. 구현이 없으면 ({@link HarborApi#parties()}가 비어 있으면) 모두 혼자인 것으로 본다. 메인 스레드 전용.
 */
public interface PartyService {

    /** 최대 인원 ('7. 밸런스 기획' 9장). */
    int MAX_MEMBERS = 10;

    enum Role {
        LEADER,
        /** 선장: 선박 조종·회피 담당. */
        CAPTAIN,
        /** 작살수: 부위 공격 담당. */
        HARPOONER,
        /** 감는 사람(닻): 텐션 담당. */
        REELER,
        /** 정비사: 선체 수리. */
        MECHANIC,
        /** 보급관: 보급 (수리로 집계). */
        QUARTERMASTER,
        MEMBER
    }

    record Member(UUID player, Role role) {}

    record Party(UUID id, UUID leader, List<Member> members) {

        public Party {
            members = List.copyOf(members);
        }

        public List<UUID> players() {
            return members.stream().map(Member::player).toList();
        }

        public boolean contains(UUID player) {
            return members.stream().anyMatch(m -> m.player().equals(player));
        }
    }

    Optional<Party> partyOf(UUID player);

    /** 같은 파티면 true. 파티가 없으면 자기 자신과만 같다. */
    default boolean sameParty(UUID a, UUID b) {
        if (a.equals(b)) {
            return true;
        }
        return partyOf(a).map(p -> p.contains(b)).orElse(false);
    }

    /** 파티원 목록 (자신 포함). 파티가 없으면 자신만. */
    default List<UUID> membersOf(UUID player) {
        return partyOf(player).map(Party::players).orElse(List.of(player));
    }
}
