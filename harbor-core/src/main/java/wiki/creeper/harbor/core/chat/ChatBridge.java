package wiki.creeper.harbor.core.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import wiki.creeper.harbor.common.config.InfraConfig;
import wiki.creeper.harbor.common.proxy.ProxyMessages;
import wiki.creeper.harbor.common.proxy.ProxyMessages.ChatMessage;
import wiki.creeper.harbor.common.redis.MessageBus;
import wiki.creeper.harbor.common.redis.Redis;
import wiki.creeper.harbor.core.api.Harbor;

/**
 * 서버 간 채팅 (docs 20 1.4 채팅 경로). 서명 채팅을 프록시에서 취소하면 접속이 끊기므로, 백엔드가 채팅을 취소하고 ChatMessage를 프록시로 보낸다.
 * Redis가 끊기면 취소하지 않고 이 서버 안에서만 보이게 둔다 (복구되면 자동으로 되돌아온다).
 */
public final class ChatBridge implements Listener {

    private static final String PARTY_PREFIX = "!";

    private final InfraConfig infra;
    private final Redis redis;
    private final MessageBus bus;

    public ChatBridge(InfraConfig infra, Redis redis, MessageBus bus) {
        this.infra = infra;
        this.redis = redis;
        this.bus = bus;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!redis.isConnected()) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.originalMessage());
        String channel = "global";
        List<UUID> recipients = List.of();
        // 파티 채팅: '!'로 시작하면 파티원에게만 (제안값). 파티 구현(Boss)이 없거나 파티가 없으면 전체 채팅
        if (text.length() > 1 && text.startsWith(PARTY_PREFIX)) {
            UUID sender = event.getPlayer().getUniqueId();
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
                        event.getPlayer().getUniqueId(),
                        event.getPlayer().getName(),
                        infra.serverId(),
                        channel,
                        text,
                        recipients));
    }
}
