package wiki.creeper.harbor.season.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.season.SeasonContentView;
import wiki.creeper.harbor.season.api.League;
import wiki.creeper.harbor.season.api.OwnedTitle;
import wiki.creeper.harbor.season.api.TitleId;
import wiki.creeper.harbor.season.clock.SeasonClockManager;
import wiki.creeper.harbor.season.content.RankingBoardDef;
import wiki.creeper.harbor.season.content.TitleDef;
import wiki.creeper.harbor.season.ranking.RankingManager;
import wiki.creeper.harbor.season.title.TitleManager;
import wiki.creeper.harbor.season.ui.Formats;
import wiki.creeper.harbor.season.ui.HallOfFameMenu;
import wiki.creeper.harbor.season.ui.RankingMenu;
import wiki.creeper.harbor.season.ui.TitleMenu;

/** Player commands: /시즌, /랭킹, /명예의전당, /칭호 (docs 20 11.5). */
final class SeasonPlayerCommands {

    private static final String PERM_PLAYER = "harbor.season";

    private final HarborApi api;
    private final SeasonContentView content;
    private final SeasonClockManager clock;
    private final RankingManager rankings;
    private final TitleManager titles;
    private final RankingMenu rankingMenu;
    private final HallOfFameMenu hofMenu;
    private final TitleMenu titleMenu;

    SeasonPlayerCommands(
            HarborApi api,
            SeasonContentView content,
            SeasonClockManager clock,
            RankingManager rankings,
            TitleManager titles,
            RankingMenu rankingMenu,
            HallOfFameMenu hofMenu,
            TitleMenu titleMenu) {
        this.api = api;
        this.content = content;
        this.clock = clock;
        this.rankings = rankings;
        this.titles = titles;
        this.rankingMenu = rankingMenu;
        this.hofMenu = hofMenu;
        this.titleMenu = titleMenu;
    }

    void register(Commands commands) {
        commands.register(
                Commands.literal("시즌")
                        .requires(src -> src.getSender().hasPermission(PERM_PLAYER))
                        .executes(this::season)
                        .build(),
                "현재 시즌·주차·다음 초기화",
                List.of());
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
                List.of());
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
                List.of());
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
                        .then(Commands.literal("목록").executes(this::titleList))
                        .then(
                                Commands.literal("장착")
                                        .then(ownedTitleArgument().executes(this::titleEquip)))
                        .then(
                                Commands.literal("해제")
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
                List.of());
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

    private RequiredArgumentBuilder<CommandSourceStack, String> boardArgument() {
        return Commands.argument("board", StringArgumentType.word())
                .suggests(
                        (c, b) -> {
                            rankings.boards().forEach(d -> b.suggest(d.id()));
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
