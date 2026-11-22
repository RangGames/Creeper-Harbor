package wiki.creeper.harbor.proxy;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import wiki.creeper.harbor.common.account.AccountLinkReader.LinkedAccount;
import wiki.creeper.harbor.common.donor.DonorTier;
import wiki.creeper.harbor.common.redis.Redis;

/**
 * /hp sanction and lookup commands (docs 20 1.5).
 *
 * <ul>
 *   <li>{@code /hp ban|mute <player> [duration] [reason]}, {@code /hp unban|unmute <player>},
 *       {@code /hp kick <player> [reason]}: harbor.admin.proxy.sanction
 *   <li>{@code /hp lookup|alts <player>}, {@code /hp sharedip add|remove <player> [label]}:
 *       harbor.admin.proxy.lookup
 * </ul>
 *
 * The target need not be online (name → players · join_log, or UUID). Every command is logged to
 * admin_log.
 */
final class SanctionCommands {

    private static final int LOOKUP_ROWS = 5;

    /** Command target. */
    private record Target(UUID uuid, String name) {}

    private final ProxyServer proxy;
    private final ProxyConfig config;
    private final ProxyText text;
    private final SanctionService sanctions;
    private final AltDetector alts;
    private final JoinLog joinLog;
    private final Redis redis;
    private final Executor io;
    private final ProxyCommands.AdminLogger adminLog;

    SanctionCommands(
            ProxyServer proxy,
            ProxyConfig config,
            ProxyText text,
            SanctionService sanctions,
            AltDetector alts,
            JoinLog joinLog,
            Redis redis,
            Executor io,
            ProxyCommands.AdminLogger adminLog) {
        this.proxy = proxy;
        this.config = config;
        this.text = text;
        this.sanctions = sanctions;
        this.alts = alts;
        this.joinLog = joinLog;
        this.redis = redis;
        this.io = io;
        this.adminLog = adminLog;
    }

    List<LiteralArgumentBuilder<CommandSource>> nodes() {
        return List.of(
                issue("ban", Sanction.Type.BAN),
                issue("mute", Sanction.Type.MUTE),
                revoke("unban", Sanction.Type.BAN),
                revoke("unmute", Sanction.Type.MUTE),
                kick(),
                lookup(),
                altsNode(),
                sharedIp());
    }

    // ------------------------------------------------------------------ Sanctions

    private LiteralArgumentBuilder<CommandSource> issue(String name, Sanction.Type type) {
        return BrigadierCommand.literalArgumentBuilder(name)
                .requires(src -> src.hasPermission(ProxyPerms.SANCTION))
                .then(
                        target().executes(ctx -> issue(ctx, type, ""))
                                .then(
                                        BrigadierCommand.requiredArgumentBuilder(
                                                        "args", StringArgumentType.greedyString())
                                                .executes(
                                                        ctx ->
                                                                issue(
                                                                        ctx,
                                                                        type,
                                                                        StringArgumentType
                                                                                .getString(
                                                                                        ctx,
                                                                                        "args")))));
    }

    private int issue(CommandContext<CommandSource> ctx, Sanction.Type type, String rest) {
        CommandSource src = ctx.getSource();
        String first = SanctionTerm.firstToken(rest);
        if (SanctionTerm.looksLikeTerm(first)) {
            src.sendMessage(text.get("sanction.bad_term", Placeholder.unparsed("term", first)));
            return 0;
        }
        SanctionTerm fallback =
                type == Sanction.Type.BAN ? config.sanctions().ban() : config.sanctions().mute();
        SanctionTerm.Args args =
                SanctionTerm.parseArgs(rest, fallback, text.raw("sanction.default_reason"));
        String termLabel = args.term().describe(text.raw("common.permanent"));
        withTarget(
                ctx,
                target -> {
                    adminLog.record(
                            src,
                            type.name().toLowerCase(Locale.ROOT),
                            target.uuid().toString(),
                            target.name() + " · " + termLabel + " · " + args.reason());
                    return sanctions
                            .issue(target.uuid(), type, args.term(), args.reason(), issuer(src))
                            .thenAccept(
                                    sanction ->
                                            src.sendMessage(
                                                    text.get(
                                                            "sanction.issued",
                                                            Placeholder.unparsed(
                                                                    "name", target.name()),
                                                            Placeholder.unparsed(
                                                                    "type", type.name()),
                                                            Placeholder.unparsed("term", termLabel),
                                                            Placeholder.unparsed(
                                                                    "reason", args.reason()))));
                });
        return Command.SINGLE_SUCCESS;
    }

    private LiteralArgumentBuilder<CommandSource> revoke(String name, Sanction.Type type) {
        return BrigadierCommand.literalArgumentBuilder(name)
                .requires(src -> src.hasPermission(ProxyPerms.SANCTION))
                .then(
                        target().executes(
                                        ctx -> {
                                            CommandSource src = ctx.getSource();
                                            withTarget(
                                                    ctx,
                                                    target -> {
                                                        adminLog.record(
                                                                src,
                                                                name,
                                                                target.uuid().toString(),
                                                                target.name());
                                                        return sanctions
                                                                .revoke(
                                                                        target.uuid(),
                                                                        type,
                                                                        issuer(src))
                                                                .thenAccept(
                                                                        count ->
                                                                                src.sendMessage(
                                                                                        revoked(
                                                                                                target,
                                                                                                type,
                                                                                                count)));
                                                    });
                                            return Command.SINGLE_SUCCESS;
                                        }));
    }

    private net.kyori.adventure.text.Component revoked(
            Target target, Sanction.Type type, int count) {
        return count == 0
                ? text.get(
                        "sanction.none",
                        Placeholder.unparsed("name", target.name()),
                        Placeholder.unparsed("type", type.name()))
                : text.get(
                        "sanction.revoked",
                        Placeholder.unparsed("name", target.name()),
                        Placeholder.unparsed("type", type.name()),
                        Placeholder.unparsed("count", String.valueOf(count)));
    }

    private LiteralArgumentBuilder<CommandSource> kick() {
        return BrigadierCommand.literalArgumentBuilder("kick")
                .requires(src -> src.hasPermission(ProxyPerms.SANCTION))
                .then(
                        target().executes(ctx -> kick(ctx, ""))
                                .then(
                                        BrigadierCommand.requiredArgumentBuilder(
                                                        "reason", StringArgumentType.greedyString())
                                                .executes(
                                                        ctx ->
                                                                kick(
                                                                        ctx,
                                                                        StringArgumentType
                                                                                .getString(
                                                                                        ctx,
                                                                                        "reason")))));
    }

    /** A kick is only logged and creates no sanction row (sanctions holds only BAN and MUTE). */
    private int kick(CommandContext<CommandSource> ctx, String rawReason) {
        CommandSource src = ctx.getSource();
        Optional<Player> player = proxy.getPlayer(StringArgumentType.getString(ctx, "target"));
        if (player.isEmpty()) {
            src.sendMessage(text.get("chat.no_target"));
            return 0;
        }
        String reason = rawReason.isBlank() ? text.raw("sanction.default_reason") : rawReason;
        adminLog.record(src, "kick", player.get().getUniqueId().toString(), reason);
        joinLog.markKick(player.get().getUniqueId(), "kick: " + reason);
        player.get().disconnect(text.get("sanction.kick", Placeholder.unparsed("reason", reason)));
        src.sendMessage(text.get("admin.done"));
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ Lookups

    private LiteralArgumentBuilder<CommandSource> lookup() {
        return BrigadierCommand.literalArgumentBuilder("lookup")
                .requires(src -> src.hasPermission(ProxyPerms.LOOKUP))
                .then(
                        target().executes(
                                        ctx -> {
                                            CommandSource src = ctx.getSource();
                                            withTarget(
                                                    ctx,
                                                    target -> {
                                                        adminLog.record(
                                                                src,
                                                                "lookup",
                                                                target.uuid().toString(),
                                                                target.name());
                                                        return showLookup(src, target);
                                                    });
                                            return Command.SINGLE_SUCCESS;
                                        }));
    }

    private CompletableFuture<Void> showLookup(CommandSource src, Target target) {
        CompletableFuture<DonorTier> donor =
                CompletableFuture.supplyAsync(() -> DonorTier.lookup(redis, target.uuid()), io);
        CompletableFuture<List<JoinLog.Entry>> joins = joinLog.recent(target.uuid(), LOOKUP_ROWS);
        CompletableFuture<List<Sanction>> active = sanctions.active(target.uuid());
        return CompletableFuture.allOf(donor, joins, active)
                .thenRun(
                        () -> {
                            boolean online = proxy.getPlayer(target.uuid()).isPresent();
                            src.sendMessage(
                                    text.get(
                                            "lookup.header",
                                            Placeholder.unparsed("name", target.name()),
                                            Placeholder.unparsed("uuid", target.uuid().toString()),
                                            Placeholder.unparsed("online", online ? "O" : "X"),
                                            Placeholder.unparsed("tier", donor.join().name())));
                            for (JoinLog.Entry entry : joins.join()) {
                                String brand = entry.brand();
                                String server = entry.firstServer();
                                String kick = entry.kickReason();
                                src.sendMessage(
                                        text.get(
                                                "lookup.join_line",
                                                Placeholder.unparsed(
                                                        "time", text.time(entry.joinedAt())),
                                                Placeholder.unparsed(
                                                        "server",
                                                        server == null
                                                                ? text.raw("common.none")
                                                                : server),
                                                Placeholder.unparsed(
                                                        "protocol",
                                                        String.valueOf(entry.protocol())),
                                                Placeholder.unparsed(
                                                        "brand",
                                                        brand == null
                                                                ? text.raw("common.none")
                                                                : brand),
                                                Placeholder.unparsed(
                                                        "ip", shortHash(entry.ipHash())),
                                                Placeholder.unparsed(
                                                        "kick", kick == null ? "" : " · " + kick)));
                            }
                            for (Sanction sanction : active.join()) {
                                src.sendMessage(
                                        text.get(
                                                "lookup.sanction_line",
                                                Placeholder.unparsed(
                                                        "type", sanction.type().name()),
                                                Placeholder.unparsed("reason", sanction.reason()),
                                                Placeholder.unparsed(
                                                        "until", text.time(sanction.expiresAt())),
                                                Placeholder.unparsed("by", sanction.issuedBy())));
                            }
                        });
    }

    private LiteralArgumentBuilder<CommandSource> altsNode() {
        return BrigadierCommand.literalArgumentBuilder("alts")
                .requires(src -> src.hasPermission(ProxyPerms.LOOKUP))
                .then(
                        target().executes(
                                        ctx -> {
                                            CommandSource src = ctx.getSource();
                                            withTarget(
                                                    ctx,
                                                    target -> {
                                                        adminLog.record(
                                                                src,
                                                                "alts",
                                                                target.uuid().toString(),
                                                                target.name());
                                                        return showAlts(src, target);
                                                    });
                                            return Command.SINGLE_SUCCESS;
                                        }));
    }

    private CompletableFuture<Void> showAlts(CommandSource src, Target target) {
        return alts.linked(target.uuid())
                .thenCompose(
                        list ->
                                joinLog.names(list.stream().map(LinkedAccount::player).toList())
                                        .thenAccept(names -> printAlts(src, target, list, names)));
    }

    private void printAlts(
            CommandSource src, Target target, List<LinkedAccount> list, Map<UUID, String> names) {
        src.sendMessage(
                text.get(
                        "lookup.alts_header",
                        Placeholder.unparsed("name", target.name()),
                        Placeholder.unparsed("count", String.valueOf(list.size()))));
        for (LinkedAccount account : list) {
            src.sendMessage(
                    text.get(
                            "lookup.alt_line",
                            Placeholder.unparsed(
                                    "name",
                                    names.getOrDefault(
                                            account.player(), account.player().toString())),
                            Placeholder.unparsed("ip", shortHash(account.ipHash())),
                            Placeholder.unparsed("hits", String.valueOf(account.hits())),
                            Placeholder.unparsed("last", text.time(account.lastSeen())),
                            Placeholder.parsed(
                                    "shared",
                                    account.sharedIp() ? text.raw("lookup.alt_shared") : "")));
        }
    }

    private LiteralArgumentBuilder<CommandSource> sharedIp() {
        return BrigadierCommand.literalArgumentBuilder("sharedip")
                .requires(src -> src.hasPermission(ProxyPerms.LOOKUP))
                .then(
                        BrigadierCommand.literalArgumentBuilder("add")
                                .then(
                                        target().executes(ctx -> addShared(ctx, ""))
                                                .then(
                                                        BrigadierCommand.requiredArgumentBuilder(
                                                                        "label",
                                                                        StringArgumentType
                                                                                .greedyString())
                                                                .executes(
                                                                        ctx ->
                                                                                addShared(
                                                                                        ctx,
                                                                                        StringArgumentType
                                                                                                .getString(
                                                                                                        ctx,
                                                                                                        "label"))))))
                .then(
                        BrigadierCommand.literalArgumentBuilder("remove")
                                .then(target().executes(this::removeShared)));
    }

    private int removeShared(CommandContext<CommandSource> ctx) {
        CommandSource src = ctx.getSource();
        withTarget(
                ctx,
                target -> {
                    adminLog.record(
                            src, "sharedip.remove", target.uuid().toString(), target.name());
                    return alts.removeShared(target.uuid())
                            .thenAccept(
                                    count ->
                                            src.sendMessage(
                                                    text.get(
                                                            "lookup.shared_removed",
                                                            Placeholder.unparsed(
                                                                    "name", target.name()),
                                                            Placeholder.unparsed(
                                                                    "count",
                                                                    String.valueOf(count)))));
                });
        return Command.SINGLE_SUCCESS;
    }

    private int addShared(CommandContext<CommandSource> ctx, String rawLabel) {
        CommandSource src = ctx.getSource();
        withTarget(
                ctx,
                target -> {
                    String label = rawLabel.isBlank() ? target.name() : rawLabel.strip();
                    adminLog.record(src, "sharedip.add", target.uuid().toString(), label);
                    return alts.addShared(target.uuid(), label, issuer(src))
                            .thenAccept(
                                    hash ->
                                            src.sendMessage(
                                                    hash.isEmpty()
                                                            ? text.get(
                                                                    "lookup.no_ip",
                                                                    Placeholder.unparsed(
                                                                            "name", target.name()))
                                                            : text.get(
                                                                    "lookup.shared_added",
                                                                    Placeholder.unparsed(
                                                                            "name", target.name()),
                                                                    Placeholder.unparsed(
                                                                            "ip",
                                                                            shortHash(hash)))));
                });
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ Helpers

    private RequiredArgumentBuilder<CommandSource, String> target() {
        return BrigadierCommand.requiredArgumentBuilder("target", StringArgumentType.word())
                .suggests(
                        (ctx, builder) -> {
                            proxy.getAllPlayers().forEach(p -> builder.suggest(p.getUsername()));
                            return builder.buildFuture();
                        });
    }

    /**
     * Finds the target and runs the task. Notifies the command sender if not found or on failure.
     */
    private void withTarget(
            CommandContext<CommandSource> ctx,
            java.util.function.Function<Target, CompletableFuture<Void>> work) {
        CommandSource src = ctx.getSource();
        String input = StringArgumentType.getString(ctx, "target");
        var _ =
                resolve(input)
                        .thenCompose(
                                target -> {
                                    if (target.isEmpty()) {
                                        src.sendMessage(
                                                text.get(
                                                        "lookup.not_found",
                                                        Placeholder.unparsed("name", input)));
                                        return CompletableFuture.completedFuture(null);
                                    }
                                    return work.apply(target.get());
                                })
                        .exceptionally(
                                e -> {
                                    src.sendMessage(
                                            text.get(
                                                    "sanction.failed",
                                                    Placeholder.unparsed(
                                                            "reason",
                                                            String.valueOf(e.getMessage()))));
                                    return null;
                                });
    }

    private CompletableFuture<Optional<Target>> resolve(String input) {
        Optional<Player> online = proxy.getPlayer(input);
        if (online.isPresent()) {
            return CompletableFuture.completedFuture(
                    Optional.of(
                            new Target(online.get().getUniqueId(), online.get().getUsername())));
        }
        try {
            UUID uuid = UUID.fromString(input);
            return CompletableFuture.completedFuture(Optional.of(new Target(uuid, input)));
        } catch (IllegalArgumentException e) {
            return joinLog.resolve(input).thenApply(found -> found.map(u -> new Target(u, input)));
        }
    }

    private static String issuer(CommandSource src) {
        return src instanceof Player player ? player.getUniqueId().toString() : "CONSOLE";
    }

    /** First 8 characters of the IP hash (for staff to eyeball whether IPs match). */
    private static String shortHash(String hash) {
        return hash.length() > 8 ? hash.substring(0, 8) : hash;
    }
}
