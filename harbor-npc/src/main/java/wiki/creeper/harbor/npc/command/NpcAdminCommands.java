package wiki.creeper.harbor.npc.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.npc.affinity.AffinityManager;
import wiki.creeper.harbor.npc.api.AffinityChange;
import wiki.creeper.harbor.npc.api.AffinityLevel;
import wiki.creeper.harbor.npc.api.ReputationSource;
import wiki.creeper.harbor.npc.api.WandererState;
import wiki.creeper.harbor.npc.content.CutsceneDef;
import wiki.creeper.harbor.npc.content.DialogDef;
import wiki.creeper.harbor.npc.content.NpcDef;
import wiki.creeper.harbor.npc.cutscene.CutsceneManager;
import wiki.creeper.harbor.npc.dialog.DialogManager;
import wiki.creeper.harbor.npc.entity.NpcManager;
import wiki.creeper.harbor.npc.reputation.ReputationManager;
import wiki.creeper.harbor.npc.wanderer.WandererManager;

/** Admin commands under /harbor npc (docs 20 5.5). Every command is recorded in admin_log. */
final class NpcAdminCommands {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final HarborApi api;
    private final NpcManager npcs;
    private final DialogManager dialogs;
    private final AffinityManager affinity;
    private final ReputationManager reputation;
    private final WandererManager wanderers;
    private final CutsceneManager cutscenes;

    NpcAdminCommands(
            HarborApi api,
            NpcManager npcs,
            DialogManager dialogs,
            AffinityManager affinity,
            ReputationManager reputation,
            WandererManager wanderers,
            CutsceneManager cutscenes) {
        this.api = api;
        this.npcs = npcs;
        this.dialogs = dialogs;
        this.affinity = affinity;
        this.reputation = reputation;
        this.wanderers = wanderers;
        this.cutscenes = cutscenes;
    }

    LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("npc")
                .then(Commands.literal("list").executes(this::list))
                .then(Commands.literal("tp").then(npcArg().executes(this::tp)))
                .then(
                        Commands.literal("respawn")
                                .executes(this::respawnAll)
                                .then(npcArg().executes(this::respawnOne)))
                .then(
                        perm(Commands.literal("dialog"), "dialog")
                                .then(npcArg().then(nodeArg().executes(this::dialog))))
                .then(perm(Commands.literal("affinity"), "affinity").then(affinityTree()))
                .then(perm(Commands.literal("rep"), "rep").then(repTree()))
                .then(perm(Commands.literal("wanderer"), "wanderer").then(wandererTree()))
                .then(
                        perm(Commands.literal("cutscene"), "cutscene")
                                .then(
                                        Commands.literal("play")
                                                .then(
                                                        playerArg()
                                                                .then(
                                                                        cutsceneArg()
                                                                                .executes(
                                                                                        this
                                                                                                ::cutscenePlay))))
                                .then(
                                        Commands.literal("stop")
                                                .then(playerArg().executes(this::cutsceneStop)))
                                .then(
                                        Commands.literal("restore")
                                                .then(
                                                        playerArg()
                                                                .executes(this::cutsceneRestore))));
    }

    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T perm(
            T builder, String sub) {
        return builder.requires(src -> src.getSender().hasPermission("harbor.admin.npc." + sub));
    }

    private RequiredArgumentBuilder<CommandSourceStack, PlayerSelectorArgumentResolver>
            affinityTree() {
        var npc = npcArg();
        npc.then(Commands.literal("get").executes(this::affinityGet));
        npc.then(
                Commands.literal("set")
                        .then(valueArg().executes(ctx -> affinityChange(ctx, true))));
        npc.then(
                Commands.literal("add")
                        .then(valueArg().executes(ctx -> affinityChange(ctx, false))));
        return playerArg().then(npc);
    }

    private RequiredArgumentBuilder<CommandSourceStack, PlayerSelectorArgumentResolver> repTree() {
        return playerArg()
                .then(Commands.literal("get").executes(this::repGet))
                .then(Commands.literal("add").then(valueArg().executes(this::repAdd)));
    }

    private RequiredArgumentBuilder<CommandSourceStack, Key> wandererTree() {
        return npcArg().executes(this::wandererInfo)
                .then(
                        Commands.literal("move")
                                .then(
                                        Commands.argument("candidate", StringArgumentType.word())
                                                .suggests(
                                                        (ctx, b) -> {
                                                            npc(ctx).flatMap(npcs::def)
                                                                    .map(NpcDef::schedule)
                                                                    .ifPresent(
                                                                            s ->
                                                                                    s.candidates()
                                                                                            .forEach(
                                                                                                    c ->
                                                                                                            b
                                                                                                                    .suggest(
                                                                                                                            c
                                                                                                                                    .id())));
                                                            return b.buildFuture();
                                                        })
                                                .executes(this::wandererMove)))
                .then(Commands.literal("reset").executes(this::wandererReset));
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        api.messages()
                .send(
                        sender,
                        "npc.admin.list_header",
                        Placeholder.unparsed("count", String.valueOf(npcs.defs().size())));
        for (NpcDef def : npcs.defs()) {
            String where =
                    npcs.location(def.id())
                            .map(
                                    l ->
                                            l.getWorld().getName()
                                                    + " "
                                                    + l.getBlockX()
                                                    + " "
                                                    + l.getBlockY()
                                                    + " "
                                                    + l.getBlockZ())
                            .orElse("-");
            api.messages()
                    .send(
                            sender,
                            "npc.admin.list_line",
                            Placeholder.unparsed("id", def.id().toString()),
                            Placeholder.parsed("name", def.name()),
                            Placeholder.unparsed("role", def.role().key()),
                            Placeholder.unparsed("where", where),
                            Placeholder.unparsed(
                                    "state", npcs.spawnedIds().contains(def.id()) ? "O" : "X"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int tp(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getExecutor() instanceof Player player)) {
            return 0;
        }
        Optional<NpcId> npc = npc(ctx);
        Optional<org.bukkit.Location> at = npc.flatMap(npcs::location);
        if (at.isEmpty()) {
            api.messages().send(player, "npc.admin.unknown_npc");
            return 0;
        }
        player.teleport(at.get());
        return Command.SINGLE_SUCCESS;
    }

    private int respawnAll(CommandContext<CommandSourceStack> ctx) {
        npcs.spawnedIds().forEach(id -> npcs.despawn(id, false));
        npcs.syncAll();
        done(ctx, "respawn", "*", "");
        return Command.SINGLE_SUCCESS;
    }

    private int respawnOne(CommandContext<CommandSourceStack> ctx) {
        Optional<NpcId> npc = npc(ctx).filter(id -> npcs.def(id).isPresent());
        if (npc.isEmpty()) {
            api.messages().send(ctx.getSource().getSender(), "npc.admin.unknown_npc");
            return 0;
        }
        npcs.respawn(npc.get());
        done(ctx, "respawn", npc.get().toString(), "");
        return Command.SINGLE_SUCCESS;
    }

    private int dialog(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getExecutor() instanceof Player player)) {
            return 0;
        }
        Optional<NpcId> npc = npc(ctx);
        String node = StringArgumentType.getString(ctx, "node");
        if (npc.isEmpty() || !dialogs.preview(player, npc.get(), node)) {
            api.messages()
                    .send(player, "npc.admin.unknown_node", Placeholder.unparsed("node", node));
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    private int affinityGet(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player target = player(ctx);
        Optional<NpcId> npc = npc(ctx);
        if (npc.isEmpty()) {
            api.messages().send(ctx.getSource().getSender(), "npc.admin.unknown_npc");
            return 0;
        }
        AffinityLevel level = affinity.levelInfo(target.getUniqueId(), npc.get());
        api.messages()
                .send(
                        ctx.getSource().getSender(),
                        "npc.admin.affinity",
                        Placeholder.unparsed("player", target.getName()),
                        Placeholder.unparsed("npc", npc.get().toString()),
                        Placeholder.unparsed(
                                "points",
                                String.valueOf(affinity.points(target.getUniqueId(), npc.get()))),
                        Placeholder.unparsed("level", level.name()));
        return Command.SINGLE_SUCCESS;
    }

    private int affinityChange(CommandContext<CommandSourceStack> ctx, boolean set)
            throws CommandSyntaxException {
        Player target = player(ctx);
        Optional<NpcId> npc = npc(ctx).filter(id -> npcs.def(id).isPresent());
        if (npc.isEmpty()) {
            api.messages().send(ctx.getSource().getSender(), "npc.admin.unknown_npc");
            return 0;
        }
        int value = IntegerArgumentType.getInteger(ctx, "value");
        AffinityChange change =
                set
                        ? affinity.set(target.getUniqueId(), npc.get(), value)
                        : affinity.add(
                                target.getUniqueId(),
                                npc.get(),
                                value,
                                wiki.creeper.harbor.npc.api.AffinitySource.ADMIN);
        done(
                ctx,
                set ? "affinity.set" : "affinity.add",
                target.getUniqueId().toString(),
                npc.get() + " " + change.oldPoints() + "→" + change.newPoints());
        return affinityGet(ctx);
    }

    private int repGet(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player target = player(ctx);
        var uuid = target.getUniqueId();
        api.messages()
                .send(
                        ctx.getSource().getSender(),
                        "npc.admin.rep",
                        Placeholder.unparsed("player", target.getName()),
                        Placeholder.unparsed("value", String.valueOf(reputation.value(uuid))),
                        Placeholder.unparsed(
                                "tier", reputation.tierInfo(reputation.tier(uuid)).name()),
                        Placeholder.unparsed("best", String.valueOf(reputation.bestTier(uuid))));
        return Command.SINGLE_SUCCESS;
    }

    private int repAdd(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player target = player(ctx);
        int value = IntegerArgumentType.getInteger(ctx, "value");
        CommandSender sender = ctx.getSource().getSender();
        // recorded in admin_log instead of a ledger (a fresh reference ID per command)
        String ref =
                "admin:"
                        + System.currentTimeMillis()
                        + ":"
                        + Integer.toHexString(System.identityHashCode(ctx));
        var _ =
                reputation
                        .add(target.getUniqueId(), value, ReputationSource.ADMIN, ref)
                        .thenAccept(
                                r -> {
                                    api.messages()
                                            .send(
                                                    sender,
                                                    "npc.admin.rep_result",
                                                    Placeholder.unparsed(
                                                            "status", r.status().name()),
                                                    Placeholder.unparsed(
                                                            "value", String.valueOf(r.value())));
                                    log(
                                            sender,
                                            "rep.add",
                                            target.getUniqueId().toString(),
                                            value + " → " + r.value() + " " + r.status());
                                });
        return Command.SINGLE_SUCCESS;
    }

    private int wandererInfo(CommandContext<CommandSourceStack> ctx) {
        Optional<WandererState> state = npc(ctx).flatMap(wanderers::current);
        if (state.isEmpty()) {
            api.messages().send(ctx.getSource().getSender(), "npc.admin.not_wanderer");
            return 0;
        }
        WandererState s = state.get();
        api.messages()
                .send(
                        ctx.getSource().getSender(),
                        "npc.admin.wanderer",
                        Placeholder.unparsed("npc", s.npc().toString()),
                        Placeholder.unparsed("candidate", String.valueOf(s.candidate())),
                        Placeholder.unparsed("visible", s.visible() ? "O" : "X"),
                        Placeholder.unparsed("forced", s.forced() ? "O" : "X"),
                        Placeholder.unparsed(
                                "next", TIME.format(s.nextMoveAt().atZone(api.time().zone()))));
        return Command.SINGLE_SUCCESS;
    }

    private int wandererMove(CommandContext<CommandSourceStack> ctx) {
        Optional<NpcId> npc = npc(ctx);
        String candidate = StringArgumentType.getString(ctx, "candidate");
        if (npc.isEmpty() || !wanderers.forceMove(npc.get(), candidate)) {
            api.messages()
                    .send(
                            ctx.getSource().getSender(),
                            "npc.admin.unknown_candidate",
                            Placeholder.unparsed("candidate", candidate));
            return 0;
        }
        done(ctx, "wanderer.move", npc.get().toString(), candidate);
        return Command.SINGLE_SUCCESS;
    }

    private int wandererReset(CommandContext<CommandSourceStack> ctx) {
        Optional<NpcId> npc = npc(ctx).filter(id -> wanderers.current(id).isPresent());
        if (npc.isEmpty()) {
            api.messages().send(ctx.getSource().getSender(), "npc.admin.not_wanderer");
            return 0;
        }
        wanderers.reset(npc.get());
        done(ctx, "wanderer.reset", npc.get().toString(), "");
        return Command.SINGLE_SUCCESS;
    }

    private int cutscenePlay(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player target = player(ctx);
        Optional<HarborId> id = harborId(ctx, "cutscene").filter(cutscenes::exists);
        CommandSender sender = ctx.getSource().getSender();
        if (id.isEmpty()) {
            api.messages().send(sender, "npc.admin.unknown_cutscene");
            return 0;
        }
        var _ =
                cutscenes
                        .play(target, id.get())
                        .thenAccept(
                                r ->
                                        api.messages()
                                                .send(
                                                        sender,
                                                        "npc.admin.cutscene_result",
                                                        Placeholder.unparsed("result", r.name())));
        done(ctx, "cutscene.play", target.getUniqueId().toString(), id.get().toString());
        return Command.SINGLE_SUCCESS;
    }

    private int cutsceneStop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player target = player(ctx);
        boolean stopped = cutscenes.stop(target);
        api.messages()
                .send(
                        ctx.getSource().getSender(),
                        stopped ? "npc.admin.done" : "npc.admin.not_playing");
        if (stopped) {
            log(ctx.getSource().getSender(), "cutscene.stop", target.getUniqueId().toString(), "");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int cutsceneRestore(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        Player target = player(ctx);
        CommandSender sender = ctx.getSource().getSender();
        var _ =
                cutscenes
                        .forceRestore(target)
                        .thenAccept(
                                ok -> {
                                    api.messages()
                                            .send(
                                                    sender,
                                                    ok ? "npc.admin.done" : "npc.admin.no_restore");
                                    if (ok) {
                                        log(
                                                sender,
                                                "cutscene.restore",
                                                target.getUniqueId().toString(),
                                                "");
                                    }
                                });
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ arguments · logging

    private RequiredArgumentBuilder<CommandSourceStack, Key> npcArg() {
        return Commands.argument("npc", ArgumentTypes.key())
                .suggests(
                        (ctx, b) -> {
                            api.content()
                                    .snapshot()
                                    .ids(NpcDef.TYPE.name())
                                    .forEach(id -> b.suggest(id.toString()));
                            return b.buildFuture();
                        });
    }

    private RequiredArgumentBuilder<CommandSourceStack, Key> cutsceneArg() {
        return Commands.argument("cutscene", ArgumentTypes.key())
                .suggests(
                        (ctx, b) -> {
                            api.content()
                                    .snapshot()
                                    .ids(CutsceneDef.TYPE.name())
                                    .forEach(id -> b.suggest(id.toString()));
                            return b.buildFuture();
                        });
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> nodeArg() {
        return Commands.argument("node", StringArgumentType.word())
                .suggests(
                        (ctx, b) -> {
                            npc(ctx).flatMap(npcs::def)
                                    .map(NpcDef::dialog)
                                    .flatMap(dialogs::dialog)
                                    .map(DialogDef::nodes)
                                    .ifPresent(nodes -> nodes.keySet().forEach(b::suggest));
                            return b.buildFuture();
                        });
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Integer> valueArg() {
        return Commands.argument("value", IntegerArgumentType.integer());
    }

    private static RequiredArgumentBuilder<CommandSourceStack, PlayerSelectorArgumentResolver>
            playerArg() {
        return Commands.argument("target", ArgumentTypes.player());
    }

    private static Player player(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        return ctx.getArgument("target", PlayerSelectorArgumentResolver.class)
                .resolve(ctx.getSource())
                .getFirst();
    }

    private static Optional<NpcId> npc(CommandContext<CommandSourceStack> ctx) {
        return harborId(ctx, "npc").filter(id -> id.hasPrefix(NpcId.PREFIX)).map(NpcId::new);
    }

    private static Optional<HarborId> harborId(
            CommandContext<CommandSourceStack> ctx, String name) {
        Key key;
        try {
            key = ctx.getArgument(name, Key.class);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String namespace =
                key.namespace().equals(Key.MINECRAFT_NAMESPACE)
                        ? HarborId.DEFAULT_NAMESPACE
                        : key.namespace();
        try {
            return Optional.of(new HarborId(namespace, key.value()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private void done(
            CommandContext<CommandSourceStack> ctx, String action, String target, String detail) {
        api.messages().send(ctx.getSource().getSender(), "npc.admin.done");
        log(ctx.getSource().getSender(), action, target, detail);
    }

    private void log(CommandSender sender, String action, String target, String detail) {
        String admin = sender instanceof Player p ? p.getUniqueId().toString() : "CONSOLE";
        var _ =
                api.database()
                        .asyncVoid(
                                c -> {
                                    try (var ps =
                                            c.prepareStatement(
                                                    "INSERT INTO admin_log (admin, action, target, detail) VALUES (?, ?, ?, ?)")) {
                                        ps.setString(1, admin);
                                        ps.setString(2, "npc." + action);
                                        ps.setString(3, target);
                                        ps.setString(4, detail);
                                        ps.executeUpdate();
                                    }
                                });
    }
}
