package wiki.creeper.harbor.core.rig;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RigId;
import wiki.creeper.harbor.common.rig.RigDef;
import wiki.creeper.harbor.core.admin.AdminLog;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.api.RigInstance;
import wiki.creeper.harbor.core.api.RigOptions;

/**
 * /harbor rig 관리 명령 (권한 harbor.admin.rig). 리그 정의·압축 결과 확인과 현장 시험용.
 *
 * <pre>
 * /harbor rig list
 * /harbor rig spawn &lt;리그&gt; [동작]   시선 앞 3블록에 모두에게 보이는 리그 (판정 상자가 있으면 클릭 시 부위 이름 표시)
 * /harbor rig play &lt;동작&gt; [once]    16블록 안 가장 가까운 리그
 * /harbor rig clear [반경]
 * /harbor rig stats
 * </pre>
 */
public final class RigCommand {

    private static final double NEAR = 16;
    private static final int GLOW_TICKS = 10;

    private final RigManager rigs;
    private final MessageService messages;
    private final AdminLog adminLog;

    public RigCommand(RigManager rigs, MessageService messages, AdminLog adminLog) {
        this.rigs = rigs;
        this.messages = messages;
        this.adminLog = adminLog;
    }

    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("rig")
                .then(Commands.literal("list").executes(this::list))
                .then(
                        Commands.literal("spawn")
                                .then(
                                        Commands.argument("rig", ArgumentTypes.key())
                                                .suggests(
                                                        (ctx, builder) -> {
                                                            String typed =
                                                                    builder.getRemaining()
                                                                            .toLowerCase(
                                                                                    Locale.ROOT);
                                                            for (RigId id : rigs.ids()) {
                                                                String full = id.toString();
                                                                if (full.startsWith(typed)
                                                                        || id.id()
                                                                                .path()
                                                                                .startsWith(
                                                                                        typed)) {
                                                                    builder.suggest(full);
                                                                }
                                                            }
                                                            return builder.buildFuture();
                                                        })
                                                .executes(ctx -> spawn(ctx, null))
                                                .then(
                                                        Commands.argument(
                                                                        "animation",
                                                                        StringArgumentType.word())
                                                                .executes(
                                                                        ctx ->
                                                                                spawn(
                                                                                        ctx,
                                                                                        StringArgumentType
                                                                                                .getString(
                                                                                                        ctx,
                                                                                                        "animation"))))))
                .then(
                        Commands.literal("play")
                                .then(
                                        Commands.argument("animation", StringArgumentType.word())
                                                .executes(ctx -> play(ctx, false))
                                                .then(
                                                        Commands.literal("once")
                                                                .executes(ctx -> play(ctx, true)))))
                .then(
                        Commands.literal("clear")
                                .executes(ctx -> clear(ctx, -1))
                                .then(
                                        Commands.argument("radius", DoubleArgumentType.doubleArg(0))
                                                .executes(
                                                        ctx ->
                                                                clear(
                                                                        ctx,
                                                                        DoubleArgumentType
                                                                                .getDouble(
                                                                                        ctx,
                                                                                        "radius")))))
                .then(Commands.literal("stats").executes(this::stats));
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        messages.send(
                sender,
                "rig.list",
                Placeholder.unparsed("defs", String.valueOf(rigs.ids().size())),
                Placeholder.unparsed("instances", String.valueOf(rigs.instances().size())),
                Placeholder.unparsed("entities", String.valueOf(rigs.entityCount())));
        for (RigId id : rigs.ids()) {
            Optional<RigDef> def = rigs.definition(id);
            if (def.isEmpty()) {
                continue;
            }
            Map<String, Integer> keys = rigs.keyCounts(id);
            messages.send(
                    sender,
                    "rig.list_line",
                    Placeholder.unparsed("id", id.toString()),
                    Placeholder.unparsed("parts", String.valueOf(def.get().partCount())),
                    Placeholder.unparsed("hitboxes", String.valueOf(def.get().hitboxCount())),
                    Placeholder.unparsed("animations", keys.toString()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int spawn(CommandContext<CommandSourceStack> ctx, @Nullable String animation) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            messages.send(sender, "rig.players_only");
            return 0;
        }
        HarborId raw = harborId(ctx.getArgument("rig", Key.class));
        if (!raw.hasPrefix(RigId.PREFIX) || raw.path().equals(RigId.PREFIX)) {
            messages.send(sender, "rig.unknown", Placeholder.unparsed("id", raw.toString()));
            return 0;
        }
        RigId id = new RigId(raw);
        Optional<RigDef> def = rigs.definition(id);
        if (def.isEmpty()) {
            messages.send(sender, "rig.unknown", Placeholder.unparsed("id", id.toString()));
            return 0;
        }
        if (animation != null && def.get().animation(animation).isEmpty()) {
            messages.send(
                    sender,
                    "rig.unknown_animation",
                    Placeholder.unparsed("id", id.toString()),
                    Placeholder.unparsed("animation", animation));
            return 0;
        }
        Location eye = player.getEyeLocation();
        Location at = eye.clone().add(eye.getDirection().setY(0).normalize().multiply(3));
        at.setY(feet(player).getY() + 1);
        at.setPitch(0);
        RigOptions options = RigOptions.publicRig().withAnimation(animation);
        if (def.get().hitboxCount() > 0) {
            options =
                    options.withHitboxes(
                            hit -> {
                                messages.send(
                                        hit.player(),
                                        "rig.hit",
                                        Placeholder.unparsed("bone", hit.bone()),
                                        Placeholder.unparsed(
                                                "kind", hit.attack() ? "attack" : "interact"));
                                RigInstance rig = hit.rig();
                                rig.setPartGlow(hit.bone(), true);
                                var _ =
                                        Bukkit.getScheduler()
                                                .runTaskLater(
                                                        rigs.plugin(),
                                                        () -> rig.setPartGlow(hit.bone(), false),
                                                        GLOW_TICKS);
                            });
        }
        RigInstance rig = rigs.spawn(id, at, options);
        adminLog.record(sender, "rig.spawn", null, id + " " + animation);
        messages.send(
                sender,
                "rig.spawned",
                Placeholder.unparsed("id", id.toString()),
                Placeholder.unparsed("parts", String.valueOf(def.get().partCount())),
                Placeholder.unparsed("keys", rigs.keyCounts(id).toString()),
                Placeholder.unparsed("animation", String.valueOf(rig.animation())));
        return Command.SINGLE_SUCCESS;
    }

    private int play(CommandContext<CommandSourceStack> ctx, boolean once) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            messages.send(sender, "rig.players_only");
            return 0;
        }
        String animation = StringArgumentType.getString(ctx, "animation");
        Optional<CoreRig> near = rigs.nearest(feet(player), NEAR);
        if (near.isEmpty()) {
            messages.send(sender, "rig.none_near");
            return 0;
        }
        CoreRig rig = near.get();
        boolean ok = once ? rig.playOnce(animation, null) : rig.play(animation);
        if (!ok) {
            messages.send(
                    sender,
                    "rig.unknown_animation",
                    Placeholder.unparsed("id", rig.rig().toString()),
                    Placeholder.unparsed("animation", animation));
            return 0;
        }
        messages.send(
                sender,
                "rig.played",
                Placeholder.unparsed("id", rig.rig().toString()),
                Placeholder.unparsed("animation", animation));
        return Command.SINGLE_SUCCESS;
    }

    private int clear(CommandContext<CommandSourceStack> ctx, double radius) {
        CommandSender sender = ctx.getSource().getSender();
        Location at = sender instanceof Player player ? feet(player) : null;
        int removed = rigs.clear(at, radius);
        adminLog.record(sender, "rig.clear", null, radius < 0 ? "all" : String.valueOf(radius));
        messages.send(
                sender, "rig.cleared", Placeholder.unparsed("count", String.valueOf(removed)));
        return Command.SINGLE_SUCCESS;
    }

    private int stats(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        int parts = sender instanceof Player player ? rigs.visibleParts(player) : 0;
        messages.send(
                sender,
                "rig.stats",
                Placeholder.unparsed("instances", String.valueOf(rigs.instances().size())),
                Placeholder.unparsed("entities", String.valueOf(rigs.entityCount())),
                Placeholder.unparsed(
                        "ms", String.format(Locale.ROOT, "%.3f", rigs.lastTickNanos() / 1e6)),
                Placeholder.unparsed("deferred", String.valueOf(rigs.lastDeferred())),
                Placeholder.unparsed("parts", String.valueOf(parts)),
                Placeholder.unparsed("budget", String.valueOf(rigs.settings().partBudget())));
        if (parts > rigs.settings().partBudget()) {
            messages.send(sender, "rig.over_budget");
        }
        return Command.SINGLE_SUCCESS;
    }

    /** 플레이어 위치 (Entity 쪽 getLocation은 널이 아니다). */
    private static Location feet(Entity entity) {
        return entity.getLocation();
    }

    /** 키 인자는 네임스페이스를 생략하면 minecraft로 읽히므로 harbor로 바꾼다. */
    private static HarborId harborId(Key key) {
        String namespace =
                key.namespace().equals(Key.MINECRAFT_NAMESPACE)
                        ? HarborId.DEFAULT_NAMESPACE
                        : key.namespace();
        return new HarborId(namespace, key.value());
    }
}
