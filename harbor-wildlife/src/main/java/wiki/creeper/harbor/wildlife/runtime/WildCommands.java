package wiki.creeper.harbor.wildlife.runtime;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.wildlife.api.ScriptedBehaviour;
import wiki.creeper.harbor.wildlife.api.WildState;
import wiki.creeper.harbor.wildlife.api.event.WildFishSpawnEvent;
import wiki.creeper.harbor.wildlife.sim.FishOut;
import wiki.creeper.harbor.wildlife.sim.ProfileIndex;
import wiki.creeper.harbor.wildlife.sim.SpeciesProfile;

/**
 * /harbor wildlife … ('17-3' 9.5). Core가 harbor.admin.wildlife를 걸고, 하위 명령마다
 * harbor.admin.wildlife.wild를 더 본다.
 *
 * <ul>
 *   <li>spawn &lt;어종&gt; [수량] [동작]: 시선 앞 4블록에 소환 (시제품 wild·wild5 대체)
 *   <li>clear [반경]: 주변 또는 전체 제거
 *   <li>debug: 개체 위에 상태·성향·위상 Text Display, 조향 벡터 파티클
 *   <li>stats: 개체 수, 표시 부품 수(플레이어별), 계산 시간, 반영 지연
 *   <li>budget &lt;플레이어&gt;: 그 플레이어의 표시 목록과 예산 사용량
 * </ul>
 */
final class WildCommands {

    private static final String PERMISSION = "harbor.admin.wildlife.wild";

    private final WildlifeKit kit;
    private final WildlifeManager manager;
    private final MediumCache media;
    private final FishingLink fishing;
    private final DebugOverlay debug;

    WildCommands(
            WildlifeKit kit,
            WildlifeManager manager,
            MediumCache media,
            FishingLink fishing,
            DebugOverlay debug) {
        this.kit = kit;
        this.manager = manager;
        this.media = media;
        this.fishing = fishing;
        this.debug = debug;
    }

    private static boolean allowed(CommandSourceStack src) {
        return src.getSender().hasPermission(PERMISSION);
    }

    LiteralArgumentBuilder<CommandSourceStack> adminCommand() {
        return Commands.literal("wildlife")
                .then(
                        Commands.literal("spawn")
                                .requires(WildCommands::allowed)
                                .then(
                                        Commands.argument("species", ArgumentTypes.key())
                                                .suggests(
                                                        (ctx, b) -> {
                                                            kit.index()
                                                                    .bySpecies()
                                                                    .values()
                                                                    .forEach(
                                                                            p ->
                                                                                    b.suggest(
                                                                                            p.fish()
                                                                                                    .id()
                                                                                                    .toString()));
                                                            return b.buildFuture();
                                                        })
                                                .executes(ctx -> spawn(ctx, 1, null))
                                                .then(
                                                        Commands.argument(
                                                                        "count",
                                                                        IntegerArgumentType.integer(
                                                                                1, 16))
                                                                .executes(
                                                                        ctx ->
                                                                                spawn(
                                                                                        ctx,
                                                                                        IntegerArgumentType
                                                                                                .getInteger(
                                                                                                        ctx,
                                                                                                        "count"),
                                                                                        null))
                                                                .then(
                                                                        Commands.argument(
                                                                                        "action",
                                                                                        StringArgumentType
                                                                                                .word())
                                                                                .suggests(
                                                                                        (ctx,
                                                                                                b) -> {
                                                                                            ProfileIndex
                                                                                                    .book(
                                                                                                            kit.api
                                                                                                                    .content()
                                                                                                                    .snapshot())
                                                                                                    .actions()
                                                                                                    .keySet()
                                                                                                    .forEach(
                                                                                                            b
                                                                                                                    ::suggest);
                                                                                            return b
                                                                                                    .buildFuture();
                                                                                        })
                                                                                .executes(
                                                                                        ctx ->
                                                                                                spawn(
                                                                                                        ctx,
                                                                                                        IntegerArgumentType
                                                                                                                .getInteger(
                                                                                                                        ctx,
                                                                                                                        "count"),
                                                                                                        StringArgumentType
                                                                                                                .getString(
                                                                                                                        ctx,
                                                                                                                        "action")))))))
                .then(
                        Commands.literal("clear")
                                .requires(WildCommands::allowed)
                                .executes(ctx -> clear(ctx, -1))
                                .then(
                                        Commands.argument(
                                                        "radius",
                                                        DoubleArgumentType.doubleArg(1, 512))
                                                .executes(
                                                        ctx ->
                                                                clear(
                                                                        ctx,
                                                                        DoubleArgumentType
                                                                                .getDouble(
                                                                                        ctx,
                                                                                        "radius")))))
                .then(
                        Commands.literal("debug")
                                .requires(WildCommands::allowed)
                                .executes(this::debug))
                .then(
                        Commands.literal("stats")
                                .requires(WildCommands::allowed)
                                .executes(this::stats))
                .then(
                        Commands.literal("budget")
                                .requires(WildCommands::allowed)
                                .then(
                                        Commands.argument("target", ArgumentTypes.player())
                                                .executes(this::budget)));
    }

    private MessageService messages() {
        return kit.api.messages();
    }

    private int spawn(CommandContext<CommandSourceStack> ctx, int count, @Nullable String action) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            messages().send(sender, "wildlife.admin.players_only");
            return 0;
        }
        Key key = ctx.getArgument("species", Key.class);
        SpeciesProfile p = find(kit.index(), harborId(key));
        if (p == null) {
            messages()
                    .send(
                            sender,
                            "wildlife.admin.unknown_species",
                            Placeholder.unparsed("species", key.asString()));
            return 0;
        }
        if (action != null && !p.actions().containsKey(action)) {
            messages()
                    .send(
                            sender,
                            "wildlife.admin.unknown_action",
                            Placeholder.unparsed("action", action));
            return 0;
        }
        Location eye = player.getEyeLocation();
        Location at = eye.clone().add(eye.getDirection().normalize().multiply(4));
        List<?> ids =
                manager.spawnAt(
                        p,
                        at,
                        count,
                        new ScriptedBehaviour(action, false, 0, false),
                        WildFishSpawnEvent.Reason.SCRIPTED,
                        List.<Vec3>of());
        if (ids.isEmpty()) {
            messages()
                    .send(
                            sender,
                            "wildlife.admin.spawn_failed",
                            Placeholder.unparsed("medium", p.medium().key()));
            return 0;
        }
        messages()
                .send(
                        sender,
                        "wildlife.admin.spawned",
                        Placeholder.unparsed("species", p.species().toString()),
                        Placeholder.unparsed("count", String.valueOf(ids.size())));
        return Command.SINGLE_SUCCESS;
    }

    private static @Nullable SpeciesProfile find(ProfileIndex index, HarborId id) {
        SpeciesProfile bySpecies = index.bySpecies().get(id);
        if (bySpecies != null) {
            return bySpecies;
        }
        try {
            return index.byFish().get(new FishId(id));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static HarborId harborId(Key key) {
        String namespace =
                key.namespace().equals(Key.MINECRAFT_NAMESPACE)
                        ? HarborId.DEFAULT_NAMESPACE
                        : key.namespace();
        return new HarborId(namespace, key.value());
    }

    private int clear(CommandContext<CommandSourceStack> ctx, double radius) {
        CommandSender sender = ctx.getSource().getSender();
        int count;
        if (radius > 0 && sender instanceof Player player) {
            Location l = Locs.of(player);
            count =
                    manager.frame()
                            .nearby(
                                    player.getWorld().getName(),
                                    l.getX(),
                                    l.getY(),
                                    l.getZ(),
                                    radius)
                            .size();
            String world = player.getWorld().getName();
            Vec3 c = new Vec3(l.getX(), l.getY(), l.getZ());
            manager.engine().submit(w -> w.removeWithin(world, c, radius));
        } else {
            count = manager.count();
            manager.engine().submit(w -> w.removeWithin(null, Vec3.ZERO, -1));
        }
        messages()
                .send(
                        sender,
                        "wildlife.admin.cleared",
                        Placeholder.unparsed("count", String.valueOf(count)));
        return Command.SINGLE_SUCCESS;
    }

    private int debug(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            messages().send(sender, "wildlife.admin.players_only");
            return 0;
        }
        messages()
                .send(
                        sender,
                        debug.toggle(player)
                                ? "wildlife.admin.debug_on"
                                : "wildlife.admin.debug_off");
        return Command.SINGLE_SUCCESS;
    }

    private int stats(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        List<FishOut> fish = manager.frame().fish();
        Map<WildState, Integer> states = new EnumMap<>(WildState.class);
        int hidden = 0;
        for (FishOut o : fish) {
            states.merge(o.state(), 1, Integer::sum);
            if (o.hidden()) {
                hidden++;
            }
        }
        String stateText =
                states.entrySet().stream()
                        .map(e -> e.getKey().name().toLowerCase(Locale.ROOT) + " " + e.getValue())
                        .collect(Collectors.joining(", "));
        int[] shown = manager.shownStats();
        double[] sim = manager.engine().takeStats();
        messages()
                .send(
                        sender,
                        "wildlife.admin.stats",
                        Placeholder.unparsed("count", String.valueOf(fish.size())),
                        Placeholder.unparsed("max", String.valueOf(kit.balance().globalMax())),
                        Placeholder.unparsed("states", stateText.isEmpty() ? "-" : stateText),
                        Placeholder.unparsed("hidden", String.valueOf(hidden)),
                        Placeholder.unparsed("rigs", String.valueOf(shown[1])),
                        Placeholder.unparsed("tracked", String.valueOf(shown[0])),
                        Placeholder.unparsed("sim_avg", fmt(sim[0])),
                        Placeholder.unparsed("sim_max", fmt(sim[1])),
                        Placeholder.unparsed("apply", fmt(manager.lastApplyMillis())),
                        Placeholder.unparsed("deferred", String.valueOf(manager.deferred())),
                        Placeholder.unparsed("degrade", String.valueOf(manager.degradeLevel())),
                        Placeholder.unparsed("chunks", String.valueOf(kit.grid.chunks())),
                        Placeholder.unparsed("grid_kb", String.valueOf(kit.grid.bytes() / 1024)),
                        Placeholder.unparsed("queued", String.valueOf(media.queued())),
                        Placeholder.unparsed("boosts", String.valueOf(manager.boostCount())),
                        Placeholder.unparsed("spots", String.valueOf(manager.anchorCount())),
                        Placeholder.unparsed("casts", String.valueOf(fishing.activeCasts())),
                        Placeholder.unparsed("debug", String.valueOf(debug.active())));
        for (Player p : Bukkit.getOnlinePlayers()) {
            int ours = manager.ourParts(p.getUniqueId());
            if (ours == 0) {
                continue;
            }
            int[] budget = manager.budgetOf(p);
            messages()
                    .send(
                            sender,
                            "wildlife.admin.stats_player",
                            Placeholder.unparsed("player", p.getName()),
                            Placeholder.unparsed("parts", String.valueOf(ours)),
                            Placeholder.unparsed("budget", String.valueOf(budget[1])),
                            Placeholder.unparsed(
                                    "other", String.valueOf(manager.otherParts(p.getUniqueId()))));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int budget(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        Player target =
                ctx.getArgument("target", PlayerSelectorArgumentResolver.class)
                        .resolve(ctx.getSource())
                        .getFirst();
        Location l = Locs.of(target);
        int[] budget = manager.budgetOf(target);
        List<FishOut> visible =
                manager
                        .frame()
                        .nearby(
                                target.getWorld().getName(),
                                l.getX(),
                                l.getY(),
                                l.getZ(),
                                kit.balance().keepDistance())
                        .stream()
                        .filter(o -> manager.visibleTo(o.id(), target.getUniqueId()))
                        .toList();
        int parts = visible.stream().mapToInt(o -> o.profile().parts()).sum();
        messages()
                .send(
                        sender,
                        "wildlife.admin.budget_header",
                        Placeholder.unparsed("player", target.getName()),
                        Placeholder.unparsed("count", String.valueOf(visible.size())),
                        Placeholder.unparsed("max_count", String.valueOf(budget[0])),
                        Placeholder.unparsed("parts", String.valueOf(parts)),
                        Placeholder.unparsed("max_parts", String.valueOf(budget[1])),
                        Placeholder.unparsed(
                                "other", String.valueOf(manager.otherParts(target.getUniqueId()))));
        for (FishOut o : visible) {
            messages()
                    .send(
                            sender,
                            "wildlife.admin.budget_line",
                            Placeholder.unparsed("species", o.profile().species().name()),
                            Placeholder.unparsed(
                                    "state", o.state().name().toLowerCase(Locale.ROOT)),
                            Placeholder.unparsed("parts", String.valueOf(o.profile().parts())),
                            Placeholder.unparsed(
                                    "dist",
                                    fmt(Math.sqrt(o.distanceSq(l.getX(), l.getY(), l.getZ())))));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
