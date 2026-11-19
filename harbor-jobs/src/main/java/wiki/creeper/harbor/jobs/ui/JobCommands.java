package wiki.creeper.harbor.jobs.ui;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.model.JobLine;
import wiki.creeper.harbor.common.model.JobTier;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.jobs.api.ExpGrant;
import wiki.creeper.harbor.jobs.api.ExpSource;
import wiki.creeper.harbor.jobs.api.JobState;
import wiki.creeper.harbor.jobs.service.CapstoneService;
import wiki.creeper.harbor.jobs.service.CatchUpService;
import wiki.creeper.harbor.jobs.service.JobsManager;

/** /직업, /스킬, /harbor jobs … (docs 20 2.5). 관리자 명령은 Core가 admin_log에 남기지 않으므로 여기서 남긴다. */
public final class JobCommands {

    private static final List<String> RESET_SCOPES = List.of("common", "line", "all");

    private final HarborApi api;
    private final JobsManager jobs;
    private final SkillMenu menu;
    private final CapstoneService capstones;
    private final CatchUpService catchUp;

    public JobCommands(
            HarborApi api,
            JobsManager jobs,
            SkillMenu menu,
            CapstoneService capstones,
            CatchUpService catchUp) {
        this.api = api;
        this.jobs = jobs;
        this.menu = menu;
        this.capstones = capstones;
        this.catchUp = catchUp;
    }

    /** 플레이어 명령 (Paper COMMANDS 이벤트에서 등록). */
    public void registerPlayerCommands(Commands commands) {
        commands.register(playerCommand("직업", this::info), "직업 정보", List.of());
        commands.register(playerCommand("스킬", p -> menu.open(p, 0)), "스킬 트리", List.of());
    }

    private static LiteralCommandNode<CommandSourceStack> playerCommand(
            String name, Consumer<Player> action) {
        return Commands.literal(name)
                .requires(src -> src.getExecutor() instanceof Player)
                .executes(
                        ctx -> {
                            if (ctx.getSource().getExecutor() instanceof Player player) {
                                action.accept(player);
                            }
                            return Command.SINGLE_SUCCESS;
                        })
                .build();
    }

    private void info(Player player) {
        HarborPlayer hp = api.players().get(player);
        JobState state = jobs.state(player.getUniqueId());
        MessageService msg = api.messages();
        String job =
                jobs.lineDef(hp.jobLine().orElse(null))
                        .map(d -> d.name(hp.jobTier().level()))
                        .orElseGet(() -> plain(msg.get("jobs.info.apprentice")));
        List<Component> body = new ArrayList<>();
        body.add(
                msg.get(
                        "jobs.info.job",
                        Placeholder.unparsed("job", job),
                        Placeholder.unparsed("tier", String.valueOf(hp.jobTier().level()))));
        body.add(
                msg.get(
                        "jobs.info.level",
                        Placeholder.unparsed("level", String.valueOf(state.level())),
                        Placeholder.unparsed("cap", String.valueOf(state.levelCap())),
                        Placeholder.unparsed("exp", String.valueOf(state.exp())),
                        Placeholder.unparsed("need", String.valueOf(state.nextLevelExp()))));
        body.add(
                msg.get(
                        "jobs.info.quality_cap",
                        Placeholder.unparsed(
                                "stars", jobs.qualityCap(player.getUniqueId()).display())));
        if (hp.jobTier() != JobTier.THIRD) {
            JobTier next = JobTier.of(hp.jobTier().level() + 1);
            body.add(
                    msg.get(
                            "jobs.info.next",
                            Placeholder.unparsed(
                                    "level", String.valueOf(jobs.balance().advanceLevel(next))),
                            Placeholder.unparsed(
                                    "gold", String.valueOf(jobs.balance().advanceCost(next)))));
        }
        catchUp.median()
                .ifPresent(
                        m ->
                                body.add(
                                        msg.get(
                                                "jobs.info.catchup",
                                                Placeholder.unparsed(
                                                        "median", String.valueOf(m)))));
        api.dialogs().notice(player, msg.get("jobs.info.title"), body);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** /harbor jobs … (권한 harbor.admin.jobs는 Core가 건다). */
    public LiteralArgumentBuilder<CommandSourceStack> adminCommand() {
        return Commands.literal("jobs")
                .then(Commands.literal("info").then(target().executes(this::adminInfo)))
                .then(
                        Commands.literal("exp")
                                .then(
                                        target().then(
                                                        Commands.argument(
                                                                        "amount",
                                                                        LongArgumentType.longArg(1))
                                                                .executes(this::exp))))
                .then(
                        Commands.literal("level")
                                .then(
                                        target().then(
                                                        Commands.argument(
                                                                        "level",
                                                                        IntegerArgumentType.integer(
                                                                                1, 70))
                                                                .executes(this::level))))
                .then(Commands.literal("tier").then(target().then(tierArgument())))
                .then(Commands.literal("reset").then(target().then(resetArgument())))
                .then(
                        Commands.literal("cooldown")
                                .then(
                                        target().then(
                                                        Commands.literal("clear")
                                                                .executes(this::clearCooldown))))
                .then(
                        Commands.literal("median")
                                .executes(this::median)
                                .then(Commands.literal("recalc").executes(this::recalc)));
    }

    private RequiredArgumentBuilder<CommandSourceStack, Integer> tierArgument() {
        return Commands.argument("tier", IntegerArgumentType.integer(1, 3))
                .executes(ctx -> tier(ctx, null))
                .then(
                        Commands.argument("line", StringArgumentType.word())
                                .suggests(
                                        (ctx, b) -> {
                                            for (JobLine l : JobLine.values()) {
                                                b.suggest(l.key());
                                            }
                                            return b.buildFuture();
                                        })
                                .executes(
                                        ctx ->
                                                tier(
                                                        ctx,
                                                        StringArgumentType.getString(
                                                                ctx, "line"))));
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> resetArgument() {
        return Commands.argument("scope", StringArgumentType.word())
                .suggests(
                        (ctx, b) -> {
                            RESET_SCOPES.forEach(b::suggest);
                            return b.buildFuture();
                        })
                .executes(ctx -> reset(ctx, false))
                .then(
                        Commands.argument("refund", BoolArgumentType.bool())
                                .executes(
                                        ctx ->
                                                reset(
                                                        ctx,
                                                        BoolArgumentType.getBool(ctx, "refund"))));
    }

    private int exp(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = player(ctx);
        double amount = (double) LongArgumentType.getLong(ctx, "amount");
        long granted = jobs.grantExp(p.getUniqueId(), ExpGrant.of(ExpSource.ADMIN, amount));
        done(ctx, "exp", p, String.valueOf(granted));
        return Command.SINGLE_SUCCESS;
    }

    private int level(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = player(ctx);
        HarborPlayer hp = api.players().get(p);
        hp.setJob(
                hp.jobLine().orElse(null),
                hp.jobTier(),
                IntegerArgumentType.getInteger(ctx, "level"),
                0);
        done(ctx, "level", p, String.valueOf(hp.level()));
        return Command.SINGLE_SUCCESS;
    }

    private int clearCooldown(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        Player p = player(ctx);
        capstones.clearCooldown(p.getUniqueId());
        done(ctx, "cooldown.clear", p, "");
        return Command.SINGLE_SUCCESS;
    }

    private int median(CommandContext<CommandSourceStack> ctx) {
        api.messages()
                .send(
                        ctx.getSource().getSender(),
                        "jobs.admin.median",
                        Placeholder.unparsed(
                                "median", catchUp.median().map(String::valueOf).orElse("-")));
        return Command.SINGLE_SUCCESS;
    }

    private int recalc(CommandContext<CommandSourceStack> ctx) {
        var _ = api.database().executor().submit(catchUp::recalculate);
        api.messages().send(ctx.getSource().getSender(), "jobs.admin.done");
        return Command.SINGLE_SUCCESS;
    }

    private int adminInfo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = player(ctx);
        JobState state = jobs.state(p.getUniqueId());
        CommandSender sender = ctx.getSource().getSender();
        api.messages()
                .send(
                        sender,
                        "jobs.admin.info",
                        Placeholder.unparsed("player", p.getName()),
                        Placeholder.unparsed("line", state.line().map(JobLine::key).orElse("-")),
                        Placeholder.unparsed("tier", String.valueOf(state.tier().level())),
                        Placeholder.unparsed("level", String.valueOf(state.level())),
                        Placeholder.unparsed("exp", state.exp() + "/" + state.nextLevelExp()),
                        Placeholder.unparsed(
                                "skills", jobs.data(api.players().get(p)).skills().toString()),
                        Placeholder.unparsed(
                                "capstone", jobs.capstone(p.getUniqueId()).toString()));
        return Command.SINGLE_SUCCESS;
    }

    private int tier(CommandContext<CommandSourceStack> ctx, @Nullable String lineKey)
            throws CommandSyntaxException {
        Player p = player(ctx);
        JobTier tier = JobTier.of(IntegerArgumentType.getInteger(ctx, "tier"));
        JobLine line = null;
        if (lineKey != null) {
            Optional<JobLine> parsed =
                    Keyed.fromKey(JobLine.class, lineKey.toLowerCase(Locale.ROOT));
            if (parsed.isEmpty()) {
                api.messages().send(ctx.getSource().getSender(), "jobs.admin.bad_line");
                return 0;
            }
            line = parsed.get();
        }
        CommandSender sender = ctx.getSource().getSender();
        var _ =
                jobs.forceTier(p.getUniqueId(), tier, line)
                        .thenAccept(
                                ok ->
                                        api.messages()
                                                .send(
                                                        sender,
                                                        ok
                                                                ? "jobs.admin.done"
                                                                : "jobs.admin.failed"));
        log(sender, "tier", p, tier.level() + " " + lineKey);
        return Command.SINGLE_SUCCESS;
    }

    private int reset(CommandContext<CommandSourceStack> ctx, boolean refund)
            throws CommandSyntaxException {
        Player p = player(ctx);
        String scope = StringArgumentType.getString(ctx, "scope");
        if (!RESET_SCOPES.contains(scope)) {
            api.messages().send(ctx.getSource().getSender(), "jobs.admin.bad_scope");
            return 0;
        }
        CommandSender sender = ctx.getSource().getSender();
        var _ =
                jobs.adminReset(p.getUniqueId(), scope, refund)
                        .thenAccept(
                                ok ->
                                        api.messages()
                                                .send(
                                                        sender,
                                                        ok
                                                                ? "jobs.admin.done"
                                                                : "jobs.admin.failed"));
        log(sender, "reset", p, scope + " refund=" + refund);
        return Command.SINGLE_SUCCESS;
    }

    private void done(
            CommandContext<CommandSourceStack> ctx, String action, Player target, String detail) {
        api.messages().send(ctx.getSource().getSender(), "jobs.admin.done");
        log(ctx.getSource().getSender(), action, target, detail);
    }

    private void log(CommandSender sender, String action, Player target, String detail) {
        String admin = sender instanceof Player p ? p.getUniqueId().toString() : "CONSOLE";
        String targetId = target.getUniqueId().toString();
        var _ =
                api.database()
                        .asyncVoid(
                                c -> {
                                    try (var ps =
                                            c.prepareStatement(
                                                    "INSERT INTO admin_log (admin, action, target, detail) VALUES (?, ?, ?, ?)")) {
                                        ps.setString(1, admin);
                                        ps.setString(2, "jobs." + action);
                                        ps.setString(3, targetId);
                                        ps.setString(4, detail);
                                        ps.executeUpdate();
                                    }
                                });
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
