package wiki.creeper.harbor.season.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.season.SeasonContentView;
import wiki.creeper.harbor.season.api.TitleId;
import wiki.creeper.harbor.season.api.WeeklyLimitService;
import wiki.creeper.harbor.season.clock.SeasonClockManager;
import wiki.creeper.harbor.season.content.TitleDef;
import wiki.creeper.harbor.season.data.SeasonRepository;
import wiki.creeper.harbor.season.limit.WeeklyLimitManager;
import wiki.creeper.harbor.season.ranking.RankingManager;
import wiki.creeper.harbor.season.title.TitleManager;
import wiki.creeper.harbor.season.transition.DryRunReport;
import wiki.creeper.harbor.season.transition.LogRow;
import wiki.creeper.harbor.season.transition.TransitionEngine;
import wiki.creeper.harbor.season.ui.Formats;

/**
 * Admin commands under /harbor season (docs 20 11.5). Every admin command is recorded in admin_log.
 * Fine-grained permissions are harbor.admin.season.&lt;command&gt; (transition · week · ranking ·
 * title · limit).
 */
final class SeasonAdminCommands {

    private final HarborApi api;
    private final SeasonContentView content;
    private final SeasonClockManager clock;
    private final RankingManager rankings;
    private final TitleManager titles;
    private final WeeklyLimitManager limits;
    private final TransitionEngine engine;
    private final Tasks tasks;

    SeasonAdminCommands(
            HarborApi api,
            SeasonContentView content,
            SeasonClockManager clock,
            RankingManager rankings,
            TitleManager titles,
            WeeklyLimitManager limits,
            TransitionEngine engine,
            Tasks tasks) {
        this.api = api;
        this.content = content;
        this.clock = clock;
        this.rankings = rankings;
        this.titles = titles;
        this.limits = limits;
        this.engine = engine;
        this.tasks = tasks;
    }

    LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("season")
                .then(Commands.literal("info").executes(this::adminInfo))
                .then(
                        Commands.literal("transition")
                                .requires(perm("transition"))
                                .then(Commands.literal("dry-run").executes(this::dryRun))
                                .then(
                                        Commands.literal("start")
                                                .executes(ctx -> transition(ctx, "start")))
                                .then(
                                        Commands.literal("confirm-backup")
                                                .executes(ctx -> transition(ctx, "confirm-backup")))
                                .then(
                                        Commands.literal("resume")
                                                .executes(ctx -> transition(ctx, "resume")))
                                .then(
                                        Commands.literal("rollback")
                                                .executes(ctx -> transition(ctx, "rollback")))
                                .then(Commands.literal("status").executes(this::status)))
                .then(
                        Commands.literal("week")
                                .requires(perm("week"))
                                .then(
                                        Commands.literal("shift")
                                                .then(
                                                        Commands.argument(
                                                                        "hours",
                                                                        IntegerArgumentType.integer(
                                                                                0, 24 * 14))
                                                                .executes(this::weekShift))))
                .then(
                        Commands.literal("ranking")
                                .requires(perm("ranking"))
                                .then(Commands.literal("refresh").executes(this::rankingRefresh)))
                .then(
                        Commands.literal("title")
                                .requires(perm("title"))
                                .then(
                                        Commands.literal("grant")
                                                .then(
                                                        playerArgument()
                                                                .then(
                                                                        titleArgument()
                                                                                .executes(
                                                                                        ctx ->
                                                                                                titleAdmin(
                                                                                                        ctx,
                                                                                                        true)))))
                                .then(
                                        Commands.literal("revoke")
                                                .then(
                                                        playerArgument()
                                                                .then(
                                                                        titleArgument()
                                                                                .executes(
                                                                                        ctx ->
                                                                                                titleAdmin(
                                                                                                        ctx,
                                                                                                        false))))))
                .then(
                        Commands.literal("limit")
                                .requires(perm("limit"))
                                .then(
                                        Commands.literal("show")
                                                .then(playerArgument().executes(this::limitShow)))
                                .then(
                                        Commands.literal("reset")
                                                .then(
                                                        playerArgument()
                                                                .then(
                                                                        Commands.argument(
                                                                                        "key",
                                                                                        StringArgumentType
                                                                                                .greedyString())
                                                                                .executes(
                                                                                        this
                                                                                                ::limitReset)))));
    }

    private static Predicate<CommandSourceStack> perm(String sub) {
        return src -> src.getSender().hasPermission("harbor.admin.season." + sub);
    }

    private int adminInfo(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        api.messages()
                .send(
                        sender,
                        "season.admin.info",
                        Placeholder.unparsed("season", String.valueOf(clock.current().value())),
                        Placeholder.unparsed("state", clock.state().name()),
                        Placeholder.unparsed("week", String.valueOf(clock.week())),
                        Placeholder.unparsed("weeks", String.valueOf(clock.weeks())),
                        Placeholder.unparsed("shift", String.valueOf(clock.weekShiftHours())),
                        Placeholder.unparsed(
                                "start",
                                Formats.date(clock.startsAt().toInstant(), api.infra().zone())),
                        Placeholder.unparsed(
                                "tables", String.valueOf(content.plan().tables().size())));
        return Command.SINGLE_SUCCESS;
    }

    private int dryRun(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        api.messages().send(sender, "season.admin.working");
        log(sender, "transition.dry-run", null, "");
        async(
                sender,
                engine::dryRun,
                report -> {
                    var msg = api.messages();
                    msg.send(
                            sender,
                            "season.admin.dryrun.header",
                            Placeholder.unparsed("from", String.valueOf(clock.current().value())),
                            Placeholder.unparsed("errors", String.valueOf(report.errors().size())),
                            Placeholder.unparsed(
                                    "warnings", String.valueOf(report.warnings().size())));
                    report.errors()
                            .forEach(
                                    e ->
                                            msg.send(
                                                    sender,
                                                    "season.admin.dryrun.error",
                                                    Placeholder.unparsed("text", e)));
                    report.warnings()
                            .forEach(
                                    w ->
                                            msg.send(
                                                    sender,
                                                    "season.admin.dryrun.warning",
                                                    Placeholder.unparsed("text", w)));
                    for (DryRunReport.TablePlan t : report.tables()) {
                        if (!t.exists()) {
                            continue;
                        }
                        msg.send(
                                sender,
                                "season.admin.dryrun.table",
                                Placeholder.unparsed("table", t.table()),
                                Placeholder.unparsed("policy", t.policy()),
                                Placeholder.unparsed("archive", String.valueOf(t.archiveRows())),
                                Placeholder.unparsed("delete", String.valueOf(t.deleteRows())));
                    }
                    report.hookNotes()
                            .forEach(
                                    n ->
                                            msg.send(
                                                    sender,
                                                    "season.admin.dryrun.hook",
                                                    Placeholder.unparsed("text", n)));
                    msg.send(
                            sender,
                            "season.admin.dryrun.footer",
                            Placeholder.unparsed(
                                    "balances", String.valueOf(report.balanceResets())),
                            Placeholder.unparsed("fish", String.valueOf(report.fishLedgerRows())),
                            Placeholder.unparsed(
                                    "minutes",
                                    String.valueOf(Math.max(1, report.estimatedSeconds() / 60))));
                });
        return Command.SINGLE_SUCCESS;
    }

    private int transition(CommandContext<CommandSourceStack> ctx, String action) {
        CommandSender sender = ctx.getSource().getSender();
        String operator = operator(sender);
        boolean console = sender instanceof ConsoleCommandSender;
        log(sender, "transition." + action, null, "");
        async(
                sender,
                () ->
                        switch (action) {
                            case "start" -> engine.start(operator);
                            case "resume" -> engine.resume(operator);
                            case "rollback" -> engine.rollback(operator);
                            default -> engine.confirmBackup(operator, console);
                        },
                refusal -> {
                    if (refusal.isPresent()) {
                        api.messages()
                                .send(
                                        sender,
                                        "season.admin.refused",
                                        Placeholder.unparsed("reason", refusal.get()));
                    } else {
                        api.messages()
                                .send(
                                        sender,
                                        "season.admin.transition_ok",
                                        Placeholder.unparsed("action", action));
                    }
                });
        return Command.SINGLE_SUCCESS;
    }

    private int status(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        TransitionEngine.View view = engine.view();
        api.messages()
                .send(
                        sender,
                        "season.admin.status",
                        Placeholder.unparsed(
                                "id",
                                view.transitionId() == null
                                        ? "-"
                                        : String.valueOf(view.transitionId())),
                        Placeholder.unparsed("from", String.valueOf(view.fromSeason())),
                        Placeholder.unparsed("to", String.valueOf(view.toSeason())),
                        Placeholder.unparsed(
                                "step", view.step() == null ? "-" : String.valueOf(view.step())),
                        Placeholder.unparsed("status", view.status().name()),
                        Placeholder.unparsed(
                                "error", view.error() == null ? "" : String.valueOf(view.error())));
        async(
                sender,
                engine::history,
                rows -> {
                    int from = Math.max(0, rows.size() - 30);
                    for (LogRow row : rows.subList(from, rows.size())) {
                        api.messages()
                                .send(
                                        sender,
                                        "season.admin.status_line",
                                        Placeholder.unparsed("step", row.step()),
                                        Placeholder.unparsed("state", row.state()),
                                        Placeholder.unparsed("rows", String.valueOf(row.rows())),
                                        Placeholder.unparsed(
                                                "detail",
                                                row.detail() == null
                                                        ? ""
                                                        : String.valueOf(row.detail())));
                    }
                });
        return Command.SINGLE_SUCCESS;
    }

    private int weekShift(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        int hours = IntegerArgumentType.getInteger(ctx, "hours");
        log(sender, "week.shift", null, String.valueOf(hours));
        var _ =
                clock.shiftWeek(hours)
                        .thenAccept(
                                ok ->
                                        api.messages()
                                                .send(
                                                        sender,
                                                        ok
                                                                ? "season.admin.shift_ok"
                                                                : "season.admin.shift_refused",
                                                        Placeholder.unparsed(
                                                                "hours", String.valueOf(hours)),
                                                        Placeholder.unparsed(
                                                                "current",
                                                                String.valueOf(
                                                                        clock.weekShiftHours()))));
        return Command.SINGLE_SUCCESS;
    }

    private int rankingRefresh(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        log(sender, "ranking.refresh", null, "");
        var _ =
                rankings.refreshNow()
                        .thenAccept(
                                ok ->
                                        api.messages()
                                                .send(
                                                        sender,
                                                        ok
                                                                ? "season.admin.done"
                                                                : "season.admin.ranking_locked"));
        return Command.SINGLE_SUCCESS;
    }

    private int titleAdmin(CommandContext<CommandSourceStack> ctx, boolean grant) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "player");
        Optional<TitleId> title = TitleId.tryOf(StringArgumentType.getString(ctx, "title"));
        if (title.isEmpty()) {
            api.messages().send(sender, "season.admin.unknown_title");
            return 0;
        }
        var _ =
                resolve(name)
                        .thenAcceptAsync(
                                uuid -> {
                                    if (uuid.isEmpty()) {
                                        api.messages().send(sender, "season.admin.unknown_player");
                                        return;
                                    }
                                    log(
                                            sender,
                                            grant ? "title.grant" : "title.revoke",
                                            uuid.get(),
                                            title.get().toString());
                                    if (grant) {
                                        var _ =
                                                titles.grant(
                                                                uuid.get(),
                                                                title.get(),
                                                                "admin:" + operator(sender))
                                                        .thenAccept(
                                                                r ->
                                                                        api.messages()
                                                                                .send(
                                                                                        sender,
                                                                                        "season.admin.title_result",
                                                                                        Placeholder
                                                                                                .unparsed(
                                                                                                        "result",
                                                                                                        r
                                                                                                                .name())));
                                    } else {
                                        var _ =
                                                titles.revoke(uuid.get(), title.get())
                                                        .thenAccept(
                                                                ok ->
                                                                        api.messages()
                                                                                .send(
                                                                                        sender,
                                                                                        ok
                                                                                                ? "season.admin.done"
                                                                                                : "season.admin.failed"));
                                    }
                                },
                                tasks.main());
        return Command.SINGLE_SUCCESS;
    }

    private int limitShow(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        var _ =
                resolve(StringArgumentType.getString(ctx, "player"))
                        .thenAcceptAsync(
                                uuid -> {
                                    if (uuid.isEmpty()) {
                                        api.messages().send(sender, "season.admin.unknown_player");
                                        return;
                                    }
                                    var _ =
                                            limits.usage(uuid.get())
                                                    .thenAccept(
                                                            usage -> {
                                                                api.messages()
                                                                        .send(
                                                                                sender,
                                                                                "season.admin.limit_header",
                                                                                Placeholder
                                                                                        .unparsed(
                                                                                                "week",
                                                                                                String
                                                                                                        .valueOf(
                                                                                                                clock
                                                                                                                        .week())));
                                                                for (Map.Entry<String, Integer> e :
                                                                        usage.entrySet()) {
                                                                    api.messages()
                                                                            .send(
                                                                                    sender,
                                                                                    "season.admin.limit_line",
                                                                                    Placeholder
                                                                                            .unparsed(
                                                                                                    "key",
                                                                                                    e
                                                                                                            .getKey()),
                                                                                    Placeholder
                                                                                            .unparsed(
                                                                                                    "count",
                                                                                                    String
                                                                                                            .valueOf(
                                                                                                                    e
                                                                                                                            .getValue())));
                                                                }
                                                            });
                                },
                                tasks.main());
        return Command.SINGLE_SUCCESS;
    }

    private int limitReset(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String key = StringArgumentType.getString(ctx, "key");
        try {
            WeeklyLimitService.requireKey(key);
        } catch (IllegalArgumentException e) {
            api.messages().send(sender, "season.admin.failed");
            return 0;
        }
        var _ =
                resolve(StringArgumentType.getString(ctx, "player"))
                        .thenAcceptAsync(
                                uuid -> {
                                    if (uuid.isEmpty()) {
                                        api.messages().send(sender, "season.admin.unknown_player");
                                        return;
                                    }
                                    log(sender, "limit.reset", uuid.get(), key);
                                    var _ =
                                            limits.reset(uuid.get(), key)
                                                    .thenAccept(
                                                            ok ->
                                                                    api.messages()
                                                                            .send(
                                                                                    sender,
                                                                                    ok
                                                                                            ? "season.admin.done"
                                                                                            : "season.admin.failed"));
                                },
                                tasks.main());
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ helpers

    /** Runs blocking work on a virtual thread and hands the result back to the main thread. */
    private <T> void async(CommandSender sender, Blocking<T> work, Consumer<T> then) {
        var _ =
                tasks.supplyAsync(
                                () -> {
                                    try {
                                        return work.get();
                                    } catch (SQLException e) {
                                        throw new IllegalStateException(e);
                                    }
                                })
                        .handleAsync(
                                (result, error) -> {
                                    if (error != null) {
                                        api.messages()
                                                .send(
                                                        sender,
                                                        "season.admin.error",
                                                        Placeholder.unparsed(
                                                                "error",
                                                                String.valueOf(
                                                                        error.getMessage())));
                                    } else {
                                        then.accept(result);
                                    }
                                    return null;
                                },
                                tasks.main());
    }

    @FunctionalInterface
    private interface Blocking<T> {
        T get() throws SQLException;
    }

    private CompletableFuture<Optional<UUID>> resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return CompletableFuture.completedFuture(Optional.of(online.getUniqueId()));
        }
        try {
            return CompletableFuture.completedFuture(Optional.of(UUID.fromString(name)));
        } catch (IllegalArgumentException ignored) {
            // look up by name
        }
        return api.database().async(c -> SeasonRepository.uuidByName(c, name));
    }

    private static String operator(CommandSender sender) {
        return sender instanceof Player p ? p.getUniqueId().toString() : "CONSOLE";
    }

    private void log(CommandSender sender, String action, @Nullable UUID target, String detail) {
        String admin = operator(sender);
        var _ =
                api.database()
                        .asyncVoid(
                                c -> {
                                    try (var ps =
                                            c.prepareStatement(
                                                    "INSERT INTO admin_log (admin, action, target, detail) VALUES (?, ?, ?, ?)")) {
                                        ps.setString(1, admin);
                                        ps.setString(2, "season." + action);
                                        ps.setString(3, target == null ? null : target.toString());
                                        ps.setString(4, detail);
                                        ps.executeUpdate();
                                    }
                                });
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> playerArgument() {
        return Commands.argument("player", StringArgumentType.word())
                .suggests(
                        (c, b) -> {
                            Bukkit.getOnlinePlayers().forEach(p -> b.suggest(p.getName()));
                            return b.buildFuture();
                        });
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> titleArgument() {
        return Commands.argument("title", StringArgumentType.greedyString())
                .suggests(
                        (c, b) -> {
                            for (TitleDef def : content.titles().values()) {
                                b.suggest(def.id().shortName());
                            }
                            return b.buildFuture();
                        });
    }
}
