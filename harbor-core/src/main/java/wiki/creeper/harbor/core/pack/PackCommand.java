package wiki.creeper.harbor.core.pack;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.nio.file.Path;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import wiki.creeper.harbor.common.pack.PublishedPack;
import wiki.creeper.harbor.core.admin.AdminLog;
import wiki.creeper.harbor.core.api.MessageService;

/**
 * /harbor pack admin commands (permission harbor.admin.pack).
 *
 * <pre>
 * /harbor pack            current pack (URL, SHA-1, model/sound counts)
 * /harbor pack build      re-zip the asset folder, and if changed upload to the CDN and notify all servers (build server only)
 * </pre>
 */
public final class PackCommand {

    private final PackManager packs;
    private final MessageService messages;
    private final AdminLog adminLog;

    public PackCommand(PackManager packs, MessageService messages, AdminLog adminLog) {
        this.packs = packs;
        this.messages = messages;
        this.adminLog = adminLog;
    }

    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("pack")
                .executes(this::status)
                .then(Commands.literal("build").executes(this::build));
    }

    private int status(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        packs.current()
                .ifPresentOrElse(
                        pack -> messages.send(sender, "pack.status", placeholders(pack)),
                        () -> messages.send(sender, "pack.none"));
        if (packs.builder()) {
            messages.send(
                    sender,
                    "pack.builder",
                    Placeholder.unparsed("source", packs.source().toString()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int build(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        messages.send(sender, "pack.building");
        var _ =
                packs.build()
                        .thenAccept(
                                outcome -> {
                                    switch (outcome) {
                                        case PackManager.Outcome.Uploaded(
                                                        PublishedPack pack,
                                                        int files) -> {
                                            messages.send(
                                                    sender,
                                                    "pack.uploaded",
                                                    Placeholder.unparsed(
                                                            "files", String.valueOf(files)),
                                                    Placeholder.unparsed("sha1", pack.sha1()),
                                                    Placeholder.unparsed("url", pack.url()));
                                            adminLog.record(
                                                    sender, "pack.build", pack.sha1(), pack.url());
                                        }
                                        case PackManager.Outcome.Unchanged(PublishedPack pack) ->
                                                messages.send(
                                                        sender,
                                                        "pack.unchanged",
                                                        Placeholder.unparsed("sha1", pack.sha1()));
                                        case PackManager.Outcome.NoAssets(Path source) ->
                                                messages.send(
                                                        sender,
                                                        "pack.no_assets",
                                                        Placeholder.unparsed(
                                                                "source", source.toString()));
                                        case PackManager.Outcome.Failed(String reason) ->
                                                messages.send(
                                                        sender,
                                                        "pack.failed",
                                                        Placeholder.unparsed("reason", reason));
                                    }
                                });
        return Command.SINGLE_SUCCESS;
    }

    private static TagResolver[] placeholders(PublishedPack pack) {
        return new TagResolver[] {
            Placeholder.unparsed("url", pack.url()),
            Placeholder.unparsed("sha1", pack.sha1()),
            Placeholder.unparsed(
                    "size", String.format(Locale.ROOT, "%.1f", pack.sizeBytes() / 1_048_576.0)),
            Placeholder.unparsed("models", String.valueOf(pack.models().size())),
            Placeholder.unparsed("sounds", String.valueOf(pack.sounds().size()))
        };
    }
}
