package wiki.creeper.harbor.core.currency;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.model.PlayerRef;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.core.admin.AdminLog;
import wiki.creeper.harbor.core.api.BalanceRank;
import wiki.creeper.harbor.core.api.CurrencyService;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.api.PlayerService;

/**
 * /골드: 잔액·순위·관리자 지급 (CMI 이코노미 비슷하게, 추가 제안). 잔액은 이번 시즌 골드이고, 바꾸는 것은 모두 원장을 거친다.
 *
 * <pre>
 * /골드                              내 잔액과 순위                (harbor.gold)
 * /골드 순위 [쪽]                     시즌 골드 순위 (쪽마다 10명)   (harbor.gold)
 * /골드 &lt;플레이어&gt;                    다른 사람 잔액                 (harbor.admin.gold)
 * /골드 지급|회수|설정 &lt;플레이어&gt; &lt;금액&gt;  오프라인·다른 서버 접속자 포함 (harbor.admin.gold, admin_log 기록)
 * </pre>
 */
public final class GoldCommand {

    static final String PERM = "harbor.gold";
    static final String PERM_ADMIN = "harbor.admin.gold";

    private static final int PAGE_SIZE = 10;
    private static final int MAX_PAGE = CurrencyService.TOP_CACHED / PAGE_SIZE;

    private final CurrencyService currency;
    private final PlayerService players;
    private final MessageService messages;
    private final AdminLog adminLog;

    public GoldCommand(
            CurrencyService currency,
            PlayerService players,
            MessageService messages,
            AdminLog adminLog) {
        this.currency = currency;
        this.players = players;
        this.messages = messages;
        this.adminLog = adminLog;
    }

    private enum Op {
        GIVE,
        TAKE,
        SET
    }

    /** LifecycleEvents.COMMANDS에서 등록한다. */
    public void register(Commands commands) {
        Predicate<CommandSourceStack> admin = src -> src.getSender().hasPermission(PERM_ADMIN);
        LiteralArgumentBuilder<CommandSourceStack> root =
                Commands.literal("골드")
                        .requires(src -> src.getSender().hasPermission(PERM))
                        .executes(this::self)
                        .then(
                                Commands.literal("순위")
                                        .executes(ctx -> top(ctx, 1))
                                        .then(
                                                Commands.argument(
                                                                "page",
                                                                IntegerArgumentType.integer(
                                                                        1, MAX_PAGE))
                                                        .executes(
                                                                ctx ->
                                                                        top(
                                                                                ctx,
                                                                                IntegerArgumentType
                                                                                        .getInteger(
                                                                                                ctx,
                                                                                                "page")))))
                        .then(adminOp("지급", Op.GIVE, admin))
                        .then(adminOp("회수", Op.TAKE, admin))
                        .then(adminOp("설정", Op.SET, admin))
                        .then(playerArg().requires(admin).executes(this::other));
        commands.register(root.build(), "골드 잔액·순위", List.of());
    }

    private LiteralArgumentBuilder<CommandSourceStack> adminOp(
            String name, Op op, Predicate<CommandSourceStack> admin) {
        return Commands.literal(name)
                .requires(admin)
                .then(
                        playerArg()
                                .then(
                                        Commands.argument(
                                                        "amount",
                                                        LongArgumentType.longArg(
                                                                op == Op.SET ? 0 : 1))
                                                .executes(ctx -> change(ctx, op))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> playerArg() {
        return Commands.argument("player", StringArgumentType.word())
                .suggests(
                        (ctx, b) -> {
                            String typed = b.getRemaining().toLowerCase(Locale.ROOT);
                            for (Player p : Bukkit.getOnlinePlayers()) {
                                if (p.getName().toLowerCase(Locale.ROOT).startsWith(typed)) {
                                    b.suggest(p.getName());
                                }
                            }
                            return b.buildFuture();
                        });
    }

    // ------------------------------------------------------------------ 하위 명령

    private int self(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            messages.send(sender, "gold.players_only");
            return 0;
        }
        showBalance(sender, new PlayerRef(player.getUniqueId(), player.getName()), true);
        return Command.SINGLE_SUCCESS;
    }

    private int other(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "player");
        var _ =
                players.find(name)
                        .thenAccept(
                                ref -> {
                                    if (ref.isEmpty()) {
                                        messages.send(
                                                sender,
                                                "gold.unknown_player",
                                                Placeholder.unparsed("player", name));
                                        return;
                                    }
                                    showBalance(sender, ref.get(), false);
                                });
        return Command.SINGLE_SUCCESS;
    }

    private void showBalance(CommandSender sender, PlayerRef who, boolean mine) {
        UUID id = who.uuid();
        var _ =
                currency.balance(id, Currency.GOLD)
                        .thenCompose(
                                gold ->
                                        currency.rank(id, Currency.GOLD)
                                                .thenAccept(
                                                        rank ->
                                                                messages.send(
                                                                        sender,
                                                                        mine
                                                                                ? "gold.balance.mine"
                                                                                : "gold.balance.other",
                                                                        Placeholder.unparsed(
                                                                                "player",
                                                                                who.name()),
                                                                        Placeholder.unparsed(
                                                                                "balance",
                                                                                fmt(gold)),
                                                                        Placeholder.unparsed(
                                                                                "rank",
                                                                                rank.map(
                                                                                                String
                                                                                                        ::valueOf)
                                                                                        .orElse(
                                                                                                "-")))));
    }

    private int top(CommandContext<CommandSourceStack> ctx, int page) {
        CommandSender sender = ctx.getSource().getSender();
        var _ =
                currency.top(Currency.GOLD, (page - 1) * PAGE_SIZE, PAGE_SIZE)
                        .thenAccept(
                                rows -> {
                                    if (rows.isEmpty()) {
                                        messages.send(sender, "gold.top.empty");
                                        return;
                                    }
                                    messages.send(
                                            sender,
                                            "gold.top.header",
                                            Placeholder.unparsed("page", String.valueOf(page)));
                                    for (BalanceRank r : rows) {
                                        messages.send(
                                                sender,
                                                r.rank() <= 3
                                                        ? "gold.top.line_top"
                                                        : "gold.top.line",
                                                Placeholder.unparsed(
                                                        "rank", String.valueOf(r.rank())),
                                                Placeholder.unparsed("player", r.player().name()),
                                                Placeholder.unparsed("balance", fmt(r.balance())));
                                    }
                                    if (page < MAX_PAGE && rows.size() == PAGE_SIZE) {
                                        sender.sendMessage(
                                                messages.get(
                                                                "gold.top.next",
                                                                Placeholder.unparsed(
                                                                        "page",
                                                                        String.valueOf(page + 1)))
                                                        .clickEvent(
                                                                ClickEvent.runCommand(
                                                                        "/골드 순위 " + (page + 1))));
                                    }
                                });
        return Command.SINGLE_SUCCESS;
    }

    private int change(CommandContext<CommandSourceStack> ctx, Op op) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "player");
        long amount = LongArgumentType.getLong(ctx, "amount");
        var _ =
                players.find(name)
                        .thenAccept(
                                ref -> {
                                    if (ref.isEmpty()) {
                                        messages.send(
                                                sender,
                                                "gold.unknown_player",
                                                Placeholder.unparsed("player", name));
                                        return;
                                    }
                                    apply(sender, ref.get(), op, amount);
                                });
        return Command.SINGLE_SUCCESS;
    }

    private void apply(CommandSender sender, PlayerRef target, Op op, long amount) {
        UUID id = target.uuid();
        adminLog.record(
                sender,
                switch (op) {
                    case GIVE -> "gold.give";
                    case TAKE -> "gold.take";
                    case SET -> "gold.set";
                },
                id.toString(),
                String.valueOf(amount));
        CompletableFuture<Result<Long, LedgerError>> result =
                switch (op) {
                    case GIVE ->
                            currency.give(
                                    id, Currency.GOLD, amount, LedgerReason.ADMIN_GRANT, null);
                    case TAKE ->
                            currency.take(id, Currency.GOLD, amount, LedgerReason.ADMIN_TAKE, null);
                    case SET -> currency.set(id, Currency.GOLD, amount, null);
                };
        var _ =
                result.thenAccept(
                        r -> {
                            switch (r) {
                                case Result.Ok<Long, LedgerError>(Long balance) -> {
                                    messages.send(
                                            sender,
                                            switch (op) {
                                                case GIVE -> "gold.admin.given";
                                                case TAKE -> "gold.admin.taken";
                                                case SET -> "gold.admin.set";
                                            },
                                            Placeholder.unparsed("player", target.name()),
                                            Placeholder.unparsed("amount", fmt(amount)),
                                            Placeholder.unparsed("balance", fmt(balance)));
                                    Player online = Bukkit.getPlayer(id);
                                    if (online != null && op == Op.GIVE && !online.equals(sender)) {
                                        messages.send(
                                                online,
                                                "gold.received",
                                                Placeholder.unparsed("amount", fmt(amount)),
                                                Placeholder.unparsed("balance", fmt(balance)));
                                    }
                                }
                                case Result.Err<Long, LedgerError>(LedgerError error) ->
                                        messages.send(
                                                sender,
                                                error == LedgerError.INSUFFICIENT_FUNDS
                                                        ? "gold.admin.insufficient"
                                                        : "gold.admin.failed",
                                                Placeholder.unparsed("player", target.name()),
                                                Placeholder.unparsed("error", error.name()));
                            }
                        });
    }

    static String fmt(long gold) {
        return String.format(Locale.ROOT, "%,d", gold);
    }
}
