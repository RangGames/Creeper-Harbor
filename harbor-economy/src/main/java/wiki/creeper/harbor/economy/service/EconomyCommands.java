package wiki.creeper.harbor.economy.service;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
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

/** /판매, /우편함, /수리, /시세, /harbor market·mail·shop (docs 20 7.5). */
public final class EconomyCommands {

    private final HarborApi api;
    private final MarketEngine market;
    private final SellService sell;
    private final MailManager mail;
    private final ShopManager shops;
    private final RepairManager repair;

    public EconomyCommands(
            HarborApi api,
            MarketEngine market,
            SellService sell,
            MailManager mail,
            ShopManager shops,
            RepairManager repair) {
        this.api = api;
        this.market = market;
        this.sell = sell;
        this.mail = mail;
        this.shops = shops;
        this.repair = repair;
    }

    public void registerPlayerCommands(Commands commands) {
        commands.register(
                Commands.literal("판매")
                        .requires(src -> src.getExecutor() instanceof Player)
                        .executes(ctx -> run(ctx, p -> sell.open(p, false)))
                        .then(
                                Commands.literal("손")
                                        .executes(ctx -> run(ctx, p -> sell.open(p, true))))
                        .build(),
                "어종 판매",
                List.of("sell"));
        commands.register(player("우편함", mail::open), "우편함", List.of("mail"));
        commands.register(player("수리", repair::open), "장비 수리", List.of("repair"));
        commands.register(
                Commands.literal("시세")
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

    private static LiteralCommandNode<CommandSourceStack> player(
            String name, Consumer<Player> action) {
        return Commands.literal(name)
                .requires(src -> src.getExecutor() instanceof Player)
                .executes(ctx -> run(ctx, action))
                .build();
    }

    private static int run(CommandContext<CommandSourceStack> ctx, Consumer<Player> action) {
        if (ctx.getSource().getExecutor() instanceof Player player) {
            action.accept(player);
        }
        return Command.SINGLE_SUCCESS;
    }

    /** 경제 계열 '시세 정보' 1랭크 이상이면 현재 시세, 그 밖에는 어시장 게시판 안내. */
    private int price(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (sender instanceof Player p
                && api.modifiers().value(p.getUniqueId(), StatKey.of("market_info")) < 1) {
            api.messages().send(sender, "economy.market.locked");
            return 0;
        }
        FishId fish = new FishId(harborId(ctx.getArgument("fish", Key.class)));
        api.messages()
                .send(
                        sender,
                        "economy.market.coef",
                        Placeholder.unparsed("fish", fish.toString()),
                        Placeholder.unparsed(
                                "coef",
                                String.format(Locale.ROOT, "%.2f", market.coefficient(fish))));
        return Command.SINGLE_SUCCESS;
    }

    /** /harbor market|mail|shop (권한 harbor.admin.<이름>은 Core가 건다). */
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
                                                    api.messages()
                                                            .send(
                                                                    ctx.getSource().getSender(),
                                                                    "economy.admin.done");
                                                    return Command.SINGLE_SUCCESS;
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
                                                                                    api.messages()
                                                                                            .send(
                                                                                                    ctx.getSource()
                                                                                                            .getSender(),
                                                                                                    "economy.admin.done");
                                                                                    return Command
                                                                                            .SINGLE_SUCCESS;
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
                                                                ctx -> {
                                                                    boolean ok =
                                                                            ctx.getSource()
                                                                                                    .getExecutor()
                                                                                            instanceof
                                                                                            Player p
                                                                                    && shops.open(
                                                                                            p,
                                                                                            harborId(
                                                                                                    ctx
                                                                                                            .getArgument(
                                                                                                                    "shop",
                                                                                                                    Key
                                                                                                                            .class)));
                                                                    api.messages()
                                                                            .send(
                                                                                    ctx.getSource()
                                                                                            .getSender(),
                                                                                    ok
                                                                                            ? "economy.admin.done"
                                                                                            : "economy.admin.failed");
                                                                    return Command.SINGLE_SUCCESS;
                                                                })));
        return List.of(marketCmd, mailCmd, shopCmd);
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
        api.messages().send(ctx.getSource().getSender(), "economy.admin.done");
        return Command.SINGLE_SUCCESS;
    }

    /** 관리자 손의 아이템을 대상 우편함으로 (보상 재발송). */
    private int mailHeld(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player target =
                ctx.getArgument("target", PlayerSelectorArgumentResolver.class)
                        .resolve(ctx.getSource())
                        .getFirst();
        if (!(ctx.getSource().getExecutor() instanceof Player admin)
                || admin.getInventory().getItemInMainHand().isEmpty()) {
            api.messages().send(ctx.getSource().getSender(), "economy.admin.failed");
            return 0;
        }
        var _ =
                mail.sendItem(
                        target.getUniqueId(),
                        admin.getInventory().getItemInMainHand().clone(),
                        "admin",
                        null);
        api.messages().send(ctx.getSource().getSender(), "economy.admin.done");
        return Command.SINGLE_SUCCESS;
    }

    private static HarborId harborId(Key key) {
        String namespace =
                key.namespace().equals(Key.MINECRAFT_NAMESPACE)
                        ? HarborId.DEFAULT_NAMESPACE
                        : key.namespace();
        return new HarborId(namespace, key.value());
    }
}
