package wiki.creeper.harbor.shop.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.shop.ShopRuntime;
import wiki.creeper.harbor.shop.api.PearlService;
import wiki.creeper.harbor.shop.chest.ChestManager;
import wiki.creeper.harbor.shop.content.CosmeticDef;
import wiki.creeper.harbor.shop.cosmetic.CosmeticManager;
import wiki.creeper.harbor.shop.data.ShopRepository;
import wiki.creeper.harbor.shop.donor.DonorManager;
import wiki.creeper.harbor.shop.festival.FestivalManager;
import wiki.creeper.harbor.shop.order.ShopOrders;
import wiki.creeper.harbor.shop.pass.PassManager;
import wiki.creeper.harbor.shop.pearl.PearlAudit;
import wiki.creeper.harbor.shop.pearl.PearlManager;
import wiki.creeper.harbor.shop.ui.ShopMenus;
import wiki.creeper.harbor.shop.util.UuidV7;
import wiki.creeper.harbor.shop.webhook.InboxProcessor;
import wiki.creeper.harbor.shop.webhook.Reconciler;

/**
 * /상점 · /보관함 · /패스 · /진주 · /보물함 · /선물 · /닉네임 · /축제 · /크리에이터, /harbor shop … (docs 20 12.5). Every
 * admin command is written to admin_log; fine-grained permissions are
 * harbor.admin.shop.&lt;command&gt;.
 */
public final class ShopCommands {

    private final ShopRuntime rt;
    private final FestivalManager festivals;
    private final ShopOrders orders;
    private final ShopMenus menus;
    private final ShopAdminCommands admin;

    public ShopCommands(
            ShopRuntime rt,
            PearlManager pearls,
            CosmeticManager cosmetics,
            PassManager passes,
            ChestManager chests,
            DonorManager donors,
            FestivalManager festivals,
            ShopOrders orders,
            ShopMenus menus,
            InboxProcessor inbox,
            Reconciler reconciler,
            PearlAudit audit) {
        this.rt = rt;
        this.festivals = festivals;
        this.orders = orders;
        this.menus = menus;
        this.admin =
                new ShopAdminCommands(
                        rt, pearls, cosmetics, passes, chests, donors, inbox, reconciler, audit);
    }

    // ------------------------------------------------------------------ player commands

    public void registerPlayerCommands(Commands commands) {
        LiteralArgumentBuilder<CommandSourceStack> shop =
                player("상점", "harbor.shop", p -> menus.openShop(p, ShopMenus.Tab.ROTATION));
        for (ShopMenus.Tab tab : ShopMenus.Tab.values()) {
            shop.then(
                    Commands.literal(tabCommand(tab))
                            .executes(ctx -> run(ctx, p -> menus.openShop(p, tab))));
        }
        commands.register(shop.build(), "코스메틱 상점", List.of());
        commands.register(
                player("보관함", "harbor.shop", p -> menus.openWardrobe(p, null)).build(),
                "코스메틱 보관함·장착",
                List.of());
        commands.register(
                player("패스", "harbor.shop", p -> menus.openPass(p, 0)).build(),
                "시즌 패스 「항해 일지」",
                List.of());
        commands.register(
                player("진주", "harbor.shop", this::pearlInfo).build(), "진주 잔액·내역", List.of());
        commands.register(
                player("보물함", "harbor.shop.chest", menus::openChest).build(), "진주 보물함", List.of());
        commands.register(
                player("축제", "harbor.shop", this::festivalInfo).build(), "항구 축제", List.of());
        commands.register(
                player("선물", "harbor.shop", p -> rt.api().messages().send(p, "shop.gift.usage"))
                        .then(
                                Commands.argument("target", StringArgumentType.word())
                                        .suggests(
                                                (c, b) -> {
                                                    Bukkit.getOnlinePlayers()
                                                            .forEach(p -> b.suggest(p.getName()));
                                                    return b.buildFuture();
                                                })
                                        .then(
                                                CommandSupport.cosmeticArgument(rt)
                                                        .executes(
                                                                ctx ->
                                                                        run(
                                                                                ctx,
                                                                                p ->
                                                                                        gift(
                                                                                                p,
                                                                                                StringArgumentType
                                                                                                        .getString(
                                                                                                                ctx,
                                                                                                                "target"),
                                                                                                StringArgumentType
                                                                                                        .getString(
                                                                                                                ctx,
                                                                                                                "cosmetic"))))))
                        .build(),
                "코스메틱 선물하기",
                List.of());
        commands.register(
                player("닉네임", "harbor.shop", p -> rt.api().messages().send(p, "shop.name.usage"))
                        .then(
                                Commands.argument("name", StringArgumentType.word())
                                        .executes(
                                                ctx ->
                                                        run(
                                                                ctx,
                                                                p ->
                                                                        changeName(
                                                                                p,
                                                                                StringArgumentType
                                                                                        .getString(
                                                                                                ctx,
                                                                                                "name")))))
                        .build(),
                "닉네임 표시 변경권",
                List.of());
        commands.register(
                player(
                                "크리에이터",
                                "harbor.shop",
                                p -> rt.api().messages().send(p, "shop.creator.usage"))
                        .then(
                                Commands.argument("code", StringArgumentType.word())
                                        .executes(
                                                ctx ->
                                                        run(
                                                                ctx,
                                                                p ->
                                                                        report(
                                                                                p,
                                                                                orders
                                                                                        .supportCreator(
                                                                                                p,
                                                                                                StringArgumentType
                                                                                                        .getString(
                                                                                                                ctx,
                                                                                                                "code"))))))
                        .build(),
                "크리에이터 코드 등록",
                List.of());
    }

    /** Subcommand name for a /상점 tab (Korean, no spaces). */
    private static String tabCommand(ShopMenus.Tab tab) {
        return switch (tab) {
            case ROTATION -> "진열";
            case PERMANENT -> "상시";
            case SEASON -> "시즌";
            case BUNDLE -> "묶음";
        };
    }

    private static LiteralArgumentBuilder<CommandSourceStack> player(
            String name, String permission, Consumer<Player> action) {
        return Commands.literal(name)
                .requires(
                        src ->
                                src.getExecutor() instanceof Player
                                        && src.getSender().hasPermission(permission))
                .executes(ctx -> run(ctx, action));
    }

    private static int run(CommandContext<CommandSourceStack> ctx, Consumer<Player> action) {
        if (ctx.getSource().getExecutor() instanceof Player player) {
            action.accept(player);
        }
        return Command.SINGLE_SUCCESS;
    }

    private void report(CommandSender sender, CompletableFuture<Optional<String>> result) {
        var _ =
                result.thenAccept(
                        error -> rt.api().messages().send(sender, error.orElse("shop.done")));
    }

    private record PearlInfo(PearlService.Balance wallet, List<ShopRepository.LedgerLine> lines) {}

    private void pearlInfo(Player player) {
        UUID uuid = player.getUniqueId();
        var _ =
                rt.db(
                                c ->
                                        new PearlInfo(
                                                ShopRepository.wallet(c, uuid),
                                                ShopRepository.recentLedger(c, uuid, 10)))
                        .thenAccept(
                                info -> {
                                    rt.api()
                                            .messages()
                                            .send(
                                                    player,
                                                    "shop.pearl.balance",
                                                    Placeholder.unparsed(
                                                            "total",
                                                            String.valueOf(info.wallet().total())),
                                                    Placeholder.unparsed(
                                                            "paid",
                                                            String.valueOf(info.wallet().paid())),
                                                    Placeholder.unparsed(
                                                            "free",
                                                            String.valueOf(info.wallet().free())),
                                                    Placeholder.unparsed(
                                                            "debt",
                                                            String.valueOf(info.wallet().debt())));
                                    rt.api().messages().send(player, "shop.pearl.order_notice");
                                    for (ShopRepository.LedgerLine line : info.lines()) {
                                        rt.api()
                                                .messages()
                                                .send(
                                                        player,
                                                        "shop.pearl.line",
                                                        Placeholder.unparsed(
                                                                "delta",
                                                                (line.delta() > 0 ? "+" : "")
                                                                        + line.delta()),
                                                        Placeholder.unparsed(
                                                                "reason",
                                                                reasonName(line.reason())),
                                                        Placeholder.unparsed(
                                                                "date",
                                                                line.at()
                                                                        .atZone(
                                                                                rt.api()
                                                                                        .time()
                                                                                        .zone())
                                                                        .toLocalDate()
                                                                        .toString()));
                                    }
                                });
    }

    private String reasonName(String reason) {
        String key = "shop.reason." + reason.toLowerCase(Locale.ROOT);
        return rt.api().messages().has(key)
                ? PlainTextComponentSerializer.plainText().serialize(rt.api().messages().get(key))
                : reason;
    }

    private void festivalInfo(Player player) {
        if (!rt.settings().features().festival()) {
            rt.api().messages().send(player, "shop.festival.closed");
            return;
        }
        var list = festivals.current();
        if (list.isEmpty()) {
            rt.api().messages().send(player, "shop.festival.closed");
            return;
        }
        for (var def : list) {
            long total = festivals.gauge(def.id()).map(FestivalManager.Gauge::totalKrw).orElse(0L);
            int percent = (int) Math.min(100, total * 100 / Math.max(1, def.goalKrw()));
            rt.api()
                    .messages()
                    .send(
                            player,
                            "shop.festival.gauge",
                            Placeholder.unparsed("name", def.name()),
                            Placeholder.unparsed("percent", String.valueOf(percent)));
            var _ =
                    festivals
                            .topContributors(def.id(), 10)
                            .thenAccept(
                                    rows -> {
                                        rt.api().messages().send(player, "shop.festival.plaque");
                                        for (String[] row : rows) {
                                            rt.api()
                                                    .messages()
                                                    .send(
                                                            player,
                                                            "shop.festival.plaque_line",
                                                            Placeholder.unparsed("name", row[0]));
                                        }
                                    });
        }
    }

    private void gift(Player sender, String target, String rawCosmetic) {
        Optional<CosmeticDef> def = CommandSupport.cosmetic(rt, rawCosmetic);
        if (def.isEmpty()) {
            rt.api().messages().send(sender, "shop.error.unknown_cosmetic");
            return;
        }
        UUID order = UuidV7.next();
        rt.api()
                .dialogs()
                .confirm(
                        sender,
                        rt.api().messages().get("shop.gift.title"),
                        List.of(
                                rt.api()
                                        .messages()
                                        .get(
                                                "shop.gift.body",
                                                Placeholder.unparsed("target", target),
                                                Placeholder.unparsed("name", def.get().name()),
                                                Placeholder.unparsed(
                                                        "price",
                                                        String.valueOf(def.get().price()))),
                                rt.api().messages().get("shop.confirm.no_refund")),
                        rt.api().messages().get("shop.confirm.yes"),
                        rt.api().messages().get("shop.confirm.no"),
                        yes -> {
                            if (yes) {
                                report(sender, orders.gift(sender, target, def.get(), order));
                            }
                        });
    }

    private void changeName(Player player, String name) {
        UUID order = UuidV7.next();
        int price = rt.settings().nameChange().price();
        rt.api()
                .dialogs()
                .confirm(
                        player,
                        rt.api().messages().get("shop.name.title"),
                        List.of(
                                rt.api()
                                        .messages()
                                        .get(
                                                "shop.name.body",
                                                Placeholder.unparsed("name", name),
                                                Placeholder.unparsed(
                                                        "price", String.valueOf(price))),
                                rt.api().messages().get("shop.confirm.no_refund")),
                        rt.api().messages().get("shop.confirm.yes"),
                        rt.api().messages().get("shop.confirm.no"),
                        yes -> {
                            if (yes) {
                                report(player, orders.changeName(player, name, order));
                            }
                        });
    }

    // ------------------------------------------------------------------ admin commands

    /**
     * /harbor shop … (Core gates it with harbor.admin.shop; subcommands require
     * harbor.admin.shop.&lt;command&gt;).
     */
    public LiteralArgumentBuilder<CommandSourceStack> adminCommand() {
        return admin.build();
    }
}
