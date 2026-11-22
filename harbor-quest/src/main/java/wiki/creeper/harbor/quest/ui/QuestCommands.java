package wiki.creeper.harbor.quest.ui;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.service.QuestEngine;
import wiki.creeper.harbor.quest.service.QuestServices;

/**
 * /퀘스트 · /의뢰 · /연대기, /harbor quest … (docs 20 6.5, gui and editor are proposed). All admin commands
 * are recorded in admin_log.
 */
public final class QuestCommands {

    private static final String PERM = "harbor.quest";
    static final List<String> STATES = List.of("active", "ready", "completed", "failed");

    private final HarborApi api;
    private final QuestServices services;
    private final QuestDialogs dialogs;
    private final QuestBook book;
    private final AdminLog adminLog;
    private final QuestAdminMenu adminMenu;
    private final QuestEditor editor;
    private final QuestCommandArgs args;
    private final QuestAdminActions actions;

    public QuestCommands(
            HarborApi api,
            QuestServices services,
            QuestDialogs dialogs,
            QuestBook book,
            wiki.creeper.harbor.quest.editor.EditorStore store) {
        this.api = api;
        this.services = services;
        this.dialogs = dialogs;
        this.book = book;
        this.adminLog = new AdminLog(api);
        this.adminMenu = new QuestAdminMenu(api, services, adminLog);
        this.editor = new QuestEditor(api, services, store, adminLog);
        this.args = new QuestCommandArgs(api, services);
        this.actions = new QuestAdminActions(api, services, adminLog, args);
    }

    private QuestEngine engine() {
        return services.engine();
    }

    // ------------------------------------------------------------------ Player

    public void registerPlayerCommands(Commands commands) {
        commands.register(questCommand(), "퀘스트 목록·추적·포기", List.of());
        commands.register(
                Commands.literal("의뢰")
                        .requires(
                                src ->
                                        src.getExecutor() instanceof Player
                                                && src.getSender().hasPermission(PERM))
                        .executes(ctx -> run(ctx, dialogs::boardRoot))
                        .build(),
                "어부 길드 게시판",
                List.of());
        commands.register(
                Commands.literal("연대기")
                        .requires(
                                src ->
                                        src.getExecutor() instanceof Player
                                                && src.getSender()
                                                        .hasPermission("harbor.quest.chronicle"))
                        .executes(ctx -> run(ctx, p -> services.chronicle().open(p)))
                        .build(),
                "항구 연대기",
                List.of());
    }

    /**
     * /퀘스트: quest book (GUI). A Dialog list if balance.yml quest.journal.style is dialog.
     * Subcommands: 목록 (Dialog list) · 표시 (toggle the tracker boss bar) · 추적 &lt;quest&gt; (track) ·
     * 포기 &lt;quest&gt; (abandon).
     */
    private LiteralCommandNode<CommandSourceStack> questCommand() {
        return Commands.literal("퀘스트")
                .requires(
                        src ->
                                src.getExecutor() instanceof Player
                                        && src.getSender().hasPermission(PERM))
                .executes(ctx -> run(ctx, this::openJournal))
                .then(Commands.literal("목록").executes(ctx -> run(ctx, dialogs::journal)))
                .then(Commands.literal("표시").executes(ctx -> run(ctx, this::toggleBar)))
                .then(
                        Commands.literal("추적")
                                .then(
                                        args.questArgument(true)
                                                .executes(
                                                        ctx ->
                                                                run(
                                                                        ctx,
                                                                        p ->
                                                                                trackCommand(
                                                                                        ctx, p)))))
                .then(
                        Commands.literal("포기")
                                .then(
                                        args.questArgument(true)
                                                .executes(
                                                        ctx ->
                                                                run(
                                                                        ctx,
                                                                        p ->
                                                                                abandonCommand(
                                                                                        ctx, p)))))
                .build();
    }

    private void openJournal(Player player) {
        String style =
                engine().balance()
                        .string("quest.journal.style")
                        .orElse("gui")
                        .toLowerCase(Locale.ROOT);
        if (style.equals("dialog")) {
            dialogs.journal(player);
        } else {
            book.open(player);
        }
    }

    private void toggleBar(Player player) {
        var _ =
                services.trackerBar()
                        .toggle(player)
                        .thenAccept(
                                on ->
                                        api.messages()
                                                .send(
                                                        player,
                                                        on
                                                                ? "quest.bossbar.toggled_on"
                                                                : "quest.bossbar.toggled_off"));
    }

    private void trackCommand(CommandContext<CommandSourceStack> ctx, Player p) {
        Optional<QuestDef> d = args.quest(ctx);
        if (d.isEmpty()) {
            api.messages().send(p, "quest.error.unknown");
        } else if (!engine().track(p, d.get().id())) {
            api.messages().send(p, "quest.error.not_active");
        }
    }

    private void abandonCommand(CommandContext<CommandSourceStack> ctx, Player p) {
        Optional<QuestDef> d = args.quest(ctx);
        if (d.isEmpty()) {
            api.messages().send(p, "quest.error.unknown");
        } else {
            var _ = engine().abandon(p, d.get().id());
        }
    }

    private static int run(
            CommandContext<CommandSourceStack> ctx, java.util.function.Consumer<Player> action) {
        if (ctx.getSource().getExecutor() instanceof Player player) {
            action.accept(player);
        }
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ Admin

    public LiteralArgumentBuilder<CommandSourceStack> adminCommand() {
        return Commands.literal("quest")
                .then(
                        Commands.literal("editor")
                                .requires(perm("editor"))
                                .executes(
                                        ctx -> {
                                            if (ctx.getSource().getExecutor()
                                                    instanceof Player admin) {
                                                editor.open(admin);
                                            }
                                            return Command.SINGLE_SUCCESS;
                                        })
                                .then(
                                        args.questArgument(false)
                                                .executes(
                                                        ctx -> {
                                                            if (ctx.getSource().getExecutor()
                                                                    instanceof Player admin) {
                                                                editor.open(
                                                                        admin,
                                                                        QuestCommandArgs.rawQuest(
                                                                                        ctx)
                                                                                .id()
                                                                                .toString());
                                                            }
                                                            return Command.SINGLE_SUCCESS;
                                                        })))
                .then(
                        Commands.literal("gui")
                                .requires(perm("info"))
                                .then(
                                        QuestCommandArgs.target()
                                                .executes(
                                                        ctx -> {
                                                            if (ctx.getSource().getExecutor()
                                                                    instanceof Player admin) {
                                                                adminMenu.open(
                                                                        admin,
                                                                        QuestCommandArgs.player(
                                                                                ctx));
                                                            }
                                                            return Command.SINGLE_SUCCESS;
                                                        })))
                .then(
                        Commands.literal("info")
                                .requires(perm("info"))
                                .then(
                                        QuestCommandArgs.target()
                                                .executes(ctx -> actions.info(ctx, false))
                                                .then(
                                                        args.questArgument(false)
                                                                .executes(
                                                                        ctx ->
                                                                                actions.info(
                                                                                        ctx,
                                                                                        true)))))
                .then(
                        Commands.literal("set")
                                .requires(perm("set"))
                                .then(
                                        QuestCommandArgs.target()
                                                .then(
                                                        args.questArgument(false)
                                                                .then(
                                                                        Commands.argument(
                                                                                        "state",
                                                                                        StringArgumentType
                                                                                                .word())
                                                                                .suggests(
                                                                                        (c, b) -> {
                                                                                            STATES
                                                                                                    .forEach(
                                                                                                            b
                                                                                                                    ::suggest);
                                                                                            return b
                                                                                                    .buildFuture();
                                                                                        })
                                                                                .executes(
                                                                                        actions
                                                                                                ::set)))))
                .then(
                        Commands.literal("progress")
                                .requires(perm("set"))
                                .then(
                                        QuestCommandArgs.target()
                                                .then(
                                                        args.questArgument(false)
                                                                .then(
                                                                        Commands.argument(
                                                                                        "key",
                                                                                        StringArgumentType
                                                                                                .word())
                                                                                .then(
                                                                                        Commands
                                                                                                .argument(
                                                                                                        "value",
                                                                                                        IntegerArgumentType
                                                                                                                .integer(
                                                                                                                        0))
                                                                                                .executes(
                                                                                                        actions
                                                                                                                ::progress))))))
                .then(
                        Commands.literal("complete")
                                .requires(perm("complete"))
                                .then(
                                        QuestCommandArgs.target()
                                                .then(
                                                        args.questArgument(false)
                                                                .executes(
                                                                        ctx ->
                                                                                actions.complete(
                                                                                        ctx, true))
                                                                .then(
                                                                        Commands.literal(
                                                                                        "--no-reward")
                                                                                .executes(
                                                                                        ctx ->
                                                                                                actions
                                                                                                        .complete(
                                                                                                                ctx,
                                                                                                                false))))))
                .then(
                        Commands.literal("reset")
                                .requires(perm("set"))
                                .then(
                                        QuestCommandArgs.target()
                                                .then(
                                                        args.questArgument(false)
                                                                .executes(actions::reset))))
                .then(
                        Commands.literal("board")
                                .requires(perm("board"))
                                .then(
                                        Commands.literal("reroll")
                                                .then(
                                                        QuestCommandArgs.target()
                                                                .executes(
                                                                        ctx ->
                                                                                actions.reroll(
                                                                                        ctx,
                                                                                        "daily"))
                                                                .then(
                                                                        Commands.argument(
                                                                                        "kind",
                                                                                        StringArgumentType
                                                                                                .word())
                                                                                .suggests(
                                                                                        (c, b) -> {
                                                                                            List.of(
                                                                                                            "daily",
                                                                                                            "weekly",
                                                                                                            "collect")
                                                                                                    .forEach(
                                                                                                            b
                                                                                                                    ::suggest);
                                                                                            return b
                                                                                                    .buildFuture();
                                                                                        })
                                                                                .executes(
                                                                                        ctx ->
                                                                                                actions
                                                                                                        .reroll(
                                                                                                                ctx,
                                                                                                                StringArgumentType
                                                                                                                        .getString(
                                                                                                                                ctx,
                                                                                                                                "kind")))))))
                .then(
                        Commands.literal("server")
                                .requires(perm("server"))
                                .executes(actions::server)
                                .then(
                                        Commands.literal("open")
                                                .executes(
                                                        ctx -> {
                                                            services.serverProgress()
                                                                    .openClimax(true);
                                                            actions.log(
                                                                    ctx.getSource().getSender(),
                                                                    "server.open",
                                                                    "-",
                                                                    "climax_ready");
                                                            api.messages()
                                                                    .send(
                                                                            ctx.getSource()
                                                                                    .getSender(),
                                                                            "quest.admin.done");
                                                            return Command.SINGLE_SUCCESS;
                                                        }))
                                .then(
                                        Commands.literal("start")
                                                .executes(
                                                        ctx -> {
                                                            services.serverProgress()
                                                                    .requestClimaxStart();
                                                            actions.log(
                                                                    ctx.getSource().getSender(),
                                                                    "server.start",
                                                                    "-",
                                                                    "climax_start");
                                                            api.messages()
                                                                    .send(
                                                                            ctx.getSource()
                                                                                    .getSender(),
                                                                            "quest.admin.start_requested");
                                                            return Command.SINGLE_SUCCESS;
                                                        })));
    }

    // ------------------------------------------------------------------ Helpers

    private static Predicate<CommandSourceStack> perm(String name) {
        return src -> src.getSender().hasPermission("harbor.admin.quest." + name);
    }
}
