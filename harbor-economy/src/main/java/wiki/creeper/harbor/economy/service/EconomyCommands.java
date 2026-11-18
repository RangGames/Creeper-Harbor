package wiki.creeper.harbor.economy.service;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.economy.content.ShopDef;
import wiki.creeper.harbor.economy.content.StationDef;

/**
 * /판매, /우편함, /수리, /시세, /경매, /제작, /큰손, /harbor market·mail·shop·auction·econ·craft (docs 20 7.5).
 */
public final class EconomyCommands {

    private static final StatKey MARKET_INFO = StatKey.of("market_info");
    private static final StatKey FORECAST = StatKey.of("feature_market_forecast");

    private final HarborApi api;
    private final MarketEngine market;
    private final SellService sell;
    private final MailManager mail;
    private final ShopManager shops;
    private final RepairManager repair;
    private final AuctionHouse auction;
    private final CraftManager craft;
    private final FraudMonitor fraud;
    private final StationManager stations;

    public EconomyCommands(
            HarborApi api,
            MarketEngine market,
            SellService sell,
            MailManager mail,
            ShopManager shops,
            RepairManager repair,
            AuctionHouse auction,
            CraftManager craft,
            FraudMonitor fraud,
            StationManager stations) {
        this.api = api;
        this.market = market;
        this.sell = sell;
        this.mail = mail;
        this.shops = shops;
        this.repair = repair;
        this.auction = auction;
        this.craft = craft;
        this.fraud = fraud;
        this.stations = stations;
    }

    private static boolean player(CommandSourceStack src, String permission) {
        return src.getExecutor() instanceof Player && src.getSender().hasPermission(permission);
    }

    public void registerPlayerCommands(Commands commands) {
        commands.register(
                Commands.literal("판매")
                        .requires(src -> player(src, "harbor.economy.sell"))
                        .executes(ctx -> run(ctx, p -> sell.open(p, false)))
                        .then(
                                Commands.literal("손")
                                        .executes(ctx -> run(ctx, p -> sell.open(p, true))))
                        .build(),
                "어종 판매",
                List.of("sell"));
        commands.register(player("우편함", "harbor.economy.mail", mail::open), "우편함", List.of("mail"));
        commands.register(
                player("수리", "harbor.economy.repair", repair::open), "장비 수리", List.of("repair"));
        commands.register(
                player("큰손", "harbor.economy.big_deal", sell::bigDeal),
                "큰손 거래 (경제 계열 캡스톤)",
                List.of("bigdeal"));
        commands.register(
                player(
                        "제작",
                        "harbor.economy.craft",
                        p -> {
                            if (!craft.openNearby(p)) {
                                api.messages().send(p, "economy.craft.no_station");
                            }
                        }),
                "제작 스테이션",
                List.of("craft"));
        commands.register(auctionCommand(), "경매장", List.of("auction"));
        commands.register(
                Commands.literal("시세")
                        .requires(src -> src.getSender().hasPermission("harbor.economy.market"))
                        .then(
                                Commands.argument("fish", ArgumentTypes.key())
                                        .suggests(
                                                (ctx, b) -> {
                                                    api.content()
                                                            .snapshot()
                                                            .ids(FishDef.TYPE.name())
                                                            .forEach(
                                                                    id -> b.suggest(id.toString()));
                                                    return b.buildFuture();
                                                })
                                        .executes(this::price))
                        .build(),
                "시세",
                List.of("market"));
    }

    private LiteralCommandNode<CommandSourceStack> auctionCommand() {
        LiteralArgumentBuilder<CommandSourceStack> root =
                Commands.literal("경매")
                        .requires(src -> player(src, "harbor.economy.auction"))
                        .executes(ctx -> run(ctx, p -> auction.open(p, "all", null, false, 0)))
                        .then(
                                Commands.literal("등록")
                                        .then(
                                                Commands.argument(
                                                                "price",
                                                                LongArgumentType.longArg(
                                                                        1, 1_000_000_000L))
                                                        .executes(
                                                                ctx ->
                                                                        run(
                                                                                ctx,
                                                                                p ->
                                                                                        auction
                                                                                                .startListing(
                                                                                                        p,
                                                                                                        LongArgumentType
                                                                                                                .getLong(
                                                                                                                        ctx,
                                                                                                                        "price"))))))
                        .then(
                                Commands.literal("검색")
                                        .then(
                                                Commands.argument(
                                                                "word",
                                                                StringArgumentType.greedyString())
                                                        .executes(
                                                                ctx ->
                                                                        run(
                                                                                ctx,
                                                                                p ->
                                                                                        auction
                                                                                                .open(
                                                                                                        p,
                                                                                                        "all",
                                                                                                        StringArgumentType
                                                                                                                .getString(
                                                                                                                        ctx,
                                                                                                                        "word"),
                                                                                                        false,
                                                                                                        0)))))
                        .then(
                                Commands.literal("내목록")
                                        .executes(
                                                ctx ->
                                                        run(
                                                                ctx,
                                                                p ->
                                                                        auction.open(
                                                                                p, "all", null,
                                                                                true, 0))));
        for (String category : AuctionHouse.CATEGORIES) {
            root.then(
                    Commands.literal(category)
                            .executes(
                                    ctx ->
                                            run(
                                                    ctx,
                                                    p ->
                                                            auction.open(
                                                                    p, category, null, false, 0))));
        }
        return root.build();
    }

    private static LiteralCommandNode<CommandSourceStack> player(
            String name, String permission, Consumer<Player> action) {
        return Commands.literal(name)
                .requires(src -> player(src, permission))
                .executes(ctx -> run(ctx, action))
                .build();
    }

    private static int run(CommandContext<CommandSourceStack> ctx, Consumer<Player> action) {
        if (ctx.getSource().getExecutor() instanceof Player player) {
            action.accept(player);
        }
        return Command.SINGLE_SUCCESS;
    }

    /** 경제 계열 '시세 정보' 1랭크 이상이면 현재 시세, 2랭크면 24시간 기록, '시세 예측'이 있으면 다음 시간 예측. 그 밖에는 어시장 게시판 안내. */
    private int price(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        double info =
                sender instanceof Player p
                        ? api.modifiers().value(p.getUniqueId(), MARKET_INFO)
                        : 2;
        if (info < 1) {
            api.messages().send(sender, "economy.market.locked");
            return 0;
        }
        FishId fish = new FishId(harborId(ctx.getArgument("fish", Key.class)));
        api.messages()
                .send(
                        sender,
                        "economy.market.coef",
                        Placeholder.unparsed("fish", fish.toString()),
                        Placeholder.unparsed("coef", format(market.coefficient(fish))));
        boolean forecast =
                !(sender instanceof Player p)
                        || api.modifiers().value(p.getUniqueId(), FORECAST) >= 1;
        if (forecast) {
            market.nextCoefficient(fish)
                    .ifPresent(
                            next ->
                                    api.messages()
                                            .send(
                                                    sender,
                                                    "economy.market.forecast",
                                                    Placeholder.unparsed("coef", format(next))));
        }
        if (info >= 2) {
            var _ =
                    market.history(fish)
                            .thenAccept(
                                    values ->
                                            api.messages()
                                                    .send(
                                                            sender,
                                                            "economy.market.history",
                                                            Placeholder.unparsed(
                                                                    "values",
                                                                    values.stream()
                                                                            .map(
                                                                                    EconomyCommands
                                                                                            ::format)
                                                                            .collect(
                                                                                    Collectors
                                                                                            .joining(
                                                                                                    " ")))));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private int done(CommandContext<CommandSourceStack> ctx, boolean ok) {
        api.messages()
                .send(
                        ctx.getSource().getSender(),
                        ok ? "economy.admin.done" : "economy.admin.failed");
        return Command.SINGLE_SUCCESS;
    }

    /** /harbor market|mail|shop|auction|econ|craft (권한 harbor.admin.<이름>은 Core가 건다). */
    public List<LiteralArgumentBuilder<CommandSourceStack>> adminCommands() {
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
                                                                                                    harborId(
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
                Commands.literal("shop")
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
                                                                                                        harborId(
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
                                                                    : format(index)),
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

    /** release: 보류 대금을 지금 푼다 / resolve: 처리 완료 표시. */
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

    /** 관리자 손의 아이템을 대상 우편함으로 (보상 재발송). */
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

    static HarborId harborId(Key key) {
        String namespace =
                key.namespace().equals(Key.MINECRAFT_NAMESPACE)
                        ? HarborId.DEFAULT_NAMESPACE
                        : key.namespace();
        return new HarborId(namespace, key.value());
    }
}
