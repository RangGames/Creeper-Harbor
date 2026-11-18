package wiki.creeper.harbor.shop.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.shop.ShopRuntime;
import wiki.creeper.harbor.shop.api.PearlService;
import wiki.creeper.harbor.shop.chest.ChestManager;
import wiki.creeper.harbor.shop.content.ChestDef;
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
 * /상점 · /보관함 · /패스 · /진주 · /보물함 · /선물 · /닉네임 · /축제 · /크리에이터, /harbor shop … (docs 20 12.5). 관리 명령은
 * 모두 admin_log에 남기고, 세부 권한은 harbor.admin.shop.&lt;명령&gt;.
 */
public final class ShopCommands {

    /** 큰 관리자 진주 지급 대기 (다른 관리자 확인 필요). */
    private record PendingGrant(String requester, UUID target, long amount, String reason) {}

    private final ShopRuntime rt;
    private final PearlManager pearls;
    private final CosmeticManager cosmetics;
    private final PassManager passes;
    private final ChestManager chests;
    private final DonorManager donors;
    private final FestivalManager festivals;
    private final ShopOrders orders;
    private final ShopMenus menus;
    private final InboxProcessor inbox;
    private final Reconciler reconciler;
    private final PearlAudit audit;
    private final Map<String, PendingGrant> pendingGrants = new ConcurrentHashMap<>();

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
        this.pearls = pearls;
        this.cosmetics = cosmetics;
        this.passes = passes;
        this.chests = chests;
        this.donors = donors;
        this.festivals = festivals;
        this.orders = orders;
        this.menus = menus;
        this.inbox = inbox;
        this.reconciler = reconciler;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ 플레이어 명령

    public void registerPlayerCommands(Commands commands) {
        LiteralArgumentBuilder<CommandSourceStack> shop =
                player("상점", "harbor.shop", p -> menus.openShop(p, ShopMenus.Tab.ROTATION));
        for (ShopMenus.Tab tab : ShopMenus.Tab.values()) {
            shop.then(
                    Commands.literal(tab.name().toLowerCase(Locale.ROOT))
                            .executes(ctx -> run(ctx, p -> menus.openShop(p, tab))));
        }
        commands.register(shop.build(), "코스메틱 상점", List.of("shop"));
        commands.register(
                player("보관함", "harbor.shop", p -> menus.openWardrobe(p, null)).build(),
                "코스메틱 보관함·장착",
                List.of("wardrobe"));
        commands.register(
                player("패스", "harbor.shop", p -> menus.openPass(p, 0)).build(),
                "시즌 패스 「항해 일지」",
                List.of("pass"));
        commands.register(
                player("진주", "harbor.shop", this::pearlInfo).build(), "진주 잔액·내역", List.of("pearl"));
        commands.register(
                player("보물함", "harbor.shop.chest", menus::openChest).build(),
                "진주 보물함",
                List.of("chest"));
        commands.register(
                player("축제", "harbor.shop", this::festivalInfo).build(),
                "항구 축제",
                List.of("festival"));
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
                                                cosmeticArgument()
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
                List.of("gift"));
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
                List.of("nickname"));
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
                List.of("creator"));
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

    private RequiredArgumentBuilder<CommandSourceStack, String> cosmeticArgument() {
        return Commands.argument("cosmetic", StringArgumentType.word())
                .suggests(
                        (c, b) -> {
                            rt.snapshot()
                                    .ids(CosmeticDef.TYPE.name())
                                    .forEach(
                                            id ->
                                                    b.suggest(
                                                            id.path()
                                                                    .substring(
                                                                            CosmeticDef.PREFIX
                                                                                    .length())));
                            return b.buildFuture();
                        });
    }

    private Optional<CosmeticDef> cosmetic(String raw) {
        String path =
                raw.contains(":")
                        ? raw
                        : raw.startsWith(CosmeticDef.PREFIX) ? raw : CosmeticDef.PREFIX + raw;
        return HarborId.tryParse(path).flatMap(rt::cosmetic);
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
        Optional<CosmeticDef> def = cosmetic(rawCosmetic);
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

    // ------------------------------------------------------------------ 관리 명령

    /** /harbor shop … (Core가 harbor.admin.shop을 건다. 하위 명령은 harbor.admin.shop.&lt;명령&gt;). */
    public LiteralArgumentBuilder<CommandSourceStack> adminCommand() {
        return Commands.literal("shop")
                .then(
                        sub("pearl")
                                .then(pearlAdmin("give"))
                                .then(pearlAdmin("take"))
                                .then(
                                        Commands.literal("confirm")
                                                .then(
                                                        Commands.argument(
                                                                        "id",
                                                                        StringArgumentType.word())
                                                                .executes(this::pearlConfirm))))
                .then(
                        sub("tx")
                                .then(
                                        Commands.argument("tx", StringArgumentType.greedyString())
                                                .executes(this::tx)))
                .then(
                        sub("inbox")
                                .executes(this::inboxList)
                                .then(
                                        Commands.literal("retry")
                                                .executes(ctx -> inboxRetry(ctx, null))
                                                .then(
                                                        Commands.argument(
                                                                        "id",
                                                                        StringArgumentType
                                                                                .greedyString())
                                                                .executes(
                                                                        ctx ->
                                                                                inboxRetry(
                                                                                        ctx,
                                                                                        StringArgumentType
                                                                                                .getString(
                                                                                                        ctx,
                                                                                                        "id"))))))
                .then(sub("cosmetic").then(cosmeticAdmin("give")).then(cosmeticAdmin("revoke")))
                .then(
                        sub("flag")
                                .then(
                                        playerArg()
                                                .then(
                                                        Commands.literal("lock")
                                                                .executes(ctx -> flag(ctx, true)))
                                                .then(
                                                        Commands.literal("unlock")
                                                                .executes(ctx -> flag(ctx, false)))
                                                .then(
                                                        Commands.literal("clear_debt")
                                                                .executes(this::clearDebt))))
                .then(
                        sub("chest")
                                .then(
                                        Commands.literal("stats")
                                                .then(
                                                        Commands.argument(
                                                                        "chest",
                                                                        StringArgumentType.word())
                                                                .executes(this::chestStats))))
                .then(
                        sub("reconcile")
                                .executes(this::reconcile)
                                .then(Commands.literal("audit").executes(this::audit)))
                .then(
                        sub("pass")
                                .then(
                                        Commands.literal("exp")
                                                .then(
                                                        playerArg()
                                                                .then(
                                                                        Commands.argument(
                                                                                        "amount",
                                                                                        LongArgumentType
                                                                                                .longArg(
                                                                                                        1))
                                                                                .executes(
                                                                                        this
                                                                                                ::passExp)))))
                .then(
                        sub("donor")
                                .then(Commands.literal("reconcile").executes(this::donorReconcile))
                                .then(
                                        Commands.literal("show")
                                                .then(playerArg().executes(this::donorShow))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> sub(String name) {
        return Commands.literal(name)
                .requires(src -> src.getSender().hasPermission("harbor.admin.shop." + name));
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> playerArg() {
        return Commands.argument("player", StringArgumentType.word())
                .suggests(
                        (c, b) -> {
                            Bukkit.getOnlinePlayers().forEach(p -> b.suggest(p.getName()));
                            return b.buildFuture();
                        });
    }

    private LiteralArgumentBuilder<CommandSourceStack> pearlAdmin(String mode) {
        return Commands.literal(mode)
                .then(
                        playerArg()
                                .then(
                                        Commands.argument(
                                                        "amount",
                                                        LongArgumentType.longArg(1, 1_000_000))
                                                .then(
                                                        Commands.argument(
                                                                        "reason",
                                                                        StringArgumentType
                                                                                .greedyString())
                                                                .executes(
                                                                        ctx ->
                                                                                pearlAdmin(
                                                                                        ctx,
                                                                                        mode.equals(
                                                                                                "give"))))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> cosmeticAdmin(String mode) {
        return Commands.literal(mode)
                .then(
                        playerArg()
                                .then(
                                        cosmeticArgument()
                                                .executes(
                                                        ctx ->
                                                                cosmeticAdmin(
                                                                        ctx,
                                                                        mode.equals("give")))));
    }

    private CompletableFuture<Optional<UUID>> resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return CompletableFuture.completedFuture(Optional.of(online.getUniqueId()));
        }
        return rt.db(c -> ShopRepository.uuidByName(c, name));
    }

    private static @Nullable Player adminOf(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getSender() instanceof Player p ? p : null;
    }

    private static String adminId(CommandSender sender) {
        return sender instanceof Player p ? p.getUniqueId().toString() : "CONSOLE";
    }

    private void withTarget(CommandContext<CommandSourceStack> ctx, Consumer<UUID> action) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "player");
        var _ =
                resolve(name)
                        .thenAccept(
                                uuid -> {
                                    if (uuid.isEmpty()) {
                                        rt.api()
                                                .messages()
                                                .send(sender, "shop.admin.unknown_player");
                                    } else {
                                        action.accept(uuid.get());
                                    }
                                });
    }

    private int pearlAdmin(CommandContext<CommandSourceStack> ctx, boolean give) {
        CommandSender sender = ctx.getSource().getSender();
        long amount = LongArgumentType.getLong(ctx, "amount");
        String reason = StringArgumentType.getString(ctx, "reason").trim();
        if (reason.length() < 2) {
            rt.api().messages().send(sender, "shop.admin.reason_required");
            return 0;
        }
        withTarget(
                ctx,
                target -> {
                    if (give && amount > rt.settings().adminConfirmOver()) {
                        String id = UuidV7.next().toString().substring(0, 8);
                        pendingGrants.put(
                                id, new PendingGrant(adminId(sender), target, amount, reason));
                        rt.adminLog(
                                adminOf(ctx),
                                "pearl.give.request",
                                target.toString(),
                                amount + " " + reason);
                        rt.api()
                                .messages()
                                .send(
                                        sender,
                                        "shop.admin.confirm_needed",
                                        Placeholder.unparsed("id", id));
                        return;
                    }
                    executeGrant(sender, adminOf(ctx), target, give ? amount : -amount, reason);
                });
        return Command.SINGLE_SUCCESS;
    }

    private int pearlConfirm(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String id = StringArgumentType.getString(ctx, "id");
        PendingGrant pending = pendingGrants.get(id);
        if (pending == null) {
            rt.api().messages().send(sender, "shop.admin.no_pending");
            return 0;
        }
        if (pending.requester().equals(adminId(sender))) {
            // 5,000 진주 초과 지급은 다른 관리자가 확인한다 (docs 20 12.4 '악용 방지')
            rt.api().messages().send(sender, "shop.admin.other_admin");
            return 0;
        }
        pendingGrants.remove(id);
        executeGrant(sender, adminOf(ctx), pending.target(), pending.amount(), pending.reason());
        return Command.SINGLE_SUCCESS;
    }

    private void executeGrant(
            CommandSender sender, @Nullable Player admin, UUID target, long delta, String reason) {
        String ref = "admin:" + UuidV7.next();
        var future =
                delta > 0
                        ? pearls.grant(target, delta, LedgerReason.ADMIN_GRANT, ref)
                        : pearls.spendAdmin(target, -delta, ref);
        var _ =
                future.thenAccept(
                        r -> {
                            boolean ok = r.isOk();
                            rt.api()
                                    .messages()
                                    .send(sender, ok ? "shop.done" : "shop.admin.failed");
                            if (ok) {
                                rt.adminLog(
                                        admin,
                                        delta > 0 ? "pearl.give" : "pearl.take",
                                        target.toString(),
                                        delta + " " + reason);
                                rt.notifyPlayer(target, "ADMIN", delta);
                            }
                        });
    }

    private int tx(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String tx = StringArgumentType.getString(ctx, "tx").trim();
        rt.adminLog(adminOf(ctx), "tx", tx, null);
        var _ =
                rt.db(
                                c -> {
                                    List<String> lines = new ArrayList<>();
                                    try (PreparedStatement ps =
                                            c.prepareStatement(
                                                    "SELECT p.tx_id, p.uuid, p.product, p.amount_krw, p.pearls, p.state,"
                                                            + " COALESCE(l.remaining, -1), COALESCE(p.detail, '')"
                                                            + " FROM purchases p LEFT JOIN pearl_lot l ON l.uuid = p.uuid AND l.source_ref = p.tx_id"
                                                            + " WHERE p.tx_id = ? OR p.tx_id LIKE ?")) {
                                        ps.setString(1, tx);
                                        ps.setString(2, tx + "#%");
                                        try (ResultSet rs = ps.executeQuery()) {
                                            while (rs.next()) {
                                                long pearlsGranted = rs.getLong(5);
                                                long remaining = rs.getLong(7);
                                                lines.add(
                                                        rs.getString(1)
                                                                + " "
                                                                + rs.getString(2)
                                                                + " "
                                                                + rs.getString(3)
                                                                + " "
                                                                + rs.getLong(4)
                                                                + "원 "
                                                                + pearlsGranted
                                                                + "진주 "
                                                                + rs.getString(6)
                                                                + " 남은 묶음 "
                                                                + remaining
                                                                + (remaining == pearlsGranted
                                                                                && pearlsGranted > 0
                                                                        ? " (미사용: 청약철회 가능)"
                                                                        : "")
                                                                + " "
                                                                + rs.getString(8));
                                            }
                                        }
                                    }
                                    return lines;
                                })
                        .thenAccept(lines -> sendLines(sender, lines));
        return Command.SINGLE_SUCCESS;
    }

    private void sendLines(CommandSender sender, List<String> lines) {
        if (lines.isEmpty()) {
            rt.api().messages().send(sender, "shop.admin.none");
            return;
        }
        lines.forEach(
                l ->
                        rt.api()
                                .messages()
                                .send(sender, "shop.admin.line", Placeholder.unparsed("text", l)));
    }

    private int inboxList(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        var _ =
                rt.db(inbox::problems)
                        .thenAccept(
                                rows ->
                                        sendLines(
                                                sender,
                                                rows.stream()
                                                        .map(
                                                                r ->
                                                                        r.webhookId()
                                                                                + " "
                                                                                + r.type()
                                                                                + " "
                                                                                + r.state()
                                                                                + " ×"
                                                                                + r.attempts()
                                                                                + " "
                                                                                + r.error())
                                                        .toList()));
        return Command.SINGLE_SUCCESS;
    }

    private int inboxRetry(CommandContext<CommandSourceStack> ctx, @Nullable String id) {
        CommandSender sender = ctx.getSource().getSender();
        rt.adminLog(adminOf(ctx), "inbox.retry", id, null);
        var _ =
                rt.db(c -> inbox.retry(c, id))
                        .thenAccept(
                                n -> {
                                    inbox.signal();
                                    rt.api()
                                            .messages()
                                            .send(
                                                    sender,
                                                    "shop.admin.line",
                                                    Placeholder.unparsed("text", "재처리 " + n + "건"));
                                });
        return Command.SINGLE_SUCCESS;
    }

    private int cosmeticAdmin(CommandContext<CommandSourceStack> ctx, boolean give) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<CosmeticDef> def = cosmetic(StringArgumentType.getString(ctx, "cosmetic"));
        if (def.isEmpty()) {
            rt.api().messages().send(sender, "shop.error.unknown_cosmetic");
            return 0;
        }
        withTarget(
                ctx,
                target -> {
                    rt.adminLog(
                            adminOf(ctx),
                            give ? "cosmetic.give" : "cosmetic.revoke",
                            target.toString(),
                            def.get().id().toString());
                    var future =
                            give
                                    ? cosmetics.grant(
                                            target,
                                            def.get().id(),
                                            "ADMIN",
                                            "admin:" + UuidV7.next())
                                    : cosmetics.revoke(target, def.get().id());
                    var _ =
                            future.thenAccept(
                                    ok ->
                                            rt.api()
                                                    .messages()
                                                    .send(
                                                            sender,
                                                            ok
                                                                    ? "shop.done"
                                                                    : "shop.admin.failed"));
                });
        return Command.SINGLE_SUCCESS;
    }

    private int flag(CommandContext<CommandSourceStack> ctx, boolean lock) {
        CommandSender sender = ctx.getSource().getSender();
        withTarget(
                ctx,
                target -> {
                    rt.adminLog(
                            adminOf(ctx),
                            lock ? "flag.lock" : "flag.unlock",
                            target.toString(),
                            null);
                    var _ =
                            rt.db(
                                            c -> {
                                                ShopRepository.setLock(
                                                        c, target, lock, lock ? "admin" : null);
                                                return lock;
                                            })
                                    .thenAccept(
                                            v -> {
                                                rt.update(target, p -> p.withLocked(v));
                                                rt.notifyPlayer(target, "FLAG", 0);
                                                rt.api().messages().send(sender, "shop.done");
                                            });
                });
        return Command.SINGLE_SUCCESS;
    }

    /** 미성년자 결제 취소 등 관리자 판단으로 debt를 면제한다 (docs 20 12.4 '법·규정 준수'). */
    private int clearDebt(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        withTarget(
                ctx,
                target -> {
                    rt.adminLog(adminOf(ctx), "debt.clear", target.toString(), null);
                    var _ =
                            rt.db(
                                            c -> {
                                                long debt = ShopRepository.lockDebt(c, target);
                                                ShopRepository.addDebt(c, target, -debt);
                                                ShopRepository.setLock(c, target, false, null);
                                                return debt;
                                            })
                                    .thenAccept(
                                            debt -> {
                                                rt.notifyPlayer(target, "FLAG", 0);
                                                rt.api()
                                                        .messages()
                                                        .send(
                                                                sender,
                                                                "shop.admin.line",
                                                                Placeholder.unparsed(
                                                                        "text",
                                                                        "debt " + debt + " 면제"));
                                            });
                });
        return Command.SINGLE_SUCCESS;
    }

    private int chestStats(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String raw = StringArgumentType.getString(ctx, "chest");
        Optional<ChestDef> chest =
                HarborId.tryParse(raw.startsWith(ChestDef.PREFIX) ? raw : ChestDef.PREFIX + raw)
                        .flatMap(id -> rt.snapshot().get(ChestDef.TYPE, id));
        if (chest.isEmpty()) {
            rt.api().messages().send(sender, "shop.admin.none");
            return 0;
        }
        var _ =
                chests.stats(chest.get())
                        .thenAccept(
                                stats -> {
                                    long total = stats.values().stream().mapToLong(v -> v[0]).sum();
                                    List<String> lines = new ArrayList<>();
                                    lines.add(
                                            "표 버전 "
                                                    + chest.get().tableVersion()
                                                    + " · 개봉 "
                                                    + total
                                                    + "회");
                                    for (Map.Entry<Grade, long[]> e : stats.entrySet()) {
                                        double actual =
                                                total == 0 ? 0 : e.getValue()[0] * 100.0 / total;
                                        double published = e.getValue()[1] / 10_000.0;
                                        lines.add(
                                                String.format(
                                                        Locale.ROOT,
                                                        "%s 실제 %.4f%% · 공개 %.4f%% (%d회)",
                                                        e.getKey().key(),
                                                        actual,
                                                        published,
                                                        e.getValue()[0]));
                                    }
                                    sendLines(sender, lines);
                                });
        return Command.SINGLE_SUCCESS;
    }

    private int reconcile(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        rt.adminLog(adminOf(ctx), "reconcile", null, null);
        var _ =
                rt.tasks()
                        .supplyAsync(reconciler::runOnce)
                        .thenAcceptAsync(s -> sendLines(sender, List.of(s)), rt.tasks().main());
        return Command.SINGLE_SUCCESS;
    }

    private int audit(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        rt.adminLog(adminOf(ctx), "audit", null, null);
        var _ =
                rt.tasks()
                        .supplyAsync(audit::runOnce)
                        .thenAcceptAsync(
                                lines ->
                                        sendLines(
                                                sender,
                                                lines.isEmpty() ? List.of("진주 원장 검사 통과") : lines),
                                rt.tasks().main());
        return Command.SINGLE_SUCCESS;
    }

    private int passExp(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        long amount = LongArgumentType.getLong(ctx, "amount");
        Player target = Bukkit.getPlayerExact(StringArgumentType.getString(ctx, "player"));
        if (target == null) {
            rt.api().messages().send(sender, "shop.admin.unknown_player");
            return 0;
        }
        rt.adminLog(
                adminOf(ctx), "pass.exp", target.getUniqueId().toString(), String.valueOf(amount));
        boolean ok = passes.adminExp(target.getUniqueId(), amount);
        rt.api().messages().send(sender, ok ? "shop.done" : "shop.admin.failed");
        return Command.SINGLE_SUCCESS;
    }

    private int donorReconcile(CommandContext<CommandSourceStack> ctx) {
        rt.adminLog(adminOf(ctx), "donor.reconcile", null, null);
        var _ = rt.tasks().async(donors::reconcile);
        rt.api().messages().send(ctx.getSource().getSender(), "shop.done");
        return Command.SINGLE_SUCCESS;
    }

    private int donorShow(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        withTarget(
                ctx,
                target -> {
                    var _ =
                            rt.db(c -> ShopRepository.subscription(c, target))
                                    .thenAccept(
                                            sub ->
                                                    sendLines(
                                                            sender,
                                                            sub.map(
                                                                            s ->
                                                                                    List.of(
                                                                                            s.tier()
                                                                                                    + " "
                                                                                                    + s
                                                                                                            .state()
                                                                                                    + " ~"
                                                                                                    + s
                                                                                                            .periodEnd()
                                                                                                    + " "
                                                                                                    + s
                                                                                                            .recurringId()))
                                                                    .orElse(List.of())));
                });
        return Command.SINGLE_SUCCESS;
    }
}
