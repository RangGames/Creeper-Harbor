package wiki.creeper.harbor.economy.service;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.economy.content.ShopDef;
import wiki.creeper.harbor.economy.content.StationDef;

/** The /harbor market|mail|npcshop|auction|econ|craft admin subcommands of the economy module. */
final class EconomyAdminCommands {

    private final HarborApi api;
    private final MarketEngine market;
    private final MailManager mail;
    private final ShopManager shops;
    private final AuctionHouse auction;
    private final CraftManager craft;
    private final FraudMonitor fraud;
    private final StationManager stations;

    EconomyAdminCommands(
            HarborApi api,
            MarketEngine market,
            MailManager mail,
            ShopManager shops,
            AuctionHouse auction,
            CraftManager craft,
            FraudMonitor fraud,
            StationManager stations) {
        this.api = api;
        this.market = market;
        this.mail = mail;
        this.shops = shops;
        this.auction = auction;
        this.craft = craft;
        this.fraud = fraud;
        this.stations = stations;
    }

    private int done(CommandContext<CommandSourceStack> ctx, boolean ok) {
        api.messages()
                .send(
                        ctx.getSource().getSender(),
                        ok ? "economy.admin.done" : "economy.admin.failed");
        return Command.SINGLE_SUCCESS;
    }

    /**
     * /harbor market|mail|shop|auction|econ|craft (Core applies the harbor.admin.<name>
     * permission).
     */
    List<LiteralArgumentBuilder<CommandSourceStack>> commands() {
        LiteralArgumentBuilder<CommandSourceStack> marketCmd =
                Commands.literal("market")
                        .then(
                                Commands.literal("recompute")
                                        .executes(
                                                ctx -> {
                                                    var _ =
                                                            api.database()
                                                                    .executor()
                                                                    .submit(market::recompute);
                                                    return done(ctx, true);
                                                }))
                        .then(
                                Commands.literal("set")
                                        .then(
                                                Commands.argument("fish", ArgumentTypes.key())
                                                        .then(
                                                                Commands.argument(
                                                                                "coef",
                                                                                DoubleArgumentType
                                                                                        .doubleArg(
                                                                                                0.1,
                                                                                                5))
                                                                        .executes(
                                                                                ctx -> {
                                                                                    market.override(
                                                                                            new FishId(
                                                                                                    EconomyCommands
                                                                                                            .harborId(
                                                                                                                    ctx
                                                                                                                            .getArgument(
                                                                                                                                    "fish",
                                                                                                                                    Key
                                                                                                                                            .class))),
                                                                                            DoubleArgumentType
                                                                                                    .getDouble(
                                                                                                            ctx,
                                                                                                            "coef"));
                                                                                    return done(
                                                                                            ctx,
                                                                                            true);
                                                                                }))));
        LiteralArgumentBuilder<CommandSourceStack> mailCmd =
                Commands.literal("mail")
                        .then(
                                Commands.literal("gold")
                                        .then(
                                                Commands.argument("target", ArgumentTypes.player())
                                                        .then(
                                                                Commands.argument(
                                                                                "amount",
                                                                                LongArgumentType
                                                                                        .longArg(1))
                                                                        .executes(this::mailGold))))
                        .then(
                                Commands.literal("held")
                                        .then(
                                                Commands.argument("target", ArgumentTypes.player())
                                                        .executes(this::mailHeld)));
        LiteralArgumentBuilder<CommandSourceStack> shopCmd =
                Commands.literal("npcshop")
                        .then(
                                Commands.literal("open")
                                        .then(
                                                Commands.argument("shop", ArgumentTypes.key())
                                                        .suggests(
                                                                (ctx, b) -> {
                                                                    api.content()
                                                                            .snapshot()
                                                                            .ids(
                                                                                    ShopDef.TYPE
                                                                                            .name())
                                                                            .forEach(
                                                                                    id ->
                                                                                            b
                                                                                                    .suggest(
                                                                                                            id
                                                                                                                    .toString()));
                                                                    return b.buildFuture();
                                                                })
                                                        .executes(
                                                                ctx ->
                                                                        done(
                                                                                ctx,
                                                                                ctx.getSource()
                                                                                                        .getExecutor()
                                                                                                instanceof
                                                                                                Player
                                                                                                        p
                                                                                        && shops
                                                                                                .open(
                                                                                                        p,
                                                                                                        EconomyCommands
                                                                                                                .harborId(
                                                                                                                        ctx
                                                                                                                                .getArgument(
                                                                                                                                        "shop",
                                                                                                                                        Key
                                                                                                                                                .class)))))));
        LiteralArgumentBuilder<CommandSourceStack> auctionCmd =
                Commands.literal("auction")
                        .then(
                                Commands.literal("cancel")
                                        .then(
                                                Commands.argument("id", LongArgumentType.longArg(1))
                                                        .executes(
                                                                ctx -> {
                                                                    CommandSender sender =
                                                                            ctx.getSource()
                                                                                    .getSender();
                                                                    var _ =
                                                                            auction.adminCancel(
                                                                                            LongArgumentType
                                                                                                    .getLong(
                                                                                                            ctx,
                                                                                                            "id"))
                                                                                    .thenAccept(
                                                                                            ok ->
                                                                                                    api.messages()
                                                                                                            .send(
                                                                                                                    sender,
                                                                                                                    ok
                                                                                                                            ? "economy.admin.done"
                                                                                                                            : "economy.admin.failed"));
                                                                    return Command.SINGLE_SUCCESS;
                                                                })))
                        .then(
                                Commands.literal("list")
                                        .then(
                                                Commands.argument("target", ArgumentTypes.player())
                                                        .executes(this::auctionList)))
                        .then(
                                Commands.literal("expire")
                                        .executes(
                                                ctx -> {
                                                    var _ =
                                                            api.database()
                                                                    .executor()
                                                                    .submit(auction::expireDue);
                                                    return done(ctx, true);
                                                }));
        LiteralArgumentBuilder<CommandSourceStack> econCmd =
                Commands.literal("econ")
                        .then(Commands.literal("report").executes(this::report))
                        .then(Commands.literal("flags").executes(this::flags))
                        .then(
                                Commands.literal("flag")
                                        .then(
                                                Commands.literal("release")
                                                        .then(
                                                                Commands.argument(
                                                                                "id",
                                                                                LongArgumentType
                                                                                        .longArg(1))
                                                                        .executes(
                                                                                ctx ->
                                                                                        flagAction(
                                                                                                ctx,
                                                                                                true))))
                                        .then(
                                                Commands.literal("resolve")
                                                        .then(
                                                                Commands.argument(
                                                                                "id",
                                                                                LongArgumentType
                                                                                        .longArg(1))
                                                                        .executes(
                                                                                ctx ->
                                                                                        flagAction(
                                                                                                ctx,
                                                                                                false)))))
                        .then(
                                Commands.literal("stations")
                                        .executes(
                                                ctx -> {
                                                    stations.respawn();
                                                    return done(ctx, true);
                                                }));
        LiteralArgumentBuilder<CommandSourceStack> craftCmd =
                Commands.literal("craft")
                        .then(
                                Commands.literal("open")
                                        .then(
                                                Commands.argument(
                                                                "target",
                                                                StringArgumentType.greedyString())
                                                        .suggests(
                                                                (ctx, b) -> {
                                                                    api.content()
                                                                            .snapshot()
                                                                            .ids(
                                                                                    StationDef.TYPE
                                                                                            .name())
                                                                            .forEach(
                                                                                    id ->
                                                                                            b
                                                                                                    .suggest(
                                                                                                            id
                                                                                                                    .toString()));
                                                                    List.of(
                                                                                    "workshop",
                                                                                    "cooking",
                                                                                    "shipyard")
                                                                            .forEach(b::suggest);
                                                                    return b.buildFuture();
                                                                })
                                                        .executes(this::craftOpen)));
        return List.of(marketCmd, mailCmd, shopCmd, auctionCmd, econCmd, craftCmd);
    }

    private int craftOpen(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getExecutor() instanceof Player p)) {
            return done(ctx, false);
        }
        String target = StringArgumentType.getString(ctx, "target").trim();
        boolean ok =
                craft.openKind(p, target)
                        || HarborId.tryParse(target)
                                .map(id -> craft.openStation(p, id))
                                .orElse(false);
        if (!ok) {
            return done(ctx, false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int auctionList(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player target =
                ctx.getArgument("target", PlayerSelectorArgumentResolver.class)
                        .resolve(ctx.getSource())
                        .getFirst();
        CommandSender sender = ctx.getSource().getSender();
        var _ =
                auction.listingsOf(target.getUniqueId())
                        .thenAccept(
                                rows -> {
                                    if (rows.isEmpty()) {
                                        api.messages().send(sender, "economy.admin.empty");
                                    }
                                    rows.forEach(
                                            row ->
                                                    api.messages()
                                                            .send(
                                                                    sender,
                                                                    "economy.admin.line",
                                                                    Placeholder.unparsed(
                                                                            "line",
                                                                            AuctionHouse.describe(
                                                                                    row))));
                                });
        return Command.SINGLE_SUCCESS;
    }

    private int report(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        var _ =
                fraud.report()
                        .thenAccept(
                                r -> {
                                    api.messages()
                                            .send(
                                                    sender,
                                                    "economy.admin.report.header",
                                                    Placeholder.unparsed(
                                                            "earned", String.valueOf(r.earned())),
                                                    Placeholder.unparsed(
                                                            "spent", String.valueOf(r.spent())),
                                                    Placeholder.unparsed(
                                                            "rate",
                                                            Double.isNaN(r.destructionRate())
                                                                    ? "-"
                                                                    : String.format(
                                                                            Locale.ROOT,
                                                                            "%.1f%%",
                                                                            r.destructionRate()
                                                                                    * 100)));
                                    r.reasons()
                                            .forEach(
                                                    (reason, sum) ->
                                                            api.messages()
                                                                    .send(
                                                                            sender,
                                                                            "economy.admin.report.reason",
                                                                            Placeholder.unparsed(
                                                                                    "reason",
                                                                                    reason),
                                                                            Placeholder.unparsed(
                                                                                    "plus",
                                                                                    String.valueOf(
                                                                                            sum
                                                                                                    .plus())),
                                                                            Placeholder.unparsed(
                                                                                    "minus",
                                                                                    String.valueOf(
                                                                                            sum
                                                                                                    .minus())),
                                                                            Placeholder.unparsed(
                                                                                    "count",
                                                                                    String.valueOf(
                                                                                            sum
                                                                                                    .count()))));
                                    var rules = market.balance().fraud();
                                    double index = r.auctionIndex();
                                    boolean warn =
                                            !Double.isNaN(index)
                                                    && (index < rules.indexMin()
                                                            || index > rules.indexMax());
                                    api.messages()
                                            .send(
                                                    sender,
                                                    warn
                                                            ? "economy.admin.report.index_warn"
                                                            : "economy.admin.report.index",
                                                    Placeholder.unparsed(
                                                            "index",
                                                            Double.isNaN(index)
                                                                    ? "-"
                                                                    : EconomyCommands.format(
                                                                            index)),
                                                    Placeholder.unparsed(
                                                            "samples",
                                                            String.valueOf(r.indexSamples())),
                                                    Placeholder.unparsed(
                                                            "flags",
                                                            String.valueOf(r.openFlags())));
                                });
        return Command.SINGLE_SUCCESS;
    }

    private int flags(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        var _ =
                fraud.open(20)
                        .thenAccept(
                                list -> {
                                    if (list.isEmpty()) {
                                        api.messages().send(sender, "economy.admin.empty");
                                    }
                                    for (FraudMonitor.Flag flag : list) {
                                        api.messages()
                                                .send(
                                                        sender,
                                                        "economy.admin.flag_line",
                                                        Placeholder.unparsed(
                                                                "id", String.valueOf(flag.id())),
                                                        Placeholder.unparsed("kind", flag.kind()),
                                                        Placeholder.unparsed("ref", flag.refId()),
                                                        Placeholder.unparsed(
                                                                "score",
                                                                String.valueOf((int) flag.score())),
                                                        Placeholder.unparsed(
                                                                "detail", flag.detail()),
                                                        Placeholder.unparsed(
                                                                "held",
                                                                flag.held() ? "HOLD" : "-"));
                                    }
                                });
        return Command.SINGLE_SUCCESS;
    }

    /** release: releases the held proceeds now / resolve: marks the flag as handled. */
    private int flagAction(CommandContext<CommandSourceStack> ctx, boolean release) {
        CommandSender sender = ctx.getSource().getSender();
        long id = LongArgumentType.getLong(ctx, "id");
        if (release) {
            var _ =
                    fraud.refOf(id)
                            .thenCompose(
                                    ref ->
                                            ref.isEmpty()
                                                    ? java.util.concurrent.CompletableFuture
                                                            .completedFuture(0)
                                                    : mail.releaseHold(ref))
                            .thenAccept(
                                    n ->
                                            api.messages()
                                                    .send(
                                                            sender,
                                                            n > 0
                                                                    ? "economy.admin.done"
                                                                    : "economy.admin.failed"));
        } else {
            var _ =
                    fraud.resolve(id, sender.getName())
                            .thenAccept(
                                    ref ->
                                            api.messages()
                                                    .send(
                                                            sender,
                                                            ref.isEmpty()
                                                                    ? "economy.admin.failed"
                                                                    : "economy.admin.done"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int mailGold(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player target =
                ctx.getArgument("target", PlayerSelectorArgumentResolver.class)
                        .resolve(ctx.getSource())
                        .getFirst();
        var _ =
                mail.sendGold(
                        target.getUniqueId(),
                        LongArgumentType.getLong(ctx, "amount"),
                        "admin",
                        null);
        return done(ctx, true);
    }

    /** Sends the item in the admin's hand to the target's mailbox (reward resend). */
    private int mailHeld(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player target =
                ctx.getArgument("target", PlayerSelectorArgumentResolver.class)
                        .resolve(ctx.getSource())
                        .getFirst();
        if (!(ctx.getSource().getExecutor() instanceof Player admin)
                || admin.getInventory().getItemInMainHand().isEmpty()) {
            return done(ctx, false);
        }
        var _ =
                mail.sendItem(
                        target.getUniqueId(),
                        admin.getInventory().getItemInMainHand().clone(),
                        "admin",
                        null);
        return done(ctx, true);
    }
}
