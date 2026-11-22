package wiki.creeper.harbor.collection.service;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.collection.api.AquariumService;
import wiki.creeper.harbor.collection.api.DisplayCaseService;
import wiki.creeper.harbor.collection.api.RecordEntry;
import wiki.creeper.harbor.collection.api.RecordService;
import wiki.creeper.harbor.collection.release.ReleaseManager;
import wiki.creeper.harbor.collection.view.AquariumView;
import wiki.creeper.harbor.collection.view.CodexView;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.core.api.FishArgs;
import wiki.creeper.harbor.core.api.HarborApi;

/**
 * /도감 [보상 · 최근], /기록 [species [채팅]], /수족관 [상태 · 기부 · 기부함], /harbor collection … (docs 20 4.5, GUI
 * is proposed).
 */
public final class CollectionCommands {

    private final HarborApi api;
    private final CollectionManager codex;
    private final AquariumManager aquarium;
    private final ReleaseManager release;
    private final Supplier<@Nullable DisplayCaseService> display;

    public CollectionCommands(
            HarborApi api,
            CollectionManager codex,
            AquariumManager aquarium,
            ReleaseManager release,
            Supplier<@Nullable DisplayCaseService> display) {
        this.api = api;
        this.codex = codex;
        this.aquarium = aquarium;
        this.release = release;
        this.display = display;
    }

    public void registerPlayerCommands(Commands commands) {
        commands.register(codexCommand(), "도감", List.of());
        commands.register(
                Commands.literal("기록")
                        .executes(
                                ctx -> {
                                    if (ctx.getSource().getExecutor() instanceof Player player) {
                                        new RecordsMenu(api, codex).pick(player, 0);
                                    }
                                    return Command.SINGLE_SUCCESS;
                                })
                        .then(
                                fishArgument()
                                        .executes(this::recordsMenu)
                                        .then(Commands.literal("채팅").executes(this::records)))
                        .build(),
                "최대어 기록",
                List.of());
        commands.register(
                Commands.literal("수족관")
                        .executes(this::aquariumMenu)
                        .then(Commands.literal("상태").executes(this::aquariumStatus))
                        .then(
                                Commands.literal("기부")
                                        .executes(
                                                ctx -> {
                                                    if (ctx.getSource().getExecutor()
                                                            instanceof Player player) {
                                                        var _ = aquarium.donateHeld(player);
                                                    }
                                                    return Command.SINGLE_SUCCESS;
                                                }))
                        .then(
                                Commands.literal("기부함")
                                        .executes(
                                                ctx -> {
                                                    if (ctx.getSource().getExecutor()
                                                            instanceof Player player) {
                                                        aquarium.openDonationBox(player);
                                                    }
                                                    return Command.SINGLE_SUCCESS;
                                                }))
                        .build(),
                "항구 수족관",
                List.of());
        commands.register(
                Commands.literal("방류")
                        .requires(src -> src.getExecutor() instanceof Player)
                        .executes(
                                ctx -> {
                                    if (ctx.getSource().getExecutor() instanceof Player player) {
                                        var _ = release.releaseHeld(player);
                                    }
                                    return Command.SINGLE_SUCCESS;
                                })
                        .build(),
                "손에 든 어종 방류",
                List.of());
    }

    private LiteralCommandNode<CommandSourceStack> codexCommand() {
        return Commands.literal("도감")
                .requires(src -> src.getExecutor() instanceof Player)
                .executes(
                        ctx -> {
                            if (ctx.getSource().getExecutor() instanceof Player player) {
                                codex.open(player, player.getUniqueId(), 0);
                            }
                            return Command.SINGLE_SUCCESS;
                        })
                .then(
                        Commands.literal("보상")
                                .executes(
                                        ctx -> {
                                            if (ctx.getSource().getExecutor()
                                                    instanceof Player player) {
                                                UUID owner = player.getUniqueId();
                                                new MilestoneMenu(api, codex)
                                                        .open(
                                                                player,
                                                                owner,
                                                                () -> codex.open(player, owner, 0));
                                            }
                                            return Command.SINGLE_SUCCESS;
                                        }))
                .then(
                        Commands.literal("최근")
                                .executes(
                                        ctx -> {
                                            if (ctx.getSource().getExecutor()
                                                    instanceof Player player) {
                                                new CodexMenu(api, codex)
                                                        .open(
                                                                player,
                                                                player.getUniqueId(),
                                                                CodexView.State.RECENT);
                                            }
                                            return Command.SINGLE_SUCCESS;
                                        }))
                .then(
                        Commands.argument("target", ArgumentTypes.player())
                                .executes(
                                        ctx -> {
                                            Player owner =
                                                    ctx.getArgument(
                                                                    "target",
                                                                    PlayerSelectorArgumentResolver
                                                                            .class)
                                                            .resolve(ctx.getSource())
                                                            .getFirst();
                                            if (ctx.getSource().getExecutor()
                                                    instanceof Player viewer) {
                                                codex.open(viewer, owner.getUniqueId(), 0);
                                            }
                                            return Command.SINGLE_SUCCESS;
                                        }))
                .build();
    }

    private int records(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<FishDef> fish = fish(ctx);
        if (fish.isEmpty()) {
            api.messages().send(sender, "collection.unknown_fish");
            return 0;
        }
        api.messages()
                .send(
                        sender,
                        "collection.records.header",
                        Placeholder.unparsed("name", fish.get().name()));
        for (String league : RecordService.LEAGUES) {
            List<RecordEntry> top = codex.serverTop(fish.get().id(), league, 10);
            for (int i = 0; i < top.size(); i++) {
                api.messages()
                        .send(
                                sender,
                                "collection.records.line",
                                Placeholder.unparsed("league", league),
                                Placeholder.unparsed("rank", String.valueOf(i + 1)),
                                Placeholder.unparsed("player", top.get(i).name()),
                                Placeholder.unparsed(
                                        "size", CollectionManager.fmt(top.get(i).size())));
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    /** /기록 <species>: GUI for players, chat output for the console. */
    private int recordsMenu(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getExecutor() instanceof Player player)) {
            return records(ctx);
        }
        Optional<FishDef> fish = fish(ctx);
        if (fish.isEmpty()) {
            api.messages().send(player, "collection.unknown_fish");
            return 0;
        }
        RecordsMenu menu = new RecordsMenu(api, codex);
        menu.open(player, fish.get(), "all", () -> menu.pick(player, 0));
        return Command.SINGLE_SUCCESS;
    }

    /** /수족관: progress screen for players, one status line for the console. */
    private int aquariumMenu(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getExecutor() instanceof Player player) {
            new AquariumMenu(api, aquarium, codex).open(player, AquariumView.Filter.MISSING, 0);
            return Command.SINGLE_SUCCESS;
        }
        return aquariumStatus(ctx);
    }

    private int aquariumStatus(CommandContext<CommandSourceStack> ctx) {
        AquariumService.Status s = aquarium.status();
        aquarium.nextStage(s)
                .ifPresent(
                        next ->
                                api.messages()
                                        .send(
                                                ctx.getSource().getSender(),
                                                "collection.aquarium.next",
                                                Placeholder.unparsed(
                                                        "stage", String.valueOf(next.stage())),
                                                Placeholder.unparsed(
                                                        "rate",
                                                        String.format(
                                                                Locale.ROOT,
                                                                "%.0f",
                                                                next.rate() * 100))));
        api.messages()
                .send(
                        ctx.getSource().getSender(),
                        "collection.aquarium.status",
                        Placeholder.unparsed("stage", String.valueOf(s.stage())),
                        Placeholder.unparsed("species", String.valueOf(s.species())),
                        Placeholder.unparsed("donatable", String.valueOf(s.donatable())),
                        Placeholder.unparsed(
                                "rate", String.format(Locale.ROOT, "%.1f", s.rate() * 100)),
                        Placeholder.unparsed("points", String.valueOf(s.points())));
        return Command.SINGLE_SUCCESS;
    }

    /** /harbor collection … (permission harbor.admin.collection is applied by Core). */
    public LiteralArgumentBuilder<CommandSourceStack> adminCommand() {
        return Commands.literal("collection")
                .then(
                        Commands.literal("display")
                                .then(
                                        Commands.argument("plot", LongArgumentType.longArg(0))
                                                .then(
                                                        Commands.literal("clear")
                                                                .then(
                                                                        Commands.argument(
                                                                                        "slot",
                                                                                        IntegerArgumentType
                                                                                                .integer(
                                                                                                        0,
                                                                                                        1000))
                                                                                .executes(
                                                                                        this
                                                                                                ::displayClear)))))
                .then(
                        Commands.literal("codex")
                                .then(
                                        target().then(
                                                        Commands.literal("set")
                                                                .then(
                                                                        fishArgument()
                                                                                .executes(
                                                                                        ctx ->
                                                                                                codexSet(
                                                                                                        ctx,
                                                                                                        false))
                                                                                .then(
                                                                                        Commands
                                                                                                .argument(
                                                                                                        "size",
                                                                                                        DoubleArgumentType
                                                                                                                .doubleArg(
                                                                                                                        0.1))
                                                                                                .then(
                                                                                                        Commands
                                                                                                                .argument(
                                                                                                                        "quality",
                                                                                                                        IntegerArgumentType
                                                                                                                                .integer(
                                                                                                                                        1,
                                                                                                                                        5))
                                                                                                                .executes(
                                                                                                                        ctx ->
                                                                                                                                codexSet(
                                                                                                                                        ctx,
                                                                                                                                        true))))))
                                                .then(
                                                        Commands.literal("remove")
                                                                .then(
                                                                        fishArgument()
                                                                                .executes(
                                                                                        this
                                                                                                ::codexRemove)))))
                .then(
                        Commands.literal("record")
                                .then(
                                        Commands.literal("remove")
                                                .then(
                                                        fishArgument()
                                                                .then(
                                                                        Commands.argument(
                                                                                        "league",
                                                                                        StringArgumentType
                                                                                                .word())
                                                                                .suggests(
                                                                                        (c, b) -> {
                                                                                            RecordService
                                                                                                    .LEAGUES
                                                                                                    .forEach(
                                                                                                            b
                                                                                                                    ::suggest);
                                                                                            return b
                                                                                                    .buildFuture();
                                                                                        })
                                                                                .then(
                                                                                        target().executes(
                                                                                                        this
                                                                                                                ::recordRemove))))))
                .then(
                        Commands.literal("hint")
                                .then(
                                        target().then(
                                                        fishArgument()
                                                                .then(
                                                                        Commands.argument(
                                                                                        "level",
                                                                                        IntegerArgumentType
                                                                                                .integer(
                                                                                                        0,
                                                                                                        3))
                                                                                .executes(
                                                                                        this
                                                                                                ::hint)))))
                .then(
                        Commands.literal("aquarium")
                                .then(
                                        Commands.literal("recount")
                                                .executes(
                                                        ctx -> {
                                                            var _ = aquarium.recount();
                                                            api.messages()
                                                                    .send(
                                                                            ctx.getSource()
                                                                                    .getSender(),
                                                                            "collection.admin.done");
                                                            return Command.SINGLE_SUCCESS;
                                                        }))
                                .then(
                                        Commands.literal("stage")
                                                .then(
                                                        Commands.argument(
                                                                        "stage",
                                                                        IntegerArgumentType.integer(
                                                                                1, 100))
                                                                .executes(
                                                                        ctx -> {
                                                                            CommandSender sender =
                                                                                    ctx.getSource()
                                                                                            .getSender();
                                                                            var _ =
                                                                                    aquarium.forceStage(
                                                                                                    IntegerArgumentType
                                                                                                            .getInteger(
                                                                                                                    ctx,
                                                                                                                    "stage"))
                                                                                            .thenAccept(
                                                                                                    ok ->
                                                                                                            api.messages()
                                                                                                                    .send(
                                                                                                                            sender,
                                                                                                                            ok
                                                                                                                                    ? "collection.admin.done"
                                                                                                                                    : "collection.admin.failed"));
                                                                            return Command
                                                                                    .SINGLE_SUCCESS;
                                                                        }))));
    }

    private int codexSet(CommandContext<CommandSourceStack> ctx, boolean withValues)
            throws CommandSyntaxException {
        Player p = player(ctx);
        Optional<FishDef> fish = fish(ctx);
        if (fish.isEmpty()) {
            api.messages().send(ctx.getSource().getSender(), "collection.unknown_fish");
            return 0;
        }
        double size = withValues ? DoubleArgumentType.getDouble(ctx, "size") : fish.get().sizeMin();
        int quality = withValues ? IntegerArgumentType.getInteger(ctx, "quality") : 1;
        codex.adminSet(
                p.getUniqueId(),
                fish.get(),
                Math.clamp(size, fish.get().sizeMin(), fish.get().sizeMax()),
                quality);
        api.messages().send(ctx.getSource().getSender(), "collection.admin.done");
        return Command.SINGLE_SUCCESS;
    }

    private int codexRemove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = player(ctx);
        fish(ctx).ifPresent(f -> codex.adminRemove(p.getUniqueId(), f.id()));
        api.messages().send(ctx.getSource().getSender(), "collection.admin.done");
        return Command.SINGLE_SUCCESS;
    }

    private int recordRemove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = player(ctx);
        String league = StringArgumentType.getString(ctx, "league");
        fish(ctx).ifPresent(f -> codex.removeRecord(f.id(), league, p.getUniqueId()));
        api.messages().send(ctx.getSource().getSender(), "collection.admin.done");
        return Command.SINGLE_SUCCESS;
    }

    private int hint(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = player(ctx);
        int level = IntegerArgumentType.getInteger(ctx, "level");
        boolean ok =
                fish(ctx)
                        .map(f -> codex.grant(p.getUniqueId(), f.id(), level, "admin"))
                        .orElse(false);
        api.messages()
                .send(
                        ctx.getSource().getSender(),
                        ok ? "collection.admin.done" : "collection.admin.failed");
        return Command.SINGLE_SUCCESS;
    }

    private int displayClear(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        DisplayCaseService service = display.get();
        if (service == null) {
            api.messages().send(sender, "collection.display.disabled");
            return 0;
        }
        long plot = LongArgumentType.getLong(ctx, "plot");
        int slot = IntegerArgumentType.getInteger(ctx, "slot");
        var _ =
                service.clear(null, plot, slot)
                        .thenAccept(
                                ok ->
                                        api.messages()
                                                .send(
                                                        sender,
                                                        ok
                                                                ? "collection.admin.done"
                                                                : "collection.admin.failed"));
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ helpers

    /** Species name argument (Korean, last argument). */
    private RequiredArgumentBuilder<CommandSourceStack, String> fishArgument() {
        return FishArgs.argument("fish", api.content()::snapshot);
    }

    private Optional<FishDef> fish(CommandContext<CommandSourceStack> ctx) {
        return FishArgs.get(ctx, "fish", api.content().snapshot());
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
