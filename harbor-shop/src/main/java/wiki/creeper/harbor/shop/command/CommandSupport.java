package wiki.creeper.harbor.shop.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.shop.ShopRuntime;
import wiki.creeper.harbor.shop.content.CosmeticDef;
import wiki.creeper.harbor.shop.data.ShopRepository;

/** Argument, target-lookup and output helpers shared by the player and admin shop commands. */
final class CommandSupport {

    private CommandSupport() {}

    static RequiredArgumentBuilder<CommandSourceStack, String> cosmeticArgument(ShopRuntime rt) {
        return Commands.argument("cosmetic", StringArgumentType.word())
                .suggests(
                        (c, b) -> {
                            rt.snapshot()
                                    .ids(CosmeticDef.TYPE.name())
                                    .forEach(
                                            id ->
                                                    b.suggest(
                                                            id.path()
                                                                    .substring(
                                                                            CosmeticDef.PREFIX
                                                                                    .length())));
                            return b.buildFuture();
                        });
    }

    static Optional<CosmeticDef> cosmetic(ShopRuntime rt, String raw) {
        String path =
                raw.contains(":")
                        ? raw
                        : raw.startsWith(CosmeticDef.PREFIX) ? raw : CosmeticDef.PREFIX + raw;
        return HarborId.tryParse(path).flatMap(rt::cosmetic);
    }

    static RequiredArgumentBuilder<CommandSourceStack, String> playerArg() {
        return Commands.argument("player", StringArgumentType.word())
                .suggests(
                        (c, b) -> {
                            Bukkit.getOnlinePlayers().forEach(p -> b.suggest(p.getName()));
                            return b.buildFuture();
                        });
    }

    static CompletableFuture<Optional<UUID>> resolve(ShopRuntime rt, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return CompletableFuture.completedFuture(Optional.of(online.getUniqueId()));
        }
        return rt.db(c -> ShopRepository.uuidByName(c, name));
    }

    static @Nullable Player adminOf(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getSender() instanceof Player p ? p : null;
    }

    static String adminId(CommandSender sender) {
        return sender instanceof Player p ? p.getUniqueId().toString() : "CONSOLE";
    }

    static void withTarget(
            ShopRuntime rt, CommandContext<CommandSourceStack> ctx, Consumer<UUID> action) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "player");
        var _ =
                resolve(rt, name)
                        .thenAccept(
                                uuid -> {
                                    if (uuid.isEmpty()) {
                                        rt.api()
                                                .messages()
                                                .send(sender, "shop.admin.unknown_player");
                                    } else {
                                        action.accept(uuid.get());
                                    }
                                });
    }

    static void sendLines(ShopRuntime rt, CommandSender sender, List<String> lines) {
        if (lines.isEmpty()) {
            rt.api().messages().send(sender, "shop.admin.none");
            return;
        }
        lines.forEach(
                l ->
                        rt.api()
                                .messages()
                                .send(sender, "shop.admin.line", Placeholder.unparsed("text", l)));
    }
}
