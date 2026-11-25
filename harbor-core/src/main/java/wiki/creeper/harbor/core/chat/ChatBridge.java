package wiki.creeper.harbor.core.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.config.InfraConfig;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Messages;
import wiki.creeper.harbor.common.proxy.ProxyMessages;
import wiki.creeper.harbor.common.proxy.ProxyMessages.ChatMessage;
import wiki.creeper.harbor.common.redis.MessageBus;
import wiki.creeper.harbor.common.redis.Redis;
import wiki.creeper.harbor.core.api.Harbor;
import wiki.creeper.harbor.core.api.Ui;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * Cross-server chat (docs 20 1.4 chat path). Cancelling signed chat on the proxy disconnects the
 * player, so the backend cancels the chat and sends a ChatMessage to the proxy. If Redis is down,
 * chat is not cancelled and stays visible only on this server (switches back automatically once it
 * recovers).
 *
 * <p>Chat line format (proposed): messages_ko.yml {@code chat.format}/{@code chat.party_format} are
 * rendered on this server (from the sender's perspective, including PlaceholderAPI {@code %…%},
 * {@code <glyph:…>} and the sender's {@code <head>} from {@link ChatHeads}) and sent along to the
 * proxy. Only the message slot is left empty, and the proxy fills in the profanity-filtered
 * message. Without the key, the proxy's harbor-proxy.yml chat.format is used. Most PlaceholderAPI
 * expansions are not thread-safe, so the format is rendered on the main thread (the chat event is
 * async, so it goes out one tick later).
 */
public final class ChatBridge implements Listener {

    private static final String PARTY_PREFIX = "!";

    private final InfraConfig infra;
    private final Redis redis;
    private final MessageBus bus;
    private final Supplier<ContentSnapshot> content;
    private final Ui ui;
    private final PlaceholderApiHook papi;
    private final ChatHeads heads;
    private final Tasks tasks;

    public ChatBridge(
            InfraConfig infra,
            Redis redis,
            MessageBus bus,
            Supplier<ContentSnapshot> content,
            Ui ui,
            PlaceholderApiHook papi,
            ChatHeads heads,
            Tasks tasks) {
        this.infra = infra;
        this.redis = redis;
        this.bus = bus;
        this.content = content;
        this.ui = ui;
        this.papi = papi;
        this.heads = heads;
        this.tasks = tasks;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!redis.isConnected()) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        String text = PlainTextComponentSerializer.plainText().serialize(event.originalMessage());
        var _ = tasks.sync(() -> send(player, text));
    }

    private void send(Player player, String raw) {
        if (!player.isOnline()) {
            return;
        }
        String text = raw;
        String channel = "global";
        List<UUID> recipients = List.of();
        // Party chat: messages starting with '!' go to party members only (proposed value). Without
        // a party implementation (Boss) or a party, it is global chat
        if (text.length() > 1 && text.startsWith(PARTY_PREFIX)) {
            UUID sender = player.getUniqueId();
            List<UUID> members =
                    Harbor.api().parties().map(p -> p.membersOf(sender)).orElse(List.of(sender));
            if (members.size() > 1) {
                channel = "party";
                recipients = List.copyOf(members);
                text = text.substring(PARTY_PREFIX.length()).strip();
            }
        }
        bus.publish(
                ProxyMessages.toProxy(ChatMessage.class),
                new ChatMessage(
                        player.getUniqueId(),
                        player.getName(),
                        infra.serverId(),
                        channel,
                        text,
                        recipients,
                        format(
                                player,
                                channel.equals("party") ? "chat.party_format" : "chat.format")));
    }

    /** Adventure JSON of the format key rendered for this player (null if the key is missing). */
    private @Nullable String format(Player player, String key) {
        Optional<String> template = content.get().single(Messages.TYPE).get(key);
        if (template.isEmpty()) {
            return null;
        }
        PlaceholderApiHook.Applied applied = papi.apply(player, template.get());
        try {
            Component line =
                    MiniMessage.miniMessage()
                            .deserialize(
                                    applied.template(),
                                    applied.resolver(),
                                    ui.glyphs(),
                                    Placeholder.unparsed("name", player.getName()),
                                    Placeholder.component("head", heads.head(player)),
                                    Placeholder.component("display_name", player.displayName()),
                                    Placeholder.unparsed("server", infra.serverId()),
                                    Placeholder.unparsed("message", ProxyMessages.MESSAGE_SLOT));
            return GsonComponentSerializer.gson().serialize(line);
        } catch (RuntimeException e) {
            return null; // on a format error, fall back to the proxy's default format
        }
    }
}
