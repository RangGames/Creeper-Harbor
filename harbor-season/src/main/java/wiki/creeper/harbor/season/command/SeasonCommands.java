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
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.season.SeasonContentView;
import wiki.creeper.harbor.season.api.League;
import wiki.creeper.harbor.season.api.OwnedTitle;
import wiki.creeper.harbor.season.api.TitleId;
import wiki.creeper.harbor.season.api.WeeklyLimitService;
import wiki.creeper.harbor.season.clock.SeasonClockManager;
import wiki.creeper.harbor.season.content.RankingBoardDef;
import wiki.creeper.harbor.season.content.TitleDef;
import wiki.creeper.harbor.season.data.SeasonRepository;
import wiki.creeper.harbor.season.limit.WeeklyLimitManager;
import wiki.creeper.harbor.season.ranking.RankingManager;
import wiki.creeper.harbor.season.title.TitleManager;
import wiki.creeper.harbor.season.transition.DryRunReport;
import wiki.creeper.harbor.season.transition.LogRow;
import wiki.creeper.harbor.season.transition.TransitionEngine;
import wiki.creeper.harbor.season.ui.Formats;
import wiki.creeper.harbor.season.ui.HallOfFameMenu;
import wiki.creeper.harbor.season.ui.RankingMenu;
import wiki.creeper.harbor.season.ui.TitleMenu;

/**
 * /시즌, /랭킹, /명예의전당, /칭호, /harbor season … (docs 20 11.5). 관리 명령은 모두 admin_log에 남긴다. 세부 권한은
 * harbor.admin.season.&lt;명령&gt; (transition · week · ranking · title · limit).
 */
public final class SeasonCommands {

    private static final String PERM_PLAYER = "harbor.season";

    private final HarborApi api;
    private final SeasonContentView content;
    private final SeasonClockManager clock;
    private final RankingManager rankings;
    private final TitleManager titles;
    private final WeeklyLimitManager limits;
    private final TransitionEngine engine;
    private final RankingMenu rankingMenu;
    private final HallOfFameMenu hofMenu;
    private final TitleMenu titleMenu;
    private final Tasks tasks;

    public SeasonCommands(
            HarborApi api,
            SeasonContentView content,
            SeasonClockManager clock,
            RankingManager rankings,
            TitleManager titles,
            WeeklyLimitManager limits,
            TransitionEngine engine,
            RankingMenu rankingMenu,
            HallOfFameMenu hofMenu,
            TitleMenu titleMenu,
            Tasks tasks) {
        this.api = api;
        this.content = content;
        this.clock = clock;
        this.rankings = rankings;
        this.titles = titles;
        this.limits = limits;
        this.engine = engine;
        this.rankingMenu = rankingMenu;
        this.hofMenu = hofMenu;
        this.titleMenu = titleMenu;
        this.tasks = tasks;
    }

    // ------------------------------------------------------------------ 플레이어 명령

    public void registerPlayerCommands(Commands commands) {
        commands.register(
                Commands.literal("시즌")
                        .requires(src -> src.getSender().hasPermission(PERM_PLAYER))
                        .executes(this::season)
                        .build(),
                "현재 시즌·주차·다음 초기화",
                List.of("season"));
        commands.register(
                Commands.literal("랭킹")
                        .requires(
                                src ->
                                        src.getExecutor() instanceof Player
                                                && src.getSender().hasPermission(PERM_PLAYER))
                        .executes(ctx -> ranking(ctx, null, null))
                        .then(
                                boardArgument()
                                        .executes(
                                                ctx ->
                                                        ranking(
                                                                ctx,
                                                                StringArgumentType.getString(
                                                                        ctx, "board"),
                                                                null))
                                        .then(
                                                Commands.argument(
                                                                "league", StringArgumentType.word())
                                                        .suggests(
                                                                (c, b) -> {
                                                                    for (League l :
                                                                            League.values()) {
                                                                        b.suggest(l.key());
                                                                    }
                                                                    return b.buildFuture();
                                                                })
                                                        .executes(
                                                                ctx ->
                                                                        ranking(
                                                                                ctx,
                                                                                StringArgumentType
                                                                                        .getString(
                                                                                                ctx,
                                                                                                "board"),
                                                                                StringArgumentType
                                                                                        .getString(
                                                                                                ctx,
                                                                                                "league")))))
                        .build(),
                "랭킹",
                List.of("ranking"));
        commands.register(
                Commands.literal("명예의전당")
                        .requires(
                                src ->
                                        src.getExecutor() instanceof Player
                                                && src.getSender().hasPermission(PERM_PLAYER))
                        .executes(ctx -> hallOfFame(ctx, 0))
                        .then(
                                Commands.argument("season", IntegerArgumentType.integer(1, 1000))
                                        .executes(
                                                ctx ->
                                                        hallOfFame(
                                                                ctx,
                                                                IntegerArgumentType.getInteger(
                                                                        ctx, "season"))))
                        .build(),
                "명예의 전당",
                List.of("halloffame"));
        commands.register(
                Commands.literal("칭호")
                        .requires(
                                src ->
                                        src.getExecutor() instanceof Player
                                                && src.getSender().hasPermission(PERM_PLAYER))
                        .executes(
                                ctx -> {
                                    if (ctx.getSource().getExecutor() instanceof Player p) {
                                        titleMenu.open(p, 0);
                                    }
                                    return Command.SINGLE_SUCCESS;
                                })
                        .then(Commands.literal("list").executes(this::titleList))
                        .then(
                                Commands.literal("equip")
                                        .then(ownedTitleArgument().executes(this::titleEquip)))
                        .then(
                                Commands.literal("unequip")
                                        .executes(
                                                ctx -> {
                                                    if (ctx.getSource().getExecutor()
                                                            instanceof Player p) {
                                                        titles.equip(p.getUniqueId(), null);
                                                        api.messages()
                                                                .send(p, "season.title.unequipped");
                                                    }
                                                    return Command.SINGLE_SUCCESS;
                                                }))
                        .build(),
                "칭호",
                List.of("title"));
    }

    private int season(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        int week = clock.week();
        api.messages()
                .send(
                        sender,
                        "season.info.header",
                        Placeholder.unparsed("name", clock.displayName()),
                        Placeholder.unparsed("season", String.valueOf(clock.current().value())));
        api.messages()
                .send(
                        sender,
                        week == 0
                                ? "season.info.before_start"
                                : clock.extended()
                                        ? "season.info.week_extended"
                                        : "season.info.week",
                        Placeholder.unparsed("week", String.valueOf(week)),
                        Placeholder.unparsed("weeks", String.valueOf(clock.weeks())),
                        Placeholder.unparsed(
                                "start",
                                Formats.date(clock.startsAt().toInstant(), api.infra().zone())),
                        Placeholder.unparsed(
                                "end",
                                Formats.date(clock.endsAt().toInstant(), api.infra().zone())));
        api.messages()
                .send(
                        sender,
                        "season.info.resets",
                        Placeholder.component("daily", remaining(clock.nextDailyReset())),
                        Placeholder.component("weekly", remaining(clock.nextWeeklyReset())));
        return Command.SINGLE_SUCCESS;
    }

    private Component remaining(ZonedDateTime at) {
        Duration d = Duration.between(api.time().now(), at);
        if (d.isNegative()) {
            d = Duration.ZERO;
        }
        return api.messages()
                .get(
                        d.toDays() > 0 ? "season.info.duration_days" : "season.info.duration",
                        Placeholder.unparsed("d", String.valueOf(d.toDays())),
                        Placeholder.unparsed("h", String.valueOf(d.toHoursPart())),
                        Placeholder.unparsed("m", String.valueOf(d.toMinutesPart())));
    }

    private int ranking(
            CommandContext<CommandSourceStack> ctx,
            @Nullable String board,
            @Nullable String league) {
        if (!(ctx.getSource().getExecutor() instanceof Player p)) {
            return 0;
        }
        List<RankingBoardDef> boards = rankings.boards();
        if (boards.isEmpty()) {
            api.messages().send(p, "season.ranking.no_boards");
            return 0;
        }
        RankingBoardDef def =
                board == null ? boards.getFirst() : rankings.boardDef(board).orElse(null);
        if (def == null) {
            api.messages().send(p, "season.ranking.unknown_board");
            return 0;
        }
        League l =
                league == null
                        ? def.defaultLeague()
                        : League.parse(league).orElse(def.defaultLeague());
        rankingMenu.open(p, def.id(), l, 0);
        return Command.SINGLE_SUCCESS;
    }

    private int hallOfFame(CommandContext<CommandSourceStack> ctx, int season) {
        if (ctx.getSource().getExecutor() instanceof Player p) {
            hofMenu.open(p, season, 0);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int titleList(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getExecutor() instanceof Player p)) {
            return 0;
        }
        var _ =
                titles.list(p.getUniqueId())
                        .thenAccept(
                                list -> {
                                    api.messages()
                                            .send(
                                                    p,
                                                    "season.title.list_header",
                                                    Placeholder.unparsed(
                                                            "count", String.valueOf(list.size())));
                                    for (OwnedTitle t : list) {
                                        api.messages()
                                                .send(
                                                        p,
                                                        "season.title.list_line",
                                                        Placeholder.component(
                                                                "title", titles.display(t.title())),
                                                        Placeholder.unparsed(
                                                                "id", t.title().shortName()),
                                                        Placeholder.unparsed(
                                                                "season",
                                                                String.valueOf(t.season())),
                                                        Placeholder.unparsed(
                                                                "date",
                                                                Formats.date(
                                                                        t.obtainedAt(),
                                                                        api.infra().zone())));
                                    }
                                });
        return Command.SINGLE_SUCCESS;
    }

    private int titleEquip(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getExecutor() instanceof Player p)) {
            return 0;
        }
        Optional<TitleId> title = TitleId.tryOf(StringArgumentType.getString(ctx, "title"));
        if (title.isPresent() && titles.equip(p.getUniqueId(), title.get())) {
            api.messages()
                    .send(
                            p,
                            "season.title.equipped",
                            Placeholder.component("title", titles.display(title.get())));
            return Command.SINGLE_SUCCESS;
        }
        api.messages().send(p, "season.title.not_owned");
        return 0;
    }

    // ------------------------------------------------------------------ 관리 명령

    /** /harbor season … (권한 harbor.admin.season은 Core가 건다). */
    public LiteralArgumentBuilder<CommandSourceStack> adminCommand() {
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

    // ------------------------------------------------------------------ 도우미

    /** 블로킹 작업을 가상 스레드에서 하고 결과를 메인 스레드로. */
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
            // 이름으로 찾는다
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

    private RequiredArgumentBuilder<CommandSourceStack, String> boardArgument() {
        return Commands.argument("board", StringArgumentType.word())
                .suggests(
                        (c, b) -> {
                            rankings.boards().forEach(d -> b.suggest(d.id()));
                            return b.buildFuture();
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

    private RequiredArgumentBuilder<CommandSourceStack, String> ownedTitleArgument() {
        return Commands.argument("title", StringArgumentType.greedyString())
                .suggests(
                        (c, b) -> {
                            if (c.getSource().getExecutor() instanceof Player p) {
                                for (TitleDef def : content.titles().values()) {
                                    if (titles.has(p.getUniqueId(), def.id())) {
                                        b.suggest(def.id().shortName());
                                    }
                                }
                            }
                            return b.buildFuture();
                        });
    }
}
