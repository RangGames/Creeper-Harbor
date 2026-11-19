package wiki.creeper.harbor.boss.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.boss.api.BossRole;
import wiki.creeper.harbor.boss.party.PartyManager;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.PartyService;

/**
 * /party (/파티) create · invite · join · leave · kick · disband · role · promote (docs 20 10.5, 권한
 * harbor.party).
 */
public final class PartyCommands {

    private final HarborApi api;
    private final PartyManager parties;

    public PartyCommands(HarborApi api, PartyManager parties) {
        this.api = api;
        this.parties = parties;
    }

    public void register(Commands commands) {
        LiteralArgumentBuilder<CommandSourceStack> root =
                Commands.literal("파티")
                        .requires(
                                src ->
                                        src.getExecutor() instanceof Player
                                                && src.getSender().hasPermission("harbor.party"))
                        .executes(this::info)
                        .then(Commands.literal("만들기").executes(this::create))
                        .then(Commands.literal("초대").then(target().executes(this::invite)))
                        .then(Commands.literal("수락").executes(this::join))
                        .then(Commands.literal("나가기").executes(this::leave))
                        .then(Commands.literal("추방").then(target().executes(this::kick)))
                        .then(Commands.literal("해산").executes(this::disband))
                        .then(Commands.literal("위임").then(target().executes(this::promote)))
                        .then(
                                Commands.literal("역할")
                                        .then(
                                                roleArg()
                                                        .executes(ctx -> role(ctx, false))
                                                        .then(
                                                                target().executes(
                                                                                ctx ->
                                                                                        role(
                                                                                                ctx,
                                                                                                true)))));
        commands.register(root.build(), "파티 (보스전)", List.of());
    }

    static RequiredArgumentBuilder<CommandSourceStack, String> roleArg() {
        return Commands.argument("role", StringArgumentType.word())
                .suggests(
                        (ctx, b) -> {
                            for (BossRole r : BossRole.values()) {
                                b.suggest(r.key());
                            }
                            return b.buildFuture();
                        });
    }

    static Optional<BossRole> role(CommandContext<CommandSourceStack> ctx) {
        return Keyed.fromKey(BossRole.class, StringArgumentType.getString(ctx, "role"));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, PlayerSelectorArgumentResolver>
            target() {
        return Commands.argument("target", ArgumentTypes.player());
    }

    private static Player target(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        return ctx.getArgument("target", PlayerSelectorArgumentResolver.class)
                .resolve(ctx.getSource())
                .getFirst();
    }

    private static Player self(CommandContext<CommandSourceStack> ctx) {
        return java.util.Objects.requireNonNull((Player) ctx.getSource().getExecutor());
    }

    private int done(Player p, PartyManager.Result r, String okKey, String target) {
        if (r == PartyManager.Result.OK) {
            api.messages().send(p, okKey, Placeholder.unparsed("player", target));
            return Command.SINGLE_SUCCESS;
        }
        api.messages().send(p, "boss.party.error." + r.name().toLowerCase(java.util.Locale.ROOT));
        return 0;
    }

    private int info(CommandContext<CommandSourceStack> ctx) {
        Player p = self(ctx);
        Optional<PartyService.Party> party = parties.partyOf(p.getUniqueId());
        if (party.isEmpty()) {
            api.messages().send(p, "boss.party.none");
            return Command.SINGLE_SUCCESS;
        }
        api.messages()
                .send(
                        p,
                        "boss.party.header",
                        Placeholder.unparsed("count", String.valueOf(party.get().members().size())),
                        Placeholder.unparsed("max", String.valueOf(PartyService.MAX_MEMBERS)));
        for (UUID u : parties.ordered(p.getUniqueId())) {
            String role = parties.roleOf(u).map(BossRole::key).orElse("none");
            api.messages()
                    .send(
                            p,
                            "boss.party.line",
                            Placeholder.unparsed("player", name(u)),
                            Placeholder.component("role", api.messages().get("boss.role." + role)),
                            Placeholder.unparsed(
                                    "leader", u.equals(party.get().leader()) ? "★" : ""),
                            Placeholder.component(
                                    "online",
                                    Bukkit.getPlayer(u) != null
                                            ? net.kyori.adventure.text.Component.empty()
                                            : api.messages().get("boss.party.offline")));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static String name(UUID u) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(u);
        String n = op.getName();
        return n == null ? u.toString().substring(0, 8) : n;
    }

    private int create(CommandContext<CommandSourceStack> ctx) {
        Player p = self(ctx);
        return done(p, parties.create(p.getUniqueId()), "boss.party.created", p.getName());
    }

    private int invite(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = self(ctx);
        Player t = target(ctx);
        long ttl =
                new wiki.creeper.harbor.boss.content.BossBalance(
                                        api.content()
                                                .snapshot()
                                                .single(
                                                        wiki.creeper.harbor.common.content.type
                                                                .Balance.TYPE))
                                .inviteSeconds()
                        * 1000L;
        PartyManager.Result r =
                parties.invite(p.getUniqueId(), t.getUniqueId(), System.currentTimeMillis(), ttl);
        if (r == PartyManager.Result.OK) {
            api.messages()
                    .send(t, "boss.party.invited", Placeholder.unparsed("player", p.getName()));
        }
        return done(p, r, "boss.party.invite_sent", t.getName());
    }

    private int join(CommandContext<CommandSourceStack> ctx) {
        Player p = self(ctx);
        Optional<UUID> from = parties.inviter(p.getUniqueId(), System.currentTimeMillis());
        PartyManager.Result r = parties.join(p.getUniqueId(), System.currentTimeMillis());
        if (r == PartyManager.Result.OK) {
            for (UUID u : parties.membersOf(p.getUniqueId())) {
                Player m = Bukkit.getPlayer(u);
                if (m != null && !m.equals(p)) {
                    api.messages()
                            .send(
                                    m,
                                    "boss.party.joined_other",
                                    Placeholder.unparsed("player", p.getName()));
                }
            }
        }
        return done(p, r, "boss.party.joined", from.map(PartyCommands::name).orElse("?"));
    }

    private int leave(CommandContext<CommandSourceStack> ctx) {
        Player p = self(ctx);
        List<UUID> before = parties.membersOf(p.getUniqueId());
        PartyManager.Result r = parties.leave(p.getUniqueId());
        if (r == PartyManager.Result.OK) {
            notify(before, p.getUniqueId(), "boss.party.left_other", p.getName());
        }
        return done(p, r, "boss.party.left", p.getName());
    }

    private int kick(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = self(ctx);
        Player t = target(ctx);
        PartyManager.Result r = parties.kick(p.getUniqueId(), t.getUniqueId());
        if (r == PartyManager.Result.OK) {
            api.messages().send(t, "boss.party.kicked_you");
        }
        return done(p, r, "boss.party.kicked", t.getName());
    }

    private int disband(CommandContext<CommandSourceStack> ctx) {
        Player p = self(ctx);
        List<UUID> before = parties.membersOf(p.getUniqueId());
        PartyManager.Result r = parties.disband(p.getUniqueId());
        if (r == PartyManager.Result.OK) {
            notify(before, p.getUniqueId(), "boss.party.disbanded", p.getName());
        }
        return done(p, r, "boss.party.disbanded", p.getName());
    }

    private int promote(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = self(ctx);
        Player t = target(ctx);
        return done(
                p,
                parties.promote(p.getUniqueId(), t.getUniqueId()),
                "boss.party.promoted",
                t.getName());
    }

    private int role(CommandContext<CommandSourceStack> ctx, boolean other)
            throws CommandSyntaxException {
        Player p = self(ctx);
        Optional<BossRole> role = role(ctx);
        if (role.isEmpty()) {
            api.messages().send(p, "boss.role.unknown");
            return 0;
        }
        Player t = other ? target(ctx) : p;
        PartyManager.Result r = parties.role(p.getUniqueId(), t.getUniqueId(), role.get());
        if (r == PartyManager.Result.OK) {
            api.messages()
                    .send(
                            p,
                            "boss.party.role_set",
                            Placeholder.unparsed("player", t.getName()),
                            Placeholder.component(
                                    "role", api.messages().get("boss.role." + role.get().key())));
            return Command.SINGLE_SUCCESS;
        }
        return done(p, r, "boss.party.role_set", t.getName());
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
