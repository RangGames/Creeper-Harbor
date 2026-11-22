package wiki.creeper.harbor.shop.command;

import static wiki.creeper.harbor.shop.command.CommandSupport.adminId;
import static wiki.creeper.harbor.shop.command.CommandSupport.adminOf;
import static wiki.creeper.harbor.shop.command.CommandSupport.playerArg;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.shop.ShopRuntime;
import wiki.creeper.harbor.shop.pearl.PearlAudit;
import wiki.creeper.harbor.shop.pearl.PearlManager;
import wiki.creeper.harbor.shop.util.UuidV7;
import wiki.creeper.harbor.shop.webhook.InboxProcessor;
import wiki.creeper.harbor.shop.webhook.Reconciler;

/**
 * Pearl and payment handlers of the /harbor shop admin tree: pearl give/take with second-admin
 * confirmation, purchase lookup, webhook inbox, reconciliation and the pearl ledger audit.
 */
final class ShopPaymentAdmin {

    /** A large admin pearl grant awaiting confirmation (requires a different admin). */
    private record PendingGrant(String requester, UUID target, long amount, String reason) {}

    private final ShopRuntime rt;
    private final PearlManager pearls;
    private final InboxProcessor inbox;
    private final Reconciler reconciler;
    private final PearlAudit audit;
    private final Map<String, PendingGrant> pendingGrants = new ConcurrentHashMap<>();

    ShopPaymentAdmin(
            ShopRuntime rt,
            PearlManager pearls,
            InboxProcessor inbox,
            Reconciler reconciler,
            PearlAudit audit) {
        this.rt = rt;
        this.pearls = pearls;
        this.inbox = inbox;
        this.reconciler = reconciler;
        this.audit = audit;
    }

    LiteralArgumentBuilder<CommandSourceStack> pearlAdmin(String mode) {
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

    private int pearlAdmin(CommandContext<CommandSourceStack> ctx, boolean give) {
        CommandSender sender = ctx.getSource().getSender();
        long amount = LongArgumentType.getLong(ctx, "amount");
        String reason = StringArgumentType.getString(ctx, "reason").trim();
        if (reason.length() < 2) {
            rt.api().messages().send(sender, "shop.admin.reason_required");
            return 0;
        }
        CommandSupport.withTarget(
                rt,
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

    int pearlConfirm(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String id = StringArgumentType.getString(ctx, "id");
        PendingGrant pending = pendingGrants.get(id);
        if (pending == null) {
            rt.api().messages().send(sender, "shop.admin.no_pending");
            return 0;
        }
        if (pending.requester().equals(adminId(sender))) {
            // Grants over 5,000 pearls are confirmed by a different admin (docs 20 12.4 "abuse
            // prevention")
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

    int tx(CommandContext<CommandSourceStack> ctx) {
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
                        .thenAccept(lines -> CommandSupport.sendLines(rt, sender, lines));
        return Command.SINGLE_SUCCESS;
    }

    int inboxList(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        var _ =
                rt.db(inbox::problems)
                        .thenAccept(
                                rows ->
                                        CommandSupport.sendLines(
                                                rt,
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

    int inboxRetry(CommandContext<CommandSourceStack> ctx, @Nullable String id) {
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

    int reconcile(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        rt.adminLog(adminOf(ctx), "reconcile", null, null);
        var _ =
                rt.tasks()
                        .supplyAsync(reconciler::runOnce)
                        .thenAcceptAsync(
                                s -> CommandSupport.sendLines(rt, sender, List.of(s)),
                                rt.tasks().main());
        return Command.SINGLE_SUCCESS;
    }

    int audit(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        rt.adminLog(adminOf(ctx), "audit", null, null);
        var _ =
                rt.tasks()
                        .supplyAsync(audit::runOnce)
                        .thenAcceptAsync(
                                lines ->
                                        CommandSupport.sendLines(
                                                rt,
                                                sender,
                                                lines.isEmpty() ? List.of("진주 원장 검사 통과") : lines),
                                rt.tasks().main());
        return Command.SINGLE_SUCCESS;
    }
}
