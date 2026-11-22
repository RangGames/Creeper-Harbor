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

/** messages_ko.yml + MiniMessage. Thread-safe since it only reads the snapshot. */
public final class MessageManager implements MessageService {

    private final Supplier<Messages> messages;
    private final Supplier<TagResolver> globalTags;
    private final Logger log;
    private final Set<String> warnedMissing = ConcurrentHashMap.newKeySet();

    /**
     * @param globalTags tags usable in every message (glyphs etc.)
     */
    public MessageManager(
            Supplier<Messages> messages, Supplier<TagResolver> globalTags, Logger log) {
        this.messages = messages;
        this.globalTags = globalTags;
        this.log = log;
    }

    @Override
    public Component get(String key, TagResolver... resolvers) {
        return messages.get()
                .get(key)
                .map(
                        raw ->
                                MiniMessage.miniMessage()
                                        .deserialize(
                                                raw,
                                                TagResolver.resolver(
                                                        TagResolver.resolver(resolvers),
                                                        globalTags.get())))
                .orElseGet(
                        () -> {
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

    /** Allows warning again after a reload. */
    public void clearWarnings() {
        warnedMissing.clear();
    }
}
