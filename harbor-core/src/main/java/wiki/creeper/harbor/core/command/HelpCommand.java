package wiki.creeper.harbor.core.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import wiki.creeper.harbor.common.content.type.HelpBook;
import wiki.creeper.harbor.core.api.ContentService;
import wiki.creeper.harbor.core.api.FlagService;
import wiki.creeper.harbor.core.api.MessageService;

/**
 * Player /도움말 command (alias /명령어, proposed). Shows commands in help.yml order, hiding commands
 * missing on this server and commands the player cannot use (Brigadier requires). Clicking a line
 * runs it or puts it in the chat input.
 *
 * <p>First-time players get a one-time welcome message (permanent flag {@value #WELCOMED}; not
 * resent after moving servers).
 */
public final class HelpCommand implements Listener {

    static final String WELCOMED = "help/welcomed";

    /**
     * Slightly delayed so it is not buried under other notices right after login (resource pack,
     * join message).
     */
    private static final long WELCOME_DELAY_TICKS = 60;

    private final Plugin plugin;
    private final ContentService content;
    private final MessageService messages;
    private final FlagService flags;

    public HelpCommand(
            Plugin plugin, ContentService content, MessageService messages, FlagService flags) {
        this.plugin = plugin;
        this.content = content;
        this.messages = messages;
        this.flags = flags;
    }

    /** Registered in LifecycleEvents.COMMANDS. */
    public void register(Commands commands) {
        commands.register(
                Commands.literal("도움말").executes(this::show).build(),
                "크리퍼 항구 명령어 안내",
                List.of("명령어"));
    }

    private int show(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        CommandNode<CommandSourceStack> root = ctx.getRootNode();
        HelpBook book = content.snapshot().single(HelpBook.TYPE);
        List<Component> lines = new ArrayList<>();
        for (HelpBook.Section section : book.sections()) {
            List<Component> entries = new ArrayList<>();
            for (HelpBook.Entry entry : section.entries()) {
                CommandNode<CommandSourceStack> node = root.getChild(entry.root());
                if (node == null || !node.canUse(src)) {
                    continue;
                }
                entries.add(line(entry));
            }
            if (!entries.isEmpty()) {
                lines.add(
                        messages.get(
                                "help.section",
                                Placeholder.component(
                                        "title",
                                        MiniMessage.miniMessage().deserialize(section.title()))));
                lines.addAll(entries);
            }
        }
        var sender = src.getSender();
        if (lines.isEmpty()) {
            messages.send(sender, "help.empty");
            return Command.SINGLE_SUCCESS;
        }
        messages.send(sender, "help.header");
        lines.forEach(sender::sendMessage);
        messages.send(sender, "help.footer");
        return Command.SINGLE_SUCCESS;
    }

    private Component line(HelpBook.Entry entry) {
        String click = entry.clickText();
        return messages.get(
                        "help.line",
                        Placeholder.unparsed("command", "/" + entry.command()),
                        Placeholder.component(
                                "text", MiniMessage.miniMessage().deserialize(entry.text())))
                .clickEvent(
                        entry.run()
                                ? ClickEvent.runCommand(click)
                                : ClickEvent.suggestCommand(click))
                .hoverEvent(
                        HoverEvent.showText(
                                messages.get(
                                        entry.run() ? "help.hover_run" : "help.hover_suggest",
                                        Placeholder.unparsed("command", click.strip()))));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (flags.hasPlayer(player.getUniqueId(), WELCOMED)) {
            return;
        }
        var _ =
                Bukkit.getScheduler()
                        .runTaskLater(
                                plugin,
                                () -> {
                                    if (!player.isOnline()
                                            || flags.hasPlayer(player.getUniqueId(), WELCOMED)) {
                                        return;
                                    }
                                    messages.send(
                                            player,
                                            "help.welcome",
                                            Placeholder.unparsed("player", player.getName()));
                                    var _ =
                                            flags.setPlayer(
                                                    player.getUniqueId(),
                                                    FlagService.Scope.PERM,
                                                    WELCOMED);
                                },
                                WELCOME_DELAY_TICKS);
    }
}
