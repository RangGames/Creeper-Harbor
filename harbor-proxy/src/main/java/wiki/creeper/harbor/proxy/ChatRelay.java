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
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import wiki.creeper.harbor.common.proxy.ProxyMessages;
import wiki.creeper.harbor.common.proxy.ProxyMessages.ChatMessage;
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.common.redis.MessageBus;

/**
 * 서버 간 채팅·귓속말·공지 배달 (docs 20 1.4). 백엔드 Core가 채팅을 취소하고 ChatMessage를 보내면, 프록시가 MUTE 제재 확인 → 도배 검사 →
 * 금칙어 치환 → 수신자 결정 → 시스템 메시지로 배달한다. 공지(harbor:announce)는 프록시만 구독해 전원에게 한 번만 보낸다.
 *
 * <p>파티 채널: 파티는 Boss 소유라 프록시는 파티 목록을 모른다. 요청한 백엔드가 Core api PartyService로 파티원을 recipients에 넣어 보내면,
 * 프록시는 서버와 관계없이 그 사람들에게 배달한다 (보낸 사람 포함, 중복 제거).
 */
final class ChatRelay {

    /** 보낸 사람별 도배 검사 상태. */
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
                Component line =
                        text.get(
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

    /** /귓 · /답장. 서버와 관계없이 배달한다. MUTE면 귓속말도 막는다. */
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

    private Component format(ChatMessage chat, String message) {
        return text.get(
                "chat.format",
                Placeholder.unparsed("server", chat.server()),
                Placeholder.unparsed("name", chat.senderName()),
                Placeholder.unparsed("message", message));
    }

    /** 같은 문장 연속, 1초에 여러 줄이면 잠시 막는다 (제안값은 harbor-proxy.yml). */
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
}
