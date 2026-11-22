package wiki.creeper.harbor.proxy;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.proxy.ProxyMessages;
import wiki.creeper.harbor.common.proxy.ProxyMessages.ChatMessage;
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.common.redis.MessageBus;

/**
 * Cross-server chat, whisper and announcement delivery (docs 20 1.4). When a backend Core cancels
 * chat and sends a ChatMessage, the proxy checks MUTE sanctions → spam check → banned-word
 * replacement → picks recipients → delivers as a system message. Announcements (harbor:announce)
 * are subscribed only by the proxy and sent to everyone exactly once.
 *
 * <p>Party channel: parties are owned by Boss, so the proxy does not know party lists. The
 * requesting backend puts party members into recipients via Core api PartyService, and the proxy
 * delivers to those people regardless of server (including the sender, deduplicated).
 */
final class ChatRelay {

    /** Per-sender spam check state. */
    private static final class SenderState {
        String lastMessage = "";
        int repeats;
        final Deque<Instant> recent = new ArrayDeque<>();
        Instant blockedUntil = Instant.EPOCH;
    }

    private final ProxyServer proxy;
    private final ProxyConfig config;
    private final ProxyText text;
    private final SanctionService sanctions;
    private final Map<UUID, SenderState> senders = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> lastWhisper = new ConcurrentHashMap<>();

    ChatRelay(ProxyServer proxy, ProxyConfig config, ProxyText text, SanctionService sanctions) {
        this.proxy = proxy;
        this.config = config;
        this.text = text;
        this.sanctions = sanctions;
    }

    void start(MessageBus bus) {
        bus.subscribe(
                ProxyMessages.toProxy(ChatMessage.class), envelope -> deliver(envelope.payload()));
        bus.subscribe(
                Channels.ANNOUNCEMENTS,
                envelope -> {
                    Component message =
                            MiniMessage.miniMessage().deserialize(envelope.payload().message());
                    proxy.getAllPlayers().forEach(p -> p.sendMessage(message));
                });
    }

    void deliver(ChatMessage chat) {
        Optional<Player> sender = proxy.getPlayer(chat.sender());
        if (sanctions.blockIfMuted(chat.sender())) {
            return;
        }
        if (!allow(chat.sender(), chat.message())) {
            sender.ifPresent(p -> p.sendMessage(text.get("chat.blocked")));
            return;
        }
        String clean = filter(chat.message());
        switch (chat.channel()) {
            case "whisper" ->
                    chat.recipients()
                            .forEach(to -> whisper(chat.sender(), chat.senderName(), to, clean));
            case "party" -> {
                Component drawn = drawn(chat, clean);
                Component line =
                        drawn != null
                                ? drawn
                                : text.get(
                                        "chat.party_format",
                                        Placeholder.unparsed("server", chat.server()),
                                        Placeholder.unparsed("name", chat.senderName()),
                                        Placeholder.unparsed("message", clean));
                Set<UUID> to = new LinkedHashSet<>(chat.recipients());
                to.add(chat.sender());
                to.forEach(uuid -> proxy.getPlayer(uuid).ifPresent(p -> p.sendMessage(line)));
            }
            case "server" -> {
                Component line = format(chat, clean);
                proxy.getServer(chat.server())
                        .ifPresent(s -> s.getPlayersConnected().forEach(p -> p.sendMessage(line)));
            }
            default -> {
                Component line = format(chat, clean);
                proxy.getAllPlayers().forEach(p -> p.sendMessage(line));
            }
        }
    }

    /** /귓 · /답장 (whisper/reply). Delivered regardless of server. MUTE blocks whispers too. */
    boolean whisper(UUID from, String fromName, UUID to, String message) {
        if (sanctions.blockIfMuted(from)) {
            return false;
        }
        Optional<Player> target = proxy.getPlayer(to);
        if (target.isEmpty()) {
            proxy.getPlayer(from).ifPresent(p -> p.sendMessage(text.get("chat.no_target")));
            return false;
        }
        String clean = filter(message);
        target.get()
                .sendMessage(
                        text.get(
                                "chat.whisper_from",
                                Placeholder.unparsed("name", fromName),
                                Placeholder.unparsed("message", clean)));
        proxy.getPlayer(from)
                .ifPresent(
                        p ->
                                p.sendMessage(
                                        text.get(
                                                "chat.whisper_to",
                                                Placeholder.unparsed(
                                                        "name", target.get().getUsername()),
                                                Placeholder.unparsed("message", clean))));
        lastWhisper.put(to, from);
        lastWhisper.put(from, to);
        return true;
    }

    Optional<UUID> lastPartner(UUID player) {
        return Optional.ofNullable(lastWhisper.get(player));
    }

    void forget(UUID player) {
        senders.remove(player);
        lastWhisper.remove(player);
    }

    /**
     * If the backend rendered a line (with PlaceholderAPI), only fills the message slot; otherwise
     * harbor-proxy.yml chat.format.
     */
    private Component format(ChatMessage chat, String message) {
        Component drawn = drawn(chat, message);
        if (drawn != null) {
            return drawn;
        }
        return text.get(
                "chat.format",
                Placeholder.unparsed("server", chat.server()),
                Placeholder.unparsed("name", chat.senderName()),
                Placeholder.unparsed("message", message));
    }

    /**
     * Briefly blocks repeated identical lines or several lines per second (proposed values in
     * harbor-proxy.yml).
     */
    private boolean allow(UUID sender, String message) {
        SenderState state = senders.computeIfAbsent(sender, k -> new SenderState());
        Instant now = Instant.now();
        synchronized (state) {
            if (now.isBefore(state.blockedUntil)) {
                return false;
            }
            state.repeats = message.equalsIgnoreCase(state.lastMessage) ? state.repeats + 1 : 1;
            state.lastMessage = message;
            state.recent.addLast(now);
            while (!state.recent.isEmpty()
                    && state.recent.peekFirst().isBefore(now.minusSeconds(1))) {
                state.recent.pollFirst();
            }
            if (state.repeats >= config.chat().repeatLimit()
                    || state.recent.size() > config.chat().linesPerSecond()) {
                state.blockedUntil = now.plusSeconds(config.chat().blockSeconds());
                return false;
            }
            return true;
        }
    }

    private String filter(String message) {
        String result = message;
        for (String word : config.chat().bannedWords()) {
            if (!word.isEmpty()
                    && result.toLowerCase(Locale.ROOT).contains(word.toLowerCase(Locale.ROOT))) {
                result =
                        result.replaceAll(
                                "(?i)" + java.util.regex.Pattern.quote(word),
                                "*".repeat(word.length()));
            }
        }
        return result;
    }

    private static @Nullable Component drawn(ChatMessage chat, String message) {
        String formatted = chat.formatted();
        if (formatted == null || formatted.isBlank()) {
            return null;
        }
        try {
            return GsonComponentSerializer.gson()
                    .deserialize(formatted)
                    .replaceText(
                            TextReplacementConfig.builder()
                                    .matchLiteral(ProxyMessages.MESSAGE_SLOT)
                                    .replacement(Component.text(message))
                                    .build());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
