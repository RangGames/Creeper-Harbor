package wiki.creeper.harbor.core.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Parties (docs 20 0.2). Parties are owned by Boss. Only the interface lives in Core and Boss
 * registers the implementation, so Jobs (capstone, banquet cooking), built before Boss, and Proxy
 * party chat can use it. If there is no implementation (if {@link HarborApi#parties()} is empty),
 * everyone is treated as solo. Main thread only.
 */
public interface PartyService {

    /** Maximum members ('7. Balance design' chapter 9). */
    int MAX_MEMBERS = 10;

    enum Role {
        LEADER,
        /** Captain: steers the ship and dodges. */
        CAPTAIN,
        /** Harpooner: attacks parts. */
        HARPOONER,
        /** Reeler (anchor): manages tension. */
        REELER,
        /** Mechanic: repairs the hull. */
        MECHANIC,
        /** Quartermaster: supplies (counted as repair). */
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

    /** True if in the same party. Without a party, a player matches only themselves. */
    default boolean sameParty(UUID a, UUID b) {
        if (a.equals(b)) {
            return true;
        }
        return partyOf(a).map(p -> p.contains(b)).orElse(false);
    }

    /** Party members (including self). Only self if there is no party. */
    default List<UUID> membersOf(UUID player) {
        return partyOf(player).map(Party::players).orElse(List.of(player));
    }
}
