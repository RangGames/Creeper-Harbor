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
 * 파티 (docs 20 0.1·10.2). 파티는 Boss 소유이고, Core api {@link PartyService} 구현으로 ServicesManager에 등록해
 * Jobs 캡스톤·연회 요리·Core 파티 채팅이 쓴다. 최대 10명. 파티는 서버 메모리에만 있다 (서버를 옮기면 인스턴스 배정 요청의 파티원 목록으로 이어 간다). 메인
 * 스레드 전용.
 */
public final class PartyManager implements PartyService {

    /** 처리 결과. */
    public enum Result {
        OK,
        ALREADY_IN_PARTY,
        NOT_IN_PARTY,
        NOT_LEADER,
        FULL,
        NO_INVITE,
        SELF,
        TARGET_IN_PARTY,
        /** 보스전 중에는 파티를 바꿀 수 없다. */
        BUSY
    }

    /** 초대 한 건. */
    private record Invite(UUID party, UUID from, long expiresAt) {}

    /** 파티 하나 (가변, 내부용). members는 들어온 순서 = 역할 선택 순서 기준. */
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
     * @param busy 보스전 중인 플레이어 (그동안 파티 구성 변경 금지)
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

    // ------------------------------------------------------------------ 관리

    public Result create(UUID leader) {
        if (byPlayer.containsKey(leader)) {
            return Result.ALREADY_IN_PARTY;
        }
        Group g = new Group(leader);
        byPlayer.put(leader, g);
        byId.put(g.id, g);
        return Result.OK;
    }

    /** 초대 (파티가 없으면 먼저 만든다). */
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

    /** 받은 초대를 수락한다. */
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

    /** 누가 초대했는지 (안내용). */
    public Optional<UUID> inviter(UUID player, long now) {
        Invite invite = invites.get(player);
        return invite == null || invite.expiresAt() < now
                ? Optional.empty()
                : Optional.of(invite.from());
    }

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

    /** 보스전 역할 지정 (자기 역할, 파티장은 다른 사람 역할도). */
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

    /** 파티장 넘기기. */
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

    /** 파티에서 정한 보스전 역할 (정하지 않았으면 빈 값). */
    public Optional<BossRole> roleOf(UUID player) {
        Group g = byPlayer.get(player);
        return g == null ? Optional.empty() : Optional.ofNullable(g.members.get(player));
    }

    /** 파티원 순서 (파티장 먼저, 이후 들어온 순서). */
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

    /** 오래 접속하지 않은 파티원 정리 (퇴장 뒤 제한 시간이 지난 사람). */
    public void dropOffline(UUID player) {
        Group g = byPlayer.get(player);
        if (g != null && !busy.test(player)) {
            remove(g, player);
        }
        invites.remove(player);
    }

    /** 만료된 초대 정리. */
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
            // 혼자 남은 파티는 해산 (다시 만들면 된다)
            UUID last = g.leader;
            g.members.clear();
            byPlayer.remove(last);
            byId.remove(g.id);
        }
    }
}
