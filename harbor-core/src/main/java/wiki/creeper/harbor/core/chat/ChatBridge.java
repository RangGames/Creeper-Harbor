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
 * 서버 간 채팅 (docs 20 1.4 채팅 경로). 서명 채팅을 프록시에서 취소하면 접속이 끊기므로, 백엔드가 채팅을 취소하고 ChatMessage를 프록시로 보낸다.
 * Redis가 끊기면 취소하지 않고 이 서버 안에서만 보이게 둔다 (복구되면 자동으로 되돌아온다).
 *
 * <p>채팅 줄 형식 (추가 제안): messages_ko.yml {@code chat.format}·{@code chat.party_format}을 이 서버에서 그려서(보낸
 * 사람 기준 PlaceholderAPI {@code %…%}, {@code <glyph:…>} 포함) 프록시에 함께 보낸다. 메시지 자리만 비워 두면 프록시가 금칙어를 거른
 * 메시지를 넣는다. 키가 없으면 프록시의 harbor-proxy.yml chat.format을 쓴다. PlaceholderAPI 확장 대부분이 스레드 안전하지 않아서 형식은
 * 메인 스레드에서 그린다 (채팅 이벤트는 비동기라 한 틱 늦게 나간다).
 */
public final class ChatBridge implements Listener {

    private static final String PARTY_PREFIX = "!";

    private final InfraConfig infra;
    private final Redis redis;
    private final MessageBus bus;
    private final Supplier<ContentSnapshot> content;
    private final Ui ui;
    private final PlaceholderApiHook papi;
    private final Tasks tasks;

    public ChatBridge(
            InfraConfig infra,
            Redis redis,
            MessageBus bus,
            Supplier<ContentSnapshot> content,
            Ui ui,
            PlaceholderApiHook papi,
            Tasks tasks) {
        this.infra = infra;
        this.redis = redis;
        this.bus = bus;
        this.content = content;
        this.ui = ui;
        this.papi = papi;
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
        // 파티 채팅: '!'로 시작하면 파티원에게만 (제안값). 파티 구현(Boss)이 없거나 파티가 없으면 전체 채팅
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

    /** 형식 키를 이 플레이어 기준으로 그린 Adventure JSON (키가 없으면 null). */
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
                                    Placeholder.component("display_name", player.displayName()),
                                    Placeholder.unparsed("server", infra.serverId()),
                                    Placeholder.unparsed("message", ProxyMessages.MESSAGE_SLOT));
            return GsonComponentSerializer.gson().serialize(line);
        } catch (RuntimeException e) {
            return null; // 형식 오류면 프록시 기본 형식
        }
    }
}
