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

    // ------------------------------------------------------------------ Changes

    /**
     * Result of a party change (same rules as the /party commands). Lowercase names match the
     * messages_ko.yml keys {@code boss.party.error.<result>}.
     */
    enum Result {
        OK,
        ALREADY_IN_PARTY,
        NOT_IN_PARTY,
        NOT_LEADER,
        FULL,
        NO_INVITE,
        SELF,
        TARGET_IN_PARTY,
        /** The party cannot be changed during a boss fight. */
        BUSY,
        /** LEADER and MEMBER cannot be set as a role (use promote for the leader). */
        INVALID_ROLE,
        /** The implementation does not support changes. */
        UNSUPPORTED
    }

    /**
     * Creates a party led by the player. The changes below behave like the /party commands,
     * including the chat notices to the players involved (main thread).
     */
    default Result create(UUID leader) {
        return Result.UNSUPPORTED;
    }

    /**
     * Invites target to the leader's party (creates one first if there is none). The invite expires
     * after balance.yml boss.party.invite_seconds.
     */
    default Result invite(UUID leader, UUID target) {
        return Result.UNSUPPORTED;
    }

    /** Accepts the player's received invite. */
    default Result accept(UUID player) {
        return Result.UNSUPPORTED;
    }

    /** Leaves the party (a party left with one member is disbanded). */
    default Result leave(UUID player) {
        return Result.UNSUPPORTED;
    }

    default Result kick(UUID leader, UUID target) {
        return Result.UNSUPPORTED;
    }

    default Result disband(UUID leader) {
        return Result.UNSUPPORTED;
    }

    /** Transfers party leadership. */
    default Result promote(UUID leader, UUID target) {
        return Result.UNSUPPORTED;
    }

    /**
     * Sets a boss fight role (one's own, or anyone's by the party leader). LEADER and MEMBER are
     * rejected with {@link Result#INVALID_ROLE}.
     */
    default Result setRole(UUID actor, UUID target, Role role) {
        return Result.UNSUPPORTED;
    }

    /** Who sent the player's pending invite (empty if none or expired). */
    default Optional<UUID> pendingInvite(UUID player) {
        return Optional.empty();
    }

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
