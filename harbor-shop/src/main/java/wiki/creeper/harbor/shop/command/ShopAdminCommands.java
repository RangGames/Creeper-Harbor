package wiki.creeper.harbor.shop.command;

import static wiki.creeper.harbor.shop.command.CommandSupport.adminOf;
import static wiki.creeper.harbor.shop.command.CommandSupport.playerArg;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.shop.ShopRuntime;
import wiki.creeper.harbor.shop.chest.ChestManager;
import wiki.creeper.harbor.shop.content.ChestDef;
import wiki.creeper.harbor.shop.content.CosmeticDef;
import wiki.creeper.harbor.shop.cosmetic.CosmeticManager;
import wiki.creeper.harbor.shop.data.ShopRepository;
import wiki.creeper.harbor.shop.data.SubscriptionRepository;
import wiki.creeper.harbor.shop.donor.DonorManager;
import wiki.creeper.harbor.shop.pass.PassManager;
import wiki.creeper.harbor.shop.pearl.PearlAudit;
import wiki.creeper.harbor.shop.pearl.PearlManager;
import wiki.creeper.harbor.shop.util.UuidV7;
import wiki.creeper.harbor.shop.webhook.InboxProcessor;
import wiki.creeper.harbor.shop.webhook.Reconciler;

/**
 * The /harbor shop admin subtree (split out of {@link ShopCommands}). Every admin action is written
 * to admin_log, and each subcommand requires harbor.admin.shop.&lt;command&gt;.
 */
final class ShopAdminCommands {

    private final ShopRuntime rt;
    private final ShopPaymentAdmin payments;
    private final CosmeticManager cosmetics;
    private final PassManager passes;
    private final ChestManager chests;
    private final DonorManager donors;

    ShopAdminCommands(
            ShopRuntime rt,
            PearlManager pearls,
            CosmeticManager cosmetics,
            PassManager passes,
            ChestManager chests,
            DonorManager donors,
            InboxProcessor inbox,
            Reconciler reconciler,
            PearlAudit audit) {
        this.rt = rt;
        this.payments = new ShopPaymentAdmin(rt, pearls, inbox, reconciler, audit);
        this.cosmetics = cosmetics;
        this.passes = passes;
        this.chests = chests;
        this.donors = donors;
    }

    /** Builds the /harbor shop literal. */
    LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("shop")
                .then(
                        sub("pearl")
                                .then(payments.pearlAdmin("give"))
                                .then(payments.pearlAdmin("take"))
                                .then(
                                        Commands.literal("confirm")
                                                .then(
                                                        Commands.argument(
                                                                        "id",
                                                                        StringArgumentType.word())
                                                                .executes(payments::pearlConfirm))))
                .then(
                        sub("tx")
                                .then(
                                        Commands.argument("tx", StringArgumentType.greedyString())
                                                .executes(payments::tx)))
                .then(
                        sub("inbox")
                                .executes(payments::inboxList)
                                .then(
                                        Commands.literal("retry")
                                                .executes(ctx -> payments.inboxRetry(ctx, null))
                                                .then(
                                                        Commands.argument(
                                                                        "id",
                                                                        StringArgumentType
                                                                                .greedyString())
                                                                .executes(
                                                                        ctx ->
                                                                                payments.inboxRetry(
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
                                .executes(payments::reconcile)
                                .then(Commands.literal("audit").executes(payments::audit)))
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

    private LiteralArgumentBuilder<CommandSourceStack> cosmeticAdmin(String mode) {
        return Commands.literal(mode)
                .then(
                        playerArg()
                                .then(
                                        CommandSupport.cosmeticArgument(rt)
                                                .executes(
                                                        ctx ->
                                                                cosmeticAdmin(
                                                                        ctx,
                                                                        mode.equals("give")))));
    }

    private int cosmeticAdmin(CommandContext<CommandSourceStack> ctx, boolean give) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<CosmeticDef> def =
                CommandSupport.cosmetic(rt, StringArgumentType.getString(ctx, "cosmetic"));
        if (def.isEmpty()) {
            rt.api().messages().send(sender, "shop.error.unknown_cosmetic");
            return 0;
        }
        CommandSupport.withTarget(
                rt,
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
        CommandSupport.withTarget(
                rt,
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

    /**
     * Waives debt at admin discretion, e.g. a minor's cancelled payment (docs 20 12.4 "legal
     * compliance").
     */
    private int clearDebt(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        CommandSupport.withTarget(
                rt,
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
                                    CommandSupport.sendLines(rt, sender, lines);
                                });
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
        CommandSupport.withTarget(
                rt,
                ctx,
                target -> {
                    var _ =
                            rt.db(c -> SubscriptionRepository.subscription(c, target))
                                    .thenAccept(
                                            sub ->
                                                    CommandSupport.sendLines(
                                                            rt,
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
