package wiki.creeper.harbor.economy.service;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.FishArgs;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.StatKey;

/**
 * /판매, /우편함, /수리, /시세, /경매, /제작, /큰손, /harbor market·mail·shop·auction·econ·craft (docs 20 7.5).
 * The admin subcommands are in {@link EconomyAdminCommands}.
 */
public final class EconomyCommands {

    private static final StatKey MARKET_INFO = StatKey.of("market_info");
    private static final StatKey FORECAST = StatKey.of("feature_market_forecast");

    private final HarborApi api;
    private final MarketEngine market;
    private final SellService sell;
    private final MailManager mail;
    private final RepairManager repair;
    private final AuctionHouse auction;
    private final CraftManager craft;
    private final SellMenu sellMenu;
    private final MarketBoard board;
    private final LedgerHistoryMenu history;
    private final EconomyAdminCommands admin;

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
            StationManager stations,
            SellMenu sellMenu) {
        this.api = api;
        this.sellMenu = sellMenu;
        this.board = new MarketBoard(api, market, stations);
        this.history =
                new LedgerHistoryMenu(api, org.slf4j.LoggerFactory.getLogger("Harbor-Economy"));
        this.market = market;
        this.sell = sell;
        this.mail = mail;
        this.repair = repair;
        this.auction = auction;
        this.craft = craft;
        this.admin =
                new EconomyAdminCommands(api, market, mail, shops, auction, craft, fraud, stations);
    }

    private static boolean player(CommandSourceStack src, String permission) {
        return src.getExecutor() instanceof Player && src.getSender().hasPermission(permission);
    }

    public void registerPlayerCommands(Commands commands) {
        commands.register(
                Commands.literal("판매")
                        .requires(src -> player(src, "harbor.economy.sell"))
                        .executes(ctx -> run(ctx, sellMenu::open))
                        .then(
                                Commands.literal("손")
                                        .executes(ctx -> run(ctx, p -> sell.open(p, true))))
                        .then(
                                Commands.literal("전부")
                                        .executes(ctx -> run(ctx, p -> sell.open(p, false))))
                        .build(),
                "어종 판매 (판매 상자 · 손 · 전부)",
                List.of());
        commands.register(
                player("잠금", "harbor.economy.lock", this::toggleLock), "손에 든 아이템 잠금", List.of());
        commands.register(
                player("거래내역", "harbor.economy.history", history::open),
                "내 골드·물비늘 거래 내역",
                List.of());
        commands.register(player("우편함", "harbor.economy.mail", mail::open), "우편함", List.of());
        commands.register(player("수리", "harbor.economy.repair", repair::open), "장비 수리", List.of());
        commands.register(
                player("큰손", "harbor.economy.big_deal", sell::bigDeal),
                "큰손 거래 (경제 계열 캡스톤)",
                List.of());
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
                List.of());
        commands.register(auctionCommand(), "경매장", List.of());
        commands.register(
                Commands.literal("시세")
                        .requires(src -> src.getSender().hasPermission("harbor.economy.market"))
                        .executes(ctx -> run(ctx, board::open))
                        .then(
                                FishArgs.argument("fish", api.content()::snapshot)
                                        .executes(this::price))
                        .build(),
                "시세",
                List.of());
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
                    Commands.literal(categoryCommand(category))
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

    /**
     * /경매 category subcommand names (Korean, same as the economy auction category names in
     * messages).
     */
    private static String categoryCommand(String category) {
        return switch (category) {
            case "all" -> "전체";
            case "fish" -> "어종";
            case "gear" -> "장비";
            case "bait" -> "미끼";
            case "material" -> "재료";
            case "food" -> "요리";
            default -> category;
        };
    }

    private static LiteralCommandNode<CommandSourceStack> player(
            String name, String permission, Consumer<Player> action) {
        return Commands.literal(name)
                .requires(src -> player(src, permission))
                .executes(ctx -> run(ctx, action))
                .build();
    }

    /**
     * /잠금: toggles the lock on the held item (locked items are excluded from bulk sales and auction
     * listings).
     */
    private void toggleLock(Player player) {
        org.bukkit.inventory.ItemStack held = player.getInventory().getItemInMainHand();
        if (held.isEmpty()) {
            api.messages().send(player, "economy.lock.hold_item");
            return;
        }
        boolean locked = ItemLock.toggle(held);
        api.messages().send(player, locked ? "economy.lock.locked" : "economy.lock.unlocked");
    }

    private static int run(CommandContext<CommandSourceStack> ctx, Consumer<Player> action) {
        if (ctx.getSource().getExecutor() instanceof Player player) {
            action.accept(player);
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * With economy skill 'Market Info' rank 1+ shows the current price, rank 2 the 24-hour history,
     * and with 'Market Forecast' the next hour's forecast. Otherwise points to the fish market
     * board.
     */
    private int price(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        double info =
                sender instanceof Player p
                        ? api.modifiers().value(p.getUniqueId(), MARKET_INFO)
                        : 2;
        if (info < 1) {
            board.sendLocked(sender);
            return 0;
        }
        Optional<FishDef> def = FishArgs.get(ctx, "fish", api.content().snapshot());
        if (def.isEmpty()) {
            api.messages()
                    .send(
                            sender,
                            "economy.market.unknown_fish",
                            Placeholder.unparsed(
                                    "fish", StringArgumentType.getString(ctx, "fish")));
            return 0;
        }
        FishId fish = def.get().id();
        api.messages()
                .send(
                        sender,
                        "economy.market.coef",
                        Placeholder.unparsed("fish", def.get().name()),
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

    static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** Click on a market_board station: the price board screen. */
    public void openMarketBoard(Player player) {
        board.openAtBoard(player);
    }

    /** Text Display content of market_board stations. */
    public net.kyori.adventure.text.Component marketBoardText() {
        return MarketBoardText.build(api, market);
    }

    /**
     * /harbor market|mail|shop|auction|econ|craft (Core applies the harbor.admin.&lt;name&gt;
     * permission).
     */
    public List<LiteralArgumentBuilder<CommandSourceStack>> adminCommands() {
        return admin.commands();
    }

    static HarborId harborId(Key key) {
        String namespace =
                key.namespace().equals(Key.MINECRAFT_NAMESPACE)
                        ? HarborId.DEFAULT_NAMESPACE
                        : key.namespace();
        return new HarborId(namespace, key.value());
    }
}
