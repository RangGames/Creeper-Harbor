package wiki.creeper.harbor.quest.ui;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.content.QuestType;
import wiki.creeper.harbor.quest.data.QuestEntry;
import wiki.creeper.harbor.quest.data.QuestRepository;
import wiki.creeper.harbor.quest.service.QuestEngine;
import wiki.creeper.harbor.quest.service.QuestServices;

/** /퀘스트 · /의뢰 · /연대기, /harbor quest … (docs 20 6.5, gui·편집기는 추가 제안). 관리 명령은 모두 admin_log에 남긴다. */
public final class QuestCommands {

    private static final String PERM = "harbor.quest";
    private static final List<String> STATES = List.of("active", "ready", "completed", "failed");

    private final HarborApi api;
    private final QuestServices services;
    private final QuestDialogs dialogs;
    private final QuestBook book;
    private final AdminLog adminLog;
    private final QuestAdminMenu adminMenu;

    public QuestCommands(
            HarborApi api, QuestServices services, QuestDialogs dialogs, QuestBook book) {
        this.api = api;
        this.services = services;
        this.dialogs = dialogs;
        this.book = book;
        this.adminLog = new AdminLog(api);
        this.adminMenu = new QuestAdminMenu(api, services, adminLog);
    }

    private QuestEngine engine() {
        return services.engine();
    }

    // ------------------------------------------------------------------ 플레이어

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
     * /퀘스트: 퀘스트북(GUI). balance.yml quest.journal.style이 dialog면 Dialog 목록. 하위: 목록(Dialog) · 표시(추적
     * 보스바 켜기/끄기) · 추적 &lt;퀘스트&gt; · 포기 &lt;퀘스트&gt;.
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
                                        questArgument(true)
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
                                        questArgument(true)
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
        Optional<QuestDef> d = quest(ctx);
        if (d.isEmpty()) {
            api.messages().send(p, "quest.error.unknown");
        } else if (!engine().track(p, d.get().id())) {
            api.messages().send(p, "quest.error.not_active");
        }
    }

    private void abandonCommand(CommandContext<CommandSourceStack> ctx, Player p) {
        Optional<QuestDef> d = quest(ctx);
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

    // ------------------------------------------------------------------ 관리

    public LiteralArgumentBuilder<CommandSourceStack> adminCommand() {
        return Commands.literal("quest")
                .then(
                        Commands.literal("gui")
                                .requires(perm("info"))
                                .then(
                                        target().executes(
                                                        ctx -> {
                                                            if (ctx.getSource().getExecutor()
                                                                    instanceof Player admin) {
                                                                adminMenu.open(admin, player(ctx));
                                                            }
                                                            return Command.SINGLE_SUCCESS;
                                                        })))
                .then(
                        Commands.literal("info")
                                .requires(perm("info"))
                                .then(
                                        target().executes(ctx -> info(ctx, false))
                                                .then(
                                                        questArgument(false)
                                                                .executes(ctx -> info(ctx, true)))))
                .then(
                        Commands.literal("set")
                                .requires(perm("set"))
                                .then(
                                        target().then(
                                                        questArgument(false)
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
                                                                                        this
                                                                                                ::set)))))
                .then(
                        Commands.literal("progress")
                                .requires(perm("set"))
                                .then(
                                        target().then(
                                                        questArgument(false)
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
                                                                                                        this
                                                                                                                ::progress))))))
                .then(
                        Commands.literal("complete")
                                .requires(perm("complete"))
                                .then(
                                        target().then(
                                                        questArgument(false)
                                                                .executes(
                                                                        ctx -> complete(ctx, true))
                                                                .then(
                                                                        Commands.literal(
                                                                                        "--no-reward")
                                                                                .executes(
                                                                                        ctx ->
                                                                                                complete(
                                                                                                        ctx,
                                                                                                        false))))))
                .then(
                        Commands.literal("reset")
                                .requires(perm("set"))
                                .then(target().then(questArgument(false).executes(this::reset))))
                .then(
                        Commands.literal("board")
                                .requires(perm("board"))
                                .then(
                                        Commands.literal("reroll")
                                                .then(
                                                        target().executes(
                                                                        ctx -> reroll(ctx, "daily"))
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
                                                                                                reroll(
                                                                                                        ctx,
                                                                                                        StringArgumentType
                                                                                                                .getString(
                                                                                                                        ctx,
                                                                                                                        "kind")))))))
                .then(
                        Commands.literal("server")
                                .requires(perm("server"))
                                .executes(this::server)
                                .then(
                                        Commands.literal("open")
                                                .executes(
                                                        ctx -> {
                                                            services.serverProgress()
                                                                    .openClimax(true);
                                                            log(
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
                                                            log(
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

    private int info(CommandContext<CommandSourceStack> ctx, boolean one)
            throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        Player target = player(ctx);
        Optional<QuestDef> filter = one ? quest(ctx) : Optional.empty();
        var uuid = target.getUniqueId();
        var _ =
                api.database()
                        .async(c -> QuestRepository.rows(c, uuid))
                        .thenAcceptAsync(
                                rows -> {
                                    api.messages()
                                            .send(
                                                    sender,
                                                    "quest.admin.info_header",
                                                    Placeholder.unparsed(
                                                            "player", target.getName()),
                                                    Placeholder.unparsed(
                                                            "count", String.valueOf(rows.size())));
                                    for (QuestRepository.Row row : rows) {
                                        if (filter.isPresent()
                                                && !filter.get().id().equals(row.quest())) {
                                            continue;
                                        }
                                        api.messages()
                                                .send(
                                                        sender,
                                                        one
                                                                ? "quest.admin.info_detail"
                                                                : "quest.admin.info_line",
                                                        Placeholder.unparsed(
                                                                "quest", row.quest().toString()),
                                                        Placeholder.unparsed(
                                                                "season",
                                                                String.valueOf(row.season())),
                                                        Placeholder.unparsed("state", row.state()),
                                                        Placeholder.unparsed(
                                                                "progress", row.progress()),
                                                        Placeholder.unparsed(
                                                                "updated", row.updatedAt()));
                                    }
                                    if (one) {
                                        filter.ifPresent(
                                                d ->
                                                        api.messages()
                                                                .send(
                                                                        sender,
                                                                        "quest.admin.info_state",
                                                                        Placeholder.unparsed(
                                                                                "state",
                                                                                engine().state(
                                                                                                uuid,
                                                                                                d
                                                                                                        .id())
                                                                                        .key())));
                                    }
                                },
                                engine().mainExecutor());
        return Command.SINGLE_SUCCESS;
    }

    private int set(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player target = player(ctx);
        Optional<QuestDef> def = quest(ctx);
        String raw = StringArgumentType.getString(ctx, "state").toLowerCase(Locale.ROOT);
        if (def.isEmpty() || !STATES.contains(raw)) {
            api.messages().send(ctx.getSource().getSender(), "quest.admin.bad_args");
            return 0;
        }
        var _ =
                engine().adminSet(
                                target,
                                def.get(),
                                QuestState.valueOf(raw.toUpperCase(Locale.ROOT)));
        done(ctx, "set", target, def.get().id() + " " + raw);
        return Command.SINGLE_SUCCESS;
    }

    private int progress(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player target = player(ctx);
        Optional<QuestDef> def = quest(ctx);
        String key = StringArgumentType.getString(ctx, "key");
        int value = IntegerArgumentType.getInteger(ctx, "value");
        if (def.isEmpty() || !engine().adminProgress(target, def.get(), key, value)) {
            api.messages().send(ctx.getSource().getSender(), "quest.admin.bad_args");
            return 0;
        }
        done(ctx, "progress", target, def.get().id() + " " + key + "=" + value);
        return Command.SINGLE_SUCCESS;
    }

    private int complete(CommandContext<CommandSourceStack> ctx, boolean reward)
            throws CommandSyntaxException {
        Player target = player(ctx);
        Optional<QuestDef> def = quest(ctx);
        if (def.isEmpty()) {
            api.messages().send(ctx.getSource().getSender(), "quest.admin.bad_args");
            return 0;
        }
        HarborPlayer hp = api.players().get(target);
        Optional<QuestEntry> entry = hp.get(QuestEngine.COMPONENT).entry(def.get().id());
        if (entry.isEmpty() || !entry.get().state().open()) {
            // 진행 중이 아니면 상태만 COMPLETED로 (보상 없음)
            var _ = engine().adminSet(target, def.get(), QuestState.COMPLETED);
        } else {
            var _ = services.forceComplete(target, def.get(), reward);
        }
        done(ctx, "complete", target, def.get().id() + (reward ? "" : " --no-reward"));
        return Command.SINGLE_SUCCESS;
    }

    private int reset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player target = player(ctx);
        Optional<QuestDef> def = quest(ctx);
        QuestId id = def.map(QuestDef::id).orElseGet(() -> rawQuest(ctx));
        var _ = engine().adminReset(target, id);
        done(ctx, "reset", target, id.toString());
        return Command.SINGLE_SUCCESS;
    }

    private int reroll(CommandContext<CommandSourceStack> ctx, String kindKey)
            throws CommandSyntaxException {
        Player target = player(ctx);
        Optional<QuestType> kind =
                wiki.creeper.harbor.common.model.Keyed.fromKey(QuestType.class, kindKey)
                        .filter(QuestType::board);
        if (kind.isEmpty()) {
            api.messages().send(ctx.getSource().getSender(), "quest.admin.bad_args");
            return 0;
        }
        var _ = services.board().ensure(target, kind.get(), true);
        done(ctx, "board.reroll", target, kind.get().key());
        return Command.SINGLE_SUCCESS;
    }

    private int server(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        var _ =
                services.serverProgress()
                        .climaxStatus()
                        .thenAccept(
                                s ->
                                        api.messages()
                                                .send(
                                                        sender,
                                                        "quest.admin.server",
                                                        Placeholder.unparsed(
                                                                "rate",
                                                                String.format(
                                                                        Locale.ROOT,
                                                                        "%.1f",
                                                                        s.donationRate() * 100)),
                                                        Placeholder.unparsed(
                                                                "need_rate",
                                                                String.format(
                                                                        Locale.ROOT,
                                                                        "%.1f",
                                                                        s.requiredRate() * 100)),
                                                        Placeholder.unparsed(
                                                                "clears",
                                                                String.valueOf(s.clears())),
                                                        Placeholder.unparsed(
                                                                "need_clears",
                                                                String.valueOf(s.requiredClears())),
                                                        Placeholder.unparsed(
                                                                "active",
                                                                String.valueOf(s.activePlayers())),
                                                        Placeholder.unparsed(
                                                                "ready", String.valueOf(s.ready())),
                                                        Placeholder.unparsed(
                                                                "flagged",
                                                                String.valueOf(s.flagged()))));
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ 도우미

    private static Predicate<CommandSourceStack> perm(String name) {
        return src -> src.getSender().hasPermission("harbor.admin.quest." + name);
    }

    private void done(
            CommandContext<CommandSourceStack> ctx, String action, Player target, String detail) {
        api.messages().send(ctx.getSource().getSender(), "quest.admin.done");
        log(ctx.getSource().getSender(), action, target.getUniqueId().toString(), detail);
    }

    private void log(CommandSender sender, String action, String target, String detail) {
        adminLog.log(sender, action, target, detail);
    }

    private RequiredArgumentBuilder<CommandSourceStack, Key> questArgument(boolean activeOnly) {
        return Commands.argument("quest", ArgumentTypes.key())
                .suggests(
                        (ctx, b) -> {
                            if (activeOnly && ctx.getSource().getExecutor() instanceof Player p) {
                                api.players()
                                        .get(p.getUniqueId())
                                        .ifPresent(
                                                hp ->
                                                        engine().open(hp)
                                                                .forEach(
                                                                        e ->
                                                                                b.suggest(
                                                                                        e.getKey()
                                                                                                .id()
                                                                                                .toString())));
                            } else {
                                engine().defs().forEach(d -> b.suggest(d.id().toString()));
                            }
                            return b.buildFuture();
                        });
    }

    private Optional<QuestDef> quest(CommandContext<CommandSourceStack> ctx) {
        return engine().def(rawQuest(ctx));
    }

    private static QuestId rawQuest(CommandContext<CommandSourceStack> ctx) {
        Key key = ctx.getArgument("quest", Key.class);
        String namespace =
                key.namespace().equals(Key.MINECRAFT_NAMESPACE)
                        ? HarborId.DEFAULT_NAMESPACE
                        : key.namespace();
        try {
            HarborId id = new HarborId(namespace, key.value());
            return id.hasPrefix(QuestId.PREFIX)
                    ? new QuestId(id)
                    : new QuestId(HarborId.parse("quest/invalid"));
        } catch (IllegalArgumentException e) {
            return new QuestId(HarborId.parse("quest/invalid"));
        }
    }

    private static RequiredArgumentBuilder<CommandSourceStack, PlayerSelectorArgumentResolver>
            target() {
        return Commands.argument("target", ArgumentTypes.player());
    }

    private static Player player(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        return ctx.getArgument("target", PlayerSelectorArgumentResolver.class)
                .resolve(ctx.getSource())
                .getFirst();
    }
}
