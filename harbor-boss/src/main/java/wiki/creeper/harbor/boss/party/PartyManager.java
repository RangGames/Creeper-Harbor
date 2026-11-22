package wiki.creeper.harbor.boss.party;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.boss.api.BossRole;
import wiki.creeper.harbor.core.api.PartyService;

/**
 * Parties (docs 20 0.1, 10.2). Parties are owned by Boss and registered with the ServicesManager as
 * the Core api {@link PartyService} implementation, used by Jobs capstones, banquet food and Core
 * party chat. Up to 10 members. Parties live only in server memory (on server moves they carry over
 * via the member list in the instance assignment request). Main thread only.
 *
 * <p>This class holds the party state and rules only (results are {@link PartyService.Result}). The
 * service registered with the ServicesManager is {@link PartyActions}, which wraps these changes
 * with the chat notices.
 */
public final class PartyManager implements PartyService {

    /** One invite. */
    private record Invite(UUID party, UUID from, long expiresAt) {}

    /**
     * One party (mutable, internal). members are in join order = the basis for role selection
     * order.
     */
    private static final class Group {
        final UUID id = UUID.randomUUID();
        UUID leader;
        final LinkedHashMap<UUID, @Nullable BossRole> members = new LinkedHashMap<>();

        Group(UUID leader) {
            this.leader = leader;
            members.put(leader, null);
        }

        Party snapshot() {
            List<Member> list = new ArrayList<>();
            list.add(new Member(leader, Role.LEADER));
            members.forEach(
                    (uuid, role) -> {
                        if (!uuid.equals(leader)) {
                            list.add(
                                    new Member(
                                            uuid, role == null ? Role.MEMBER : role.partyRole()));
                        }
                    });
            return new Party(id, leader, list);
        }
    }

    private final Map<UUID, Group> byPlayer = new HashMap<>();
    private final Map<UUID, Group> byId = new HashMap<>();
    private final Map<UUID, Invite> invites = new HashMap<>();
    private final Predicate<UUID> busy;

    /**
     * @param busy players in a boss fight (party changes are not allowed meanwhile)
     */
    public PartyManager(Predicate<UUID> busy) {
        this.busy = busy;
    }

    // ------------------------------------------------------------------ PartyService

    @Override
    public Optional<Party> partyOf(UUID player) {
        Group g = byPlayer.get(player);
        return g == null ? Optional.empty() : Optional.of(g.snapshot());
    }

    // ------------------------------------------------------------------ Management

    @Override
    public Result create(UUID leader) {
        if (byPlayer.containsKey(leader)) {
            return Result.ALREADY_IN_PARTY;
        }
        Group g = new Group(leader);
        byPlayer.put(leader, g);
        byId.put(g.id, g);
        return Result.OK;
    }

    /** Invite (creates a party first if there is none). */
    public Result invite(UUID leader, UUID target, long now, long ttlMillis) {
        if (leader.equals(target)) {
            return Result.SELF;
        }
        if (byPlayer.containsKey(target)) {
            return Result.TARGET_IN_PARTY;
        }
        Group g = byPlayer.get(leader);
        if (g == null) {
            create(leader);
            g = byPlayer.get(leader);
        }
        if (g == null) {
            return Result.NOT_IN_PARTY;
        }
        if (!g.leader.equals(leader)) {
            return Result.NOT_LEADER;
        }
        if (g.members.size() >= MAX_MEMBERS) {
            return Result.FULL;
        }
        invites.put(target, new Invite(g.id, leader, now + ttlMillis));
        return Result.OK;
    }

    /** Accepts a received invite. */
    public Result join(UUID player, long now) {
        if (byPlayer.containsKey(player)) {
            return Result.ALREADY_IN_PARTY;
        }
        Invite invite = invites.remove(player);
        if (invite == null || invite.expiresAt() < now) {
            return Result.NO_INVITE;
        }
        Group g = byId.get(invite.party());
        if (g == null) {
            return Result.NO_INVITE;
        }
        if (busy.test(g.leader)) {
            return Result.BUSY;
        }
        if (g.members.size() >= MAX_MEMBERS) {
            return Result.FULL;
        }
        g.members.put(player, null);
        byPlayer.put(player, g);
        return Result.OK;
    }

    /** Who sent the invite (for display). */
    public Optional<UUID> inviter(UUID player, long now) {
        Invite invite = invites.get(player);
        return invite == null || invite.expiresAt() < now
                ? Optional.empty()
                : Optional.of(invite.from());
    }

    @Override
    public Result leave(UUID player) {
        Group g = byPlayer.get(player);
        if (g == null) {
            return Result.NOT_IN_PARTY;
        }
        if (busy.test(player)) {
            return Result.BUSY;
        }
        remove(g, player);
        return Result.OK;
    }

    @Override
    public Result kick(UUID leader, UUID target) {
        Group g = byPlayer.get(leader);
        if (g == null) {
            return Result.NOT_IN_PARTY;
        }
        if (!g.leader.equals(leader)) {
            return Result.NOT_LEADER;
        }
        if (leader.equals(target)) {
            return Result.SELF;
        }
        if (!g.members.containsKey(target)) {
            return Result.NOT_IN_PARTY;
        }
        if (busy.test(target)) {
            return Result.BUSY;
        }
        remove(g, target);
        return Result.OK;
    }

    @Override
    public Result disband(UUID leader) {
        Group g = byPlayer.get(leader);
        if (g == null) {
            return Result.NOT_IN_PARTY;
        }
        if (!g.leader.equals(leader)) {
            return Result.NOT_LEADER;
        }
        if (busy.test(leader)) {
            return Result.BUSY;
        }
        for (UUID m : List.copyOf(g.members.keySet())) {
            byPlayer.remove(m);
        }
        byId.remove(g.id);
        return Result.OK;
    }

    /** Sets a boss fight role (one's own role; the party leader can set others' roles too). */
    public Result role(UUID actor, UUID target, BossRole role) {
        Group g = byPlayer.get(actor);
        if (g == null || !g.members.containsKey(target)) {
            return Result.NOT_IN_PARTY;
        }
        if (!actor.equals(target) && !g.leader.equals(actor)) {
            return Result.NOT_LEADER;
        }
        g.members.put(target, role);
        return Result.OK;
    }

    /** Transfers party leadership. */
    @Override
    public Result promote(UUID leader, UUID target) {

        Group g = byPlayer.get(leader);
        if (g == null || !g.members.containsKey(target)) {
            return Result.NOT_IN_PARTY;
        }
        if (!g.leader.equals(leader)) {
            return Result.NOT_LEADER;
        }
        g.leader = target;
        return Result.OK;
    }

    /** Boss fight role set in the party (empty if not set). */
    public Optional<BossRole> roleOf(UUID player) {
        Group g = byPlayer.get(player);
        return g == null ? Optional.empty() : Optional.ofNullable(g.members.get(player));
    }

    /** Member order (party leader first, then join order). */
    public List<UUID> ordered(UUID player) {
        Group g = byPlayer.get(player);
        if (g == null) {
            return List.of(player);
        }
        List<UUID> out = new ArrayList<>();
        out.add(g.leader);
        for (UUID m : g.members.keySet()) {
            if (!m.equals(g.leader)) {
                out.add(m);
            }
        }
        return out;
    }

    public boolean isLeader(UUID player) {
        Group g = byPlayer.get(player);
        return g == null || g.leader.equals(player);
    }

    /** Removes members who have been offline too long (time limit after quitting has passed). */
    public void dropOffline(UUID player) {
        Group g = byPlayer.get(player);
        if (g != null && !busy.test(player)) {
            remove(g, player);
        }
        invites.remove(player);
    }

    /** Removes expired invites. */
    public void purgeInvites(long now) {
        invites.values().removeIf(i -> i.expiresAt() < now);
    }

    private void remove(Group g, UUID player) {
        g.members.remove(player);
        byPlayer.remove(player);
        if (g.members.isEmpty()) {
            byId.remove(g.id);
            return;
        }
        if (g.leader.equals(player)) {
            g.leader = g.members.keySet().iterator().next();
        }
        if (g.members.size() == 1) {
            // A party with only one member left is disbanded (it can be created again)
            UUID last = g.leader;
            g.members.clear();
            byPlayer.remove(last);
            byId.remove(g.id);
        }
    }
}
