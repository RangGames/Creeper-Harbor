package wiki.creeper.harbor.core.message;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.type.Messages;
import wiki.creeper.harbor.core.api.MessageService;

/** messages_ko.yml + MiniMessage. 스냅샷을 읽기만 하므로 스레드 안전하다. */
public final class MessageManager implements MessageService {

    private final Supplier<Messages> messages;
    private final Supplier<TagResolver> globalTags;
    private final Logger log;
    private final Set<String> warnedMissing = ConcurrentHashMap.newKeySet();

    /**
     * @param globalTags 모든 문구에 쓸 수 있는 태그 (글리프 등)
     */
    public MessageManager(Supplier<Messages> messages, Supplier<TagResolver> globalTags, Logger log) {
        this.messages = messages;
        this.globalTags = globalTags;
        this.log = log;
    }

    @Override
    public Component get(String key, TagResolver... resolvers) {
        return messages.get()
                .get(key)
                .map(raw -> MiniMessage.miniMessage()
                        .deserialize(raw, TagResolver.resolver(TagResolver.resolver(resolvers), globalTags.get())))
                .orElseGet(() -> {
                    if (warnedMissing.add(key)) {
                        log.warn("messages_ko.yml에 키가 없습니다: {}", key);
                    }
                    return Component.text(key);
                });
    }

    @Override
    public void send(Audience audience, String key, TagResolver... resolvers) {
        audience.sendMessage(get(key, resolvers));
    }

    @Override
    public boolean has(String key) {
        return messages.get().get(key).isPresent();
    }

    /** 리로드되면 다시 경고할 수 있게 한다. */
    public void clearWarnings() {
        warnedMissing.clear();
    }
}
