package wiki.creeper.harbor.core.rig;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.RegionDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.common.rig.FishRigs;
import wiki.creeper.harbor.common.rig.RigDef;
import wiki.creeper.harbor.core.admin.AdminLog;
import wiki.creeper.harbor.core.api.ContentService;
import wiki.creeper.harbor.core.api.ItemService;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.api.PlayerService;
import wiki.creeper.harbor.core.api.RigInstance;
import wiki.creeper.harbor.core.api.RigOptions;

/**
 * /harbor fish: fish species demo and inspection tool (permission harbor.admin.fish, proposed). A
 * port of the resource pack repository's demo datapack (give, showcase, play, demo), used to check
 * fish models and animations at a glance on a live server.
 *
 * <pre>
 * /harbor fish list [region]                 number, name, grade, animations (click a line to spawn)
 * /harbor fish give &lt;target&gt; [region]       items for every fish (or the region's fish) (overflow drops at the feet)
 * /harbor fish spawn &lt;fish&gt; [animation]     one fish rig 3 blocks ahead (fish = number or name)
 * /harbor fish showcase [region]             display in rows per region from the feet (with name labels)
 * /harbor fish play &lt;animation|special&gt; [radius]   play the displayed rigs. special = each fish's own animation
 * /harbor fish clear                         remove the display
 * </pre>
 */
public final class FishDemoCommand {

    // Showcase layout (same spacing as the demo datapack, blocks)
    private static final int PER_ROW = 15;
    private static final double COLUMN_GAP = 3.2;
    private static final double ROW_GAP = 4.0;
    private static final double REGION_GAP = 5.0;
    private static final double LABEL_HEIGHT = 2.0;
    private static final float LABEL_SCALE = 0.6f;

    private final RigManager rigs;
    private final ContentService content;
    private final ItemService items;
    private final PlayerService players;
    private final MessageService messages;
    private final AdminLog adminLog;
    private final FishDemoArgs args;

    /** Displayed rigs and name labels. Main thread only. */
    private final List<RigInstance> shown = new ArrayList<>();

    private final List<UUID> labels = new ArrayList<>();

    public FishDemoCommand(
            RigManager rigs,
            ContentService content,
            ItemService items,
            PlayerService players,
            MessageService messages,
            AdminLog adminLog) {
        this.rigs = rigs;
        this.content = content;
        this.items = items;
        this.players = players;
        this.messages = messages;
        this.adminLog = adminLog;
        this.args = new FishDemoArgs(rigs, content);
    }

    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("fish")
                .then(
                        Commands.literal("list")
                                .executes(ctx -> list(ctx, null))
                                .then(
                                        args.regionArg()
                                                .executes(
                                                        ctx ->
                                                                list(
                                                                        ctx,
                                                                        StringArgumentType
                                                                                .getString(
                                                                                        ctx,
                                                                                        "region")))))
                .then(
                        Commands.literal("give")
                                .then(
                                        Commands.argument("target", ArgumentTypes.player())
                                                .executes(ctx -> give(ctx, null))
                                                .then(
                                                        args.regionArg()
                                                                .executes(
                                                                        ctx ->
                                                                                give(
                                                                                        ctx,
                                                                                        StringArgumentType
                                                                                                .getString(
                                                                                                        ctx,
                                                                                                        "region"))))))
                .then(
                        Commands.literal("spawn")
                                .then(
                                        Commands.argument("fish", StringArgumentType.word())
                                                .suggests(args.fishSuggestions())
                                                .executes(ctx -> spawn(ctx, null))
                                                .then(
                                                        Commands.argument(
                                                                        "animation",
                                                                        StringArgumentType.word())
                                                                .suggests(
                                                                        args.animationSuggestions())
                                                                .executes(
                                                                        ctx ->
                                                                                spawn(
                                                                                        ctx,
                                                                                        StringArgumentType
                                                                                                .getString(
                                                                                                        ctx,
                                                                                                        "animation"))))))
                .then(
                        Commands.literal("showcase")
                                .executes(ctx -> showcase(ctx, null))
                                .then(
                                        args.regionArg()
                                                .executes(
                                                        ctx ->
                                                                showcase(
                                                                        ctx,
                                                                        StringArgumentType
                                                                                .getString(
                                                                                        ctx,
                                                                                        "region")))))
                .then(
                        Commands.literal("play")
                                .then(
                                        Commands.argument("animation", StringArgumentType.word())
                                                .suggests(
                                                        (ctx, builder) -> {
                                                            for (String a :
                                                                    List.of(
                                                                            "swim",
                                                                            "idle",
                                                                            "flop",
                                                                            FishDemoArgs.SPECIAL)) {
                                                                builder.suggest(a);
                                                            }
                                                            return builder.buildFuture();
                                                        })
                                                .executes(ctx -> play(ctx, -1))
                                                .then(
                                                        Commands.argument(
                                                                        "radius",
                                                                        DoubleArgumentType
                                                                                .doubleArg(1))
                                                                .executes(
                                                                        ctx ->
                                                                                play(
                                                                                        ctx,
                                                                                        DoubleArgumentType
                                                                                                .getDouble(
                                                                                                        ctx,
                                                                                                        "radius"))))))
                .then(Commands.literal("clear").executes(this::clear));
    }

    // ------------------------------------------------------------------ Subcommands

    private int list(CommandContext<CommandSourceStack> ctx, @Nullable String region) {
        CommandSender sender = ctx.getSource().getSender();
        ContentSnapshot snap = content.snapshot();
        Optional<List<FishDef>> fish = fishOf(sender, snap, region);
        if (fish.isEmpty()) {
            return 0;
        }
        messages.send(
                sender,
                "fish_demo.list.header",
                Placeholder.unparsed("count", String.valueOf(fish.get().size())));
        HarborId lastRegion = null;
        for (FishDef def : fish.get()) {
            if (!def.region().id().equals(lastRegion)) {
                lastRegion = def.region().id();
                messages.send(
                        sender,
                        "fish_demo.list.region",
                        Placeholder.unparsed("region", regionName(snap, def)));
            }
            String animations =
                    args.rigDef(def)
                            .map(r -> String.join(" ", r.animations().keySet()))
                            .orElse("-");
            String command = "/harbor fish spawn " + def.id().id().name();
            Component line =
                    messages.get(
                                    "fish_demo.list.line",
                                    Placeholder.unparsed("no", FishDemoArgs.no(def)),
                                    Placeholder.component("name", fishName(def)),
                                    Placeholder.unparsed("grade", def.grade().key()),
                                    Placeholder.unparsed("animations", animations))
                            .clickEvent(ClickEvent.suggestCommand(command))
                            .hoverEvent(
                                    HoverEvent.showText(
                                            messages.get(
                                                    "fish_demo.list.hover",
                                                    Placeholder.unparsed("command", command))));
            sender.sendMessage(line);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int give(CommandContext<CommandSourceStack> ctx, @Nullable String region)
            throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        Player target =
                ctx.getArgument("target", PlayerSelectorArgumentResolver.class)
                        .resolve(ctx.getSource())
                        .getFirst();
        Optional<List<FishDef>> fish = fishOf(sender, content.snapshot(), region);
        if (fish.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        int dropped = 0;
        for (FishDef def : fish.get()) {
            ItemStack stack =
                    items.createFish(
                            def,
                            (def.sizeMin() + def.sizeMax()) / 2,
                            Quality.of(Quality.MIN),
                            players.get(target).ref(),
                            now);
            for (ItemStack left : target.getInventory().addItem(stack).values()) {
                target.getWorld().dropItem(target.getLocation(), left);
                dropped++;
            }
        }
        adminLog.record(
                sender,
                "fish.give",
                target.getUniqueId().toString(),
                (region == null ? "all" : region) + " x" + fish.get().size());
        messages.send(
                sender,
                "fish_demo.given",
                Placeholder.unparsed("player", target.getName()),
                Placeholder.unparsed("count", String.valueOf(fish.get().size())),
                Placeholder.unparsed("dropped", String.valueOf(dropped)));
        return Command.SINGLE_SUCCESS;
    }

    private int spawn(CommandContext<CommandSourceStack> ctx, @Nullable String animation) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            messages.send(sender, "rig.players_only");
            return 0;
        }
        ContentSnapshot snap = content.snapshot();
        String raw = StringArgumentType.getString(ctx, "fish");
        Optional<FishDef> fish = FishDemoArgs.findFish(snap, raw);
        if (fish.isEmpty()) {
            messages.send(sender, "fish_demo.unknown_fish", Placeholder.unparsed("fish", raw));
            return 0;
        }
        FishDef def = fish.get();
        Optional<RigDef> rig = args.rigDef(def);
        if (rig.isEmpty()) {
            messages.send(
                    sender,
                    "fish_demo.no_rig",
                    Placeholder.component("name", fishName(def)),
                    Placeholder.unparsed("rig", FishRigs.rigOf(def).toString()));
            return 0;
        }
        String play =
                animation == null ? null : FishDemoArgs.resolveAnimation(rig.get(), animation);
        if (animation != null && play == null) {
            messages.send(
                    sender,
                    "rig.unknown_animation",
                    Placeholder.unparsed("id", rig.get().id().toString()),
                    Placeholder.unparsed("animation", animation));
            return 0;
        }
        Location eye = player.getEyeLocation();
        Location at = eye.clone().add(eye.getDirection().setY(0).normalize().multiply(3));
        at.setY(feet(player).getY() + 1);
        at.setPitch(0);
        RigInstance instance =
                rigs.spawn(rig.get().id(), at, RigOptions.publicRig().withAnimation(play));
        shown.add(instance);
        adminLog.record(sender, "fish.spawn", null, def.id() + " " + play);
        messages.send(
                sender,
                "fish_demo.spawned",
                Placeholder.unparsed("no", FishDemoArgs.no(def)),
                Placeholder.component("name", fishName(def)),
                Placeholder.unparsed(
                        "animations", String.join(" ", rig.get().animations().keySet())));
        return Command.SINGLE_SUCCESS;
    }

    private int showcase(CommandContext<CommandSourceStack> ctx, @Nullable String region) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            messages.send(sender, "rig.players_only");
            return 0;
        }
        ContentSnapshot snap = content.snapshot();
        Optional<List<FishDef>> fish = fishOf(sender, snap, region);
        if (fish.isEmpty()) {
            return 0;
        }
        int removed = clearShown();
        Map<HarborId, List<FishDef>> byRegion = new LinkedHashMap<>();
        fish.get()
                .forEach(
                        f ->
                                byRegion.computeIfAbsent(f.region().id(), r -> new ArrayList<>())
                                        .add(f));

        Location origin = feet(player).toBlockLocation().add(0.5, 1, 0.5);
        origin.setYaw(0);
        origin.setPitch(0);
        double z = ROW_GAP;
        int spawned = 0;
        int missing = 0;
        for (List<FishDef> group : byRegion.values()) {
            label(
                    origin.clone().add(-3, LABEL_HEIGHT + 1.5, z),
                    messages.get(
                            "fish_demo.showcase.region",
                            Placeholder.unparsed("region", regionName(snap, group.getFirst()))),
                    1.0f);
            for (int i = 0; i < group.size(); i++) {
                FishDef def = group.get(i);
                int column = i % PER_ROW;
                int row = i / PER_ROW;
                Location at = origin.clone().add(column * COLUMN_GAP, 0, z + row * ROW_GAP);
                Optional<RigDef> rig = args.rigDef(def);
                if (rig.isEmpty()) {
                    missing++;
                    continue;
                }
                shown.add(rigs.spawn(rig.get().id(), at, RigOptions.publicRig().withPhase(-1)));
                label(
                        at.clone().add(0, LABEL_HEIGHT, 0),
                        messages.get(
                                "fish_demo.showcase.label",
                                Placeholder.unparsed("no", FishDemoArgs.no(def)),
                                Placeholder.component("name", fishName(def))),
                        LABEL_SCALE);
                spawned++;
            }
            int rows = (group.size() - 1) / PER_ROW + 1;
            z += rows * ROW_GAP + REGION_GAP;
        }
        adminLog.record(sender, "fish.showcase", null, region == null ? "all" : region);
        messages.send(
                sender,
                "fish_demo.showcase.done",
                Placeholder.unparsed("count", String.valueOf(spawned)),
                Placeholder.unparsed("missing", String.valueOf(missing)),
                Placeholder.unparsed("removed", String.valueOf(removed)));
        return Command.SINGLE_SUCCESS;
    }

    private int play(CommandContext<CommandSourceStack> ctx, double radius) {
        CommandSender sender = ctx.getSource().getSender();
        String animation = StringArgumentType.getString(ctx, "animation");
        Location at = sender instanceof Player p ? p.getLocation() : null;
        shown.removeIf(r -> !r.isValid());
        int played = 0;
        int skipped = 0;
        for (RigInstance rig : shown) {
            if (radius > 0 && (at == null || !near(rig.location(), at, radius))) {
                continue;
            }
            Optional<RigDef> def = rigs.definition(rig.rig());
            String name = def.map(d -> FishDemoArgs.resolveAnimation(d, animation)).orElse(null);
            if (name != null && rig.play(name)) {
                played++;
            } else {
                skipped++;
            }
        }
        if (played == 0 && skipped == 0) {
            messages.send(sender, "fish_demo.none_shown");
            return 0;
        }
        messages.send(
                sender,
                "fish_demo.played",
                Placeholder.unparsed("animation", animation),
                Placeholder.unparsed("count", String.valueOf(played)),
                Placeholder.unparsed("skipped", String.valueOf(skipped)));
        return Command.SINGLE_SUCCESS;
    }

    private int clear(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        int removed = clearShown();
        adminLog.record(sender, "fish.clear", null, String.valueOf(removed));
        messages.send(
                sender,
                "fish_demo.cleared",
                Placeholder.unparsed("count", String.valueOf(removed)));
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ Helpers

    private int clearShown() {
        int count = 0;
        for (RigInstance rig : shown) {
            if (rig.isValid()) {
                rig.remove();
                count++;
            }
        }
        shown.clear();
        for (UUID id : labels) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) {
                e.remove();
            }
        }
        labels.clear();
        return count;
    }

    private void label(Location at, Component text, float scale) {
        TextDisplay display =
                at.getWorld()
                        .spawn(
                                at,
                                TextDisplay.class,
                                d -> {
                                    d.setPersistent(false);
                                    d.text(text);
                                    d.setBillboard(Display.Billboard.VERTICAL);
                                    d.setAlignment(TextDisplay.TextAlignment.CENTER);
                                    Transformation t = d.getTransformation();
                                    d.setTransformation(
                                            new Transformation(
                                                    t.getTranslation(),
                                                    t.getLeftRotation(),
                                                    new Vector3f(scale, scale, scale),
                                                    t.getRightRotation()));
                                });
        labels.add(display.getUniqueId());
    }

    /**
     * Picks the fish list for a region argument (by number). For an unknown region, tells the
     * sender and returns empty.
     */
    private Optional<List<FishDef>> fishOf(
            CommandSender sender, ContentSnapshot snap, @Nullable String region) {
        List<FishDef> all =
                snap.all(FishDef.TYPE).values().stream()
                        .sorted(Comparator.comparingInt(FishDef::no))
                        .toList();
        if (region == null) {
            return Optional.of(all);
        }
        String path = region.startsWith("region/") ? region : "region/" + region;
        List<FishDef> picked =
                all.stream().filter(f -> f.region().id().path().equals(path)).toList();
        if (picked.isEmpty()) {
            messages.send(
                    sender, "fish_demo.unknown_region", Placeholder.unparsed("region", region));
            return Optional.empty();
        }
        return Optional.of(picked);
    }

    private String regionName(ContentSnapshot snap, FishDef def) {
        return snap.get(RegionDef.TYPE, def.region().id())
                .map(RegionDef::name)
                .orElse(def.region().toString());
    }

    private Component fishName(FishDef def) {
        String key = "item.name." + def.grade().key();
        return messages.get(key, Placeholder.unparsed("name", def.name()));
    }

    /** Player location (Entity's getLocation is non-null). */
    private static Location feet(Entity entity) {
        return entity.getLocation();
    }

    private static boolean near(Location a, Location b, double radius) {
        return java.util.Objects.equals(a.getWorld(), b.getWorld())
                && a.distanceSquared(b) <= radius * radius;
    }
}
