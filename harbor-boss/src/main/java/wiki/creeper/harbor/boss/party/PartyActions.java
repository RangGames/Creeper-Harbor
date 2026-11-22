package wiki.creeper.harbor.boss.party;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.boss.api.BossRole;
import wiki.creeper.harbor.boss.content.BossBalance;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.PartyService;

/**
 * The registered Core {@link PartyService}: party changes through {@link PartyManager} (the rules)
 * plus the chat notices to the other players involved. Shared by the /party commands and the API so
 * both behave the same. Main thread only.
 */
public final class PartyActions implements PartyService {

    private final HarborApi api;
    private final PartyManager parties;

    public PartyActions(HarborApi api, PartyManager parties) {
        this.api = api;
        this.parties = parties;
    }

    @Override
    public Optional<Party> partyOf(UUID player) {
        return parties.partyOf(player);
    }

    @Override
    public Result create(UUID leader) {
        return parties.create(leader);
    }

    @Override
    public Result invite(UUID leader, UUID target) {
        long ttl =
                new BossBalance(api.content().snapshot().single(Balance.TYPE)).inviteSeconds()
                        * 1000L;
        Result r = parties.invite(leader, target, System.currentTimeMillis(), ttl);
        if (r == Result.OK) {
            Player t = Bukkit.getPlayer(target);
            if (t != null) {
                api.messages()
                        .send(
                                t,
                                "boss.party.invited",
                                Placeholder.unparsed("player", name(leader)));
            }
        }
        return r;
    }

    @Override
    public Result accept(UUID player) {
        Result r = parties.join(player, System.currentTimeMillis());
        if (r == Result.OK) {
            notify(parties.membersOf(player), player, "boss.party.joined_other", name(player));
        }
        return r;
    }

    @Override
    public Result leave(UUID player) {
        List<UUID> before = parties.membersOf(player);
        Result r = parties.leave(player);
        if (r == Result.OK) {
            notify(before, player, "boss.party.left_other", name(player));
        }
        return r;
    }

    @Override
    public Result kick(UUID leader, UUID target) {
        Result r = parties.kick(leader, target);
        if (r == Result.OK) {
            Player t = Bukkit.getPlayer(target);
            if (t != null) {
                api.messages().send(t, "boss.party.kicked_you");
            }
        }
        return r;
    }

    @Override
    public Result disband(UUID leader) {
        List<UUID> before = parties.membersOf(leader);
        Result r = parties.disband(leader);
        if (r == Result.OK) {
            notify(before, leader, "boss.party.disbanded", name(leader));
        }
        return r;
    }

    @Override
    public Result promote(UUID leader, UUID target) {
        return parties.promote(leader, target);
    }

    @Override
    public Result setRole(UUID actor, UUID target, Role role) {
        Optional<BossRole> boss = BossRole.fromPartyRole(role);
        if (boss.isEmpty()) {
            return Result.INVALID_ROLE;
        }
        return parties.role(actor, target, boss.get());
    }

    @Override
    public Optional<UUID> pendingInvite(UUID player) {
        return parties.inviter(player, System.currentTimeMillis());
    }

    /** Player name for notices (short UUID if unknown). */
    public static String name(UUID uuid) {
        String n = Bukkit.getOfflinePlayer(uuid).getName();
        return n == null ? uuid.toString().substring(0, 8) : n;
    }

    private void notify(List<UUID> members, UUID except, String key, String name) {
        for (UUID u : members) {
            Player m = Bukkit.getPlayer(u);
            if (m != null && !u.equals(except)) {
                api.messages().send(m, key, Placeholder.unparsed("player", name));
            }
        }
    }
}
